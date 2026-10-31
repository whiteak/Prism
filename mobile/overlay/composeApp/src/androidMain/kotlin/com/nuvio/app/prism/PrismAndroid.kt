package com.nuvio.app.prism

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// Prism on the phone: files in the app's own storage, the system file picker, a linked folder
// (for example Download/Prism, filled with Send Anywhere), pictures and sounds.
object PrismAndroid : PrismPlatform {
    private lateinit var appContext: Context
    private var activity = WeakReference<ComponentActivity>(null)
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private var requestCount = 0

    private val prefs get() = appContext.getSharedPreferences("prism", Context.MODE_PRIVATE)

    fun install(activity: ComponentActivity) {
        appContext = activity.applicationContext
        this.activity = WeakReference(activity)
        if (Prism.platform == null) {
            Prism.install(this)
            recordCrashes()
        }
        activity.lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    // The TV and PC copy from the phone while Prism is on screen (PrismShare).
                    androidx.lifecycle.Lifecycle.Event.ON_START -> PrismShareServer.start()
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        stopSound()
                        PrismShareServer.stop()
                    }
                    else -> Unit
                }
            },
        )
    }

    /** Keeps the details of a crash in prism/last-problem.txt so Customise can show them next time. */
    private fun recordCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(dataDir, PRISM_PROBLEM_FILE).writeText(
                    "Prism ${runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull()}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}\n" +
                        error.stackTraceToString().take(6_000),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    override val dataDir: String
        get() = File(appContext.filesDir, "prism").apply { mkdirs() }.absolutePath

    override val wideScreen: Boolean
        get() {
            val config = appContext.resources.configuration
            return config.smallestScreenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp
        }

    override fun readBytes(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    override fun writeBytes(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.writeBytes(bytes)
            temp.delete()
        }
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    /** Runs one system screen (picker, save dialog) and waits for its answer. */
    private suspend fun <I, O> launch(contract: ActivityResultContract<I, O>, input: I): O? {
        val host = activity.get() ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val key = "prism_request_${requestCount++}"
                lateinit var launcher: androidx.activity.result.ActivityResultLauncher<I>
                launcher = host.activityResultRegistry.register(key, contract) { result ->
                    launcher.unregister()
                    if (cont.isActive) cont.resume(result)
                }
                cont.invokeOnCancellation { launcher.unregister() }
                runCatching { launcher.launch(input) }.onFailure {
                    launcher.unregister()
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    override suspend fun pickFile(kind: PrismFileKind): PrismPickedFile? {
        val types = when (kind) {
            PrismFileKind.Picture -> arrayOf("image/*")
            PrismFileKind.Sound -> arrayOf("audio/*", "video/*")
            PrismFileKind.Look -> arrayOf("*/*")
            PrismFileKind.Library -> arrayOf("*/*")
            PrismFileKind.Video -> arrayOf("video/*")
            PrismFileKind.Font -> arrayOf("*/*")
            PrismFileKind.Backup -> arrayOf("*/*")
        }
        val uri = launch(ActivityResultContracts.OpenDocument(), types) ?: return null
        return withContext(Dispatchers.IO) {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            PrismPickedFile(displayName(uri) ?: "file", bytes, uri.toString())
        }
    }

    override suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean {
        val uri = launch(ActivityResultContracts.CreateDocument("application/octet-stream"), suggestedName) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { appContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    // ---- The linked folder ----

    private val folderUri: Uri?
        get() = prefs.getString("folder", null)?.let(Uri::parse)

    override val folderLabel: String?
        get() = folderUri?.let { uri ->
            runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "the linked folder" } }.getOrNull()
                ?: "the linked folder"
        }

    override suspend fun linkFolder(): Boolean {
        val uri = launch(ActivityResultContracts.OpenDocumentTree(), folderUri) ?: return false
        runCatching {
            // Write too, so files can be deleted from the folder once they're in Prism.
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.recoverCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString("folder", uri.toString()).apply()
        return true
    }

    override suspend fun listFolder(): List<PrismFolderFile> = withContext(Dispatchers.IO) {
        val tree = folderUri ?: return@withContext emptyList()
        runCatching {
            val found = mutableListOf<Pair<Long, PrismFolderFile>>()
            fun scan(parentId: String, folder: String) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
                val folders = mutableListOf<Pair<String, String>>()
                appContext.contentResolver.query(children, columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: "file"
                        if (c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            folders += c.getString(0) to name
                            continue
                        }
                        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                        found += c.getLong(4) to PrismFolderFile(name, c.getLong(2), doc.toString(), folder)
                    }
                }
                // One folder down only: Clipper's Phone, TV and Icons.
                if (folder.isEmpty()) folders.forEach { (id, name) -> scan(id, name) }
            }
            scan(DocumentsContract.getTreeDocumentId(tree), "")
            found.sortedByDescending { it.first }.map { it.second }
        }.getOrDefault(emptyList())
    }

    override suspend fun readFolderFile(file: PrismFolderFile): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { appContext.contentResolver.openInputStream(Uri.parse(file.ref))?.use { it.readBytes() } }.getOrNull()
    }

    // ---- Clips ----

    override suspend fun pickRefs(kind: PrismFileKind, multiple: Boolean): List<PrismFolderFile> {
        val types = when (kind) {
            PrismFileKind.Video -> arrayOf("video/*")
            PrismFileKind.Picture -> arrayOf("image/*")
            PrismFileKind.Sound -> arrayOf("audio/*", "video/*")
            PrismFileKind.Look -> arrayOf("*/*")
            PrismFileKind.Library -> arrayOf("*/*")
            PrismFileKind.Font -> arrayOf("*/*")
            PrismFileKind.Backup -> arrayOf("*/*")
        }
        val uris: List<Uri> = if (multiple) {
            launch(ActivityResultContracts.OpenMultipleDocuments(), types).orEmpty()
        } else {
            listOfNotNull(launch(ActivityResultContracts.OpenDocument(), types))
        }
        return withContext(Dispatchers.IO) {
            uris.map { uri -> PrismFolderFile(displayName(uri) ?: "clip.mp4", 0L, uri.toString()) }
        }
    }

    override suspend fun copyRef(file: PrismFolderFile, destPath: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            val uri = Uri.parse(file.ref)
            val input = if (uri.scheme == "content") appContext.contentResolver.openInputStream(uri) else File(file.ref).inputStream()
            checkNotNull(input).use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            true
        }.getOrDefault(false)
    }

    override val canMakeClips: Boolean get() = true

    override suspend fun makeClip(sound: Boolean): PrismMadeClip? = launch(PrismClipContract(), sound)

    override suspend fun deleteOriginal(ref: String): Boolean = withContext(Dispatchers.IO) { deleteDeviceFile(appContext, ref) }

    override fun fileUrl(path: String): String = Uri.fromFile(File(path)).toString()

    @Composable
    override fun ClipPlayer(path: String, modifier: Modifier, onEnded: () -> Unit) {
        val context = LocalContext.current
        val ended by rememberUpdatedState(onEnded)
        var videoAspect by remember { mutableFloatStateOf(0f) }
        val player = remember {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
                // Silent clips: skip decoding their sound at all.
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            }
        }
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) ended()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }

                // A clip that won't play is skipped after a moment (not at once, so a set of broken clips can't spin).
                override fun onPlayerError(error: PlaybackException) {
                    main.postDelayed({ ended() }, 3_000)
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }
        LaunchedEffect(path) {
            player.setMediaItem(MediaItem.fromUri(if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))))
            player.prepare()
            player.playWhenReady = true
        }
        BoxWithConstraints(modifier.clipToBounds()) {
            val boxAspect = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                // The view stretches the video to fill it; scaling one side back crops it instead.
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    if (videoAspect > 0f) {
                        if (videoAspect > boxAspect) scaleX = videoAspect / boxAspect else scaleY = boxAspect / videoAspect
                    }
                },
            )
        }
    }

    // ---- Pictures ----

    override suspend fun thumbnail(ref: String, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val content = ref.startsWith("content://")
            val uri = if (content) Uri.parse(ref) else Uri.fromFile(File(ref))
            val name = (if (content) displayName(uri) else File(ref).name).orEmpty()
            if (PrismFileKind.Picture.matches(name) || (content && appContext.contentResolver.getType(uri)?.startsWith("image/") == true)) {
                val bytes = (if (content) appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } else File(ref).readBytes())
                    ?: return@runCatching null
                return@runCatching decodeImage(bytes, maxSide)
            }
            // A video: the system's own thumbnail where it has one, otherwise a frame a second in.
            val made = if (content && Build.VERSION.SDK_INT >= 29) {
                runCatching { appContext.contentResolver.loadThumbnail(uri, android.util.Size(maxSide, maxSide), null) }.getOrNull()
            } else {
                null
            }
            val frame = made ?: android.media.MediaMetadataRetriever().run {
                try {
                    if (content) setDataSource(appContext, uri) else setDataSource(ref)
                    getFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    release()
                }
            } ?: return@runCatching null
            val longest = max(frame.width, frame.height)
            val scaled = if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt().coerceAtLeast(1), (frame.height * scale).roundToInt().coerceAtLeast(1), true)
            } else {
                frame
            }
            scaled.asImageBitmap()
        }.getOrNull()
    }

    override fun decodeImage(bytes: ByteArray, maxSide: Int): ImageBitmap? = runCatching {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder turns phone photos the right way up.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val longest = max(decoded.width, decoded.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
            } else {
                decoded
            }
        }
        bitmap.asImageBitmap()
    }.getOrNull()

    override fun encodeImage(image: ImageBitmap, keepTransparency: Boolean): ByteArray? = runCatching {
        val out = ByteArrayOutputStream()
        image.asAndroidBitmap().compress(if (keepTransparency) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
        out.toByteArray()
    }.getOrNull()

    // ---- Sounds ----

    override suspend fun decodeAudio(name: String, bytes: ByteArray, maxSeconds: Int): PrismPcm? = withContext(Dispatchers.IO) {
        PrismAudio.fromWav(bytes, maxSeconds)?.let { return@withContext it }
        val temp = File(appContext.cacheDir, "prism_decode." + name.substringAfterLast('.', "bin"))
        temp.writeBytes(bytes)
        try {
            decodeWithMediaCodec(temp, maxSeconds)
        } catch (e: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    private fun decodeWithMediaCodec(file: File, maxSeconds: Int): PrismPcm? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); return null }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var out = ShortArray(rate * channels * 10)
        var count = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.order(ByteOrder.nativeOrder())
                    val n = if (floatPcm) info.size / 4 else info.size / 2
                    if (count + n > out.size) out = out.copyOf(max(out.size * 2, count + n))
                    if (floatPcm) {
                        val floats = buffer.asFloatBuffer()
                        for (i in 0 until n) out[count + i] = (floats.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    } else {
                        buffer.asShortBuffer().get(out, count, n)
                    }
                    count += n
                    codec.releaseOutputBuffer(outIndex, false)
                    val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (done || count >= maxSeconds * rate * channels) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        val frames = minOf(count, maxSeconds * rate * channels) / channels
        return PrismPcm(rate, channels, out.copyOf(frames * channels))
    }

    override fun playWav(path: String, onDone: () -> Unit) = playWav(path, 1f, onDone)

    override fun playWav(path: String, volume: Float, onDone: () -> Unit) {
        main.post {
            stopSound()
            val next = runCatching {
                MediaPlayer().apply {
                    if (path.startsWith("content://")) setDataSource(appContext, Uri.parse(path)) else setDataSource(path)
                    prepare()
                    setVolume(volume, volume)
                }
            }.getOrNull()
            if (next == null) {
                onDone()
                return@post
            }
            next.setOnCompletionListener {
                if (player === next) {
                    stopSound()
                    onDone()
                }
            }
            next.setOnErrorListener { _, _, _ ->
                if (player === next) {
                    stopSound()
                    onDone()
                }
                true
            }
            player = next
            next.start()
        }
    }

    override fun stopSound() {
        val current = player ?: return
        player = null
        runCatching { current.stop() }
        current.release()
    }

    override fun today(): Pair<Int, Int> = java.time.LocalDate.now().let { it.monthValue to it.dayOfMonth }

    override val systemFamilies: List<Pair<String, String>> = PRISM_ANDROID_FAMILIES

    override fun systemFamily(name: String): androidx.compose.ui.text.font.FontFamily? = runCatching {
        androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL))
    }.getOrNull()

    override suspend fun deviceFonts(): List<PrismFolderFile> = withContext(Dispatchers.IO) {
        val files = if (Build.VERSION.SDK_INT >= 29) {
            runCatching { android.graphics.fonts.SystemFonts.getAvailableFonts().mapNotNull { it.file } }.getOrDefault(emptyList())
        } else {
            File("/system/fonts").listFiles()?.toList().orEmpty()
        }
        files
            .filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
            // Noto covers other alphabets and emoji: hundreds of files that all look alike.
            .filterNot { it.name.startsWith("Noto") || it.name.contains("Emoji") }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
            .map { PrismFolderFile(it.name, it.length(), it.absolutePath) }
    }

    override fun loadFont(path: String): androidx.compose.ui.text.font.FontFamily? = runCatching {
        val file = java.io.File(path)
        if (!file.isFile) return null
        val typeface = if (Build.VERSION.SDK_INT >= 26) {
            android.graphics.Typeface.Builder(file).build()
        } else {
            android.graphics.Typeface.createFromFile(file)
        }
        // A file Android can't read comes back empty or as the standard font.
        typeface?.takeIf { it != android.graphics.Typeface.DEFAULT }?.let { androidx.compose.ui.text.font.FontFamily(it) }
    }.getOrNull()
}

/** Android's own extra font families (the TV knows the same names). */
internal val PRISM_ANDROID_FAMILIES = listOf(
    "casual" to "Casual",
    "sans-serif-condensed" to "Condensed",
    "sans-serif-light" to "Light",
    "sans-serif-thin" to "Thin",
    "sans-serif-medium" to "Medium",
    "sans-serif-black" to "Black",
    "sans-serif-smallcaps" to "Small caps",
    "serif-monospace" to "Old typewriter",
)
