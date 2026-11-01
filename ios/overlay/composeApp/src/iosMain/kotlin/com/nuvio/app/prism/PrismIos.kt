@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.nuvio.app.prism

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Image
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.setMuted
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSBundle
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleActionSheet
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeAudiovisualContent
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

// Prism on the iPhone: files under Application Support/prism, the Photos and Files pickers, the app's own folder in the
// Files app (On My iPhone > <app name>) in place of Download/Prism, pictures through Skia, sounds through AVFoundation.
// The clip maker is PrismIosClipMaker; copying to the TV/PC over Wi-Fi is PrismIosShare.
object PrismIos : PrismPlatform {
    private val files = NSFileManager.defaultManager
    private var player: AVAudioPlayer? = null
    private var playerDelegate: NSObject? = null
    private var playerDone: (() -> Unit)? = null

    // Pickers only hold their delegate weakly, so the open one is kept here.
    private var openDelegate: NSObject? = null

    fun install() {
        if (Prism.platform == null) {
            Prism.install(this)
            recordCrashes()
            runCatching { PrismIosShare.follow() }
        }
    }

    private fun recordCrashes() {
        val previous = setUnhandledExceptionHook { error ->
            runCatching {
                val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
                writeBytes(
                    "$dataDir/$PRISM_PROBLEM_FILE",
                    ("$appName $version, iOS ${UIDevice.currentDevice.systemVersion}, ${UIDevice.currentDevice.model}\n" +
                        error.stackTraceToString().take(6_000)).encodeToByteArray(),
                )
            }
        }
        if (previous != null) {
            setUnhandledExceptionHook { error ->
                runCatching { writeBytes("$dataDir/$PRISM_PROBLEM_FILE", error.stackTraceToString().take(6_000).encodeToByteArray()) }
                previous(error)
            }
        }
    }

    private val appName: String
        get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleDisplayName") as? String) ?: "Prism"

    private fun dir(kind: ULong): String =
        (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String) ?: NSTemporaryDirectory()

    private fun mkdirs(path: String) {
        files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    override val dataDir: String
        get() = (dir(NSApplicationSupportDirectory) + "/prism").also(::mkdirs)

    /** The app's folder in the Files app (needs UIFileSharingEnabled + LSSupportsOpeningDocumentsInPlace). */
    private val folderDir: String
        get() = dir(NSDocumentDirectory).also(::mkdirs)

    internal val pickedDirPath: String get() = pickedDir

    private val pickedDir: String
        get() = (NSTemporaryDirectory().trimEnd('/') + "/prism-picked").also(::mkdirs)

    override val wideScreen: Boolean
        get() {
            if (UIDevice.currentDevice.userInterfaceIdiom != UIUserInterfaceIdiomPad) return false
            val (w, h) = UIScreen.mainScreen.bounds.useContents { size.width to size.height }
            return w > h
        }

    // ---- Files ----

    override fun readBytes(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()

    override fun writeBytes(path: String, bytes: ByteArray) {
        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::mkdirs)
        bytes.toNSData().writeToFile(path, atomically = true)
    }

    override fun delete(path: String) {
        files.removeItemAtPath(path, error = null)
    }

    private fun copyFile(from: String, to: String): Boolean {
        to.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::mkdirs)
        if (files.fileExistsAtPath(to)) files.removeItemAtPath(to, error = null)
        return files.copyItemAtPath(from, toPath = to, error = null)
    }

    private fun sizeOf(path: String): Long =
        (files.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    // ---- Pickers ----

    private fun topController(): UIViewController? {
        var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (vc?.presentedViewController != null) vc = vc.presentedViewController
        return vc
    }

    internal fun presentController(controller: UIViewController): Boolean = present(controller)

    private fun present(controller: UIViewController): Boolean {
        val top = topController() ?: return false
        controller.popoverPresentationController?.let { pop ->
            pop.sourceView = top.view
            top.view.bounds.useContents { pop.sourceRect = CGRectMake(size.width / 2, size.height / 2, 1.0, 1.0) }
        }
        top.presentViewController(controller, animated = true, completion = null)
        return true
    }

    private fun contentTypes(kind: PrismFileKind): List<UTType> = when (kind) {
        PrismFileKind.Picture -> listOf(UTTypeImage)
        PrismFileKind.Video -> listOf(UTTypeMovie)
        PrismFileKind.Sound -> listOf(UTTypeAudio, UTTypeAudiovisualContent)
        else -> listOf(UTTypeItem)
    }.filterNotNull()

    /** Photos first for pictures, clips and sounds (that's where they are on an iPhone), the Files app for the rest. */
    private suspend fun chooseSource(kind: PrismFileKind): String? {
        if (kind != PrismFileKind.Picture && kind != PrismFileKind.Video && kind != PrismFileKind.Sound) return "files"
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val sheet = UIAlertController.alertControllerWithTitle(null, message = null, preferredStyle = UIAlertControllerStyleActionSheet)
                fun add(title: String, value: String?, style: Long) {
                    sheet.addAction(UIAlertAction.actionWithTitle(title, style) { _ -> if (cont.isActive) cont.resume(value) })
                }
                add(if (kind == PrismFileKind.Sound) "Videos in Photos" else "Photos", "photos", UIAlertActionStyleDefault)
                add("Files", "files", UIAlertActionStyleDefault)
                add("Cancel", null, UIAlertActionStyleCancel)
                if (!present(sheet) && cont.isActive) cont.resume(null)
            }
        }
    }

    /** Lets the user pick files; returns copies in the temporary folder (path, name). */
    private suspend fun pick(kind: PrismFileKind, multiple: Boolean): List<Pair<String, String>> {
        val source = chooseSource(kind) ?: return emptyList()
        return if (source == "photos") pickPhotos(kind, multiple) else pickDocuments(kind, multiple)
    }

    private suspend fun pickDocuments(kind: PrismFileKind, multiple: Boolean): List<Pair<String, String>> = withContext(Dispatchers.Main) {
        val urls: List<NSURL> = suspendCancellableCoroutine { cont ->
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = contentTypes(kind), asCopy = true)
            picker.allowsMultipleSelection = multiple
            val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
                override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                    openDelegate = null
                    if (cont.isActive) cont.resume(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
                }

                override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                    openDelegate = null
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
            openDelegate = delegate
            picker.delegate = delegate
            if (!present(picker) && cont.isActive) cont.resume(emptyList())
        }
        withContext(Dispatchers.IO) {
            urls.mapNotNull { url ->
                val from = url.path ?: return@mapNotNull null
                val name = url.lastPathComponent ?: "file"
                val to = "$pickedDir/${PrismStore.newId()}-$name"
                if (copyFile(from, to)) to to name else null
            }
        }
    }

    private suspend fun pickPhotos(kind: PrismFileKind, multiple: Boolean): List<Pair<String, String>> {
        val results: List<PHPickerResult> = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val config = PHPickerConfiguration()
                config.filter = if (kind == PrismFileKind.Picture) PHPickerFilter.imagesFilter else PHPickerFilter.videosFilter
                config.selectionLimit = if (multiple) 0 else 1
                val picker = PHPickerViewController(configuration = config)
                val delegate = object : NSObject(), PHPickerViewControllerDelegateProtocol {
                    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                        openDelegate = null
                        picker.dismissViewControllerAnimated(true, completion = null)
                        if (cont.isActive) cont.resume(didFinishPicking.filterIsInstance<PHPickerResult>())
                    }
                }
                openDelegate = delegate
                picker.delegate = delegate
                if (!present(picker) && cont.isActive) cont.resume(emptyList())
            }
        }
        val typeId = (if (kind == PrismFileKind.Picture) UTTypeImage else UTTypeMovie)?.identifier ?: return emptyList()
        return results.mapNotNull { result ->
            suspendCancellableCoroutine<Pair<String, String>?> { cont ->
                result.itemProvider.loadFileRepresentationForTypeIdentifier(typeId) { url, _ ->
                    // The file is only there until this returns, so it's copied at once.
                    val picked = url?.path?.let { from ->
                        val name = url.lastPathComponent ?: "file"
                        val to = "$pickedDir/${PrismStore.newId()}-$name"
                        if (copyFile(from, to)) to to name else null
                    }
                    if (cont.isActive) cont.resume(picked)
                }
            }
        }.map { (path, name) -> if (kind == PrismFileKind.Picture) asJpegIfNeeded(path, name) else path to name }
    }

    /** iPhone photos are often HEIC, which Skia can't read: those are turned into JPEG. */
    private fun asJpegIfNeeded(path: String, name: String): Pair<String, String> {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) return path to name
        val image = UIImage.imageWithContentsOfFile(path) ?: return path to name
        val jpeg = UIImageJPEGRepresentation(image, 0.92) ?: return path to name
        val newName = name.substringBeforeLast('.') + ".jpg"
        val newPath = "$pickedDir/${PrismStore.newId()}-$newName"
        jpeg.writeToFile(newPath, atomically = true)
        delete(path)
        return newPath to newName
    }

    override suspend fun pickFile(kind: PrismFileKind): PrismPickedFile? {
        val (path, name) = pick(kind, multiple = false).firstOrNull() ?: return null
        val bytes = withContext(Dispatchers.IO) { readBytes(path) }
        delete(path)
        return bytes?.let { PrismPickedFile(name, it) }
    }

    override suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean {
        val path = NSTemporaryDirectory().trimEnd('/') + "/" + suggestedName
        withContext(Dispatchers.IO) { writeBytes(path, bytes) }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
                sheet.completionWithItemsHandler = { _, completed, _, _ -> if (cont.isActive) cont.resume(completed) }
                if (!present(sheet) && cont.isActive) cont.resume(false)
            }
        }
    }

    // ---- The app's folder in the Files app ----

    override val folderLabel: String?
        get() = "Files app > On My iPhone > $appName"

    override suspend fun linkFolder(): Boolean {
        val path = folderDir
        withContext(Dispatchers.Main) {
            NSURL.URLWithString("shareddocuments://$path")?.let { UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any>(), completionHandler = null) }
        }
        return true
    }

    override suspend fun listFolder(): List<PrismFolderFile> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Pair<Double, PrismFolderFile>>()
        fun scan(path: String, folder: String) {
            val names = files.contentsOfDirectoryAtPath(path, error = null)?.filterIsInstance<String>().orEmpty()
            for (name in names) {
                if (name.startsWith(".") || (folder.isEmpty() && name == "Inbox")) continue
                val full = "$path/$name"
                val attrs = files.attributesOfItemAtPath(full, error = null) ?: continue
                if (attrs[NSFileType] == NSFileTypeDirectory) {
                    if (folder.isEmpty()) scan(full, name)
                    continue
                }
                val size = (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
                val modified = (attrs[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0
                found += modified to PrismFolderFile(name, size, full, folder)
            }
        }
        scan(folderDir, "")
        found.sortedByDescending { it.first }.map { it.second }
    }

    override suspend fun readFolderFile(file: PrismFolderFile): ByteArray? = withContext(Dispatchers.IO) { readBytes(file.ref) }

    // ---- Clips ----

    override val canMakeClips: Boolean get() = true

    override suspend fun makeClip(sound: Boolean): PrismMadeClip? = PrismIosClipMaker.make(sound)

    override suspend fun pickRefs(kind: PrismFileKind, multiple: Boolean): List<PrismFolderFile> =
        pick(kind, multiple).map { (path, name) -> PrismFolderFile(name, sizeOf(path), path) }

    override suspend fun copyRef(file: PrismFolderFile, destPath: String): Boolean = withContext(Dispatchers.IO) {
        val ok = copyFile(file.ref, destPath)
        // A picked file was only a temporary copy.
        if (ok && file.ref.startsWith(pickedDir)) delete(file.ref)
        ok
    }

    override suspend fun deleteOriginal(ref: String): Boolean = withContext(Dispatchers.IO) {
        if (!ref.startsWith(folderDir)) return@withContext false
        files.removeItemAtPath(ref, error = null)
    }

    override fun fileUrl(path: String): String = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"

    @Composable
    override fun ClipPlayer(path: String, modifier: Modifier, onEnded: () -> Unit) {
        val ended by rememberUpdatedState(onEnded)
        val player = remember { AVPlayer().apply { setMuted(true) } }
        val item = remember(path) {
            val url = if (path.startsWith("file:")) NSURL.URLWithString(path) else NSURL.fileURLWithPath(path)
            url?.let { AVPlayerItem.playerItemWithURL(it) }
        }
        DisposableEffect(item) {
            val center = NSNotificationCenter.defaultCenter
            val observers = listOf(AVPlayerItemDidPlayToEndTimeNotification, AVPlayerItemFailedToPlayToEndTimeNotification).map { name ->
                center.addObserverForName(name, `object` = item, queue = NSOperationQueue.mainQueue) { _ -> ended() }
            }
            player.replaceCurrentItemWithPlayerItem(item)
            player.play()
            onDispose {
                observers.forEach { center.removeObserver(it) }
                player.pause()
                player.replaceCurrentItemWithPlayerItem(null)
            }
        }
        // A clip that won't play is skipped after a moment (not at once, so a set of broken clips can't spin).
        LaunchedEffect(item) {
            if (item == null) {
                delay(3_000)
                ended()
                return@LaunchedEffect
            }
            while (true) {
                delay(1_000)
                if (item.status == AVPlayerItemStatusFailed) {
                    delay(2_000)
                    ended()
                    break
                }
            }
        }
        UIKitView(
            factory = { PrismPlayerView(player) },
            modifier = modifier,
            interactive = false,
        )
    }

    // ---- Pictures ----

    override suspend fun thumbnail(ref: String, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val path = if (ref.startsWith("file:")) NSURL.URLWithString(ref)?.path ?: ref else ref
            if (PrismFileKind.Video.matches(path)) {
                val generator = AVAssetImageGenerator(asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null))
                generator.appliesPreferredTrackTransform = true
                val cg = generator.copyCGImageAtTime(CMTimeMakeWithSeconds(1.0, 600), actualTime = null, error = null) ?: return@runCatching null
                val png = UIImagePNGRepresentation(UIImage.imageWithCGImage(cg)) ?: return@runCatching null
                decodeImage(png.toByteArray(), maxSide)
            } else {
                readBytes(path)?.let { decodeImage(it, maxSide) }
            }
        }.getOrNull()
    }

    override fun decodeImage(bytes: ByteArray, maxSide: Int): ImageBitmap? = runCatching {
        val full = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
            // Formats Skia can't read (HEIC): through UIImage first.
            ?: UIImage.imageWithData(bytes.toNSData())?.let { UIImagePNGRepresentation(it) }?.let { Image.makeFromEncoded(it.toByteArray()).toComposeImageBitmap() }
            ?: return@runCatching null
        val longest = max(full.width, full.height)
        if (longest <= maxSide) return@runCatching full
        val scale = maxSide.toFloat() / longest
        val size = IntSize((full.width * scale).roundToInt().coerceAtLeast(1), (full.height * scale).roundToInt().coerceAtLeast(1))
        PrismImages.scale(full, IntOffset.Zero, IntSize(full.width, full.height), size)
    }.getOrNull()

    override fun encodeImage(image: ImageBitmap, keepTransparency: Boolean): ByteArray? = runCatching {
        Image.makeFromBitmap(image.asSkiaBitmap())
            .encodeToData(if (keepTransparency) EncodedImageFormat.PNG else EncodedImageFormat.JPEG, 90)
            ?.bytes
    }.getOrNull()

    // ---- Sounds ----

    override suspend fun decodeAudio(name: String, bytes: ByteArray, maxSeconds: Int): PrismPcm? = withContext(Dispatchers.IO) {
        PrismAudio.fromWav(bytes, maxSeconds)?.let { return@withContext it }
        val path = "$pickedDir/convert-${PrismStore.newId()}.${name.substringAfterLast('.', "m4a")}"
        writeBytes(path, bytes)
        try {
            runCatching {
                val file = AVAudioFile(forReading = NSURL.fileURLWithPath(path), commonFormat = AVAudioPCMFormatInt16, interleaved = true, error = null)
                val format = file.processingFormat
                val rate = format.sampleRate.toInt()
                val channels = format.channelCount.toInt()
                if (rate <= 0 || channels <= 0) return@runCatching null
                val frames = minOf(file.length, maxSeconds.toLong() * rate).toInt()
                if (frames <= 0) return@runCatching null
                val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = frames.toUInt()) ?: return@runCatching null
                if (!file.readIntoBuffer(buffer, frameCount = frames.toUInt(), error = null)) return@runCatching null
                val data = buffer.int16ChannelData?.get(0) ?: return@runCatching null
                val count = buffer.frameLength.toInt() * channels
                val samples = ShortArray(count) { data[it] }
                PrismPcm(rate, channels, samples)
            }.getOrNull()
        } finally {
            delete(path)
        }
    }

    override fun playWav(path: String, onDone: () -> Unit) = playWav(path, 1f, onDone)

    override fun playWav(path: String, volume: Float, onDone: () -> Unit) {
        stopSound()
        val sound = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path), error = null)
        val delegate = object : NSObject(), AVAudioPlayerDelegateProtocol {
            override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
                finishSound()
            }
        }
        playerDone = onDone
        player = sound
        playerDelegate = delegate
        sound.delegate = delegate
        sound.volume = volume.coerceIn(0f, 1f)
        if (!sound.play()) finishSound()
    }

    private fun finishSound() {
        val done = playerDone
        playerDone = null
        player = null
        playerDelegate = null
        done?.let { NSOperationQueue.mainQueue.addOperationWithBlock { it() } }
    }

    override fun stopSound() {
        player?.stop()
        finishSound()
    }

    override fun today(): Pair<Int, Int> {
        val parts = NSCalendar.currentCalendar.components(NSCalendarUnitMonth or NSCalendarUnitDay, fromDate = NSDate())
        return parts.month.toInt() to parts.day.toInt()
    }

    override fun loadFont(path: String): FontFamily? = runCatching {
        val bytes = readBytes(path) ?: return null
        // Skia refuses files that aren't real fonts, so a broken one never reaches the screen.
        FontMgr.default.makeFromData(Data.makeFromBytes(bytes)) ?: return null
        FontFamily(androidx.compose.ui.text.platform.Font(identity = path, data = bytes))
    }.getOrNull()
}

/** Shows an AVPlayer filling the view, cropped. */
private class PrismPlayerView(player: AVPlayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val playerLayer = AVPlayerLayer.playerLayerWithPlayer(player).apply { videoGravity = AVLayerVideoGravityResizeAspectFill }

    init {
        layer.addSublayer(playerLayer)
        clipsToBounds = true
        userInteractionEnabled = false
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
    }
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
