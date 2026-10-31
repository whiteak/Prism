package com.prism.clipper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Picture tweaks. All at their default values means "leave it alone". */
data class Adjust(
    /** -1 darker .. 1 lighter. */
    val brightness: Float = 0f,
    /** 0.5 flat .. 2 punchy. */
    val contrast: Float = 1f,
    /** 0 black and white .. 2 vivid. */
    val saturation: Float = 1f,
    /** 0 sharp .. 1 very soft. */
    val blur: Float = 0f,
) {
    val isPlain get() = this == Adjust()
}

enum class TextPlace(val label: String) { Top("Top"), Middle("Middle"), Bottom("Bottom") }

/** Built-in fonts for text on pictures (Android's own families). */
val PictureFonts = listOf(
    "Sans" to "sans-serif", "Serif" to "serif", "Mono" to "monospace", "Handwriting" to "cursive",
    "Casual" to "casual", "Condensed" to "sans-serif-condensed", "Light" to "sans-serif-light",
    "Black" to "sans-serif-black", "Small caps" to "sans-serif-smallcaps",
)

/** Words written on the picture. [size] is the text height as a share of the picture's shorter side. */
data class TextOn(
    val text: String = "",
    val family: String = "sans-serif",
    /** A .ttf/.otf the user added, used instead of [family]. */
    val fontFile: File? = null,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val size: Float = 0.08f,
    val color: Int = Color.WHITE,
    val place: TextPlace = TextPlace.Bottom,
    val shadow: Boolean = true,
)

object PictureEffects {
    /** Returns a new bitmap with the colour tweaks and blur applied, or [src] itself when there's nothing to do. */
    fun apply(src: Bitmap, adjust: Adjust): Bitmap {
        if (adjust.isPlain) return src
        var out = src
        if (adjust.blur > 0f) out = softBlur(out, adjust.blur)
        if (adjust.brightness != 0f || adjust.contrast != 1f || adjust.saturation != 1f) {
            val matrix = ColorMatrix().apply { setSaturation(adjust.saturation) }
            val c = adjust.contrast
            // Contrast around mid-grey, then brightness as an offset.
            val offset = 128f * (1f - c) + adjust.brightness * 255f * 0.5f
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        c, 0f, 0f, 0f, offset,
                        0f, c, 0f, 0f, offset,
                        0f, 0f, c, 0f, offset,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
            val result = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
            Canvas(result).drawBitmap(out, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
            out = result
        }
        return out
    }

    /** A soft blur made by shrinking the picture and smoothly scaling it back up, twice. */
    private fun softBlur(src: Bitmap, amount: Float): Bitmap {
        val factor = 1f + amount * 24f
        val w = max(1, (src.width / factor).roundToInt())
        val h = max(1, (src.height / factor).roundToInt())
        var small = Bitmap.createScaledBitmap(src, w, h, true)
        small = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(small, max(1, w / 2), max(1, h / 2), true), w, h, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    fun typeface(text: TextOn): Typeface {
        val base = text.fontFile?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            ?: Typeface.create(text.family, Typeface.NORMAL)
        val style = when {
            text.bold && text.italic -> Typeface.BOLD_ITALIC
            text.bold -> Typeface.BOLD
            text.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, style)
    }

    /** Writes [text] onto a [width] x [height] area of [canvas] (already translated to that area). */
    fun drawText(canvas: Canvas, width: Float, height: Float, text: TextOn) {
        if (text.text.isBlank() || width <= 0f || height <= 0f) return
        val px = minOf(width, height) * text.size
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(text)
            textSize = px
            color = text.color
            if (text.shadow) setShadowLayer(px * 0.12f, 0f, px * 0.05f, Color.argb(200, 0, 0, 0))
        }
        val margin = minOf(width, height) * 0.05f
        val layoutWidth = (width - margin * 2).roundToInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text.text, 0, text.text.length, paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val y = when (text.place) {
            TextPlace.Top -> margin
            TextPlace.Middle -> (height - layout.height) / 2f
            TextPlace.Bottom -> height - margin - layout.height
        }
        canvas.save()
        canvas.translate(margin, y)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Crop, resize, tweak and write text: the finished picture. */
    fun render(src: Bitmap, crop: android.graphics.Rect, width: Int, height: Int, adjust: Adjust, text: TextOn): Bitmap {
        var out = Bitmap.createBitmap(src, crop.left, crop.top, crop.width(), crop.height())
        if (out.width != width || out.height != height) out = Bitmap.createScaledBitmap(out, width, height, true)
        out = apply(out, adjust)
        if (text.text.isNotBlank()) {
            if (!out.isMutable) out = out.copy(Bitmap.Config.ARGB_8888, true)
            drawText(Canvas(out), width.toFloat(), height.toFloat(), text)
        }
        return out
    }
}
