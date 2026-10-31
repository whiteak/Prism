package com.prism.clipper

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: ClipperModel by viewModels()
    private val pictures: PictureModel by viewModels()
    private val folder: FolderModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = Colors) {
                Surface(Modifier.fillMaxSize(), color = Colors.background) { App(model, pictures, folder) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        if (contentResolver.getType(uri)?.startsWith("image/") == true) {
            model.tab = Section.Pictures
            pictures.open(uri)
        } else {
            model.tab = Section.Clips
            model.open(uri)
        }
    }

    override fun onStop() {
        super.onStop()
        model.player.pause()
    }
}

private val Colors = darkColorScheme(
    primary = Color(0xFFB79CFF),
    secondary = Color(0xFF7FE0F0),
    background = Color(0xFF120E1C),
    surface = Color(0xFF120E1C),
    surfaceVariant = Color(0xFF241C38),
)

@Composable
private fun App(model: ClipperModel, pictures: PictureModel, folder: FolderModel) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Clipper", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        TabRow(selectedTabIndex = model.tab.ordinal, containerColor = Color.Transparent) {
            Section.entries.forEach { t ->
                Tab(selected = model.tab == t, onClick = { model.tab = t }, text = { Text(t.label) })
            }
        }
        when (model.tab) {
            Section.Clips -> ClipsScreen(model, pictures)
            Section.Pictures -> PictureScreen(pictures, model)
            Section.Folder -> FolderScreen(folder, model, pictures)
        }
    }
}

@Composable
private fun ClipsScreen(model: ClipperModel, pictures: PictureModel) {
    val scope = rememberCoroutineScope()
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.open(uri)
    }
    val saveVideoAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { model.savedAs(it) }
    val saveAudioAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { model.savedAs(it) }
    val systemDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        model.originalDeleted(it.resultCode == Activity.RESULT_OK)
    }
    val ask = model.pendingAsk
    LaunchedEffect(ask) {
        if (ask != null) (if (model.isSoundBite) saveAudioAs else saveVideoAs).launch(ask)
    }

    val pickTypes = arrayOf("video/*", "audio/*")
    if (model.source == null) {
        Text("Pick one of your videos (or an audio file) to cut a clip or a sound bite from, or to shrink. You can also share a video to Clipper from your gallery.")
        Button(onClick = { pickFile.launch(pickTypes) }, Modifier.fillMaxWidth()) { Text("Choose a video or audio file") }
        StatusText(model.status)
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(model.sourceName + if (model.sourceBytes > 0) " (${megabytes(model.sourceBytes)})" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { pickFile.launch(pickTypes) }) { Text("Change") }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { player = model.player } },
            modifier = Modifier.fillMaxSize(),
        )
    }

    if (model.durationMs <= 0) {
        Text("Loading…")
        return
    }

    if (!model.sourceIsAudio) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    val frame = model.grabFrame()
                    if (frame != null) {
                        pictures.openFrame(frame, model.sourceName.substringBeforeLast('.') + " still")
                        model.tab = Section.Pictures
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grab this moment as a picture") }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = model.mode == m,
                    onClick = { model.chooseMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                ) { Text(m.label) }
            }
        }
    }

    val mode = model.effectiveMode
    if (mode == Mode.Shrink) {
        Text(
            "Re-encodes the whole video to H.265 to save space. High keeps it looking the same as the original; " +
                "Medium and Small save more. Check the copy, then delete the original once you're happy.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (model.sourceAlreadyEfficient) {
            Text("This video is already in an efficient format, so it will only shrink a little at High.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    } else {
        Trimmer(model)
        Parts(model)
    }

    if (mode == Mode.SoundBite) {
        Text("Saves just the sound as an .m4a file (AAC).", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            if (model.hasHevc) "Encodes to H.265 (HEVC) in an .mp4 file."
            else "This phone has no H.265 encoder, so it uses H.264 instead.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (mode == Mode.Clip) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep the sound", Modifier.weight(1f))
                Switch(checked = model.keepSound, onCheckedChange = model::chooseKeepSound)
            }
            if (model.parts.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Smooth loop")
                        Text(
                            "Blends the end into the start so a hero clip repeats without a jump. The clip comes out that much shorter. Normal speed only.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = model.smoothLoop, onCheckedChange = model::chooseSmoothLoop)
                }
                if (model.smoothLoop) {
                    ChipRow("Blend length", JoinLengths, model.loopFadeMs, { "%.2g s".format(it / 1000.0) }, model::chooseLoopFade)
                }
            }
        }
        ChipRow("Size", ClipSize.entries, model.size, { it.label }, model::chooseSize)
        ChipRow("Quality", Quality.entries, model.quality, { it.label }, model::chooseQuality)
        ChipRow("Speed", Speeds, model.speed, { if (it == 1f) "Normal" else "${it}x" }) { model.speed = it }
        ChipRow("Turn", listOf(0, 90, 180, 270), model.rotation, { if (it == 0) "Upright" else "$it°" }) { model.rotation = it }
        ChipRow("Crop", VideoCrop.entries, model.crop, { it.label }) { model.crop = it }
    }

    if (model.soundOptionsAllowed) {
        ChipRow("Fade in", Fades, model.fadeInMs, ::fadeLabel, model::chooseFadeIn)
        ChipRow("Fade out", Fades, model.fadeOutMs, ::fadeLabel, model::chooseFadeOut)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Even out the volume")
                Text("Makes quiet and loud bites play at the same level.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = model.evenVolume, onCheckedChange = model::chooseEvenVolume)
        }
    } else if (mode == Mode.Clip && model.keepSound && model.speed != 1f) {
        Text("Fades and volume levelling work at normal speed.", style = MaterialTheme.typography.bodySmall)
    }

    OutlinedTextField(
        value = model.fileName,
        onValueChange = { model.fileName = it },
        label = { Text(if (model.parts.size > 1 && model.joinParts) "Name of the joined file" else if (model.parts.isNotEmpty()) "File name (for new parts)" else "File name") },
        suffix = { Text(".${model.extension}") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    SaveLocation(model)

    when (val s = model.status) {
        is Status.Working -> {
            LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (model.pendingAsk != null) "Choose where to save it…" else "${s.label}… ${s.percent}%", Modifier.weight(1f))
                if (model.pendingAsk == null) OutlinedButton(onClick = model::cancel) { Text("Cancel") }
            }
        }
        is Status.Done -> {
            StatusText(s)
            DeleteOriginalButton(model.sourceName) {
                model.deleteOriginal()?.let { systemDelete.launch(IntentSenderRequest.Builder(it).build()) }
            }
        }
        else -> StatusText(s)
    }

    val makeLabel = when {
        mode == Mode.Shrink -> "Shrink video"
        model.parts.size > 1 && model.joinParts -> "Join ${model.parts.size} parts"
        model.parts.size > 1 -> "Make all ${model.parts.size}"
        mode == Mode.SoundBite -> "Make sound bite"
        else -> "Make clip"
    }
    Button(
        onClick = model::make,
        enabled = model.status !is Status.Working,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(makeLabel) }
    Spacer(Modifier.height(24.dp))
}

/** The list of parts, from one video or several, to make separately or join into one. */
@Composable
private fun Parts(model: ClipperModel) {
    OutlinedButton(onClick = model::addPart, Modifier.fillMaxWidth()) {
        Text(if (model.parts.isEmpty()) "Add this part to a list (for several clips or joining)" else "Add this part to the list")
    }
    if (model.parts.isEmpty()) return
    model.parts.forEach { part ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${part.name}: ${part.sourceName.substringBeforeLast('.')} ${time(part.startMs)} to ${time(part.endMs)}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { model.removePart(part) }) { Text("Remove") }
        }
    }
    Text("Tip: tap Change to pick another video and add parts from it too.", style = MaterialTheme.typography.bodySmall)
    if (model.parts.size > 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Join into one file")
                Text("Plays the parts back to back with no gap, in this order. Different sized videos are fitted to the first one.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = model.joinParts, onCheckedChange = model::chooseJoin)
        }
        if (model.joinParts) {
            ChipRow("Join style", JoinStyle.entries, model.joinStyle, { it.label }, model::chooseJoinStyle)
            if (model.joinStyle != JoinStyle.Cut) {
                ChipRow("${model.joinStyle.label} length", JoinLengths, model.joinMs, { "%.2g s".format(it / 1000.0) }, model::chooseJoinMs)
                Text(
                    when (model.joinStyle) {
                        JoinStyle.Dip -> "Each part fades to black and the next fades up. The sound dips with it."
                        else -> "Each part dissolves into the next, sound too. The joined file is a little shorter because the parts overlap."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (model.speed != 1f && !model.isSoundBite) {
                    Text("${model.joinStyle.label} works at normal speed; at other speeds the parts join with straight cuts.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    TextButton(onClick = model::clearParts) { Text("Clear the list") }
}

@Composable
private fun <T> ChipRow(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Text(title)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) }) }
    }
}

/** The file's sound drawn as a wave, with the chosen part lit up and a line where the player is. Tap or drag to move. */
@Composable
private fun SoundWave(model: ClipperModel) {
    val peaks = model.waveform
    if (peaks == null) {
        if (model.waveformLoading) Text("Reading the sound…", style = MaterialTheme.typography.bodySmall)
        return
    }
    var position by remember { mutableStateOf(0L) }
    LaunchedEffect(model.player) {
        while (true) {
            position = model.player.currentPosition
            kotlinx.coroutines.delay(100)
        }
    }
    val lit = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val duration = model.durationMs.coerceAtLeast(1)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF1C1530))
            .pointerInput(duration) {
                detectTapGestures { model.seekTo((it.x / size.width * duration).toLong()) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures { change, _ -> model.seekTo((change.position.x / size.width * duration).toLong()) }
            },
    ) {
        val barWidth = size.width / peaks.size
        val mid = size.height / 2
        peaks.forEachIndexed { i, p ->
            val ms = i.toLong() * duration / peaks.size
            val h = (p * mid).coerceAtLeast(1f)
            drawRect(
                if (ms in model.startMs..model.endMs) lit else dim,
                topLeft = Offset(i * barWidth, mid - h),
                size = Size(barWidth.coerceAtLeast(1f), h * 2),
            )
        }
        val x = position.toFloat() / duration * size.width
        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
    }
}

private fun fadeLabel(ms: Long): String = if (ms == 0L) "None" else "%.2g s".format(ms / 1000.0)

@Composable
private fun Trimmer(model: ClipperModel) {
    val duration = model.durationMs.toFloat()
    Text("Start ${time(model.startMs)}   End ${time(model.endMs)}   Length ${seconds(model.endMs - model.startMs)}")
    SoundWave(model)
    RangeSlider(
        value = model.startMs.toFloat()..model.endMs.toFloat(),
        onValueChange = { range ->
            val start = range.start.toLong()
            val end = range.endInclusive.toLong()
            if (end - start >= 100) {
                if (start != model.startMs) model.player.seekTo(start) else if (end != model.endMs) model.player.seekTo(end)
                model.startMs = start
                model.endMs = end
            }
        },
        valueRange = 0f..duration,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = model::setStartHere, Modifier.weight(1f)) { Text("Start here") }
        OutlinedButton(onClick = model::setEndHere, Modifier.weight(1f)) { Text("End here") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Start", Modifier.weight(1f))
        TextButton(onClick = { model.nudgeStart(-1000) }) { Text("-1s") }
        TextButton(onClick = { model.nudgeStart(-100) }) { Text("-0.1") }
        TextButton(onClick = { model.nudgeStart(100) }) { Text("+0.1") }
        TextButton(onClick = { model.nudgeStart(1000) }) { Text("+1s") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("End", Modifier.weight(1f))
        TextButton(onClick = { model.nudgeEnd(-1000) }) { Text("-1s") }
        TextButton(onClick = { model.nudgeEnd(-100) }) { Text("-0.1") }
        TextButton(onClick = { model.nudgeEnd(100) }) { Text("+0.1") }
        TextButton(onClick = { model.nudgeEnd(1000) }) { Text("+1s") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = model::playSelection, Modifier.weight(1f)) { Text("Play the part") }
        OutlinedButton(onClick = model::toggleLoop, Modifier.weight(1f)) { Text(if (model.looping) "Stop looping" else "Play on repeat") }
    }
}

enum class Section(val label: String) { Clips("Clips"), Pictures("Pictures"), Folder("Prism folder") }

/** Where finished files go; shared by every tab. */
@Composable
fun SaveLocation(model: ClipperModel) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) model.setFolder(tree)
    }
    Text("Save to")
    SaveChoice("Prism folder (${Saver.PRISM_FOLDER})", model.saveTo == SaveTo.Prism) { model.chooseSaveTo(SaveTo.Prism) }
    SaveChoice(
        if (model.myFolder != null) "My folder: ${model.myFolderName}" else "A folder I choose",
        model.saveTo == SaveTo.MyFolder,
    ) {
        if (model.myFolder == null) pickFolder.launch(null) else model.chooseSaveTo(SaveTo.MyFolder)
    }
    if (model.myFolder != null) {
        TextButton(onClick = { pickFolder.launch(null) }) { Text("Pick a different folder") }
    }
    SaveChoice("Ask where each time (any location)", model.saveTo == SaveTo.AskEachTime) { model.chooseSaveTo(SaveTo.AskEachTime) }
}

@Composable
fun StatusText(status: Status) {
    when (status) {
        is Status.Done -> Text(status.message, color = MaterialTheme.colorScheme.secondary)
        is Status.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
        is Status.Working -> LinearProgressIndicator(progress = { status.percent / 100f }, modifier = Modifier.fillMaxWidth())
        Status.Idle -> {}
    }
}

/** Asks first, then deletes the file the new one was made from. */
@Composable
fun DeleteOriginalButton(name: String, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete the original?") },
            text = { Text("This permanently deletes $name from your phone. Your new file stays.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep it") } },
        )
    }
    OutlinedButton(onClick = { confirm = true }, Modifier.fillMaxWidth()) { Text("Delete the original to free up space") }
}

@Composable
private fun SaveChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

private fun time(ms: Long): String {
    val tenths = (ms / 100) % 10
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d.%d".format(s / 3600, (s / 60) % 60, s % 60, tenths)
    else "%d:%02d.%d".format(s / 60, s % 60, tenths)
}

private fun seconds(ms: Long): String = "%.1f s".format(ms / 1000.0)
