package com.prism.clipper

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PictureScreen(pictures: PictureModel, clips: ClipperModel) {
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pictures.open(uri)
    }
    val saveJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { pictures.savedAs(it) }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { pictures.savedAs(it) }
    val saveWebp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/webp")) { pictures.savedAs(it) }
    val ask = pictures.pendingAsk
    LaunchedEffect(ask) {
        if (ask != null) when (pictures.format) {
            PicFormat.Jpeg -> saveJpeg
            PicFormat.Png -> savePng
            PicFormat.Webp -> saveWebp
        }.launch(ask)
    }
    val systemDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        pictures.originalDeleted(it.resultCode == Activity.RESULT_OK)
    }

    val context = LocalContext.current
    val screen = remember {
        val bounds = (context as? Activity)?.windowManager?.maximumWindowMetrics?.bounds
        val metrics = context.resources.displayMetrics
        (bounds?.width() ?: metrics.widthPixels) to (bounds?.height() ?: metrics.heightPixels)
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pictures.batch(uris, clips.saveTo, clips.myFolder, screen.first, screen.second)
    }

    // Presets are their own section, picked first: what the picture is for.
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What's it for?", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PicturePreset.entries.forEach { p ->
                    val choose = { pictures.choosePreset(p, screen.first, screen.second) }
                    if (pictures.preset == p) {
                        Button(onClick = choose, Modifier.weight(1f), contentPadding = PresetPadding) { PresetLabel(p.label) }
                    } else {
                        OutlinedButton(onClick = choose, Modifier.weight(1f), contentPadding = PresetPadding) { PresetLabel(p.label) }
                    }
                }
            }
            Text(
                pictures.preset?.note ?: "Pick one for ready-made crop and size settings, or skip this and set them yourself below.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { pickMany.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth()) {
                Text("Do several pictures this way")
            }
            Text(
                "Each one is cropped from the middle to this shape and gets the same size, tweaks, text and format. " +
                    "Saves to the Prism folder or your folder.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (pictures.bitmap == null) {
        Text("Pick a picture to crop, rotate, flip or resize. You can also share a picture to Clipper.")
        Button(onClick = { pickPicture.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth()) { Text("Choose a picture") }
        if (pictures.loading) Text("Opening…")
        StatusText(pictures.status)
        if (pictures.status is Status.Working) Text((pictures.status as Status.Working).label + "…")
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = pictures::undo, enabled = pictures.canUndo, modifier = Modifier.weight(1f)) { Text("Undo") }
        OutlinedButton(onClick = pictures::redo, enabled = pictures.canRedo, modifier = Modifier.weight(1f)) { Text("Redo") }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pictures.sourceName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { pickPicture.launch(arrayOf("image/*")) }) { Text("Change") }
    }

    CropArea(pictures)

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pictures.rotate(-90f) }) { Text("Rotate left") }
        OutlinedButton(onClick = { pictures.rotate(90f) }) { Text("Rotate right") }
        OutlinedButton(onClick = pictures::flip) { Text("Flip") }
        OutlinedButton(onClick = pictures::reset) { Text("Whole picture") }
    }

    Text("Crop shape (or set your own)")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CropShape.entries.forEach { s ->
            FilterChip(selected = pictures.shape == s, onClick = { pictures.chooseShape(s) }, label = { Text(s.label) })
        }
    }

    val crop = pictures.cropPixels()
    val out = pictures.outputSize()
    if (crop != null && out != null) {
        Text("Cropped ${crop.width()} × ${crop.height()}, saves at ${out.first} × ${out.second}", style = MaterialTheme.typography.bodySmall)
    }

    Tweaks(pictures)
    TextSection(pictures)

    Text("Resize (longest side)")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("" to "Keep", "3840" to "3840", "1920" to "1920", "1280" to "1280", "1080" to "1080", "720" to "720", "512" to "512")
            .forEach { (value, label) ->
                FilterChip(selected = pictures.longSide == value, onClick = { pictures.checkpoint(); pictures.changeLongSide(value) }, label = { Text(label) })
            }
    }
    OutlinedTextField(
        value = pictures.longSide,
        onValueChange = { v -> pictures.changeLongSide(v.filter(Char::isDigit).take(5)) },
        label = { Text("Or type a size in pixels") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Text("Format")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PicFormat.entries.forEach { f ->
            FilterChip(selected = pictures.format == f, onClick = { pictures.format = f }, label = { Text(f.label) })
        }
    }

    OutlinedTextField(
        value = pictures.fileName,
        onValueChange = { pictures.fileName = it },
        label = { Text("File name") },
        suffix = { Text(".${pictures.format.extension}") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    SaveLocation(clips)

    StatusText(pictures.status)
    if (pictures.status is Status.Working) Text((pictures.status as Status.Working).label + "…")
    if (pictures.status is Status.Done && pictures.source != null) {
        DeleteOriginalButton(pictures.sourceName) {
            pictures.deleteOriginal()?.let { systemDelete.launch(IntentSenderRequest.Builder(it).build()) }
        }
    }
    Button(
        onClick = { pictures.save(clips.saveTo, clips.myFolder) },
        enabled = pictures.status !is Status.Working,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save picture") }
    Spacer(Modifier.height(24.dp))
}

/** The picture with a crop frame: drag inside to move it, drag a corner to resize it. */
@Composable
private fun CropArea(pictures: PictureModel) {
    val preview = pictures.preview ?: return
    val shown = pictures.shownPreview ?: preview
    val image = remember(shown) { shown.asImageBitmap() }
    val handle = with(LocalDensity.current) { 32.dp.toPx() }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(380.dp)
            .background(Color.Black),
    ) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val scale = minOf(boxW / preview.width, boxH / preview.height)
        val imgW = preview.width * scale
        val imgH = preview.height * scale
        val left = (boxW - imgW) / 2
        val top = (boxH - imgH) / 2

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(preview, pictures.shape, imgW, imgH) {
                    var mode = 0
                    detectDragGestures(
                        onDragStart = { p ->
                            pictures.checkpoint()
                            val c = pictures.crop
                            val x = (p.x - left) / imgW
                            val y = (p.y - top) / imgH
                            val hx = handle / imgW
                            val hy = handle / imgH
                            val nearL = abs(x - c.left) < hx
                            val nearR = abs(x - c.right) < hx
                            val nearT = abs(y - c.top) < hy
                            val nearB = abs(y - c.bottom) < hy
                            mode = when {
                                nearL && nearT -> 2
                                nearR && nearT -> 3
                                nearL && nearB -> 4
                                nearR && nearB -> 5
                                x in c.left..c.right && y in c.top..c.bottom -> 1
                                else -> 0
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            val dx = drag.x / imgW
                            val dy = drag.y / imgH
                            pictures.crop = when (mode) {
                                1 -> moveFrame(pictures.crop, dx, dy)
                                in 2..5 -> resizeFrame(pictures.crop, mode, dx, dy, pictures.lockedK())
                                else -> pictures.crop
                            }
                        },
                    )
                },
        ) {
            drawImage(
                image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(imgW.roundToInt(), imgH.roundToInt()),
            )
            val c = pictures.crop
            val frame = Rect(left + c.left * imgW, top + c.top * imgH, left + c.right * imgW, top + c.bottom * imgH)
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, Offset(left, top), Size(imgW, frame.top - top))
            drawRect(dim, Offset(left, frame.bottom), Size(imgW, top + imgH - frame.bottom))
            drawRect(dim, Offset(left, frame.top), Size(frame.left - left, frame.height))
            drawRect(dim, Offset(frame.right, frame.top), Size(left + imgW - frame.right, frame.height))
            drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(2.dp.toPx()))
            val r = 7.dp.toPx()
            listOf(frame.topLeft, frame.topRight, frame.bottomLeft, frame.bottomRight).forEach {
                drawCircle(Color.White, r, it)
            }
            if (pictures.textOn.text.isNotBlank()) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.clipRect(frame.left, frame.top, frame.right, frame.bottom)
                    native.translate(frame.left, frame.top)
                    PictureEffects.drawText(native, frame.width, frame.height, pictures.textOn)
                    native.restore()
                }
            }
            if (pictures.preset == PicturePreset.Icon) {
                // Launchers mask icons into shapes; the inner circle is the part every shape keeps.
                val guide = Stroke(1.5.dp.toPx())
                drawCircle(Color.White.copy(alpha = 0.5f), frame.width / 2, frame.center, style = guide)
                drawCircle(Color(0xFFFFD54F), frame.width * 0.61f / 2, frame.center, style = guide)
            }
        }
    }
}

private const val MIN_FRAME = 0.05f

private fun moveFrame(c: Rect, dx: Float, dy: Float): Rect {
    val x = (c.left + dx).coerceIn(0f, 1f - c.width)
    val y = (c.top + dy).coerceIn(0f, 1f - c.height)
    return Rect(x, y, x + c.width, y + c.height)
}

/** corner: 2 top-left, 3 top-right, 4 bottom-left, 5 bottom-right. k = height / width when the shape is locked. */
private fun resizeFrame(c: Rect, corner: Int, dx: Float, dy: Float, k: Float?): Rect {
    val leftSide = corner == 2 || corner == 4
    val topSide = corner == 2 || corner == 3
    var l = c.left
    var t = c.top
    var r = c.right
    var b = c.bottom
    if (leftSide) l = (l + dx).coerceIn(0f, r - MIN_FRAME) else r = (r + dx).coerceIn(l + MIN_FRAME, 1f)
    if (k == null) {
        if (topSide) t = (t + dy).coerceIn(0f, b - MIN_FRAME) else b = (b + dy).coerceIn(t + MIN_FRAME, 1f)
        return Rect(l, t, r, b)
    }
    // Locked shape: the width decides, the height follows, and the opposite corner stays put.
    var w = r - l
    var h = w * k
    val room = if (topSide) c.bottom else 1f - c.top
    if (h > room) {
        h = room
        w = h / k
    }
    if (leftSide) l = c.right - w else r = c.left + w
    if (topSide) t = c.bottom - h else b = c.top + h
    return Rect(l, t, r, b)
}

@Composable
private fun Tweaks(pictures: PictureModel) {
    val a = pictures.adjust
    Text("Tweaks")
    TweakSlider("Brightness", a.brightness, -1f..1f, { pictures.changeAdjust(a.copy(brightness = it)) }, pictures::doneSliding)
    TweakSlider("Contrast", a.contrast, 0.5f..2f, { pictures.changeAdjust(a.copy(contrast = it)) }, pictures::doneSliding)
    TweakSlider("Colour", a.saturation, 0f..2f, { pictures.changeAdjust(a.copy(saturation = it)) }, pictures::doneSliding)
    TweakSlider("Blur", a.blur, 0f..1f, { pictures.changeAdjust(a.copy(blur = it)) }, pictures::doneSliding)
    if (!a.isPlain) {
        TextButton(onClick = { pictures.checkpoint(); pictures.changeAdjust(Adjust()); pictures.doneSliding() }) { Text("Reset tweaks") }
    }
}

@Composable
private fun TweakSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range, modifier = Modifier.weight(1f))
    }
}

private val TextColours = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFD54F, 0xFFC0C0C0, 0xFFB79CFF, 0xFFFF6FB5, 0xFFE53935, 0xFF4CAF50, 0xFF00E5FF,
).map { it.toInt() }

/** Words on the picture: text, font, size, colour, place and shadow. */
@Composable
private fun TextSection(pictures: PictureModel) {
    val t = pictures.textOn
    val context = LocalContext.current
    val addFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val dir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
            val file = java.io.File(dir, displayName(context, uri).ifBlank { "font.ttf" }.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                android.graphics.Typeface.createFromFile(file)
            }.onSuccess { pictures.changeText(t.copy(fontFile = file)) }.onFailure { file.delete() }
        }
    }
    Text("Text on the picture")
    OutlinedTextField(
        value = t.text,
        onValueChange = { pictures.changeText(t.copy(text = it), undoable = false) },
        label = { Text("Words (leave empty for none)") },
        modifier = Modifier.fillMaxWidth(),
    )
    if (t.text.isBlank()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PictureFonts.forEach { (label, family) ->
            FilterChip(t.fontFile == null && t.family == family, { pictures.changeText(t.copy(family = family, fontFile = null)) }, label = { Text(label) })
        }
        t.fontFile?.let { FilterChip(true, {}, label = { Text(it.nameWithoutExtension) }) }
        OutlinedButton(onClick = { addFont.launch(arrayOf("*/*")) }) { Text("Add a font file") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(t.bold, { pictures.changeText(t.copy(bold = !t.bold)) }, label = { Text("Bold") })
        FilterChip(t.italic, { pictures.changeText(t.copy(italic = !t.italic)) }, label = { Text("Italic") })
        FilterChip(t.shadow, { pictures.changeText(t.copy(shadow = !t.shadow)) }, label = { Text("Shadow") })
        TextPlace.entries.forEach { p ->
            FilterChip(t.place == p, { pictures.changeText(t.copy(place = p)) }, label = { Text(p.label) })
        }
    }
    TweakSlider("Text size", t.size, 0.03f..0.25f, { pictures.changeText(t.copy(size = it), undoable = false) }, {})
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextColours.forEach { c ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (c == t.color) 3.dp else 1.dp, if (c == t.color) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .clickable { pictures.changeText(t.copy(color = c)) },
            )
        }
    }
}

// Narrow side padding and one line so "TV&PC" fits on a phone instead of wrapping.
private val PresetPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

@Composable
private fun PresetLabel(label: String) {
    Text(label, maxLines = 1, softWrap = false)
}
