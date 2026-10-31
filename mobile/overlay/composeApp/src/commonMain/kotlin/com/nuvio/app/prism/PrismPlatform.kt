package com.nuvio.app.prism

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily

// Prism: what the phone and the PC each do for Customise (files, pictures, sounds).
// The Android and desktop apps install their own at startup (PrismAndroid / PrismDesktop).

enum class PrismFileKind(val label: String, val extensions: Set<String>) {
    Picture("pictures", setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic")),
    Sound("sounds", setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "mp4", "webm", "3gp", "amr")),
    Look("saved looks", setOf("prism", "json")),
    Video("clips", setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts")),
    Library("library files", setOf("prismlib", "txt")),
    Font("fonts", setOf("ttf", "otf")),
    Backup("backups", setOf("prismbackup", "json")),
    ;

    fun matches(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions
}

/** A file brought in from the device's file picker or from the Prism folder; [ref] is where the original is, so it can be deleted. */
class PrismPickedFile(val name: String, val bytes: ByteArray, val ref: String? = null)

/**
 * A file waiting in the Prism folder (for example sent there with Send Anywhere); [folder] is the folder inside it
 * that it's in (Clipper saves to Phone, TV and Icons), or "" for the top.
 */
class PrismFolderFile(val name: String, val size: Long, val ref: String, val folder: String = "")

/** A clip or sound cut by the clip maker, and whether the original video was deleted (null if not asked to). */
class PrismMadeClip(val file: PrismFolderFile, val originalDeleted: Boolean?)

/** Decoded sound: interleaved 16-bit samples. */
class PrismPcm(val sampleRate: Int, val channels: Int, val samples: ShortArray) {
    val frames: Int get() = samples.size / channels
    val durationMs: Long get() = frames * 1000L / sampleRate
}

interface PrismPlatform {
    /** Where Prism keeps its looks and media; private to the app. */
    val dataDir: String

    /** True on the PC: pictures are cropped wide there, tall on the phone. */
    val wideScreen: Boolean

    fun readBytes(path: String): ByteArray?
    fun writeBytes(path: String, bytes: ByteArray)
    fun delete(path: String)

    /** Opens the device's own file picker. */
    suspend fun pickFile(kind: PrismFileKind): PrismPickedFile?

    /** Lets the user choose where to save [bytes]; false if cancelled or it failed. */
    suspend fun saveFile(suggestedName: String, bytes: ByteArray): Boolean

    /** Where the Prism folder is, in words, or null until one is linked. */
    val folderLabel: String?

    /** Phone: asks for the folder once (Download/Prism). PC: opens it in Explorer. */
    suspend fun linkFolder(): Boolean

    /** The Prism folder's files, newest first, with those one folder down (Phone, TV, Icons and the like). */
    suspend fun listFolder(): List<PrismFolderFile>
    suspend fun readFolderFile(file: PrismFolderFile): ByteArray?

    /** Opens the file picker for big files (clips): returns them without reading them into memory. */
    suspend fun pickRefs(kind: PrismFileKind, multiple: Boolean): List<PrismFolderFile>

    /** Copies a picked or Prism-folder file to [destPath] in Prism's storage. */
    suspend fun copyRef(file: PrismFolderFile, destPath: String): Boolean

    /** True when this device has the clip maker (the phone). */
    val canMakeClips: Boolean get() = false

    /**
     * Opens the clip maker on a video from the device to cut out a part: with [sound] just its sound (an .m4a),
     * otherwise a silent H.265 clip. The made file is temporary; copy it into Prism. Null if cancelled.
     */
    suspend fun makeClip(sound: Boolean): PrismMadeClip? = null

    /** TV and PC: they can copy what's set up on the phone (PrismShare). False on the phone itself. */
    val copiesFromPhone: Boolean get() = false

    /** The phone's code saved here, if any. */
    val phoneCode: String? get() = null

    /** Whether this device copies from the phone by itself (off: only when asked). */
    val phoneAuto: Boolean get() = false
    fun setPhoneAuto(on: Boolean) {}

    /** What the last copy from the phone did, in words. */
    val phoneStatus: String? get() = null

    /** Copies everything from the phone now; [code] when typed in. Returns what happened, in words. */
    suspend fun copyFromPhone(code: String?): String = ""

    /** Deletes the original of a picked or Prism-folder file from the device, to free up space; false if it can't. */
    suspend fun deleteOriginal(ref: String): Boolean = false

    /** An address the image loader can open for a stored file (profile photos). */
    fun fileUrl(path: String): String

    /** Plays a clip (stored, or on the phone a device address) silently, filling [modifier]'s bounds (cropped), and calls [onEnded] at its end. */
    @Composable
    fun ClipPlayer(path: String, modifier: Modifier, onEnded: () -> Unit)

    /** Decodes a picture, shrunk so neither side is over [maxSide]. */
    fun decodeImage(bytes: ByteArray, maxSide: Int): ImageBitmap?

    /** PNG when [keepTransparency], otherwise JPEG. */
    fun encodeImage(image: ImageBitmap, keepTransparency: Boolean): ByteArray?

    /** Decodes up to [maxSeconds] of any sound file the device can play. */
    suspend fun decodeAudio(name: String, bytes: ByteArray, maxSeconds: Int): PrismPcm?

    /** Plays a WAV file; [onDone] runs on the main thread when it ends or fails. */
    fun playWav(path: String, onDone: () -> Unit)

    /** Plays a WAV file at [volume] (0 to 1). */
    fun playWav(path: String, volume: Float, onDone: () -> Unit) = playWav(path, onDone)
    fun stopSound()

    /** Plays any sound or video's sound at [ref] (a stored file or a device address), for previews. */
    fun playFile(ref: String, onDone: () -> Unit) = playWav(ref, onDone)

    /** A picture of the picture or video at [ref] (a stored file or a device address), no side over [maxSide]. */
    suspend fun thumbnail(ref: String, maxSide: Int): ImageBitmap? = null

    /** Today's month (1-12) and day, on this device's clock. */
    fun today(): Pair<Int, Int>

    /** Loads a stored .ttf/.otf font, or null if the file isn't a font this device can draw. */
    fun loadFont(path: String): FontFamily? = null

    /** The device's own extra font families beyond sans, serif, mono and cursive, by name and label. */
    val systemFamilies: List<Pair<String, String>> get() = emptyList()

    /** One of [systemFamilies] by name, or null if this device hasn't got it. */
    fun systemFamily(name: String): FontFamily? = null

    /** Font files installed on this device (name, size and path), to copy into Prism's fonts. */
    suspend fun deviceFonts(): List<PrismFolderFile> = emptyList()
}

object Prism {
    var platform: PrismPlatform? = null
        private set

    fun install(platform: PrismPlatform) {
        this.platform = platform
        PrismStore.load()
    }
}
