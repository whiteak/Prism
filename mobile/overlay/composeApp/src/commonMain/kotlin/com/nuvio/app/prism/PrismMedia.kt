package com.nuvio.app.prism

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Prism: pictures (shrunk on import, cropped for the screen's shape) and sounds (trimmed, levelled, saved as WAV).

/** Biggest side a stored picture keeps; plenty for a 4K TV, small enough to stay quick. */
internal const val PRISM_MAX_PICTURE = 2560
internal const val PRISM_MAX_LOGO = 1024
internal const val PRISM_PROBLEM_FILE = "last-problem.txt"

/** The shape a picture is shown in. */
enum class PrismShape(val aspect: Float) {
    Tall(9f / 19.5f),
    Wide(16f / 9f),
    Square(1f),
}

/** Fills [shape] from the middle of a picture that is [width] by [height]. */
internal fun centreCrop(width: Int, height: Int, shape: PrismShape): PrismCrop {
    val aspect = width.toFloat() / height
    return if (aspect > shape.aspect) {
        val w = shape.aspect / aspect
        PrismCrop((1 - w) / 2, 0f, (1 + w) / 2, 1f)
    } else {
        val h = aspect / shape.aspect
        PrismCrop(0f, (1 - h) / 2, 1f, (1 + h) / 2)
    }
}

object PrismImages {
    private val cache = mutableMapOf<String, ImageBitmap>()

    fun clear() = cache.clear()

    /** The whole stored picture. */
    suspend fun original(file: String): ImageBitmap? {
        cache[file]?.let { return it }
        val p = Prism.platform ?: return null
        val image = withContext(Dispatchers.Default) {
            p.readBytes(PrismStore.mediaPath(file))?.let { p.decodeImage(it, PRISM_MAX_PICTURE) }
        } ?: return null
        cache[file] = image
        return image
    }

    /** The part of [picture] chosen for [shape] (the middle if none was chosen). */
    suspend fun cropped(picture: PrismPicture, shape: PrismShape): ImageBitmap? {
        val crop = when (shape) {
            PrismShape.Tall -> picture.tall
            PrismShape.Wide -> picture.wide
            PrismShape.Square -> picture.tall
        }
        val key = "${picture.file}|$shape|$crop|2"
        cache[key]?.let { return it }
        val p = Prism.platform ?: return null
        // The finished picture is also kept on disk, so the opening shows it straight away next launch.
        val saved = PrismStore.cachePath("${picture.file.substringBefore('.')}-${key.hashCode().toUInt().toString(16)}.jpg")
        withContext(Dispatchers.Default) {
            p.readBytes(saved)?.let { p.decodeImage(it, PRISM_MAX_PICTURE) }
        }?.let {
            cache[key] = it
            return it
        }
        val source = original(picture.file) ?: return null
        val image = withContext(Dispatchers.Default) {
            val made = if (crop?.whole == true) whole(source, shape) else crop(source, crop ?: centreCrop(source.width, source.height, shape))
            p.encodeImage(made, keepTransparency = false)?.let { runCatching { p.writeBytes(saved, it) } }
            made
        }
        cache[key] = image
        return image
    }

    fun crop(source: ImageBitmap, crop: PrismCrop): ImageBitmap {
        val left = (crop.left * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = (crop.top * source.height).roundToInt().coerceIn(0, source.height - 1)
        val width = ((crop.right - crop.left) * source.width).roundToInt().coerceIn(1, source.width - left)
        val height = ((crop.bottom - crop.top) * source.height).roundToInt().coerceIn(1, source.height - top)
        return scale(source, IntOffset(left, top), IntSize(width, height), IntSize(width, height))
    }

    /** The whole of [source] in [shape], on a dimmed, blurred copy of itself. */
    fun whole(source: ImageBitmap, shape: PrismShape): ImageBitmap {
        val aspect = source.width.toFloat() / source.height
        var w: Int
        var h: Int
        if (aspect > shape.aspect) {
            w = source.width
            h = (w / shape.aspect).roundToInt()
        } else {
            h = source.height
            w = (h * shape.aspect).roundToInt()
        }
        val fit = PRISM_MAX_PICTURE.toFloat() / max(w, h)
        if (fit < 1f) {
            w = (w * fit).roundToInt()
            h = (h * fit).roundToInt()
        }
        w = w.coerceAtLeast(1)
        h = h.coerceAtLeast(1)
        // The fill: the middle of the picture, heavily blurred.
        val fill = centreCrop(source.width, source.height, shape)
        val fillLeft = (fill.left * source.width).roundToInt()
        val fillTop = (fill.top * source.height).roundToInt()
        val fillSize = IntSize(
            ((fill.right - fill.left) * source.width).roundToInt().coerceIn(1, source.width - fillLeft),
            ((fill.bottom - fill.top) * source.height).roundToInt().coerceIn(1, source.height - fillTop),
        )
        // Shrink in halves (each step averages), blur at a quarter size, then stretch up in doubles,
        // so there are no blocky edges anywhere.
        var small = scale(source, IntOffset(fillLeft, fillTop), fillSize, fillSize)
        while (small.width > max(4, w / 16) && small.height > 4) small = half(small)
        repeat(2) { small = boxBlur(small, 1) }
        while (small.width * 2 <= w / 4) {
            small = scale(small, IntOffset.Zero, IntSize(small.width, small.height), IntSize(small.width * 2, small.height * 2))
            small = boxBlur(small, 1)
        }
        small = boxBlur(boxBlur(small, 2), 2)
        var out = small
        while (out.width * 2 <= w) out = scale(out, IntOffset.Zero, IntSize(out.width, out.height), IntSize(out.width * 2, out.height * 2))
        out = scale(out, IntOffset.Zero, IntSize(out.width, out.height), IntSize(w, h))
        val canvas = Canvas(out)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f) })
        val s = min(w.toFloat() / source.width, h.toFloat() / source.height)
        val dw = (source.width * s).roundToInt().coerceAtLeast(1)
        val dh = (source.height * s).roundToInt().coerceAtLeast(1)
        canvas.drawImageRect(
            image = source,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(source.width, source.height),
            dstOffset = IntOffset((w - dw) / 2, (h - dh) / 2),
            dstSize = IntSize(dw, dh),
            paint = Paint().apply { filterQuality = FilterQuality.High },
        )
        return out
    }

    private fun half(source: ImageBitmap): ImageBitmap =
        scale(source, IntOffset.Zero, IntSize(source.width, source.height), IntSize((source.width / 2).coerceAtLeast(1), (source.height / 2).coerceAtLeast(1)))

    /** A quick blur: the picture laid over itself, shifted by up to [step] pixels each way, averaged. */
    private fun boxBlur(source: ImageBitmap, step: Int): ImageBitmap {
        val out = ImageBitmap(source.width, source.height)
        val canvas = Canvas(out)
        val shifts = listOf(0 to 0, -step to 0, step to 0, 0 to -step, 0 to step, -step to -step, step to step, -step to step, step to -step)
        shifts.forEachIndexed { i, (dx, dy) ->
            // Running average: the n-th layer goes on at 1/n.
            canvas.drawImage(source, Offset(dx.toFloat(), dy.toFloat()), Paint().apply { alpha = 1f / (i + 1) })
        }
        return out
    }

    fun scale(source: ImageBitmap, srcOffset: IntOffset, srcSize: IntSize, dstSize: IntSize): ImageBitmap {
        val out = ImageBitmap(dstSize.width, dstSize.height)
        Canvas(out).drawImageRect(
            image = source,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = IntOffset.Zero,
            dstSize = dstSize,
            paint = Paint().apply { filterQuality = FilterQuality.High },
        )
        return out
    }

    /**
     * Makes a picture ready to keep: decoded, shrunk to [maxSide] and re-saved (JPEG, or PNG for logos so
     * see-through edges stay). Returns the stored file name, or null if it isn't a picture.
     */
    suspend fun import(bytes: ByteArray, maxSide: Int, keepTransparency: Boolean): Pair<String, ImageBitmap>? =
        withContext(Dispatchers.Default) {
            val p = Prism.platform ?: return@withContext null
            val image = p.decodeImage(bytes, maxSide) ?: return@withContext null
            val encoded = p.encodeImage(image, keepTransparency) ?: return@withContext null
            val name = PrismStore.addMedia(encoded, if (keepTransparency) "png" else "jpg")
            cache[name] = image
            name to image
        }
}

@Composable
fun rememberPrismPicture(picture: PrismPicture?, shape: PrismShape): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, picture, shape) {
        value = picture?.let { PrismImages.cropped(it, shape) }
    }

@Composable
fun rememberPrismOriginal(file: String?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, file) {
        value = file?.let { PrismImages.original(it) }
    }

// ---- Sounds ----

object PrismAudio {
    /** Longest sound that can be brought in; the part to keep is chosen afterwards. */
    const val MAX_SECONDS = 180

    /** Peaks of [pcm] in [buckets] slices, 0 to 1, for drawing its waveform. */
    fun peaks(pcm: PrismPcm, buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        val frames = pcm.frames
        if (frames == 0) return out
        for (b in 0 until buckets) {
            val from = (b.toLong() * frames / buckets).toInt()
            val to = max(from + 1, ((b + 1).toLong() * frames / buckets).toInt())
            var peak = 0
            var i = from * pcm.channels
            val end = min(to * pcm.channels, pcm.samples.size)
            val step = max(1, (end - i) / 400)
            while (i < end) {
                peak = max(peak, abs(pcm.samples[i].toInt()))
                i += step
            }
            out[b] = peak / 32768f
        }
        return out
    }

    /**
     * The part from [startMs] to [endMs], evened out to a steady loudness when [level] is on,
     * then turned up or down by [volume], with short fades so it doesn't click. Optionally fades
     * out over [fadeOutMs] milliseconds at the end with a cosine window.
     */
    fun process(pcm: PrismPcm, startMs: Long, endMs: Long, level: Boolean, volume: Float, fadeOutMs: Long = 0): PrismPcm {
        val ch = pcm.channels
        val from = (startMs * pcm.sampleRate / 1000).toInt().coerceIn(0, pcm.frames)
        val to = (endMs * pcm.sampleRate / 1000).toInt().coerceIn(from, pcm.frames)
        val part = pcm.samples.copyOfRange(from * ch, to * ch)
        var gain = volume
        if (level && part.isNotEmpty()) {
            var sum = 0.0
            var peak = 1
            for (s in part) {
                sum += s.toDouble() * s
                peak = max(peak, abs(s.toInt()))
            }
            val rms = sqrt(sum / part.size) / 32768.0
            // About -17 dBFS, like a normal speaking voice in a film, without letting peaks clip.
            val target = 0.14
            val levelGain = if (rms > 0.0005) (target / rms) else 1.0
            val peakRoom = 0.98 * 32768.0 / peak
            gain = (min(levelGain, peakRoom * 2.0) * volume).toFloat()
        }
        val fade = min(part.size / ch / 4, pcm.sampleRate / 80)
        val frames = part.size / ch
        val fadeOutFrames = (fadeOutMs * pcm.sampleRate / 1000).toInt().coerceIn(0, frames / 2)
        val fadeOutStart = frames - fadeOutFrames
        val out = ShortArray(part.size)
        for (f in 0 until frames) {
            val edge = when {
                fade <= 0 -> 1f
                f < fade -> f.toFloat() / fade
                f >= frames - fade -> (frames - 1 - f).toFloat() / fade
                else -> 1f
            }
            val tail = if (fadeOutFrames > 0 && f >= fadeOutStart) {
                val p = (f - fadeOutStart).toDouble() / max(1, fadeOutFrames - 1)
                0.5 * (1 + cos(PI * p))
            } else {
                1.0
            }
            for (c in 0 until ch) {
                val i = f * ch + c
                out[i] = softClip(part[i] * gain * edge * tail.toFloat())
            }
        }
        return PrismPcm(pcm.sampleRate, ch, out)
    }

    /** Keeps loud bits from crackling: straight up to 85 %, then rounds off. */
    private fun softClip(x: Float): Short {
        val limit = 32767f
        val knee = limit * 0.85f
        val a = abs(x)
        val y = if (a <= knee) a else knee + (limit - knee) * (1f - 1f / (1f + (a - knee) / (limit - knee)))
        return (if (x < 0) -y else y).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    fun toWav(pcm: PrismPcm): ByteArray {
        val dataSize = pcm.samples.size * 2
        val out = ByteArray(44 + dataSize)
        fun putInt(at: Int, v: Int) {
            out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte(); out[at + 2] = (v shr 16).toByte(); out[at + 3] = (v shr 24).toByte()
        }
        fun putShort(at: Int, v: Int) {
            out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte()
        }
        "RIFF".encodeToByteArray().copyInto(out, 0)
        putInt(4, 36 + dataSize)
        "WAVEfmt ".encodeToByteArray().copyInto(out, 8)
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, pcm.channels)
        putInt(24, pcm.sampleRate)
        putInt(28, pcm.sampleRate * pcm.channels * 2)
        putShort(32, pcm.channels * 2)
        putShort(34, 16)
        "data".encodeToByteArray().copyInto(out, 36)
        putInt(40, dataSize)
        var o = 44
        for (s in pcm.samples) {
            out[o] = s.toInt().toByte()
            out[o + 1] = (s.toInt() shr 8).toByte()
            o += 2
        }
        return out
    }

    /** Reads a 16-bit PCM WAV file (what Prism stores, and what the PC's converter writes). */
    fun fromWav(bytes: ByteArray, maxSeconds: Int = MAX_SECONDS): PrismPcm? {
        fun int(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        fun short(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        if (bytes.size < 44 || bytes.decodeToString(0, 4) != "RIFF" || bytes.decodeToString(8, 12) != "WAVE") return null
        var at = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        while (at + 8 <= bytes.size) {
            val id = bytes.decodeToString(at, at + 4)
            val size = int(at + 4)
            val body = at + 8
            if (id == "fmt ") {
                format = short(body)
                channels = short(body + 2)
                rate = int(body + 4)
                bits = short(body + 14)
            } else if (id == "data") {
                if ((format != 1 && format != 0xFFFE) || bits != 16 || channels !in 1..8 || rate <= 0) return null
                val available = (min(size.toLong().takeIf { it > 0 } ?: Long.MAX_VALUE, (bytes.size - body).toLong()) / 2).toInt()
                val count = min(available, maxSeconds * rate * channels) / channels * channels
                val samples = ShortArray(count) { i -> short(body + i * 2).toShort() }
                return PrismPcm(rate, channels, samples)
            }
            at = body + size + (size and 1)
            if (size < 0) return null
        }
        return null
    }
}

/** Plays the look's sound for a moment, if it has one and sounds are on. */
object PrismSounds {
    var playing by mutableStateOf(false)
        private set

    private val lastPicked = mutableMapOf<String, String>()

    /** The moment's chosen sound, or one of its sounds at random (never the same one twice in a row). */
    fun play(moment: PrismMoment, then: () -> Unit = {}) = playSet(moment.name, then)

    private fun playSet(base: String, then: () -> Unit) {
        val look = PrismStore.look
        if (!look.soundsOn || Prism.platform == null) return then()
        val choices = look.soundSet(base).filter { it.second.enabled }
        if (choices.isEmpty()) return then()
        look.soundChoice[base]?.let { key -> choices.firstOrNull { it.first == key } }?.let { chosen ->
            return playFile(PrismStore.mediaPath(chosen.second.file), chosen.second.volume, then)
        }
        val pick = choices.filter { it.first != lastPicked[base] }.ifEmpty { choices }.random()
        lastPicked[base] = pick.first
        playFile(PrismStore.mediaPath(pick.second.file), pick.second.volume, then)
    }

    /**
     * One of the card's own sounds, picked at random, or else one of the "Play random episode" sounds.
     */
    fun playCard(title: PrismTitle, then: () -> Unit = {}) {
        val look = PrismStore.look
        val base = cardSoundKey(title).takeIf { key -> look.soundSet(key).any { it.second.enabled } }
            ?: PrismMoment.PickEpisode.name
        playSet(base, then)
    }

    private var finishedId: String? = null

    /** "Finished watching", once for each thing watched. */
    fun finished(videoId: String) {
        if (finishedId == videoId) return
        finishedId = videoId
        play(PrismMoment.EpisodeFinished)
    }

    fun has(moment: PrismMoment): Boolean {
        val look = PrismStore.look
        return look.soundsOn && look.soundSet(moment.name).any { it.second.enabled }
    }

    fun playFile(path: String, volume: Float = 1f, then: () -> Unit = {}) {
        val p = Prism.platform ?: return then()
        p.stopSound()
        playing = true
        p.playWav(path, volume.coerceIn(0f, 1f)) {
            playing = false
            then()
        }
    }

    fun stop() {
        Prism.platform?.stopSound()
        playing = false
    }
}
