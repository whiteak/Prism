package com.prism.clipper

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class SaveTo { Prism, MyFolder, AskEachTime }

enum class Mode(val label: String) { Clip("Clip"), SoundBite("Sound bite"), Shrink("Shrink video") }

/** Shorter side of the picture; null keeps the original size. */
enum class ClipSize(val label: String, val shortSide: Int?) {
    Original("Original", null), P1080("1080p", 1080), P720("720p", 720), P480("480p", 480)
}

/** Picture shape for video crops; ratio is width / height. */
enum class VideoCrop(val label: String, val ratio: Float?) {
    None("No crop", null), Phone("9:16 phone", 9f / 16f), Wide("16:9", 16f / 9f), Square("Square", 1f), Photo("4:3", 4f / 3f)
}

val Speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 4f)
val Fades = listOf(0L, 250L, 500L, 1000L, 2000L)
enum class JoinStyle(val label: String) { Cut("Straight cut"), Dip("Dip to black"), Crossfade("Crossfade") }
val JoinLengths = listOf(200L, 350L, 500L, 800L, 1200L)

sealed interface Status {
    data object Idle : Status
    data class Working(val percent: Int, val label: String = "Working") : Status
    data class Done(val message: String) : Status
    data class Failed(val message: String) : Status
}

/** One part of a video to cut, for making several clips in one go or joining them into one. */
data class Part(
    val source: Uri,
    val sourceName: String,
    val startMs: Long,
    val endMs: Long,
    val name: String,
    val videoWidth: Int,
    val videoHeight: Int,
)

/** One file to make: a single part, or several parts joined. Start -1 means the whole video. */
private data class ExportJob(val parts: List<Part>, val name: String)

class ClipperModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("clipper", Context.MODE_PRIVATE)
    private val exporter = Exporter(app)

    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    var tab by mutableStateOf(Section.Clips)

    var source by mutableStateOf<Uri?>(null); private set
    var sourceName by mutableStateOf(""); private set
    var sourceBytes by mutableLongStateOf(0L); private set
    var sourceIsAudio by mutableStateOf(false); private set
    /** Video format of the open file, e.g. video/avc (H.264) or video/hevc (H.265); empty until known. */
    var sourceVideoMime by mutableStateOf(""); private set
    val sourceAlreadyEfficient get() = sourceVideoMime in listOf("video/hevc", "video/av01", "video/x-vnd.on2.vp9")
    var durationMs by mutableLongStateOf(0L); private set
    var videoWidth by mutableIntStateOf(0); private set
    var videoHeight by mutableIntStateOf(0); private set

    var startMs by mutableLongStateOf(0L)
    var endMs by mutableLongStateOf(0L)
    val parts = mutableStateListOf<Part>()
    var joinParts by mutableStateOf(prefs.getBoolean("joinParts", false)); private set
    var joinStyle by mutableStateOf(JoinStyle.entries.getOrElse(prefs.getInt("joinStyle", 1)) { JoinStyle.Dip }); private set
    var joinMs by mutableLongStateOf(prefs.getLong("joinMs", 350L)); private set

    var mode by mutableStateOf(Mode.entries.getOrElse(prefs.getInt("mode", 0)) { Mode.Clip }); private set
    var keepSound by mutableStateOf(prefs.getBoolean("keepSound", true)); private set
    var size by mutableStateOf(ClipSize.entries.getOrElse(prefs.getInt("size", 0)) { ClipSize.Original }); private set
    var quality by mutableStateOf(Quality.entries.getOrElse(prefs.getInt("quality", 0)) { Quality.High }); private set
    var speed by mutableStateOf(1f)
    var rotation by mutableIntStateOf(0)
    var crop by mutableStateOf(VideoCrop.None)
    var fadeInMs by mutableLongStateOf(prefs.getLong("fadeIn", 0L)); private set
    var fadeOutMs by mutableLongStateOf(prefs.getLong("fadeOut", 0L)); private set
    var evenVolume by mutableStateOf(prefs.getBoolean("evenVolume", false)); private set
    var smoothLoop by mutableStateOf(prefs.getBoolean("smoothLoop", false)); private set
    var loopFadeMs by mutableLongStateOf(prefs.getLong("loopFadeMs", 500L)); private set
    /** True while the chosen part is playing over and over. */
    var looping by mutableStateOf(false); private set
    private var loopJob: Job? = null

    /** Loudness across the whole file for drawing the sound wave; null while reading or when there's no sound. */
    var waveform by mutableStateOf<FloatArray?>(null); private set
    var waveformLoading by mutableStateOf(false); private set
    private var waveJob: Job? = null

    var saveTo by mutableStateOf(SaveTo.entries.getOrElse(prefs.getInt("saveTo", 0)) { SaveTo.Prism }); private set
    var myFolder by mutableStateOf(prefs.getString("myFolder", null)?.let(Uri::parse)); private set
    var myFolderName by mutableStateOf(prefs.getString("myFolderName", "") ?: ""); private set

    var fileName by mutableStateOf("")
    var status by mutableStateOf<Status>(Status.Idle); private set

    /** Set when a finished file is waiting for you to pick where to save it (Ask each time). */
    var pendingAsk by mutableStateOf<String?>(null); private set
    private var finished: File? = null
    private val queue = ArrayDeque<ExportJob>()
    private val saved = mutableListOf<String>()
    private var savedBytes = 0L

    val hasHevc get() = exporter.hasHevc
    val effectiveMode get() = if (sourceIsAudio) Mode.SoundBite else mode
    val isSoundBite get() = effectiveMode == Mode.SoundBite
    val extension get() = if (isSoundBite) "m4a" else "mp4"
    val mime get() = if (isSoundBite) "audio/mp4" else "video/mp4"
    /** Fades and levelling need normal speed, because they count time in the original sound. */
    val soundOptionsAllowed get() = effectiveMode != Mode.Shrink && (isSoundBite || (keepSound && speed == 1f))

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && durationMs == 0L && player.duration > 0) {
                    durationMs = player.duration
                    startMs = 0L
                    endMs = minOf(durationMs, 20_000L)
                    loadWaveform()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                }
            }
        })
    }

    fun open(uri: Uri) {
        val ctx = getApplication<Application>()
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        source = uri
        sourceName = displayName(ctx, uri)
        sourceBytes = fileSize(ctx, uri)
        sourceIsAudio = ctx.contentResolver.getType(uri)?.startsWith("audio/") == true
        durationMs = 0L
        waveJob?.cancel()
        waveform = null
        if (looping) toggleLoop()
        videoWidth = 0
        videoHeight = 0
        speed = 1f
        rotation = 0
        crop = VideoCrop.None
        status = Status.Idle
        fileName = sourceName.substringBeforeLast('.').ifBlank { "clip" }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = false
        sourceVideoMime = ""
        viewModelScope.launch {
            sourceVideoMime = withContext(Dispatchers.IO) {
                runCatching {
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(ctx, uri, null)
                        (0 until extractor.trackCount)
                            .mapNotNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                            .firstOrNull { it.startsWith("video/") } ?: ""
                    } finally {
                        extractor.release()
                    }
                }.getOrDefault("")
            }
        }
    }

    fun setStartHere() {
        startMs = player.currentPosition.coerceIn(0L, (endMs - 100).coerceAtLeast(0L))
    }

    fun setEndHere() {
        endMs = player.currentPosition.coerceIn(startMs + 100, durationMs)
    }

    fun nudgeStart(deltaMs: Long) {
        startMs = (startMs + deltaMs).coerceIn(0L, (endMs - 100).coerceAtLeast(0L))
        player.seekTo(startMs)
    }

    fun nudgeEnd(deltaMs: Long) {
        endMs = (endMs + deltaMs).coerceIn(startMs + 100, durationMs)
        player.seekTo(endMs)
    }

    /** Plays just the chosen part, then stops. */
    private fun loadWaveform() {
        val uri = source ?: return
        val length = durationMs
        waveJob?.cancel()
        waveform = null
        waveformLoading = true
        val ctx = getApplication<Application>()
        waveJob = viewModelScope.launch {
            val peaks = withContext(Dispatchers.Default) { Waveform.peaks(ctx, uri, length) }
            waveform = peaks?.takeIf { p -> p.any { it > 0.001f } }
            waveformLoading = false
        }
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceIn(0L, durationMs))
    }

    /** Plays the chosen part over and over (tap again to stop), to check how a hero clip loops. */
    fun toggleLoop() {
        loopJob?.cancel()
        if (looping) {
            looping = false
            player.pause()
            return
        }
        looping = true
        player.seekTo(startMs)
        player.play()
        loopJob = viewModelScope.launch {
            while (looping) {
                if (player.currentPosition >= endMs || player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(startMs)
                    player.play()
                }
                delay(30)
            }
        }
    }

    fun chooseSmoothLoop(on: Boolean) { smoothLoop = on; prefs.edit { putBoolean("smoothLoop", on) } }
    fun chooseLoopFade(ms: Long) { loopFadeMs = ms; prefs.edit { putLong("loopFadeMs", ms) } }

    fun playSelection() {
        if (looping) toggleLoop()
        player.seekTo(startMs)
        player.play()
        viewModelScope.launch {
            while (player.isPlaying || player.playbackState == Player.STATE_BUFFERING || player.currentPosition < startMs) {
                if (player.currentPosition >= endMs) break
                delay(50)
            }
            player.pause()
        }
    }

    /** Remembers the chosen part so several clips can be made from one video in one go. */
    fun addPart() {
        val base = fileName.trim().ifBlank { "clip" }
        val src = source ?: return
        parts += Part(src, sourceName, startMs, endMs, "$base ${parts.size + 1}", videoWidth, videoHeight)
    }

    fun removePart(part: Part) { parts.remove(part) }
    fun clearParts() { parts.clear() }
    fun chooseJoinStyle(j: JoinStyle) { joinStyle = j; prefs.edit { putInt("joinStyle", j.ordinal) } }
    fun chooseJoinMs(ms: Long) { joinMs = ms; prefs.edit { putLong("joinMs", ms) } }
    fun chooseJoin(on: Boolean) { joinParts = on; prefs.edit { putBoolean("joinParts", on) } }

    fun chooseMode(m: Mode) { mode = m; prefs.edit { putInt("mode", m.ordinal) } }
    fun chooseKeepSound(on: Boolean) { keepSound = on; prefs.edit { putBoolean("keepSound", on) } }
    fun chooseSize(s: ClipSize) { size = s; prefs.edit { putInt("size", s.ordinal) } }
    fun chooseQuality(q: Quality) { quality = q; prefs.edit { putInt("quality", q.ordinal) } }
    fun chooseFadeIn(ms: Long) { fadeInMs = ms; prefs.edit { putLong("fadeIn", ms) } }
    fun chooseFadeOut(ms: Long) { fadeOutMs = ms; prefs.edit { putLong("fadeOut", ms) } }
    fun chooseEvenVolume(on: Boolean) { evenVolume = on; prefs.edit { putBoolean("evenVolume", on) } }
    fun chooseSaveTo(s: SaveTo) { saveTo = s; prefs.edit { putInt("saveTo", s.ordinal) } }

    fun setFolder(tree: Uri) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        myFolder = tree
        myFolderName = Saver.folderName(ctx, tree)
        prefs.edit { putString("myFolder", tree.toString()); putString("myFolderName", myFolderName) }
        chooseSaveTo(SaveTo.MyFolder)
    }

    /** The picture at the player's current spot, full size, for the Pictures tab. */
    suspend fun grabFrame(): Bitmap? {
        val uri = source ?: return null
        val at = player.currentPosition
        player.pause()
        val ctx = getApplication<Application>()
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ctx, uri)
                retriever.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
    }

    /** Makes the chosen part, every part in the list, or the whole video when shrinking. */
    fun make() {
        if (source == null || status is Status.Working) return
        if (saveTo == SaveTo.MyFolder && myFolder == null) {
            status = Status.Failed("Pick your folder first.")
            return
        }
        if (looping) toggleLoop()
        player.pause()
        queue.clear()
        saved.clear()
        savedBytes = 0L
        when {
            effectiveMode == Mode.Shrink -> queue += ExportJob(listOf(current(-1, -1)), fileName.trim().ifBlank { "video" })
            parts.size > 1 && joinParts -> queue += ExportJob(parts.toList(), fileName.trim().ifBlank { "joined" })
            parts.isNotEmpty() -> parts.forEach { queue += ExportJob(listOf(it), it.name) }
            else -> queue += ExportJob(listOf(current(startMs, endMs)), fileName.trim().ifBlank { "clip" })
        }
        next()
    }

    private fun current(start: Long, end: Long) =
        Part(source!!, sourceName, start, end, fileName, videoWidth, videoHeight)

    private fun next() {
        val job = queue.removeFirstOrNull()
        if (job == null) {
            status = Status.Done(doneMessage())
            return
        }
        val ctx = getApplication<Application>()
        val first = job.parts.first()
        ctx.cacheDir.listFiles { f -> f.name.startsWith("clipper-") || f.name.startsWith("probe-") }?.forEach { it.delete() }
        val out = File(ctx.cacheDir, "clipper-${System.currentTimeMillis()}.$extension")
        val request = ExportRequest(
            segments = job.parts.map {
                if (it.startMs < 0) Segment(it.source, null, null) else Segment(it.source, it.startMs, it.endMs)
            },
            soundBite = isSoundBite,
            keepSound = keepSound || effectiveMode == Mode.Shrink,
            shortSide = size.shortSide,
            videoWidth = first.videoWidth,
            videoHeight = first.videoHeight,
            quality = quality,
            speed = if (isSoundBite) 1f else speed,
            rotation = if (isSoundBite) 0 else rotation,
            cropRatio = if (isSoundBite) null else crop.ratio,
            fadeInMs = if (soundOptionsAllowed) fadeInMs else 0,
            fadeOutMs = if (soundOptionsAllowed) fadeOutMs else 0,
            evenVolume = soundOptionsAllowed && evenVolume,
            maxVideoBitrate = maxVideoBitrate(job),
            loopFadeMs = if (smoothLoop && effectiveMode == Mode.Clip && job.parts.size == 1) loopFadeMs else 0L,
            // Dips and crossfades count time in the original video, so they need normal speed.
            dipMs = if (job.parts.size > 1 && joinStyle == JoinStyle.Dip && (isSoundBite || speed == 1f)) joinMs else 0L,
            crossfadeMs = if (job.parts.size > 1 && joinStyle == JoinStyle.Crossfade) joinMs else 0L,
        )
        val total = saved.size + queue.size + 1
        val label = if (total > 1) "Making ${saved.size + 1} of $total" else "Working"
        status = Status.Working(0, label)
        val poll = viewModelScope.launch {
            while (true) {
                val p = exporter.progress()
                if (p >= 0 && status is Status.Working) status = Status.Working(p, label)
                delay(250)
            }
        }
        exporter.start(request, out) { error ->
            poll.cancel()
            if (error != null) {
                out.delete()
                queue.clear()
                status = Status.Failed("Couldn't make it: $error")
            } else {
                save(out, finalName(job.name))
            }
        }
    }

    fun cancel() {
        exporter.cancel()
        queue.clear()
        status = Status.Idle
    }

    private fun finalName(name: String): String {
        val base = name.trim().ifBlank { "clip" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (base.endsWith(".$extension", ignoreCase = true)) base else "$base.$extension"
    }

    /**
     * The original's video bitrate (its size over its length, less about 128 kbit/s of sound), scaled down when
     * shrinking. Only known when every part comes from the file that's open now.
     */
    private fun maxVideoBitrate(job: ExportJob): Int? {
        val src = source ?: return null
        if (sourceBytes <= 0 || durationMs <= 0 || job.parts.any { it.source != src }) return null
        val videoBps = (sourceBytes * 8 * 1000 / durationMs - 128_000).coerceAtLeast(300_000)
        // H.265 needs about half the data of H.264 for the same look, so an H.264 original shrinks a lot with
        // no visible loss. An original that's already H.265 (or similar) only has a little to give.
        val share = when {
            effectiveMode != Mode.Shrink -> 1f
            sourceAlreadyEfficient -> quality.shrinkShareEfficient
            else -> quality.shrinkShare
        }
        return (videoBps * share).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun save(file: File, name: String) {
        val ctx = getApplication<Application>()
        val mime = mime
        if (effectiveMode == Mode.Shrink && sourceBytes > 0 && file.length() >= sourceBytes) {
            status = Status.Failed(
                "Couldn't make this one smaller (${megabytes(file.length())}, the original is ${megabytes(sourceBytes)}), " +
                    "so nothing was saved. Try Small quality or a smaller size.",
            )
            queue.clear()
            file.delete()
            return
        }
        if (saveTo == SaveTo.AskEachTime) {
            finished = file
            pendingAsk = name
            return
        }
        viewModelScope.launch {
            try {
                val bytes = file.length()
                val where = withContext(Dispatchers.IO) {
                    if (saveTo == SaveTo.Prism) Saver.toPrismFolder(ctx, file, name, mime)
                    else Saver.toFolder(ctx, file, myFolder!!, name, mime)
                }
                saved += "$name to $where"
                savedBytes += bytes
                next()
            } catch (e: Exception) {
                queue.clear()
                status = Status.Failed(e.message ?: "Couldn't save the file")
            } finally {
                file.delete()
            }
        }
    }

    /** Result of the "Save as" screen; null means you backed out. */
    fun savedAs(target: Uri?) {
        val file = finished ?: return
        val name = pendingAsk ?: ""
        pendingAsk = null
        finished = null
        if (target == null) {
            file.delete()
            next()
            return
        }
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val bytes = file.length()
                withContext(Dispatchers.IO) { Saver.copy(ctx, file, target) }
                saved += displayName(ctx, target).ifBlank { name }
                savedBytes += bytes
                next()
            } catch (e: Exception) {
                queue.clear()
                status = Status.Failed(e.message ?: "Couldn't save the file")
            } finally {
                file.delete()
            }
        }
    }

    private fun doneMessage(): String = when {
        saved.isEmpty() -> "Nothing saved."
        effectiveMode == Mode.Shrink && sourceBytes > 0 ->
            "Saved ${saved.first()}: ${megabytes(savedBytes)}, was ${megabytes(sourceBytes)}."
        saved.size == 1 -> "Saved ${saved.first()} (${megabytes(savedBytes)})."
        else -> "Saved ${saved.size} files (${megabytes(savedBytes)}):\n" + saved.joinToString("\n")
    }

    /**
     * Deletes the picked file to free up space (only after you confirm in the app). Returns a system
     * request to launch when Android wants to ask him itself (gallery videos), or null when it's done.
     */
    fun deleteOriginal(): IntentSender? {
        val uri = source ?: return null
        val ctx = getApplication<Application>()
        player.stop()
        player.clearMediaItems()
        when (val result = Saver.deleteOriginal(ctx, uri)) {
            Saver.DeleteResult.Deleted -> { originalDeleted(); return null }
            is Saver.DeleteResult.Ask -> return result.request
            Saver.DeleteResult.Failed -> {}
        }
        status = Status.Failed("Couldn't delete the original. Delete it in your file manager instead.")
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        return null
    }

    /** Called after the original is gone, from here or from Android's own delete prompt. */
    fun originalDeleted(confirmed: Boolean = true) {
        if (!confirmed) {
            source?.let { player.setMediaItem(MediaItem.fromUri(it)); player.prepare() }
            return
        }
        val name = sourceName
        parts.removeAll { it.source == source }
        source = null
        durationMs = 0L
        status = Status.Done("Deleted the original ($name), freeing ${megabytes(sourceBytes)}.")
    }

    override fun onCleared() {
        exporter.cancel()
        player.release()
    }
}

fun megabytes(bytes: Long): String = String.format(Locale.UK, "%.1f MB", bytes / 1_048_576.0)

fun displayName(ctx: Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""

fun fileSize(ctx: Context, uri: Uri): Long =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L
