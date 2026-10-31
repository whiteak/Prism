package com.nuvio.app.prism

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.CustomThemeColors
import com.nuvio.app.features.settings.ThemeSettingsRepository
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Prism: the saved looks and everything in them. Kept in the app's own storage on this device only
// (looks.json plus a media folder); a look can be exported as one .prism file to load on another device.

/** Part of a picture to show, as fractions of its width and height. */
@Serializable
data class PrismCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Show the whole picture, with a soft blurred copy of it filling the spare space. */
    val whole: Boolean = false,
)

/** A picture kept whole (shrunk on import), with the part to show on tall (phone) and wide (PC, TV) screens. */
@Serializable
data class PrismPicture(
    val file: String,
    val tall: PrismCrop? = null,
    val wide: PrismCrop? = null,
)

/** A sound already trimmed and levelled, stored as WAV. */
@Serializable
data class PrismSound(
    val file: String,
    val label: String,
    val enabled: Boolean = true,
    /** How loud, 0 to 1. */
    val volume: Float = 1f,
)

/** Poster size and style kept with a look (Nuvio's own poster settings are set from it). */
@Serializable
data class PrismPosterStyle(
    val widthDp: Int = 126,
    val cornerRadiusDp: Int = 12,
    val hideLabels: Boolean = false,
    val landscape: Boolean = false,
)

/**
 * How one kind of text looks (see [PrismTextArea]): its font (null = the look's heading or everyday font),
 * size (1 = normal), weight (100 to 900, null = as designed), italic (null = as designed), letter spacing (in ems),
 * lines through or under it, a shadow or glow in [effectColor], hollow outlined letters, and a highlight behind it.
 * Colours are ARGB.
 */
@Serializable
data class PrismTextStyle(
    val font: String? = null,
    val size: Float = 1f,
    val weight: Int? = null,
    val italic: Boolean? = null,
    val spacing: Float = 0f,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** "shadow" or "glow", or null for neither. */
    val effect: String? = null,
    val effectColor: Long = 0xFF000000,
    val outline: Boolean = false,
    val highlight: Long? = null,
    /** The text's own colour, over the app's; with [colour2] it fades from one to the other. */
    val colour: Long? = null,
    val colour2: Long? = null,
)

/** The kinds of text in the app that can each have their own font and font options. */
enum class PrismTextArea(val label: String, val description: String, val heading: Boolean) {
    Display("Big titles", "The largest text, like titles on the details page", true),
    Headline("Page headings", "Headings at the top of pages and big sections", true),
    Title("Row and card titles", "Home row names, show names and list titles", true),
    Body("Everyday text", "Descriptions, settings and most other text", false),
    Label("Buttons and labels", "Buttons, chips, tabs and small labels", false),
    Quote("Quotes", "Your quotes on the opening screen and the TV screensaver", false),
}

/** Days of the year a look switches on by itself, as month and day; may run over New Year. */
@Serializable
data class PrismSeason(
    val fromMonth: Int,
    val fromDay: Int,
    val toMonth: Int,
    val toDay: Int,
) {
    fun contains(month: Int, day: Int): Boolean {
        val today = month * 100 + day
        val from = fromMonth * 100 + fromDay
        val to = toMonth * 100 + toDay
        return if (from <= to) today in from..to else today >= from || today <= to
    }

    val label: String get() = "${fromDay}/${fromMonth} to ${toDay}/${toMonth}"
}

/** A movie or show, as picked from Cinemeta. */
@Serializable
data class PrismTitle(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val year: String? = null,
)

/** A short video for the Hero section at the top of Home, played silently. */
@Serializable
data class PrismClip(
    val file: String,
    val name: String,
)

@Serializable
data class PrismRow(
    val id: String,
    val title: String,
    val items: List<PrismTitle> = emptyList(),
)

enum class PrismMoment(val label: String, val description: String) {
    Opening("Opening", "When the app starts"),
    Profile("Profile picked", "When someone picks their profile"),
    PickEpisode("Play random episode", "Before a random episode starts, for show cards without their own sound"),
    NoResults("Nothing found", "When a search finds nothing"),
    StreamFailed("Can't play", "When a title has nothing to play"),
    PlayStarting("Play pressed", "When you pick something to watch"),
    EpisodeFinished("Finished watching", "When an episode or movie reaches its end"),
    AddedToLibrary("Added to Library", "When you add a title to your Library"),
    Removed("Removed", "When you remove a title from your Library or one of your Home rows"),
    Closing("Closing", "When the PC app is closed (the phone gives no warning before closing)"),
}

@Serializable
data class PrismLook(
    val id: String,
    val name: String,
    val theme: String? = null,
    val customColor: Int? = null,
    val amoled: Boolean? = null,
    val background: PrismPicture? = null,
    /** How much the background is darkened, so text stays readable (0 to 0.8). */
    val backgroundDim: Float = 0.35f,
    val opening: PrismPicture? = null,
    val openingSeconds: Int = 5,
    val openingZoom: Boolean = true,
    val logo: PrismPicture? = null,
    val quotes: List<String> = emptyList(),
    val soundsOn: Boolean = true,
    val sounds: Map<String, PrismSound> = emptyMap(),
    /** The one sound picked for a moment (by its key); moments not listed play one of theirs at random. */
    val soundChoice: Map<String, String> = emptyMap(),
    /** Kept only to read looks saved before several cards were allowed; see [cards]. */
    val showCard: PrismTitle? = null,
    val showCards: List<PrismTitle> = emptyList(),
    val rows: List<PrismRow> = emptyList(),
    val clipsOn: Boolean = true,
    /** Hero clips play in the list's order instead of shuffled. */
    val clipsInOrder: Boolean = false,
    val clips: List<PrismClip> = emptyList(),
    /** Font for headings and for everyday text: a built-in style (see [PrismFonts.builtIn]) or "file:<added font>"; null is Nuvio's own. */
    val titleFont: String? = null,
    val textFont: String? = null,
    /** How big all text is, 1 = normal. */
    val textSize: Float = 1f,
    /** More Home backgrounds and opening pictures; one of each (with the first) is picked per launch. */
    val backgrounds: List<PrismPicture> = emptyList(),
    val openings: List<PrismPicture> = emptyList(),
    val poster: PrismPosterStyle? = null,
    /** How rounded buttons, cards and panels are: 0 square, 1 normal, 2 extra round. */
    val roundness: Float = 1f,
    val season: PrismSeason? = null,
    /** More logos, like the extra backgrounds. */
    val logos: List<PrismPicture> = emptyList(),
    /** The one picture picked to show ("background", "opening" or "logo" to its file); not listed = random each launch. */
    val pictureChoice: Map<String, String> = emptyMap(),
    /** Font and font options for each kind of text, by [PrismTextArea] name; missing = the look's main fonts. */
    val textStyles: Map<String, PrismTextStyle> = emptyMap(),
) {
    /** The style for one kind of text (blank if it hasn't been changed). */
    fun textStyle(area: PrismTextArea): PrismTextStyle = textStyles[area.name] ?: PrismTextStyle()

    /** The font key one kind of text uses: its own, or else the look's heading or everyday font. */
    fun fontFor(area: PrismTextArea): String? = textStyle(area).font ?: if (area.heading) titleFont else textFont

    /** All the pictures kept for a slot ("background", "opening" or "logo"), the first one first. */
    fun pictureSet(slot: String): List<PrismPicture> = when (slot) {
        "background" -> listOfNotNull(background) + backgrounds
        "opening" -> listOfNotNull(opening) + openings
        "logo" -> listOfNotNull(logo) + logos
        else -> emptyList()
    }

    fun sound(moment: PrismMoment): PrismSound? = sounds[moment.name]

    /** The sound a show card plays before its random episode, if it has its own. */
    fun cardSound(title: PrismTitle): PrismSound? = sounds[cardSoundKey(title)]

    /** All the sounds kept under [base]: the first one, plus any added after it ("base#id"). */
    fun soundSet(base: String): List<Pair<String, PrismSound>> =
        sounds.filterKeys { it == base || it.startsWith("$base#") }.toList()

    /** The favourite show cards on Home, in order. */
    val cards: List<PrismTitle>
        get() = showCards.ifEmpty { listOfNotNull(showCard) }

    /** Media files this look uses. */
    val files: Set<String>
        get() = buildSet {
            (listOfNotNull(background, opening, logo) + backgrounds + openings + logos).forEach { add(it.file) }
            sounds.values.forEach { add(it.file) }
            clips.forEach { add(it.file) }
            (listOfNotNull(titleFont, textFont) + textStyles.values.mapNotNull { it.font }).filter { it.startsWith(PRISM_FONT_FILE) }.forEach { add(it.removePrefix(PRISM_FONT_FILE)) }
        }
}

@Serializable
private data class PrismState(
    val activeId: String,
    val looks: List<PrismLook>,
    val fonts: List<PrismFont> = emptyList(),
    /** The look each profile opens in, by profile key. */
    val profileLooks: Map<String, String> = emptyMap(),
)

/** Everything Prism keeps on this device, in one file (clips stay out; copy those with the Prism folder). */
@Serializable
private data class PrismBackup(
    val prismBackup: Int = 1,
    val state: PrismState,
    val avatars: Map<String, String> = emptyMap(),
    val avatarPhotos: Map<String, List<String>> = emptyMap(),
    val media: Map<String, String>,
)

/** A font added from a file; every look can use it. */
@Serializable
data class PrismFont(val name: String, val file: String) {
    val key: String get() = PRISM_FONT_FILE + file
}

/** One look in a file, with its pictures and sounds inside it. */
@Serializable
private data class PrismExport(
    val prism: Int = 1,
    val look: PrismLook,
    val media: Map<String, String>,
    val fonts: List<PrismFont> = emptyList(),
)

object PrismStore {
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }

    var looks by mutableStateOf(listOf(PrismLook(id = "first", name = "My look")))
        private set
    var activeId by mutableStateOf("first")
        private set

    val look: PrismLook get() = looks.firstOrNull { it.id == activeId } ?: looks.first()

    var profileLooks by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Fonts added from files, shared by all looks. */
    var fonts by mutableStateOf<List<PrismFont>>(emptyList())
        private set

    /** Profile photos on this device, by profile number (the same in every look). */
    var avatars by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Every photo kept for each profile, to switch between; [avatars] holds the one in use. */
    var avatarPhotos by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set

    private val platform get() = Prism.platform
    private fun path(name: String) = platform!!.dataDir + "/" + name
    fun mediaPath(file: String) = path("media/$file")
    val problemPath get() = path(PRISM_PROBLEM_FILE)
    fun cachePath(file: String) = path("cache/$file")

    internal fun load() {
        platform?.readBytes(path("avatars.json"))?.let { bytes ->
            runCatching { json.decodeFromString<Map<String, String>>(bytes.decodeToString()) }.onSuccess { avatars = it }
        }
        platform?.readBytes(path("avatar-photos.json"))?.let { bytes ->
            runCatching { json.decodeFromString<Map<String, List<String>>>(bytes.decodeToString()) }.onSuccess { avatarPhotos = it }
        }
        val bytes = platform?.readBytes(path("looks.json")) ?: return
        runCatching { json.decodeFromString<PrismState>(bytes.decodeToString()) }
            .onSuccess { state ->
                fonts = state.fonts
                profileLooks = state.profileLooks
                if (state.looks.isNotEmpty()) {
                    looks = state.looks
                    activeId = state.activeId.takeIf { id -> state.looks.any { it.id == id } } ?: state.looks.first().id
                }
            }
    }

    private fun save() {
        val p = platform ?: return
        runCatching { p.writeBytes(path("looks.json"), json.encodeToString(PrismState(activeId, looks, fonts, profileLooks)).encodeToByteArray()) }
        cleanMedia()
    }

    /** Changes the look being shown. */
    fun update(change: (PrismLook) -> PrismLook) {
        val current = look
        val next = change(current)
        looks = looks.map { if (it.id == current.id) next else it }
        save()
    }

    fun switchTo(id: String) {
        if (looks.none { it.id == id }) return
        activeId = id
        save()
        applyColours()
        PrismImages.clear()
    }

    fun saveAsNew(name: String) {
        val copy = look.copy(id = newId(), name = name.ifBlank { "Look ${looks.size + 1}" })
        looks = looks + copy
        activeId = copy.id
        save()
    }

    fun newBlank(name: String) {
        val blank = PrismLook(id = newId(), name = name.ifBlank { "Look ${looks.size + 1}" })
        looks = looks + blank
        activeId = blank.id
        save()
        PrismImages.clear()
    }

    fun rename(id: String, name: String) {
        looks = looks.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it }
        save()
    }

    fun deleteLook(id: String) {
        if (looks.size <= 1) return
        looks = looks.filterNot { it.id == id }
        if (activeId == id) {
            activeId = looks.first().id
            applyColours()
            PrismImages.clear()
        }
        save()
    }

    fun setAvatar(profileKey: String, file: String?) {
        avatars = if (file == null) avatars - profileKey else avatars + (profileKey to file)
        platform?.let { p -> runCatching { p.writeBytes(path("avatars.json"), json.encodeToString(avatars).encodeToByteArray()) } }
        save()
    }

    /** The profile's kept photos, the one in use included (photos chosen before several were kept count too). */
    fun photosFor(profileKey: String): List<String> =
        (avatarPhotos[profileKey].orEmpty() + listOfNotNull(avatars[profileKey])).distinct()

    private fun saveAvatarPhotos(photos: Map<String, List<String>>) {
        avatarPhotos = photos
        platform?.let { p -> runCatching { p.writeBytes(path("avatar-photos.json"), json.encodeToString(photos).encodeToByteArray()) } }
    }

    /** Keeps a new photo for the profile and uses it. */
    fun addAvatarPhoto(profileKey: String, file: String) {
        saveAvatarPhotos(avatarPhotos + (profileKey to photosFor(profileKey) + file))
        setAvatar(profileKey, file)
    }

    /** Forgets one of the profile's photos; if it was in use, the next kept one takes over. */
    fun removeAvatarPhoto(profileKey: String, file: String) {
        val rest = photosFor(profileKey) - file
        saveAvatarPhotos(avatarPhotos + (profileKey to rest))
        if (avatars[profileKey] == file || avatars[profileKey] == null) setAvatar(profileKey, rest.lastOrNull()) else save()
    }

    /** Copies big files (clips) straight into storage; returns the stored names, in order, for those that copied. */
    suspend fun addMediaFiles(files: List<PrismFolderFile>): List<Pair<PrismFolderFile, String>> {
        val p = platform ?: return emptyList()
        return files.mapNotNull { f ->
            val name = newId() + "." + f.name.substringAfterLast('.', "mp4").lowercase()
            if (p.copyRef(f, mediaPath(name))) f to name else null
        }
    }

    /** Stores a new media file and returns its name. */
    fun addMedia(bytes: ByteArray, extension: String): String {
        val name = newId() + "." + extension
        platform?.writeBytes(mediaPath(name), bytes)
        return name
    }

    /** Removes media files no look uses any more. */
    private fun cleanMedia() {
        val p = platform ?: return
        val used = looks.flatMap { it.files }.toSet() + avatars.values + avatarPhotos.values.flatten() + fonts.map { it.file }
        val index = p.readBytes(path("media.txt"))?.decodeToString()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val all = (index + used).toSet()
        all.filterNot { it in used }.forEach { p.delete(mediaPath(it)) }
        p.writeBytes(path("media.txt"), used.joinToString("\n").encodeToByteArray())
    }

    /** Keeps a font file (already checked) under [name]; returns it. */
    fun addFont(name: String, bytes: ByteArray, extension: String): PrismFont {
        val font = PrismFont(name.ifBlank { "Font ${fonts.size + 1}" }, addMedia(bytes, extension))
        noteMedia(font.file)
        fonts = fonts + font
        save()
        return font
    }

    /** Forgets an added font; looks that used it go back to Nuvio's own. */
    fun removeFont(font: PrismFont) {
        fonts = fonts - font
        looks = looks.map { l ->
            l.copy(
                titleFont = l.titleFont.takeUnless { it == font.key },
                textFont = l.textFont.takeUnless { it == font.key },
                textStyles = l.textStyles.mapValues { (_, st) -> if (st.font == font.key) st.copy(font = null) else st },
            )
        }
        PrismFonts.forget(font.file)
        save()
    }

    internal fun noteMedia(file: String) {
        val p = platform ?: return
        val index = p.readBytes(path("media.txt"))?.decodeToString()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        p.writeBytes(path("media.txt"), (index + file).distinct().joinToString("\n").encodeToByteArray())
    }

    // ---- Colours: the look keeps the theme, so switching looks switches colours too. ----

    fun setTheme(theme: AppTheme) {
        ThemeSettingsRepository.setTheme(theme)
        update { it.copy(theme = theme.name) }
    }

    fun setCustomColor(color: Int) {
        ThemeSettingsRepository.setCustomTheme(CustomThemeColors.solid(color))
        update { it.copy(theme = AppTheme.CUSTOM.name, customColor = color) }
    }

    fun setAmoled(on: Boolean) {
        ThemeSettingsRepository.setAmoled(on)
        update { it.copy(amoled = on) }
    }

    fun applyColours() {
        val l = look
        l.poster?.let { applyPoster(it) }
        l.customColor?.let { color ->
            if (l.theme == AppTheme.CUSTOM.name) ThemeSettingsRepository.setCustomTheme(CustomThemeColors.solid(color))
        }
        l.theme?.let { name -> runCatching { AppTheme.valueOf(name) }.getOrNull() }
            ?.takeIf { it != AppTheme.CUSTOM }
            ?.let { ThemeSettingsRepository.setTheme(it) }
        l.amoled?.let { ThemeSettingsRepository.setAmoled(it) }
    }

    // ---- Posters: kept with the look, set in Nuvio's own poster settings ----

    fun setPoster(style: PrismPosterStyle?) {
        update { it.copy(poster = style) }
        style?.let { applyPoster(it) }
    }

    private fun applyPoster(style: PrismPosterStyle) {
        val repo = com.nuvio.app.core.ui.PosterCardStyleRepository
        runCatching {
            repo.setWidthDp(style.widthDp)
            repo.setCornerRadiusDp(style.cornerRadiusDp)
            repo.setHideLabelsEnabled(style.hideLabels)
            repo.setCatalogLandscapeModeEnabled(style.landscape)
        }
    }

    fun currentPoster(): PrismPosterStyle {
        val s = com.nuvio.app.core.ui.PosterCardStyleRepository.uiState.value
        return PrismPosterStyle(s.widthDp, s.cornerRadiusDp, s.hideLabelsEnabled, s.catalogLandscapeModeEnabled)
    }

    // ---- Which look opens: by date (seasonal), then by profile ----

    /** Turns this look on for [profileKey] (or off, if it already is). */
    fun toggleProfileLook(profileKey: String, lookId: String) {
        profileLooks = if (profileLooks[profileKey] == lookId) profileLooks - profileKey else profileLooks + (profileKey to lookId)
        save()
    }

    /** The look that should be showing for this profile today, if one is set. */
    fun autoLookFor(profileKey: String?, month: Int, day: Int): String? =
        looks.firstOrNull { it.season?.contains(month, day) == true }?.id
            ?: profileKey?.let { profileLooks[it] }?.takeIf { id -> looks.any { it.id == id } }

    /** Switches to the seasonal or profile look, if there is one and it isn't showing already. */
    fun autoSwitch(profileKey: String?, month: Int, day: Int) {
        val id = autoLookFor(profileKey, month, day) ?: return
        // Also when it's showing already: a profile's own settings may have just replaced its colours.
        if (id != activeId) switchTo(id) else applyColours()
    }

    // ---- One picture of a set per launch ----

    private val launchPick = mutableMapOf<String, PrismPicture?>()

    /** The picture for a slot this launch: the one picked in Customise, or one at random, chosen once per launch. */
    private fun launchPicture(l: PrismLook, slot: String): PrismPicture? {
        val set = l.pictureSet(slot)
        l.pictureChoice[slot]?.let { file -> set.firstOrNull { it.file == file } }?.let { return it }
        return launchPick.getOrPut(slot + ":" + l.id) { set.randomOrNull() }.takeIf { it in set } ?: set.firstOrNull()
    }

    fun launchBackground(l: PrismLook = look): PrismPicture? = launchPicture(l, "background")
    fun launchOpening(l: PrismLook = look): PrismPicture? = launchPicture(l, "opening")
    fun launchLogo(l: PrismLook = look): PrismPicture? = launchPicture(l, "logo")

    // ---- Full backup ----

    @OptIn(ExperimentalEncodingApi::class)
    fun backup(): ByteArray? {
        val p = platform ?: return null
        val clipFiles = looks.flatMap { l -> l.clips.map { it.file } }.toSet()
        val files = looks.flatMap { it.files }.toSet() + avatars.values + avatarPhotos.values.flatten() + fonts.map { it.file } - clipFiles
        val media = files.mapNotNull { file -> p.readBytes(mediaPath(file))?.let { file to Base64.encode(it) } }.toMap()
        val state = PrismState(activeId, looks.map { it.copy(clips = emptyList()) }, fonts, profileLooks)
        return json.encodeToString(PrismBackup(state = state, avatars = avatars, avatarPhotos = avatarPhotos, media = media)).encodeToByteArray()
    }

    /** Replaces everything with a backup; false if the file isn't a Prism backup. */
    @OptIn(ExperimentalEncodingApi::class)
    fun restore(bytes: ByteArray): Boolean {
        val p = platform ?: return false
        val b = runCatching { json.decodeFromString<PrismBackup>(bytes.decodeToString()) }.getOrNull() ?: return false
        if (b.state.looks.isEmpty()) return false
        b.media.forEach { (file, data) ->
            if (file.contains('/') || file.contains("..")) return@forEach
            runCatching { Base64.decode(data) }.getOrNull()?.let { p.writeBytes(mediaPath(file), it); noteMedia(file) }
        }
        // Clips aren't in backups: a look that's still here keeps the clips it has on this device.
        val clipsHere = looks.associate { it.id to it.clips }
        looks = b.state.looks.map { l -> l.copy(clips = clipsHere[l.id].orEmpty()) }
        activeId = b.state.activeId.takeIf { id -> looks.any { it.id == id } } ?: looks.first().id
        fonts = b.state.fonts
        profileLooks = b.state.profileLooks
        avatars = b.avatars
        runCatching { p.writeBytes(path("avatars.json"), json.encodeToString(avatars).encodeToByteArray()) }
        saveAvatarPhotos(b.avatarPhotos)
        save()
        applyColours()
        PrismImages.clear()
        return true
    }

    // ---- Export and import ----

    @OptIn(ExperimentalEncodingApi::class)
    fun exportLook(id: String = activeId): Pair<String, ByteArray>? {
        val p = platform ?: return null
        val l = looks.firstOrNull { it.id == id } ?: return null
        // Clips stay out of the file (they can add up to gigabytes); copy them over with the Prism folder.
        val clipFiles = l.clips.map { it.file }.toSet()
        val media = (l.files - clipFiles).mapNotNull { file -> p.readBytes(mediaPath(file))?.let { file to Base64.encode(it) } }.toMap()
        val usedFonts = fonts.filter { f -> PrismTextArea.entries.any { l.fontFor(it) == f.key } || f.key == l.titleFont || f.key == l.textFont }
        val bytes = json.encodeToString(PrismExport(look = l.copy(clips = emptyList()), media = media, fonts = usedFonts)).encodeToByteArray()
        val safe = l.name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifBlank { "Look" }
        return "$safe.prism" to bytes
    }

    /** Adds the look in a .prism file and switches to it; returns its name, or null if the file isn't a look. */
    @OptIn(ExperimentalEncodingApi::class)
    fun importLook(bytes: ByteArray): String? {
        val p = platform ?: return null
        val export = runCatching { json.decodeFromString<PrismExport>(bytes.decodeToString()) }.getOrNull() ?: return null
        val renamed = mutableMapOf<String, String>()
        export.media.forEach { (file, data) ->
            val decoded = runCatching { Base64.decode(data) }.getOrNull() ?: return@forEach
            val fresh = newId() + "." + file.substringAfterLast('.', "bin")
            p.writeBytes(mediaPath(fresh), decoded)
            noteMedia(fresh)
            renamed[file] = fresh
        }
        fun PrismPicture.moved() = renamed[file]?.let { copy(file = it) }
        fun String.movedFont(): String? =
            if (startsWith(PRISM_FONT_FILE)) renamed[removePrefix(PRISM_FONT_FILE)]?.let { PRISM_FONT_FILE + it } else this
        // Fonts that came with the look join this device's added fonts.
        val newFonts = export.fonts.mapNotNull { f -> renamed[f.file]?.let { PrismFont(f.name, it) } }
        if (newFonts.isNotEmpty()) fonts = fonts + newFonts
        val l = export.look
        val look = l.copy(
            id = newId(),
            name = l.name.let { name -> if (looks.any { it.name == name }) "$name (2)" else name },
            background = l.background?.moved(),
            opening = l.opening?.moved(),
            logo = l.logo?.moved(),
            backgrounds = l.backgrounds.mapNotNull { it.moved() },
            openings = l.openings.mapNotNull { it.moved() },
            logos = l.logos.mapNotNull { it.moved() },
            pictureChoice = l.pictureChoice.mapNotNull { (slot, file) -> renamed[file]?.let { slot to it } }.toMap(),
            sounds = l.sounds.mapNotNull { (k, s) -> renamed[s.file]?.let { k to s.copy(file = it) } }.toMap(),
            titleFont = l.titleFont?.movedFont(),
            textFont = l.textFont?.movedFont(),
            textStyles = l.textStyles.mapValues { (_, st) -> st.copy(font = st.font?.movedFont()) },
        )
        looks = looks + look
        activeId = look.id
        save()
        applyColours()
        PrismImages.clear()
        return look.name
    }

    fun newId(): String = buildString {
        repeat(12) { append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]) }
    }
}

/** Marks a look's font as one added from a file. */
const val PRISM_FONT_FILE = "file:"

/** A look's font key for one of the device's own font families (Android's "sans-serif-condensed"...). */
const val PRISM_SYSTEM_FONT = "sys:"

/** Where a show card's own sound is kept in [PrismLook.sounds]. */
fun cardSoundKey(title: PrismTitle): String = "card:${title.type}:${title.id}"
