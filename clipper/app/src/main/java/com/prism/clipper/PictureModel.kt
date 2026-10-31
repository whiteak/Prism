package com.prism.clipper

import android.app.Application
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

enum class PicFormat(val label: String, val extension: String, val mime: String) {
    Jpeg("JPEG", "jpg", "image/jpeg"), Png("PNG", "png", "image/png"), Webp("WebP", "webp", "image/webp");

    @Suppress("DEPRECATION")
    val compress: Bitmap.CompressFormat
        get() = when (this) {
            Jpeg -> Bitmap.CompressFormat.JPEG
            Png -> Bitmap.CompressFormat.PNG
            Webp -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        }
}

/** Shapes for the crop frame; ratio is width / height, null means free. */
enum class CropShape(val label: String, val ratio: Float?) {
    Free("Free", null), Square("Square", 1f), Wide("16:9", 16f / 9f), Tall("9:16", 9f / 16f),
    Photo("4:3", 4f / 3f), PhotoTall("3:4", 3f / 4f),
    /** Shaped like this phone's screen; the ratio comes from the screen size. */
    PhoneScreen("Phone screen", null),
}

/** Ready-made settings for what the picture is for. */
enum class PicturePreset(val label: String, val folder: String, val note: String) {
    Phone("Phone", "Phone", "Cropped to this phone's screen shape and saved at its screen size, for wallpapers and phone backgrounds. Saves into the Phone folder."),
    Tv("TV&PC", "TV&PC", "Cropped to 16:9 and saved at 1920 × 1080, for TV and PC backgrounds and banners. Pick 3840 below for 4K. Saves into the TV&PC folder."),
    Icon("Icon", "Icons", "Square 1024 × 1024 PNG. For app icons and avatars; the inner circle shows what a round icon shape would keep. Saves into the Icons folder."),
}

/** Picture editor: crop, rotate, flip and resize one of your pictures, then save a copy. */
class PictureModel(app: Application) : AndroidViewModel(app) {
    var source by mutableStateOf<Uri?>(null); private set
    var sourceName by mutableStateOf(""); private set
    var bitmap by mutableStateOf<Bitmap?>(null); private set
    /** Smaller copy shown on screen, so dragging stays smooth with big photos. */
    var preview by mutableStateOf<Bitmap?>(null); private set

    /** Crop frame in 0..1 of the picture. */
    var crop by mutableStateOf(FULL)
    var shape by mutableStateOf(CropShape.Free); private set
    var preset by mutableStateOf<PicturePreset?>(null); private set
    /** Width / height of this phone's screen, for the Phone screen shape. */
    var phoneRatio = 9f / 19.5f
    var longSide by mutableStateOf("")
    var format by mutableStateOf(PicFormat.Jpeg)
    var fileName by mutableStateOf("")
    var status by mutableStateOf<Status>(Status.Idle); private set
    var loading by mutableStateOf(false); private set

    var pendingAsk by mutableStateOf<String?>(null); private set
    private var finished: File? = null

    var adjust by mutableStateOf(Adjust()); private set
    var textOn by mutableStateOf(TextOn()); private set
    /** The on-screen copy with the tweaks applied (or the plain preview while none are set). */
    var shownPreview by mutableStateOf<Bitmap?>(null); private set
    private var tweakJob: Job? = null

    // Undo and redo: whole snapshots of the editor, newest last.
    private data class Snapshot(
        val bitmap: Bitmap?, val crop: Rect, val shape: CropShape, val preset: PicturePreset?,
        val longSide: String, val format: PicFormat, val adjust: Adjust, val textOn: TextOn,
    )
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private var sliding = false

    private fun snapshot() = Snapshot(bitmap, crop, shape, preset, longSide, format, adjust, textOn)

    /** Remembers how things are now, so the next change can be undone. */
    fun checkpoint() {
        if (bitmap == null) return
        undoStack.addLast(snapshot())
        if (undoStack.size > 20) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true
        canRedo = false
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        restore(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(next)
    }

    private fun restore(s: Snapshot) {
        if (s.bitmap != null && s.bitmap != bitmap) setPicture(s.bitmap, keepCrop = true)
        crop = s.crop
        shape = s.shape
        preset = s.preset
        longSide = s.longSide
        format = s.format
        textOn = s.textOn
        setAdjustNow(s.adjust)
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
        canUndo = false
        canRedo = false
    }

    /** Slider moves: one undo step per slide, not per tiny movement. */
    fun changeAdjust(a: Adjust) {
        if (!sliding) {
            checkpoint()
            sliding = true
        }
        setAdjustNow(a)
    }

    fun doneSliding() { sliding = false }

    fun changeText(t: TextOn, undoable: Boolean = true) {
        if (undoable) checkpoint()
        textOn = t
    }

    fun changeLongSide(v: String) { longSide = v }

    private fun setAdjustNow(a: Adjust) {
        adjust = a
        val base = preview ?: return
        tweakJob?.cancel()
        if (a.isPlain) {
            shownPreview = base
            return
        }
        tweakJob = viewModelScope.launch {
            val tweaked = withContext(Dispatchers.Default) { PictureEffects.apply(base, a) }
            shownPreview = tweaked
        }
    }

    fun open(uri: Uri) {
        val ctx = getApplication<Application>()
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        source = uri
        sourceName = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "picture"
        fileName = sourceName.substringBeforeLast('.') + " edit"
        format = if (ctx.contentResolver.getType(uri) == "image/png") PicFormat.Png else PicFormat.Jpeg
        status = Status.Idle
        clearHistory()
        loading = true
        viewModelScope.launch {
            val decoded = runCatching {
                withContext(Dispatchers.IO) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val biggest = max(info.size.width, info.size.height)
                        if (biggest > MAX_SIDE) decoder.setTargetSampleSize(ceil(biggest / MAX_SIDE.toDouble()).toInt())
                    }
                }
            }
            loading = false
            decoded.onSuccess { setPicture(it) }
                .onFailure { status = Status.Failed("Couldn't open that picture: ${it.message}") }
        }
    }

    /** A still grabbed from a video; there's no original file to delete. */
    fun openFrame(b: Bitmap, name: String) {
        source = null
        sourceName = name
        fileName = name
        format = PicFormat.Jpeg
        status = Status.Idle
        clearHistory()
        setPicture(b)
    }

    private fun setPicture(b: Bitmap, keepCrop: Boolean = false) {
        bitmap = b
        val biggest = max(b.width, b.height)
        preview = if (biggest <= PREVIEW_SIDE) b else {
            val scale = PREVIEW_SIDE.toFloat() / biggest
            Bitmap.createScaledBitmap(b, (b.width * scale).roundToInt().coerceAtLeast(1), (b.height * scale).roundToInt().coerceAtLeast(1), true)
        }
        setAdjustNow(adjust)
        if (!keepCrop) chooseShape(shape, keepPreset = true, undoable = false)
    }

    fun rotate(degrees: Float) = transform(Matrix().apply { postRotate(degrees) })
    fun flip() = transform(Matrix().apply { postScale(-1f, 1f) })

    private fun transform(m: Matrix) {
        val b = bitmap ?: return
        checkpoint()
        setPicture(Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true))
    }

    private fun ratioOf(s: CropShape): Float? = if (s == CropShape.PhoneScreen) phoneRatio else s.ratio

    /** Sets the crop shape, size and format for a phone, TV or icon picture; tap again to turn it off. */
    fun choosePreset(p: PicturePreset, screenWidth: Int, screenHeight: Int) {
        checkpoint()
        if (preset == p) {
            preset = null
            return
        }
        preset = p
        when (p) {
            PicturePreset.Phone -> {
                val w = minOf(screenWidth, screenHeight)
                val h = maxOf(screenWidth, screenHeight)
                if (w > 0 && h > 0) phoneRatio = w.toFloat() / h
                chooseShape(CropShape.PhoneScreen, keepPreset = true, undoable = false)
                longSide = if (h > 0) h.toString() else "2400"
                format = PicFormat.Jpeg
            }
            PicturePreset.Tv -> {
                chooseShape(CropShape.Wide, keepPreset = true, undoable = false)
                longSide = "1920"
                format = PicFormat.Jpeg
            }
            PicturePreset.Icon -> {
                chooseShape(CropShape.Square, keepPreset = true, undoable = false)
                longSide = "1024"
                format = PicFormat.Png
            }
        }
    }

    fun chooseShape(s: CropShape, keepPreset: Boolean = false, undoable: Boolean = true) {
        if (undoable) checkpoint()
        if (!keepPreset) preset = null
        shape = s
        val b = bitmap ?: return
        val ratio = ratioOf(s)
        crop = if (ratio == null) FULL else {
            val w = minOf(b.width.toFloat(), b.height * ratio)
            val h = w / ratio
            val nw = w / b.width
            val nh = h / b.height
            Rect((1 - nw) / 2, (1 - nh) / 2, (1 + nw) / 2, (1 + nh) / 2)
        }
    }

    fun reset() {
        checkpoint()
        preset = null
        shape = CropShape.Free
        crop = FULL
    }

    /** Height / width of the frame in 0..1 units for the locked shape, or null when free. */
    fun lockedK(): Float? {
        val b = bitmap ?: return null
        val ratio = ratioOf(shape) ?: return null
        return b.width / (ratio * b.height)
    }

    fun cropPixels(): android.graphics.Rect? {
        val b = bitmap ?: return null
        val l = (crop.left * b.width).roundToInt().coerceIn(0, b.width - 1)
        val t = (crop.top * b.height).roundToInt().coerceIn(0, b.height - 1)
        val r = (crop.right * b.width).roundToInt().coerceIn(l + 1, b.width)
        val bt = (crop.bottom * b.height).roundToInt().coerceIn(t + 1, b.height)
        return android.graphics.Rect(l, t, r, bt)
    }

    /** Final width and height after cropping and resizing. */
    fun outputSize(): Pair<Int, Int>? {
        val c = cropPixels() ?: return null
        val target = longSide.toIntOrNull()
        val biggest = max(c.width(), c.height())
        if (target == null || target <= 0 || target >= biggest) return c.width() to c.height()
        val scale = target.toFloat() / biggest
        return (c.width() * scale).roundToInt().coerceAtLeast(1) to (c.height() * scale).roundToInt().coerceAtLeast(1)
    }

    fun save(saveTo: SaveTo, folder: Uri?) {
        val b = bitmap ?: return
        val c = cropPixels() ?: return
        val (w, h) = outputSize() ?: return
        if (saveTo == SaveTo.MyFolder && folder == null) {
            status = Status.Failed("Pick your folder first.")
            return
        }
        val ctx = getApplication<Application>()
        val fmt = format
        val sub = preset?.folder
        val name = fileName.trim().ifBlank { "picture" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .let { if (it.endsWith(".${fmt.extension}", true)) it else "$it.${fmt.extension}" }
        status = Status.Working(0)
        viewModelScope.launch {
            try {
                val tweaks = adjust
                val words = textOn
                val file = withContext(Dispatchers.Default) {
                    val out = PictureEffects.render(b, c, w, h, tweaks, words)
                    ctx.cacheDir.listFiles { f -> f.name.startsWith("picture-") }?.forEach { it.delete() }
                    File(ctx.cacheDir, "picture-${System.currentTimeMillis()}.${fmt.extension}").also { f ->
                        f.outputStream().use { out.compress(fmt.compress, if (fmt == PicFormat.Png) 100 else 92, it) }
                    }
                }
                if (saveTo == SaveTo.AskEachTime) {
                    finished = file
                    pendingAsk = name
                    return@launch
                }
                val where = withContext(Dispatchers.IO) {
                    try {
                        if (saveTo == SaveTo.Prism) Saver.toPrismFolder(ctx, file, name, fmt.mime, sub)
                        else Saver.toFolder(ctx, file, folder!!, name, fmt.mime, sub)
                    } finally {
                        file.delete()
                    }
                }
                status = Status.Done("Saved $name ($w × $h) to $where")
            } catch (e: Throwable) {
                status = Status.Failed(e.message ?: "Couldn't save the picture")
            }
        }
    }

    /**
     * Makes every picture in [uris] the same way as the current settings: crop shape (centred), size, tweaks,
     * text and format. Saves next to the others (Phone, TV&PC or Icons folder when a preset is on).
     */
    fun batch(uris: List<Uri>, saveTo: SaveTo, folder: Uri?, screenWidth: Int, screenHeight: Int) {
        if (uris.isEmpty() || status is Status.Working) return
        val ctx = getApplication<Application>()
        val target = if (saveTo == SaveTo.MyFolder && folder != null) SaveTo.MyFolder else SaveTo.Prism
        val fmt = format
        val sub = preset?.folder
        val ratio = ratioOf(shape)
        val wanted = longSide.toIntOrNull()
        val tweaks = adjust
        val words = textOn
        val tag = preset?.label ?: "edit"
        viewModelScope.launch {
            var made = 0
            var where = ""
            val failed = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                status = Status.Working(i * 100 / uris.size, "Making ${i + 1} of ${uris.size}")
                val name = displayName(ctx, uri).substringBeforeLast('.').ifBlank { "picture" }
                try {
                    val file = withContext(Dispatchers.Default) {
                        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val biggest = max(info.size.width, info.size.height)
                            if (biggest > MAX_SIDE) decoder.setTargetSampleSize(ceil(biggest / MAX_SIDE.toDouble()).toInt())
                        }
                        val c = centredCrop(src.width, src.height, ratio)
                        val biggest = max(c.width(), c.height())
                        val scale = if (wanted != null && wanted in 1 until biggest) wanted.toFloat() / biggest else 1f
                        val w = (c.width() * scale).roundToInt().coerceAtLeast(1)
                        val h = (c.height() * scale).roundToInt().coerceAtLeast(1)
                        val out = PictureEffects.render(src, c, w, h, tweaks, words)
                        File(ctx.cacheDir, "picture-batch-$i.${fmt.extension}").also { f ->
                            f.outputStream().use { out.compress(fmt.compress, if (fmt == PicFormat.Png) 100 else 92, it) }
                        }
                    }
                    val fileName = "$name $tag.${fmt.extension}"
                    where = withContext(Dispatchers.IO) {
                        try {
                            if (target == SaveTo.Prism) Saver.toPrismFolder(ctx, file, fileName, fmt.mime, sub)
                            else Saver.toFolder(ctx, file, folder!!, fileName, fmt.mime, sub)
                        } finally {
                            file.delete()
                        }
                    }
                    made++
                } catch (e: Throwable) {
                    failed += name
                }
            }
            status = when {
                failed.isEmpty() -> Status.Done("Made $made pictures in $where.")
                made == 0 -> Status.Failed("Couldn't make any of them.")
                else -> Status.Done("Made $made pictures in $where. Couldn't do: ${failed.joinToString()}.")
            }
        }
    }

    private fun centredCrop(width: Int, height: Int, ratio: Float?): android.graphics.Rect {
        if (ratio == null) return android.graphics.Rect(0, 0, width, height)
        val w = minOf(width.toFloat(), height * ratio)
        val h = w / ratio
        val l = ((width - w) / 2).roundToInt()
        val t = ((height - h) / 2).roundToInt()
        return android.graphics.Rect(l, t, (l + w.roundToInt()).coerceAtMost(width), (t + h.roundToInt()).coerceAtMost(height))
    }

    fun savedAs(target: Uri?) {
        val file = finished ?: return
        val name = pendingAsk ?: ""
        pendingAsk = null
        finished = null
        if (target == null) {
            file.delete()
            status = Status.Failed("Not saved. Tap Save again to choose a place.")
            return
        }
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            status = try {
                withContext(Dispatchers.IO) { Saver.copy(ctx, file, target) }
                Status.Done("Saved $name")
            } catch (e: Exception) {
                Status.Failed(e.message ?: "Couldn't save the picture")
            } finally {
                file.delete()
            }
        }
    }

    fun deleteOriginal(): IntentSender? {
        val uri = source ?: return null
        return when (val result = Saver.deleteOriginal(getApplication(), uri)) {
            Saver.DeleteResult.Deleted -> { originalDeleted(true); null }
            is Saver.DeleteResult.Ask -> result.request
            Saver.DeleteResult.Failed -> {
                status = Status.Failed("Couldn't delete the original. Delete it in your file manager instead.")
                null
            }
        }
    }

    fun originalDeleted(confirmed: Boolean) {
        if (!confirmed) return
        val name = sourceName
        source = null
        bitmap = null
        preview = null
        shownPreview = null
        clearHistory()
        status = Status.Done("Deleted the original ($name).")
    }

    companion object {
        val FULL = Rect(0f, 0f, 1f, 1f)
        const val MAX_SIDE = 8192
        const val PREVIEW_SIDE = 2048
    }
}
