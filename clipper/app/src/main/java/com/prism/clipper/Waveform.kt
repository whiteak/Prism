package com.prism.clipper

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Reads a file's sound and boils it down to [buckets] loudness peaks (0..1) across its length, for drawing. */
object Waveform {
    suspend fun peaks(context: Context, uri: Uri, durationMs: Long, buckets: Int = 600): FloatArray? {
        if (durationMs <= 0) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val peaks = FloatArray(buckets)
            val totalUs = durationMs * 1000
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var isFloat = false
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        isFloat = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val bucket = ((info.presentationTimeUs * buckets) / totalUs).toInt().coerceIn(0, buckets - 1)
                            val data = decoder.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            var peak = 0f
                            if (isFloat) {
                                val floats = data.asFloatBuffer()
                                // Every 4th sample is plenty for a picture of loudness.
                                var i = 0
                                while (i < floats.limit()) { peak = maxOf(peak, abs(floats.get(i))); i += 4 }
                            } else {
                                val shorts = data.asShortBuffer()
                                var i = 0
                                while (i < shorts.limit()) { peak = maxOf(peak, abs(shorts.get(i).toInt()) / 32768f); i += 4 }
                            }
                            peaks[bucket] = maxOf(peaks[bucket], peak.coerceAtMost(1f))
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return peaks
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
