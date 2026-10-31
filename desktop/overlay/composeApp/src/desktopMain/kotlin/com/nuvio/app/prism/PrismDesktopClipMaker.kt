package com.nuvio.app.prism

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.desktop.DesktopVlc
import java.awt.FileDialog
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.Locale
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

// Prism's clip maker on the PC, as on the phone: open any video, mark the part you want while you watch,
// and save it as a silent clip for Hero clips (H.265, or H.264 if this PC's libVLC can't make H.265) or as
// just its sound (an .m4a) for Sounds. The bundled libVLC does the playing and the cutting, all on the PC.
internal object PrismDesktopClipMaker {
    /** Sound bites can be up to this long (see PrismAudio.MAX_SECONDS). */
    const val MAX_SOUND_MS = 180_000L

    val available: Boolean get() = DesktopVlc.factory != null

    private fun workDir(): File = File(PrismDesktop.dataDir, "clips-work").apply { mkdirs() }

    suspend fun make(sound: Boolean): PrismMadeClip? {
        val source = withContext(Dispatchers.Main) {
            val dialog = FileDialog(null as Frame?, if (sound) "Choose a video to take a sound from" else "Choose a video to cut a clip from", FileDialog.LOAD)
            dialog.file = PrismFileKind.Video.extensions.joinToString(";") { "*.$it" }
            dialog.setFilenameFilter { _, name -> PrismFileKind.Video.matches(name) }
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it) }
        } ?: return null
        // Clips from earlier visits have been copied into Prism by now.
        withContext(Dispatchers.IO) { workDir().listFiles()?.forEach { it.delete() } }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val window = ComposeWindow()
                var done = false
                fun finish(result: PrismMadeClip?) {
                    if (done) return
                    done = true
                    window.dispose()
                    if (cont.isActive) cont.resume(result)
                }
                window.title = if (sound) "Cut a sound bite" else "Make a clip"
                window.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                window.addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent) = finish(null)
                })
                window.setContent {
                    val accent = Color(0xFFB79CFF)
                    MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = Color(0xFF14101F), background = Color(0xFF080612), surface = Color(0xFF080612))) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            ClipEditor(
                                source = source,
                                sound = sound,
                                workDir = workDir(),
                                onSaved = { file, deleted -> finish(PrismMadeClip(PrismFolderFile(file.name, file.length(), file.absolutePath), deleted)) },
                                onCancel = { finish(null) },
                            )
                        }
                    }
                }
                window.setSize(960, 900)
                window.setLocationRelativeTo(null)
                window.isVisible = true
                cont.invokeOnCancellation { SwingUtilities.invokeLater { window.dispose() } }
            }
        }
    }
}

/** How big a saved clip is, by its shorter side (1080 = 1080p). 0 keeps the video's own size. */
private val clipSizes = listOf(1080 to "1080p", 720 to "720p", 0 to "Same as the video")

@Composable
private fun ClipEditor(source: File, sound: Boolean, workDir: File, onSaved: (File, Boolean?) -> Unit, onCancel: () -> Unit) {
    val factory = DesktopVlc.factory
    if (factory == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The PC's video engine isn't available, so videos can't be cut here.")
            TextButton(onClick = onCancel) { Text("Close") }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val frames = remember { PrismFrameSink() }
    val player = remember {
        factory.mediaPlayers().newEmbeddedMediaPlayer().also {
            it.videoSurface().set(factory.videoSurfaces().newVideoSurface(frames, frames, true))
        }
    }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var shortSide by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(-1L) }
    var partEnd by remember { mutableLongStateOf(-1L) }
    var size by remember { mutableIntStateOf(1080) }
    var removeOriginal by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var saving by remember { mutableStateOf<Job?>(null) }
    var saver by remember { mutableStateOf<MediaPlayer?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var usedH264 by remember { mutableStateOf(false) }
    val sourceName = remember(source) { source.nameWithoutExtension.take(60).ifBlank { "clip" } }

    DisposableEffect(player) {
        val events = object : MediaPlayerEventAdapter() {
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                SwingUtilities.invokeLater {
                    if (newLength > 0) {
                        duration = newLength
                        if (end < 0) end = newLength.coerceAtMost(if (sound) 10_000L else 20_000L)
                    }
                }
            }

            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
                val d = runCatching { mediaPlayer.video().videoDimension() }.getOrNull() ?: return
                SwingUtilities.invokeLater {
                    if (d.width > 0 && d.height > 0) {
                        shortSide = minOf(d.width, d.height)
                        landscape = d.width >= d.height
                    }
                }
            }

            override fun playing(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater { playing = true }
            override fun paused(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater { playing = false }
            override fun stopped(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater { playing = false }
            override fun finished(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater { playing = false }

            override fun error(mediaPlayer: MediaPlayer) = SwingUtilities.invokeLater {
                problem = "This PC can't play that video, so it can't be cut here."
            }
        }
        player.events().addMediaPlayerEventListener(events)
        player.media().startPaused(source.toPath().toUri().toString())
        onDispose {
            player.events().removeMediaPlayerEventListener(events)
            val cutting = saver
            // Stopping libVLC can block for a moment, so it happens off the UI thread.
            Thread({
                runCatching { player.controls().stop() }
                runCatching { player.release() }
                if (cutting != null) {
                    runCatching { cutting.controls().stop() }
                    runCatching { cutting.release() }
                }
            }, "prism-clip-maker-release").start()
        }
    }

    // Keeps the time readout moving, stops "Play the part" at the end mark and shows how far a save has got.
    LaunchedEffect(player) {
        while (true) {
            if (dragging == null) position = player.status().time().coerceAtLeast(0L)
            if (duration <= 0) player.status().length().takeIf { it > 0 }?.let {
                duration = it
                if (end < 0) end = it.coerceAtMost(if (sound) 10_000L else 20_000L)
            }
            if (partEnd >= 0 && position >= partEnd) {
                player.controls().setPause(true)
                partEnd = -1
            }
            saver?.let { s ->
                val length = (end - start).coerceAtLeast(1L)
                progress = (((s.status().time() - start).coerceAtLeast(0L) * 100) / length).toInt().coerceIn(0, 99)
            }
            delay(80)
        }
    }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(frames) {
        while (true) {
            withFrameNanos { }
            frames.takeFrame()?.let { frame = it }
        }
    }

    fun seek(ms: Long) {
        val to = ms.coerceIn(0L, duration.coerceAtLeast(0L))
        player.controls().setTime(to)
        position = to
    }

    fun togglePlay() {
        if (player.status().isPlaying) player.controls().setPause(true) else player.controls().play()
    }

    val length = end - start
    val tooLong = sound && length > PrismDesktopClipMaker.MAX_SOUND_MS
    val canSave = duration > 0 && length >= 300 && !tooLong && saving == null && problem == null

    /** Cuts [start]..[end] of the video into [out] with libVLC; true once a real file is written. */
    suspend fun cut(out: File, chain: String, vararg extra: String): Boolean = withContext(Dispatchers.IO) {
        out.delete()
        val cutter = factory.mediaPlayers().newMediaPlayer()
        withContext(Dispatchers.Main) { saver = cutter }
        try {
            val dst = out.absolutePath.replace('\\', '/')
            val finished = withTimeoutOrNull(60 * 60_000L) {
                suspendCancellableCoroutine { cont ->
                    cutter.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                        override fun finished(mediaPlayer: MediaPlayer) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun error(mediaPlayer: MediaPlayer) {
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    val started = cutter.media().play(
                        source.toPath().toUri().toString(),
                        ":sout=#$chain:std{access=file,mux=mp4,dst=\"$dst\"}",
                        ":no-sout-spu",
                        String.format(Locale.US, ":start-time=%.3f", start / 1000.0),
                        String.format(Locale.US, ":stop-time=%.3f", end / 1000.0),
                        *extra,
                    )
                    if (!started && cont.isActive) cont.resume(false)
                }
            }
            finished == true && out.length() > 1024
        } finally {
            withContext(Dispatchers.Main) { saver = null }
            runCatching { cutter.controls().stop() }
            runCatching { cutter.release() }
        }
    }

    fun save() {
        player.controls().setPause(true)
        val safeName = sourceName.replace(Regex("[\\\\/:*?\"<>|']"), "_")
        val out = File(workDir, "$safeName ${stamp(start).replace(':', '.')}${if (sound) ".m4a" else ".mp4"}")
        progress = 0
        saving = scope.launch {
            val made = if (sound) {
                cut(out, "transcode{vcodec=none,acodec=mp4a,ab=192,channels=2,samplerate=44100}", ":no-sout-video")
            } else {
                // Only ever made smaller, never blown up.
                val scale = if (size > 0 && shortSide > size) (if (landscape) ",height=$size" else ",width=$size") else ""
                val hevc = cut(out, "transcode{vcodec=hevc$scale,acodec=none}", ":no-sout-audio")
                hevc || cut(out, "transcode{vcodec=h264$scale,acodec=none}", ":no-sout-audio").also { if (it) usedH264 = true }
            }
            saving = null
            if (made) {
                val deleted = if (removeOriginal) withContext(Dispatchers.IO) { runCatching { source.delete() }.getOrDefault(false) } else null
                onSaved(out, deleted)
            } else {
                out.delete()
                problem = "That part couldn't be saved. Try a shorter part or a smaller size."
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (sound) "Cut a sound bite" else "Make a clip", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                saving?.cancel()
                onCancel()
            }) { Text("Cancel") }
        }
        Text(source.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.fillMaxWidth().height(400.dp).background(Color.Black).clickable { togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: if (problem == null) CircularProgressIndicator() else Unit
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }

        val shown = dragging?.toLong() ?: position
        Text("${stamp(shown)} of ${stamp(duration)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = shown.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
            onValueChange = { value ->
                dragging = value
                seek(value.toLong())
            },
            onValueChangeFinished = {
                dragging?.let { seek(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-5_000L to "−5s", -500L to "−½s", 500L to "+½s", 5_000L to "+5s").forEach { (step, label) ->
                OutlinedButton(onClick = { seek(player.status().time() + step) }, modifier = Modifier.weight(1f), enabled = duration > 0) {
                    Text(label, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Button(onClick = { togglePlay() }, modifier = Modifier.fillMaxWidth(), enabled = duration > 0) {
            Text(if (playing) "Pause" else "Play")
        }

        Text("Mark the part you want while you watch:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    start = player.status().time().coerceAtLeast(0L)
                    if (end <= start) end = (start + if (sound) 5_000L else 15_000L).coerceAtMost(duration)
                },
                modifier = Modifier.weight(1f),
                enabled = duration > 0,
            ) { Text("Start here\n${stamp(start)}", fontSize = 13.sp) }
            OutlinedButton(
                onClick = {
                    end = player.status().time().coerceAtLeast(0L)
                    if (start >= end) start = (end - if (sound) 5_000L else 15_000L).coerceAtLeast(0L)
                },
                modifier = Modifier.weight(1f),
                enabled = duration > 0,
            ) { Text("End here\n${stamp(end.coerceAtLeast(0L))}", fontSize = 13.sp) }
        }
        OutlinedButton(
            onClick = {
                seek(start)
                partEnd = end
                player.controls().play()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = duration > 0 && length > 0,
        ) { Text("Play the part (${stamp(length.coerceAtLeast(0L))})") }
        if (tooLong) Text("Sound bites can be up to 3 minutes. Move the start or end closer together.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)

        if (!sound) {
            Text("Size", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                clipSizes.forEach { (value, label) ->
                    FilterChip(selected = size == value, onClick = { size = value }, label = { Text(label) })
                }
            }
            Text(
                if (usedH264) "This PC couldn't make H.265, so it was saved as H.264 with no sound." else "Saved as H.265 with no sound, ready for Hero clips.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Only the sound is kept. You can trim it more and level it on the next screen.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(Modifier.fillMaxWidth().clickable { removeOriginal = !removeOriginal }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = removeOriginal, onCheckedChange = { removeOriginal = it })
            Text("Delete the original video from this PC once it's saved, to free up space", fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
        if (saving != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Saving… $progress%", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    saving?.cancel()
                    saving = null
                    saver?.let { s -> Thread({ runCatching { s.controls().stop() } }, "prism-clip-stop").start() }
                }) { Text("Stop") }
            }
        } else {
            Button(onClick = { problem = null; save() }, modifier = Modifier.fillMaxWidth(), enabled = canSave || (problem != null && duration > 0)) {
                Text(if (sound) "Use this sound" else "Save the clip", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun stamp(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0L) / 100) % 10
    val seconds = ms.coerceAtLeast(0L) / 1000
    val h = seconds / 3600
    val m = (seconds / 60) % 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%d", h, m, s, tenths) else String.format(Locale.US, "%d:%02d.%d", m, s, tenths)
}
