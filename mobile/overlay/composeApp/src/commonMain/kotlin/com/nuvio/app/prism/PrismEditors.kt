package com.nuvio.app.prism

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.httpRequestRaw
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Prism: the editors Customise opens on top of itself — picking a file, cropping a picture,
// trimming a sound and finding a movie or show.

/** A full-screen panel with a title bar; Back closes it. */
@Composable
internal fun PrismPanel(
    title: String,
    onClose: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    PlatformBackHandler(enabled = true, onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            .pointerInput(Unit) { detectDragGestures { _, _ -> } },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 36.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.TextButton(onClick = onClose) { Text("‹ Back", color = tokens.colors.accent) }
            Text(
                title,
                color = tokens.colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
internal fun PrismButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = tokens.colors.accent, contentColor = tokens.colors.onAccent),
        ) { Text(label, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.colors.accent),
        ) { Text(label) }
    }
}

// ---- Where a file comes from: the device's picker or the Prism folder ----

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PrismSourcePanel(
    kind: PrismFileKind,
    prefer: String? = null,
    onPicked: (PrismPickedFile) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val platform = Prism.platform ?: return
    var files by remember { mutableStateOf<List<PrismFolderFile>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf<PrismFolderFile?>(null) }
    previewing?.let { f -> PrismPreviewDialog(f.ref, f.name) { previewing = null } }
    var order by remember { mutableStateOf(0) }
    // Which folder inside the Prism folder to show: null for all of them.
    var inside by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val found = runCatching { platform.listFolder().filter { kind.matches(it.name) } }.getOrDefault(emptyList())
        // Start on the folder made for this (Clipper's Phone, TV&PC or Icons) when it has something in it.
        inside = prefer?.let { p -> found.firstOrNull { it.folder.equals(p, ignoreCase = true) }?.folder }
        files = found
    }
    PrismPanel(title = "Choose ${kind.label.removeSuffix("s")}", onClose = onClose) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                PrismButton("From this device…", Modifier.fillMaxWidth(), enabled = !busy, primary = true) {
                    scope.launch {
                        busy = true
                        val picked = runCatching { platform.pickFile(kind) }.getOrNull()
                        busy = false
                        if (picked != null) onPicked(picked)
                    }
                }
            }
            if (kind == PrismFileKind.Sound && platform.canMakeClips) {
                item {
                    PrismButton("Cut from a video…", Modifier.fillMaxWidth(), enabled = !busy) {
                        scope.launch {
                            busy = true
                            val made = runCatching { platform.makeClip(sound = true) }.getOrNull()
                            val bytes = made?.let { platform.readBytes(it.file.ref) }
                            made?.let { platform.delete(it.file.ref) }
                            busy = false
                            if (made != null && bytes != null) onPicked(PrismPickedFile(made.file.name, bytes))
                        }
                    }
                }
            }
            item {
                Text(
                    platform.folderLabel?.let { "Or from the Prism folder ($it):" }
                        ?: "Or link the Prism folder (Customise > Prism folder) to pick files sent to this device.",
                    color = tokens.colors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            val folderOrders = listOf("Newest", "Oldest", "A to Z", "Z to A", "Biggest", "Smallest")
            // The folder lists newest first.
            val folders = files.orEmpty().map { it.folder }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() }
            val list = files?.filter { inside == null || it.folder == inside }?.let { all ->
                when (order) {
                    1 -> all.reversed()
                    2 -> all.sortedBy { it.name.lowercase() }
                    3 -> all.sortedByDescending { it.name.lowercase() }
                    4 -> all.sortedByDescending { it.size }
                    5 -> all.sortedBy { it.size }
                    else -> all
                }
            }
            if (folders.isNotEmpty()) {
                item {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.nuvio.app.core.ui.Chip(label = "All", selected = inside == null, onClick = { inside = null })
                        if (files.orEmpty().any { it.folder.isEmpty() }) {
                            com.nuvio.app.core.ui.Chip(label = "Top", selected = inside == "", onClick = { inside = "" })
                        }
                        folders.forEach { f -> com.nuvio.app.core.ui.Chip(label = f, selected = inside == f, onClick = { inside = f }) }
                    }
                }
            }
            if ((files?.size ?: 0) > 1) {
                item {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        folderOrders.forEachIndexed { i, label -> com.nuvio.app.core.ui.Chip(label = label, selected = order == i, onClick = { order = i }) }
                    }
                }
            }
            if (list == null) {
                item { CircularProgressIndicator(color = tokens.colors.accent) }
            } else if (list.isEmpty() && platform.folderLabel != null) {
                item { Text(if (inside.isNullOrEmpty()) "No ${kind.label} there yet." else "No ${kind.label} in $inside yet.", color = tokens.colors.textMuted, fontSize = 13.sp) }
            }
            items(list.orEmpty(), key = { it.ref }) { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.colors.surfaceCard)
                        .clickable(enabled = !busy) {
                            scope.launch {
                                busy = true
                                val bytes = runCatching { platform.readFolderFile(file) }.getOrNull()
                                busy = false
                                if (bytes != null) onPicked(PrismPickedFile(file.name, bytes, file.ref))
                            }
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Tap the little picture to see or hear it; tap the rest to use it.
                    PrismThumb(file.ref, file.name, onClick = { previewing = file })
                    Text(
                        if (file.folder.isEmpty() || inside != null) file.name else "${file.folder} / ${file.name}",
                        color = tokens.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(sizeLabel(file.size), color = tokens.colors.textMuted, fontSize = 12.sp)
                }
            }
        }
        if (busy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = tokens.colors.accent) }
    }
}

internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> "${(bytes / 100_000) / 10.0} MB"
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

// ---- Cropping ----

/**
 * Shows the whole picture with a frame in the screen's shape; drag the frame to move it and use the
 * slider to make it bigger or smaller. [shapes] lets the user set the phone and the wide crop in turn.
 */
@Composable
internal fun PrismCropPanel(
    title: String,
    image: ImageBitmap,
    shapes: List<PrismShape>,
    initial: Map<PrismShape, PrismCrop?>,
    onDone: (Map<PrismShape, PrismCrop>) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var shape by remember { mutableStateOf(shapes.first()) }
    val crops = remember {
        mutableStateOf(shapes.associateWith { s -> initial[s] ?: centreCrop(image.width, image.height, s) })
    }
    val crop = crops.value.getValue(shape)
    fun set(c: PrismCrop) { crops.value = crops.value + (shape to c) }

    // Frame size relative to the biggest frame of this shape that fits.
    val imgAspect = image.width.toFloat() / image.height
    val maxW = if (imgAspect > shape.aspect) shape.aspect / imgAspect else 1f
    val scale = if (crop.whole) 1f else ((crop.right - crop.left) / maxW).coerceIn(0.15f, 1f)

    fun frame(cx: Float, cy: Float, s: Float): PrismCrop {
        val w = (maxW * s).coerceIn(0.01f, 1f)
        val h = (w * imgAspect / shape.aspect).coerceIn(0.01f, 1f)
        val x = cx.coerceIn(w / 2, 1 - w / 2)
        val y = cy.coerceIn(h / 2, 1 - h / 2)
        return PrismCrop(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
    }

    PrismPanel(
        title = title,
        onClose = onClose,
        actions = { PrismButton("Save", primary = true) { onDone(crops.value) } },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shapes.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shapes.forEach { s ->
                        com.nuvio.app.core.ui.Chip(
                            label = when (s) {
                                PrismShape.Tall -> "Phone (tall)"
                                PrismShape.Wide -> "PC and TV (wide)"
                                PrismShape.Square -> "Square"
                            },
                            selected = s == shape,
                            onClick = { shape = s },
                        )
                    }
                }
            }
            Text(
                if (crop.whole) "The whole picture shows, with a soft blurred copy filling the rest of the screen."
                else "Drag the frame to choose what shows. The slider makes it bigger or smaller. \"Whole picture\" fits all of it in.",
                color = tokens.colors.textSecondary,
                fontSize = 13.sp,
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val boxAspect = maxWidth / maxHeight
                val fitWidth = if (imgAspect > boxAspect) maxWidth else maxHeight * imgAspect
                Box(Modifier.width(fitWidth).aspectRatio(imgAspect)) {
                    Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    val current by androidx.compose.runtime.rememberUpdatedState(crop)
                    val currentShape by androidx.compose.runtime.rememberUpdatedState(shape)
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(shape) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val c = current.takeUnless { it.whole } ?: centreCrop(image.width, image.height, currentShape)
                                    val cx = (c.left + c.right) / 2 + drag.x / size.width
                                    val cy = (c.top + c.bottom) / 2 + drag.y / size.height
                                    val maxWNow = if (imgAspect > currentShape.aspect) currentShape.aspect / imgAspect else 1f
                                    set(frame(cx, cy, (c.right - c.left) / maxWNow))
                                }
                            },
                    ) {
                        if (crop.whole) {
                            drawRect(Color.White, Offset.Zero, size, style = Stroke(2.dp.toPx()))
                            return@Canvas
                        }
                        val l = crop.left * size.width
                        val t = crop.top * size.height
                        val r = crop.right * size.width
                        val b = crop.bottom * size.height
                        val shade = Color.Black.copy(alpha = 0.6f)
                        drawRect(shade, Offset.Zero, Size(size.width, t))
                        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                        drawRect(shade, Offset(0f, t), Size(l, b - t))
                        drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
                        if (shape == PrismShape.Square) {
                            drawCircle(Color.White, radius = (r - l) / 2, center = Offset((l + r) / 2, (t + b) / 2), style = Stroke(2.dp.toPx()))
                        }
                        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = 520.dp)) {
                Text("Size", color = tokens.colors.textSecondary, modifier = Modifier.width(48.dp))
                Slider(
                    value = scale,
                    onValueChange = { s -> set(frame(if (crop.whole) 0.5f else (crop.left + crop.right) / 2, if (crop.whole) 0.5f else (crop.top + crop.bottom) / 2, s)) },
                    valueRange = 0.15f..1f,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismButton("Whole picture", primary = crop.whole) { set(PrismCrop(0f, 0f, 1f, 1f, whole = true)) }
                PrismButton("Fill screen", primary = !crop.whole && scale >= 0.999f) { set(centreCrop(image.width, image.height, shape)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismButton("◀") { set(frame((crop.left + crop.right) / 2 - 0.03f, (crop.top + crop.bottom) / 2, scale)) }
                PrismButton("▲") { set(frame((crop.left + crop.right) / 2, (crop.top + crop.bottom) / 2 - 0.03f, scale)) }
                PrismButton("▼") { set(frame((crop.left + crop.right) / 2, (crop.top + crop.bottom) / 2 + 0.03f, scale)) }
                PrismButton("▶") { set(frame((crop.left + crop.right) / 2 + 0.03f, (crop.top + crop.bottom) / 2, scale)) }
            }
        }
    }
}

// ---- Trimming a sound ----

@Composable
internal fun PrismSoundPanel(
    moment: PrismMoment,
    file: PrismPickedFile,
    title: String = moment.label,
    onDone: (PrismSound) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val platform = Prism.platform ?: return
    var pcm by remember { mutableStateOf<PrismPcm?>(null) }
    var failed by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(0f..1f) }
    var level by remember { mutableStateOf(true) }
    var volume by remember { mutableFloatStateOf(1f) }
    var saving by remember { mutableStateOf(false) }
    var removeOriginal by remember { mutableStateOf(false) }
    LaunchedEffect(file) {
        val decoded = runCatching { platform.decodeAudio(file.name, file.bytes, PrismAudio.MAX_SECONDS) }.getOrNull()
        if (decoded == null || decoded.frames == 0) {
            failed = true
        } else {
            pcm = decoded
            val longest = if (moment == PrismMoment.Opening) 30_000f else 12_000f
            range = 0f..min(1f, longest / decoded.durationMs)
        }
    }
    DisposableEffect(Unit) { onDispose { PrismSounds.stop() } }
    val previewPath = platform.dataDir + "/preview.wav"

    fun processed(): PrismPcm? {
        val p = pcm ?: return null
        return PrismAudio.process(p, (range.start * p.durationMs).toLong(), (range.endInclusive * p.durationMs).toLong(), level, volume)
    }

    PrismPanel(
        title = title,
        onClose = onClose,
        actions = {
            PrismButton("Save", primary = true, enabled = pcm != null && !saving) {
                scope.launch {
                    saving = true
                    val wav = withContext(Dispatchers.Default) { processed()?.let(PrismAudio::toWav) }
                    if (wav != null) {
                        val ref = file.ref
                        if (removeOriginal && ref != null) runCatching { platform.deleteOriginal(ref) }
                        onDone(PrismSound(PrismStore.addMedia(wav, "wav"), file.name.substringBeforeLast('.')))
                    }
                    saving = false
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(file.name, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (file.ref != null) {
                Row(Modifier.fillMaxWidth().clickable { removeOriginal = !removeOriginal }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = removeOriginal, onCheckedChange = { removeOriginal = it })
                    Text("Delete the original file from this device once it's saved, to free up space", color = tokens.colors.textSecondary, fontSize = 13.sp)
                }
            }
            val p = pcm
            when {
                failed -> Text("This file couldn't be read as a sound. Try an MP3, M4A or WAV file.", color = tokens.colors.danger)
                p == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = tokens.colors.accent, modifier = Modifier.size(28.dp))
                    Text("Reading the sound…", color = tokens.colors.textSecondary)
                }
                else -> {
                    val peaks = remember(p) { PrismAudio.peaks(p, 160) }
                    val accent = tokens.colors.accent
                    val muted = tokens.colors.textMuted
                    Canvas(
                        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(tokens.colors.surfaceCard),
                    ) {
                        val bar = size.width / peaks.size
                        peaks.forEachIndexed { i, v ->
                            val x = i * bar + bar / 2
                            val inside = (i + 0.5f) / peaks.size in range
                            val h = max(2f, v * size.height * 0.9f)
                            drawLine(
                                if (inside) accent else muted.copy(alpha = 0.4f),
                                Offset(x, (size.height - h) / 2),
                                Offset(x, (size.height + h) / 2),
                                strokeWidth = max(1f, bar * 0.6f),
                            )
                        }
                    }
                    RangeSlider(value = range, onValueChange = { range = it })
                    val start = (range.start * p.durationMs).toLong()
                    val end = (range.endInclusive * p.durationMs).toLong()
                    Text(
                        "Keeps ${time(start)} to ${time(end)} (${time(end - start)} long)",
                        color = tokens.colors.textSecondary,
                        fontSize = 13.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Even out the volume", color = tokens.colors.textPrimary)
                            Text("So it's as loud as your other sounds", color = tokens.colors.textMuted, fontSize = 12.sp)
                        }
                        Switch(checked = level, onCheckedChange = { level = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Volume ${(volume * 100).roundToInt()} %", color = tokens.colors.textPrimary, modifier = Modifier.width(120.dp))
                        Slider(value = volume, onValueChange = { volume = it }, valueRange = 0.25f..1.5f, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrismButton(if (PrismSounds.playing) "Stop" else "▶ Play this part", primary = true) {
                            if (PrismSounds.playing) {
                                PrismSounds.stop()
                            } else {
                                scope.launch {
                                    val wav = withContext(Dispatchers.Default) { processed()?.let(PrismAudio::toWav) } ?: return@launch
                                    platform.writeBytes(previewPath, wav)
                                    PrismSounds.playFile(previewPath)
                                }
                            }
                        }
                    }
                }
            }
            if (saving) CircularProgressIndicator(color = tokens.colors.accent)
        }
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    val tenths = (ms % 1000) / 100
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}.$tenths"
}

// ---- Finding a movie or show (Cinemeta) ----

internal object PrismCinemeta {
    private const val BASE = "https://v3-cinemeta.strem.io"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(query: String, types: List<String>): List<PrismTitle> = coroutineScope {
        val q = query.trim().encodeUrl()
        types.map { type ->
            async {
                try {
                    val response = httpRequestRaw("GET", "$BASE/catalog/$type/top/search=$q.json", mapOf("Accept" to "application/json"), "")
                    if (response.status !in 200..299) return@async emptyList<PrismTitle>()
                    json.parseToJsonElement(response.body).jsonObject["metas"]?.jsonArray.orEmpty().mapNotNull { element ->
                        val meta = element.jsonObject
                        val id = meta.str("imdb_id") ?: meta.str("id") ?: return@mapNotNull null
                        PrismTitle(
                            id = id,
                            type = type,
                            name = meta.str("name") ?: return@mapNotNull null,
                            poster = meta.str("poster"),
                            year = meta.str("releaseInfo") ?: meta.str("year"),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList<PrismTitle>()
                }
            }
        }.map { it.await() }.let { lists ->
            // Alternate movies and shows so both appear near the top.
            val out = mutableListOf<PrismTitle>()
            val longest = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let(out::add) }
            out.distinctBy { it.id }
        }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun String.encodeUrl(): String = buildString {
        for (c in this@encodeUrl.encodeToByteArray()) {
            val ch = c.toInt().toChar()
            if (ch.isLetterOrDigit() && c >= 0) append(ch) else append('%').append((c.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase())
        }
    }
}

/**
 * Search box with results. With [multiple], each tap adds or removes a title and Done finishes;
 * otherwise the first tap picks it.
 */
@Composable
internal fun PrismTitlePanel(
    title: String,
    types: List<String>,
    multiple: Boolean,
    initial: List<PrismTitle> = emptyList(),
    onDone: (List<PrismTitle>) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PrismTitle>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<PrismTitle>().apply { addAll(initial) } }
    fun run() {
        if (query.isBlank()) return
        scope.launch {
            searching = true
            results = PrismCinemeta.search(query, types)
            searching = false
        }
    }
    PrismPanel(
        title = title,
        onClose = onClose,
        actions = { if (multiple) PrismButton("Done (${chosen.size})", primary = true) { onDone(chosen.toList()) } },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Movie or show name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { run() }),
                    modifier = Modifier.weight(1f),
                )
                PrismButton("Search", primary = true) { run() }
            }
            Spacer(Modifier.height(10.dp))
            if (searching) CircularProgressIndicator(color = tokens.colors.accent)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { item ->
                    val picked = chosen.any { it.id == item.id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (picked) tokens.colors.accent.copy(alpha = 0.18f) else tokens.colors.surfaceCard)
                            .then(if (picked) Modifier.border(1.dp, tokens.colors.accent, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable {
                                if (!multiple) {
                                    onDone(listOf(item))
                                } else if (picked) {
                                    chosen.removeAll { it.id == item.id }
                                } else {
                                    chosen.add(item)
                                }
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AsyncImage(
                            model = item.poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 46.dp, height = 68.dp).clip(RoundedCornerShape(6.dp)).background(tokens.colors.surface),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(item.name, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(if (item.type == "series") "Show" else "Movie", item.year).joinToString(" · "),
                                color = tokens.colors.textMuted,
                                fontSize = 12.sp,
                            )
                        }
                        if (picked) Text("✓", color = tokens.colors.accent, fontSize = 20.sp)
                    }
                }
                item { Spacer(Modifier.heightIn(min = 40.dp)) }
            }
        }
    }
}
