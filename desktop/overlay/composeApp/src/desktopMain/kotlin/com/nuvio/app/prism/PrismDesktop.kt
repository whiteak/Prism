package com.nuvio.app.prism

import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nuvio.app.desktop.DesktopPaths
import com.nuvio.app.desktop.DesktopVlc
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

// Prism on the PC: files under %APPDATA%\<app>\prism, Windows' own open and save dialogs, the
// Downloads\Prism folder, pictures through Skia, and sounds converted by the bundled libVLC and
// played from memory as WAV.
object PrismDesktop : PrismPlatform {
    private var clip: Clip? = null
    private var onFinished: (() -> Unit)? = null

    fun install() {
        if (Prism.platform == null) {
            Prism.install(this)
            PrismDesktopSync.startAuto()
        }
    }

    override val copiesFromPhone: Boolean get() = true
    override val phoneCode: String? get() = PrismDesktopSync.code
    override val phoneStatus: String? get() = PrismDesktopSync.status
    override val phoneAuto: Boolean get() = PrismDesktopSync.auto
    override fun setPhoneAuto(on: Boolean) = PrismDesktopSync.changeAuto(on)
    override suspend fun copyFromPhone(code: String?): String = PrismDesktopSync.copy(code)

    /** Plays the look's "Closing" sound (if it has one) before the app closes; gives up waiting after 5 seconds. */
    fun closing(exit: () -> Unit) {
        if (!PrismSounds.has(PrismMoment.Closing)) return exit()
        var done = false
        val finish = {
            if (!done) {
                done = true
                exit()
            }
        }
        PrismSounds.play(PrismMoment.Closing) { finish() }
        javax.swing.Timer(5_000) { finish() }.apply { isRepeats = false }.start()
    }

    /** Prism's mark for the window and taskbar. */
    fun windowIcon(): androidx.compose.ui.graphics.painter.Painter? =
        System.getProperty("compose.application.resources.dir")
            ?.let { File(it, "icon.png") }
            ?.takeIf { it.isFile }
            ?.let { file ->
                runCatching { androidx.compose.ui.graphics.painter.BitmapPainter(Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()) }.getOrNull()
            }

    override val dataDir: String
        get() = File(DesktopPaths.dataDir, "prism").apply { mkdirs() }.absolutePath

    override val wideScreen: Boolean = true

    override fun readBytes(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    override fun writeBytes(path: String, bytes: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                file.writeBytes(bytes)
                temp.delete()
            }
        }
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    private suspend fun dialog(title: String, mode: Int, file: String?, kind: PrismFileKind?): File? =
        withContext(Dispatchers.Main) {
            val dialog = FileDialog(null as Frame?, title, mode)
            if (kind != null) {
                dialog.file = kind.extensions.joinToString(";") { "*.$it" }
                dialog.setFilenameFilter { _, name -> kind.matches(name) }
            } else if (file != null) {
                dialog.file = file
            }
            dialog.isVisible = true
            val name = dialog.file ?: return@withContext null
            File(dialog.directory, name)
        }

    override suspend fun pickFile(kind: PrismFileKind): PrismPickedFile? {
        val file = dialog("Choose ${kind.label.removeSuffix("s")}", FileDialog.LOAD, null, kind) ?: return null
        return withContext(Dispatchers.IO) { PrismPickedFile(file.name, file.readBytes(), file.absolutePath) }
    }

    override suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean {
        var file = dialog("Save look", FileDialog.SAVE, suggestedName, null) ?: return false
        if (file.extension.isEmpty()) file = File(file.parentFile, file.name + ".prism")
        return withContext(Dispatchers.IO) { runCatching { file.writeBytes(bytes) }.isSuccess }
    }

    // ---- Downloads\Prism ----

    private val folder: File
        get() = File(System.getProperty("user.home"), "Downloads/Prism").apply { mkdirs() }

    override val folderLabel: String
        get() = folder.absolutePath

    override suspend fun linkFolder(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Desktop.getDesktop().open(folder) }.isSuccess
    }

    override suspend fun listFolder(): List<PrismFolderFile> = withContext(Dispatchers.IO) {
        val top = folder.listFiles().orEmpty()
        // One folder down too: Clipper's Phone, TV and Icons.
        (top.filter { it.isFile }.map { it to "" } +
            top.filter { it.isDirectory }.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile }.map { it to dir.name } })
            .sortedByDescending { it.first.lastModified() }
            .map { (f, inside) -> PrismFolderFile(f.name, f.length(), f.absolutePath, inside) }
    }

    override suspend fun readFolderFile(file: PrismFolderFile): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { File(file.ref).readBytes() }.getOrNull()
    }

    // ---- Clips ----

    override suspend fun pickRefs(kind: PrismFileKind, multiple: Boolean): List<PrismFolderFile> = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, "Choose ${kind.label}", FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        dialog.file = kind.extensions.joinToString(";") { "*.$it" }
        dialog.setFilenameFilter { _, name -> kind.matches(name) }
        dialog.isVisible = true
        dialog.files.orEmpty().map { PrismFolderFile(it.name, it.length(), it.absolutePath) }
    }

    override suspend fun copyRef(file: PrismFolderFile, destPath: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            File(file.ref).copyTo(File(destPath), overwrite = true)
            true
        }.getOrDefault(false)
    }

    override suspend fun deleteOriginal(ref: String): Boolean = withContext(Dispatchers.IO) { runCatching { File(ref).delete() }.getOrDefault(false) }

    override fun fileUrl(path: String): String = File(path).toPath().toUri().toString()

    @Composable
    override fun ClipPlayer(path: String, modifier: Modifier, onEnded: () -> Unit) {
        val factory = DesktopVlc.factory ?: return
        val ended by rememberUpdatedState(onEnded)
        val frames = remember { PrismFrameSink() }
        val player = remember {
            factory.mediaPlayers().newEmbeddedMediaPlayer().also {
                it.videoSurface().set(factory.videoSurfaces().newVideoSurface(frames, frames, true))
            }
        }
        DisposableEffect(player) {
            val events = object : MediaPlayerEventAdapter() {
                override fun finished(mediaPlayer: MediaPlayer) {
                    SwingUtilities.invokeLater { ended() }
                }

                // A clip that won't play is skipped after a moment (not at once, so a set of broken clips can't spin).
                override fun error(mediaPlayer: MediaPlayer) {
                    javax.swing.Timer(3_000) { ended() }.apply { isRepeats = false }.start()
                }
            }
            player.events().addMediaPlayerEventListener(events)
            onDispose {
                player.events().removeMediaPlayerEventListener(events)
                // Stopping libVLC can block for a moment, so it happens off the UI thread.
                Thread({
                    runCatching { player.controls().stop() }
                    runCatching { player.release() }
                }, "prism-clip-release").start()
            }
        }
        LaunchedEffect(path) {
            player.media().play(File(path).toPath().toUri().toString(), ":no-audio")
        }
        var frame by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(frames) {
            while (true) {
                withFrameNanos { }
                frames.takeFrame()?.let { frame = it }
            }
        }
        Box(modifier) {
            frame?.let { ComposeImage(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
    }

    // ---- Pictures ----

    override suspend fun thumbnail(ref: String, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!PrismFileKind.Picture.matches(ref)) return@withContext null
        runCatching { decodeImage(File(ref).readBytes(), maxSide) }.getOrNull()
    }

    override fun decodeImage(bytes: ByteArray, maxSide: Int): ImageBitmap? = runCatching {
        val full = Image.makeFromEncoded(bytes).toComposeImageBitmap()
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
        val work = File(dataDir, "convert").apply { mkdirs() }
        val source = File(work, "source." + name.substringAfterLast('.', "bin"))
        val wav = File(work, "converted.wav")
        source.writeBytes(bytes)
        wav.delete()
        try {
            if (convertWithVlc(source, wav, maxSeconds)) PrismAudio.fromWav(wav.readBytes(), maxSeconds) else null
        } finally {
            source.delete()
            wav.delete()
        }
    }

    /** Has libVLC write [source] as 16-bit WAV; it runs faster than real time because nothing is played. */
    private suspend fun convertWithVlc(source: File, wav: File, maxSeconds: Int): Boolean {
        val factory = DesktopVlc.factory ?: return false
        val player = factory.mediaPlayers().newMediaPlayer()
        val dst = wav.absolutePath.replace('\\', '/')
        try {
            val finished = withTimeoutOrNull(120_000) {
                suspendCancellableCoroutine { cont ->
                    player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                        override fun finished(mediaPlayer: MediaPlayer) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun error(mediaPlayer: MediaPlayer) {
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    val started = player.media().play(
                        source.toPath().toUri().toString(),
                        ":sout=#transcode{acodec=s16l,channels=2,samplerate=44100}:std{access=file,mux=wav,dst=\"$dst\"}",
                        ":no-sout-video",
                        ":no-sout-spu",
                        ":stop-time=$maxSeconds",
                    )
                    if (!started && cont.isActive) cont.resume(false)
                }
            }
            return finished == true && wav.length() > 44
        } finally {
            runCatching { player.controls().stop() }
            runCatching { player.release() }
        }
    }

    override fun playWav(path: String, onDone: () -> Unit) = playWav(path, 1f, onDone)

    private var preview: MediaPlayer? = null

    /** Any sound (MP3, M4A, a video's sound...) through libVLC; WAVs go the usual way. */
    override fun playFile(ref: String, onDone: () -> Unit) {
        if (ref.endsWith(".wav", ignoreCase = true)) return playWav(ref, onDone)
        stopSound()
        val factory = DesktopVlc.factory ?: return onDone()
        val next = factory.mediaPlayers().newMediaPlayer()
        next.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) {
                SwingUtilities.invokeLater { if (preview === next) { stopSound(); onDone() } }
            }

            override fun error(mediaPlayer: MediaPlayer) = finished(mediaPlayer)
        })
        preview = next
        next.media().play(File(ref).toPath().toUri().toString(), ":no-video")
    }

    override fun playWav(path: String, volume: Float, onDone: () -> Unit) {
        stopSound()
        val next = runCatching {
            AudioSystem.getClip().apply { AudioSystem.getAudioInputStream(File(path)).use { open(it) } }
        }.onFailure { System.err.println("Prism sound failed: ${it.message}") }.getOrNull()
        if (next == null) {
            onDone()
            return
        }
        next.addLineListener { event ->
            // STOP also comes from stopSound(); only a clip that is still current has finished by itself.
            if (event.type == LineEvent.Type.STOP) SwingUtilities.invokeLater { done(next) }
        }
        if (volume < 1f) runCatching {
            val gain = next.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN) as javax.sound.sampled.FloatControl
            val db = 20f * kotlin.math.log10(volume.coerceAtLeast(0.001f))
            gain.value = db.coerceIn(gain.minimum, gain.maximum)
        }
        clip = next
        onFinished = onDone
        next.start()
    }

    private fun done(finished: Clip) {
        if (clip !== finished) return
        val then = onFinished
        stopSound()
        then?.invoke()
    }

    override fun stopSound() {
        preview?.let { old ->
            preview = null
            // Stopping libVLC can block for a moment, so it happens off the UI thread.
            Thread({
                runCatching { old.controls().stop() }
                runCatching { old.release() }
            }, "prism-preview-release").start()
        }
        val current = clip ?: return
        clip = null
        onFinished = null
        runCatching { current.stop() }
        runCatching { current.close() }
    }

    override fun today(): Pair<Int, Int> = java.time.LocalDate.now().let { it.monthValue to it.dayOfMonth }

    override suspend fun deviceFonts(): List<PrismFolderFile> = withContext(Dispatchers.IO) {
        // Fonts installed for everyone, then the ones installed just for this user.
        val folders = listOfNotNull(
            System.getenv("WINDIR")?.let { File(it, "Fonts") } ?: File("C:\\Windows\\Fonts"),
            System.getenv("LOCALAPPDATA")?.let { File(it, "Microsoft\\Windows\\Fonts") },
        )
        folders.flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.name.lowercase() }
            .map { PrismFolderFile(it.name, it.length(), it.absolutePath) }
    }

    override fun loadFont(path: String): androidx.compose.ui.text.font.FontFamily? = runCatching {
        val file = File(path)
        if (!file.isFile) return null
        // Java refuses files that aren't real fonts, so a broken one never reaches the screen.
        java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, file)
        androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.platform.Font(file))
    }.getOrNull()
}


/** Takes clip frames from libVLC's thread and hands the newest one to Compose (like the built-in player). */
private class PrismFrameSink : BufferFormatCallback, RenderCallback {
    private val lock = Any()
    private var width = 0
    private var height = 0
    private var pixels = ByteArray(0)
    private var dirty = false
    private var bitmap: Bitmap? = null

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        val scale = minOf(1.0, 1920.0 / sourceWidth, 1080.0 / sourceHeight)
        val w = max(2, (sourceWidth * scale).roundToInt())
        val h = max(2, (sourceHeight * scale).roundToInt())
        synchronized(lock) {
            width = w
            height = h
            pixels = ByteArray(w * h * 4)
            dirty = false
        }
        return RV32BufferFormat(w, h)
    }

    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit

    override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
        val buffer = nativeBuffers.firstOrNull() ?: return
        synchronized(lock) {
            if (buffer.remaining() < pixels.size) return
            buffer.get(pixels, 0, pixels.size)
            buffer.rewind()
            dirty = true
        }
    }

    fun takeFrame(): ImageBitmap? = synchronized(lock) {
        if (!dirty || width == 0) return null
        dirty = false
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val target = bitmap ?: Bitmap().also { bitmap = it }
        target.installPixels(info, pixels, width * 4)
        target.asComposeImageBitmap()
    }
}
