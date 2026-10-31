@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.prism

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

// Prism's clip maker on the phone: open any video, mark the part you want, and save it either as a
// silent H.265 clip for Hero clips or as just its sound (an .m4a) for Sounds. It all happens on the phone.
class PrismClipActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sound = intent.getBooleanExtra(EXTRA_SOUND, false)
        val accent = Color(0xFFB79CFF)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = Color(0xFF14101F), background = Color(0xFF080612), surface = Color(0xFF080612))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PrismClipScreen(
                        sound = sound,
                        onSaved = { file, name, deleted ->
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(EXTRA_PATH, file.absolutePath).putExtra(EXTRA_NAME, name)
                                    .putExtra(EXTRA_DELETED, when (deleted) { null -> 0; true -> 1; false -> 2 }),
                            )
                            finish()
                        },
                        onCancel = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_SOUND = "prism_sound"
        const val EXTRA_PATH = "prism_path"
        const val EXTRA_NAME = "prism_name"
        const val EXTRA_DELETED = "prism_deleted"

        /** Prism's sound bites can be up to this long (see PrismAudio.MAX_SECONDS). */
        const val MAX_SOUND_MS = 180_000L

        fun workDir(context: Context): File = File(context.cacheDir, "prism-clips").apply { mkdirs() }
    }
}

/** Opens the clip maker; the answer is the made file, or null if cancelled. */
internal class PrismClipContract : ActivityResultContract<Boolean, PrismMadeClip?>() {
    override fun createIntent(context: Context, input: Boolean): Intent =
        Intent(context, PrismClipActivity::class.java).putExtra(PrismClipActivity.EXTRA_SOUND, input)

    override fun parseResult(resultCode: Int, intent: Intent?): PrismMadeClip? {
        if (resultCode != Activity.RESULT_OK) return null
        val path = intent?.getStringExtra(PrismClipActivity.EXTRA_PATH) ?: return null
        val name = intent.getStringExtra(PrismClipActivity.EXTRA_NAME) ?: File(path).name
        val deleted = when (intent.getIntExtra(PrismClipActivity.EXTRA_DELETED, 0)) { 1 -> true; 2 -> false; else -> null }
        return PrismMadeClip(PrismFolderFile(name, File(path).length(), path), deleted)
    }
}

/** Deletes a file picked from the device, if the place it's kept allows that. */
internal fun deleteDeviceFile(context: Context, ref: String): Boolean = runCatching {
    val uri = Uri.parse(ref)
    if (uri.scheme != "content") return@runCatching File(ref).delete()
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false) ||
        context.contentResolver.delete(uri, null, null) > 0
}.getOrDefault(false)

/** How big a saved clip is, by its shorter side (1080 = 1080p). 0 keeps the video's own size. */
private val clipSizes = listOf(1080 to "1080p", 720 to "720p", 0 to "Same as the video")

@Composable
private fun PrismClipScreen(sound: Boolean, onSaved: (File, String, Boolean?) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) onCancel() else source = uri.toString()
    }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            // Old clips from earlier visits have been copied into Prism by now.
            PrismClipActivity.workDir(context).listFiles()?.forEach { it.delete() }
            picker.launch(arrayOf("video/*"))
        }
    }
    val uri = source?.let(Uri::parse)
    if (uri == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        PrismClipEditor(uri, sound, onSaved, onCancel)
    }
}

@Composable
private fun PrismClipEditor(uri: Uri, sound: Boolean, onSaved: (File, String, Boolean?) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    var shortSide by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var start by rememberSaveable { mutableLongStateOf(0L) }
    var end by rememberSaveable { mutableLongStateOf(-1L) }
    var partEnd by remember { mutableLongStateOf(-1L) }
    var size by rememberSaveable { mutableIntStateOf(1080) }
    var removeOriginal by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    val h265 = remember { runCatching { EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H265).isNotEmpty() }.getOrDefault(false) }
    val sourceName = remember(uri) { displayName(context, uri)?.substringBeforeLast('.')?.take(60) ?: "clip" }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && player.duration != C.TIME_UNSET) {
                    duration = player.duration
                    if (end < 0) end = duration.coerceAtMost(if (sound) 10_000L else 20_000L)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    shortSide = minOf(videoSize.width, videoSize.height)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                problem = "This phone can't play that video, so it can't be cut here."
            }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
            transformer?.cancel()
        }
    }

    // Keeps the time readout moving, and stops "Play the part" at the end mark.
    LaunchedEffect(player) {
        while (true) {
            if (dragging == null) position = player.currentPosition
            if (partEnd >= 0 && position >= partEnd) {
                player.pause()
                partEnd = -1
            }
            transformer?.let { t ->
                val holder = ProgressHolder()
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = holder.progress
            }
            delay(80)
        }
    }

    fun seek(ms: Long, exact: Boolean = true) {
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(ms.coerceIn(0L, duration.coerceAtLeast(0L)))
        position = ms.coerceIn(0L, duration.coerceAtLeast(0L))
    }

    val length = end - start
    val tooLong = sound && length > PrismClipActivity.MAX_SOUND_MS
    val canSave = duration > 0 && length >= 300 && !tooLong && transformer == null && problem == null

    fun save() {
        player.pause()
        val out = File(PrismClipActivity.workDir(context), "${sourceName.replace(Regex("[\\\\/:*?\"<>|]"), "_")} ${stamp(start).replace(':', '.')}${if (sound) ".m4a" else ".mp4"}")
        out.delete()
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build())
            .build()
        val edited = EditedMediaItem.Builder(item).apply {
            if (sound) {
                setRemoveVideo(true)
            } else {
                setRemoveAudio(true)
                // Only ever made smaller, never blown up.
                if (size > 0 && shortSide > size) setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(size))))
            }
        }.build()
        val made = Transformer.Builder(context)
            .setVideoMimeType(if (h265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    player.release()
                    onSaved(out, out.name, if (removeOriginal) deleteDeviceFile(context, uri.toString()) else null)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    out.delete()
                    problem = "That part couldn't be saved (${exportException.errorCodeName}). Try a shorter part or a smaller size."
                }
            })
            .build()
        progress = 0
        transformer = made
        runCatching { made.start(edited, out.absolutePath) }.onFailure {
            transformer = null
            problem = "That part couldn't be saved (${it.message})."
        }
    }

    BackHandler {
        val t = transformer
        if (t != null) {
            t.cancel()
            transformer = null
            PrismClipActivity.workDir(context).listFiles()?.forEach { it.delete() }
        } else {
            onCancel()
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sound) "Cut a sound bite" else "Make a clip",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        Box(
            Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(videoAspect.coerceIn(0.4f, 3f), matchHeightConstraintsFirst = false)
                .background(Color.Black)
                .clickable { if (player.isPlaying) player.pause() else player.play() },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } }, modifier = Modifier.fillMaxSize())
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }

        val shown = dragging?.toLong() ?: position
        Text("${stamp(shown)} of ${stamp(duration)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = shown.toFloat().coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
            onValueChange = { value ->
                dragging = value
                seek(value.toLong(), exact = false)
            },
            onValueChangeFinished = {
                dragging?.let { seek(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            enabled = duration > 0,
            colors = SliderDefaults.colors(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-5_000L to "−5s", -500L to "−½s", 500L to "+½s", 5_000L to "+5s").forEach { (step, label) ->
                OutlinedButton(onClick = { seek(player.currentPosition + step) }, modifier = Modifier.weight(1f), enabled = duration > 0) {
                    Text(label, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        Button(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.fillMaxWidth(), enabled = duration > 0) {
            Text(if (playing) "Pause" else "Play")
        }

        Text("Mark the part you want while you watch:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    start = player.currentPosition
                    if (end <= start) end = (start + if (sound) 5_000L else 15_000L).coerceAtMost(duration)
                },
                modifier = Modifier.weight(1f),
                enabled = duration > 0,
            ) { Text("Start here\n${stamp(start)}", fontSize = 13.sp) }
            OutlinedButton(
                onClick = {
                    end = player.currentPosition
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
                player.play()
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
                if (h265) "Saved as H.265 with no sound, ready for Hero clips." else "This phone can't make H.265, so it's saved as H.264 with no sound.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Only the sound is kept. You can trim it more and level it on the next screen.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(
            Modifier.fillMaxWidth().clickable { removeOriginal = !removeOriginal },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = removeOriginal, onCheckedChange = { removeOriginal = it })
            Text(
                "Delete the original video from this phone once it's saved, to free up space",
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
        }
        if (transformer != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Saving… $progress%", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    transformer?.cancel()
                    transformer = null
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

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
