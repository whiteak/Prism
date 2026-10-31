@file:OptIn(UnstableApi::class)

package com.prism.clipper

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** How hard to squeeze the video: bits per pixel per frame for H.265 (H.264 gets half as much again). */
enum class Quality(val label: String, val bitsPerPixel: Float, val shrinkShare: Float, val shrinkShareEfficient: Float) {
    // When shrinking, the share of the original video's bitrate to aim for: from an H.264 original, and from one
    // that's already H.265/AV1/VP9. High is chosen to look the same as the original.
    High("High", 0.10f, 0.6f, 0.85f), Medium("Medium", 0.06f, 0.45f, 0.65f), Small("Small", 0.035f, 0.3f, 0.45f)
}

/** What to make from the picked file. */
/** One stretch of one file; null start and end means the whole file. */
data class Segment(val source: Uri, val startMs: Long?, val endMs: Long?) {
    fun lengthMs(): Long = if (startMs != null && endMs != null) endMs - startMs else 0L
}

data class ExportRequest(
    /** Played one after another with no gap when there's more than one. */
    val segments: List<Segment>,
    val soundBite: Boolean,
    val keepSound: Boolean,
    /** Shorter side of the picture in pixels, or null to keep the original size. */
    val shortSide: Int?,
    /** Picture size as the player shows it (already upright); joined parts are fitted to this size. */
    val videoWidth: Int,
    val videoHeight: Int,
    val quality: Quality = Quality.High,
    val speed: Float = 1f,
    /** Clockwise, 0/90/180/270. */
    val rotation: Int = 0,
    /** Width / height to crop the picture to (centred), or null for no crop. */
    val cropRatio: Float? = null,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val evenVolume: Boolean = false,
    /** Joined parts: fade to black and back up over this long at each join (sound dips too). 0 is a straight cut. */
    val dipMs: Long = 0,
    /** Joined parts: blend each part into the next over this long. 0 means no crossfade. */
    val crossfadeMs: Long = 0,
    /** Smooth loop for a single clip: its end blends into its start over this long, so it repeats without a jump. */
    val loopFadeMs: Long = 0,
    /** Upper limit for the video bitrate (bits per second), worked out from the original file; null for none. */
    val maxVideoBitrate: Int? = null,
)

/**
 * Trims and converts with Media3 Transformer: video is encoded to H.265 (H.264 if the phone has no H.265
 * encoder), sound to AAC. Sound can fade in and out and be levelled so its loudest point sits just under full.
 */
class Exporter(private val context: Context) {
    private var transformer: Transformer? = null

    val hasHevc: Boolean by lazy { EncoderUtil.getSupportedEncoders(MimeTypes.VIDEO_H265).isNotEmpty() }

    /** Must be called on the main thread. [onDone] gets null on success or an error message. */
    fun start(request: ExportRequest, output: File, onDone: (String?) -> Unit) {
        val hasSound = request.soundBite || request.keepSound
        if (request.evenVolume && hasSound) {
            // First a quick sound-only pass to find the loudest point, then the real export with the gain.
            val meters = mutableListOf<PeakMeter>()
            val probe = File(output.parentFile, "probe-${output.name}.m4a")
            run(request.copy(soundBite = true, fadeInMs = 0, fadeOutMs = 0), probe, gain = 1f, meters = meters, toneMap = false) { error ->
                probe.delete()
                // If the measuring pass fails (say the video has no sound), carry on without levelling.
                val peak = meters.maxOfOrNull { it.peak } ?: 0f
                val gain = if (error == null && peak > 0.0001f) (0.89f / peak).coerceIn(0.1f, 8f) else 1f
                run(request, output, gain = gain, meters = null, toneMap = false, onDone = onDone)
            }
        } else {
            run(request, output, gain = 1f, meters = null, toneMap = false, onDone = onDone)
        }
    }

    private fun run(
        request: ExportRequest,
        output: File,
        gain: Float,
        meters: MutableList<PeakMeter>?,
        toneMap: Boolean,
        onDone: (String?) -> Unit,
    ) {
        output.delete()
        val video = if (request.soundBite) emptyList() else videoEffects(request)
        val crossMs = crossfadeLength(request)
        val loopMs = loopLength(request)
        val composition = if (loopMs > 0) {
            loopComposition(request, video, gain, meters, loopMs)
        } else if (crossMs > 0) {
            crossfadeComposition(request, video, gain, meters, crossMs)
        } else {
            val last = request.segments.lastIndex
            val items = request.segments.mapIndexed { i, segment ->
                // Joined parts: fade in at the very start and out at the very end, and dip at each join.
                val fadeIn = if (i == 0) request.fadeInMs else request.dipMs
                val fadeOut = if (i == last) request.fadeOutMs else request.dipMs
                val lengthMs = segment.lengthMs()
                val itemVideo = if (request.dipMs > 0 && lengthMs > 0 && !request.soundBite) {
                    video + DipToBlack(if (i == 0) 0 else request.dipMs, if (i == last) 0 else request.dipMs, lengthMs)
                } else {
                    video
                }
                val audio = mutableListOf<AudioProcessor>()
                if (meters != null) audio += PeakMeter().also { meters += it }
                if (gain != 1f || fadeIn > 0 || fadeOut > 0) audio += FadeAndGain(gain, fadeIn, fadeOut, lengthMs)
                item(request, segment.source, segment.startMs, segment.endMs, audio, itemVideo)
            }
            Composition.Builder(EditedMediaItemSequence(items))
                .setHdrMode(if (toneMap) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR)
                .build()
        }
        val builder = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    onDone(null)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    // Some phones can't encode HDR video; try again converted to normal colours.
                    if (!toneMap && !request.soundBite &&
                        exportException.errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED
                    ) {
                        run(request, output, gain, meters, toneMap = true, onDone = onDone)
                    } else {
                        onDone(exportException.message ?: exportException.errorCodeName)
                    }
                }
            })
        if (!request.soundBite) {
            builder.setVideoMimeType(if (hasHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            val (w, h) = outputSize(request)
            if (w > 0 && h > 0) {
                val bpp = request.quality.bitsPerPixel * (if (hasHevc) 1f else 1.5f)
                // Never spend more bits than the original had, or an already small video comes out bigger.
                val estimate = (w.toLong() * h * 30 * bpp).toInt()
                val bitrate = minOf(estimate, request.maxVideoBitrate ?: Int.MAX_VALUE).coerceIn(300_000, 60_000_000)
                builder.setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .setEnableFallback(true)
                        .build(),
                )
            }
        }
        transformer = builder.build().also { it.start(composition, output.absolutePath) }
    }

    private fun item(
        request: ExportRequest,
        source: Uri,
        startMs: Long?,
        endMs: Long?,
        audioProcessors: List<AudioProcessor>,
        videoEffects: List<Effect>,
    ): EditedMediaItem {
        val itemBuilder = MediaItem.Builder().setUri(source)
        if (startMs != null && endMs != null) {
            itemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
            )
        }
        val removeAudio = !request.soundBite && !request.keepSound
        var audio = audioProcessors
        var video = videoEffects
        if (request.speed != 1f && !request.soundBite) {
            val speed = ConstantSpeed(request.speed)
            if (removeAudio) {
                video = listOf<Effect>(SpeedChangeEffect(speed)) + video
            } else {
                // Sound and picture change speed together, driven by the sound.
                val pair = Effects.createExperimentalSpeedChangingEffect(speed)
                audio = listOf(pair.first) + audio
                video = listOf(pair.second) + video
            }
        }
        return EditedMediaItem.Builder(itemBuilder.build())
            .setRemoveVideo(request.soundBite)
            .setRemoveAudio(removeAudio)
            .setEffects(Effects(audio, video))
            .build()
    }

    /** The crossfade length to use, or 0 for no crossfade (it needs normal speed and parts longer than two fades). */
    private fun crossfadeLength(request: ExportRequest): Long {
        if (request.crossfadeMs <= 0 || request.segments.size < 2) return 0
        if (request.speed != 1f && !request.soundBite) return 0
        if (request.segments.any { it.startMs == null }) return 0
        val shortest = request.segments.minOf { it.lengthMs() }
        val x = minOf(request.crossfadeMs, shortest / 2 - 50)
        return if (x >= 50) x else 0
    }

    /** The smooth-loop blend length, or 0 when it doesn't apply (needs one part, normal speed, and room for it). */
    private fun loopLength(request: ExportRequest): Long {
        if (request.loopFadeMs <= 0 || request.segments.size != 1) return 0
        val part = request.segments.first()
        if (part.startMs == null || request.speed != 1f) return 0
        val x = minOf(request.loopFadeMs, part.lengthMs() / 3)
        return if (x >= 50) x else 0
    }

    /**
     * Smooth loop: the clip plays from [x] in to its end, and over its last [x] the top layer fades away to show
     * the clip's real first [x] underneath. When it repeats it carries on from [x] in, so there's no jump.
     * The saved clip is [x] shorter than the chosen part.
     */
    private fun loopComposition(
        request: ExportRequest,
        video: List<Effect>,
        gain: Float,
        meters: MutableList<PeakMeter>?,
        x: Long,
    ): Composition {
        val p = request.segments.first()
        val start = p.startMs!!
        val end = p.endMs!!
        fun processors(fade: FadeAndGain): List<AudioProcessor> {
            val list = mutableListOf<AudioProcessor>()
            if (meters != null) list += PeakMeter().also { meters += it }
            list += fade
            return list
        }
        val topLength = end - (start + x)
        val top = listOf(item(request, p.source, start + x, end, processors(FadeAndGain(gain, 0, x, topLength)), video))
        // Underneath: the same middle (hidden, silent), then the real start rising in.
        val bottom = listOf(
            item(request, p.source, start + x, end - x, processors(FadeAndGain(0f, 0, 0, 0)), video),
            item(request, p.source, start, start + x, processors(FadeAndGain(gain, x, 0, x)), video),
        )
        val fadeFromUs = (topLength - x) * 1000
        val xUs = x * 1000
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes[0]

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                val alpha = if (inputId == 0 && presentationTimeUs >= fadeFromUs) {
                    (1f - (presentationTimeUs - fadeFromUs).toFloat() / xUs).coerceIn(0f, 1f)
                } else {
                    1f
                }
                return StaticOverlaySettings.Builder().setAlphaScale(alpha).build()
            }
        }
        return Composition.Builder(EditedMediaItemSequence(top), EditedMediaItemSequence(bottom))
            .setVideoCompositorSettings(settings)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    /**
     * Crossfade: two layers. The top layer plays every part back to back, each after the first starting
     * [x] in. The bottom layer holds each part's real start, lined up underneath the end of the part before.
     * Over that overlap the top layer fades out to show the bottom, and the sound crossfades the same way.
     */
    private fun crossfadeComposition(
        request: ExportRequest,
        video: List<Effect>,
        gain: Float,
        meters: MutableList<PeakMeter>?,
        x: Long,
    ): Composition {
        val parts = request.segments
        val last = parts.lastIndex
        fun processors(build: () -> FadeAndGain): List<AudioProcessor> {
            val list = mutableListOf<AudioProcessor>()
            if (meters != null) list += PeakMeter().also { meters += it }
            list += build()
            return list
        }
        val top = parts.mapIndexed { i, p ->
            val start = if (i == 0) p.startMs!! else p.startMs!! + x
            val length = p.endMs!! - start
            val audio = processors {
                FadeAndGain(gain, if (i == 0) request.fadeInMs else 0, if (i == last) request.fadeOutMs else x, length)
            }
            item(request, p.source, start, p.endMs, audio, video)
        }
        val bottom = parts.mapIndexed { i, p ->
            val end = if (i == last) p.startMs!! + x else p.endMs!! - x
            // Only each part's first x is heard from here (rising in); the rest is the top layer's job.
            val audio = processors {
                if (i == 0) FadeAndGain(0f, 0, 0, 0) else FadeAndGain(gain, x, 0, end - p.startMs!!, silentAfterMs = x)
            }
            item(request, p.source, p.startMs, end, audio, video)
        }
        // Where the top layer moves on to the next part, in microseconds.
        val joins = mutableListOf<Long>()
        var t = 0L
        for (i in 0 until last) {
            val shownFrom = if (i == 0) parts[i].startMs!! else parts[i].startMs!! + x
            t += (parts[i].endMs!! - shownFrom) * 1000
            joins += t
        }
        val xUs = x * 1000
        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes[0]

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                var alpha = 1f
                if (inputId == 0) {
                    for (join in joins) {
                        if (presentationTimeUs >= join - xUs && presentationTimeUs < join) {
                            alpha = (join - presentationTimeUs).toFloat() / xUs
                        }
                    }
                }
                return StaticOverlaySettings.Builder().setAlphaScale(alpha).build()
            }
        }
        return Composition.Builder(EditedMediaItemSequence(top), EditedMediaItemSequence(bottom))
            .setVideoCompositorSettings(settings)
            // Layers can only be blended in normal colours, so HDR is converted.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    /** Crop (centred) on the upright picture, then rotate, then scale down. */
    private fun videoEffects(request: ExportRequest): List<Effect> {
        val effects = mutableListOf<Effect>()
        val w = request.videoWidth
        val h = request.videoHeight
        val ratio = request.cropRatio
        if (ratio != null && w > 0 && h > 0) {
            // The crop shape is what you see after rotating, so turn it sideways first for 90 and 270.
            val r = if (request.rotation % 180 != 0) 1f / ratio else ratio
            val fw: Float
            val fh: Float
            if (w.toFloat() / h > r) { fh = 1f; fw = r * h / w } else { fw = 1f; fh = w / (r * h) }
            effects += Crop(-fw, fw, -fh, fh)
        }
        if (request.rotation != 0) {
            // Media3 turns anticlockwise for positive degrees.
            effects += ScaleAndRotateTransformation.Builder().setRotationDegrees(360f - request.rotation).build()
        }
        val (outW, outH) = outputSize(request)
        if (outW > 0 && outH > 0) effects += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT)
        return effects
    }

    /** Final picture size after crop, rotation and scaling, or 0 x 0 when the picture size isn't known yet. */
    fun outputSize(request: ExportRequest): Pair<Int, Int> {
        var w = request.videoWidth.toFloat()
        var h = request.videoHeight.toFloat()
        if (w <= 0 || h <= 0) return 0 to 0
        request.cropRatio?.let { ratio ->
            val r = if (request.rotation % 180 != 0) 1f / ratio else ratio
            if (w / h > r) w = h * r else h = w / r
        }
        if (request.rotation % 180 != 0) { val t = w; w = h; h = t }
        val target = request.shortSide
        if (target != null && minOf(w, h) > target) {
            val scale = target / minOf(w, h)
            w *= scale
            h *= scale
        }
        return even(w) to even(h)
    }

    private fun even(value: Float): Int = ((value / 2f).roundToInt() * 2).coerceAtLeast(2)

    /** 0..100, or -1 when unknown. */
    fun progress(): Int {
        val t = transformer ?: return -1
        val holder = ProgressHolder()
        return if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }
}

private class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/** Passes sound through unchanged while noting the loudest sample (0..1). */
class PeakMeter : BaseAudioProcessor() {
    var peak = 0f
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val buffer = inputBuffer.order(ByteOrder.nativeOrder())
        while (buffer.hasRemaining()) {
            if (float) {
                val v = buffer.getFloat()
                peak = maxOf(peak, abs(v))
                out.putFloat(v)
            } else {
                val v = buffer.getShort()
                peak = maxOf(peak, abs(v.toInt()) / 32768f)
                out.putShort(v)
            }
        }
        out.flip()
    }
}

/** Multiplies the sound by [gain] and fades it in and out. Positions count from the start of the clip. */
private class FadeAndGain(
    private val gain: Float,
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val lengthMs: Long,
    /** Silence everything after this point (for the crossfade's lower layer); -1 keeps it all. */
    private val silentAfterMs: Long = -1,
) : BaseAudioProcessor() {
    private var frame = 0L
    private var fadeInFrames = 0L
    private var fadeOutFrames = 0L
    private var totalFrames = 0L
    private var silentFrames = -1L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val rate = inputAudioFormat.sampleRate
        fadeInFrames = fadeInMs * rate / 1000
        fadeOutFrames = fadeOutMs * rate / 1000
        totalFrames = lengthMs * rate / 1000
        silentFrames = if (silentAfterMs >= 0) silentAfterMs * rate / 1000 else -1
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val channels = format.channelCount
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val buffer = inputBuffer.order(ByteOrder.nativeOrder())
        while (buffer.remaining() >= format.bytesPerFrame) {
            var level = gain
            if (fadeInFrames > 0 && frame < fadeInFrames) level *= frame.toFloat() / fadeInFrames
            if (fadeOutFrames > 0 && totalFrames > 0) {
                val left = totalFrames - frame
                if (left < fadeOutFrames) level *= (left.coerceAtLeast(0)).toFloat() / fadeOutFrames
            }
            if (silentFrames >= 0 && frame >= silentFrames) level = 0f
            repeat(channels) {
                if (float) {
                    out.putFloat((buffer.getFloat() * level).coerceIn(-1f, 1f))
                } else {
                    out.putShort((buffer.getShort() * level).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            frame++
        }
        buffer.position(buffer.limit())
        out.flip()
    }

    override fun onFlush() {
        frame = 0
    }
}

/**
 * Darkens the picture to black over the first [fadeInMs] and last [fadeOutMs] of one part. Times count from
 * the first frame this part shows, so it works wherever the part sits in the joined file.
 */
private class DipToBlack(private val fadeInMs: Long, private val fadeOutMs: Long, private val lengthMs: Long) : RgbMatrix {
    @Volatile private var firstUs = Long.MIN_VALUE

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val ms = (presentationTimeUs - firstUs) / 1000f
        var k = 1f
        if (fadeInMs > 0 && ms < fadeInMs) k = minOf(k, ms / fadeInMs)
        if (fadeOutMs > 0 && lengthMs - ms < fadeOutMs) k = minOf(k, ((lengthMs - ms) / fadeOutMs).coerceAtLeast(0f))
        return floatArrayOf(
            k, 0f, 0f, 0f,
            0f, k, 0f, 0f,
            0f, 0f, k, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}
