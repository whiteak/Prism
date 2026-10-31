package com.nuvio.tv.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

// Prism on the TV: the look is made on the phone (Customise > Looks > Export), sent over (for example
// with Send Anywhere) and loaded here from Settings > Appearance > Prism. The TV keeps one look: its
// opening picture, logo, quotes, Home background, sounds, colours and show cards.

class PrismTvCrop(val left: Float, val top: Float, val right: Float, val bottom: Float, val whole: Boolean)

class PrismTvPicture(val file: String, val wide: PrismTvCrop?)

/** [startMs] and [endMs] trim a sound chosen on the TV (the phone trims its sounds into the file itself). */
class PrismTvSound(val file: String, val enabled: Boolean, val volume: Float = 1f, val startMs: Int = 0, val endMs: Int = 0)

class PrismTvTitle(val id: String, val type: String, val name: String, val poster: String? = null)

/** Poster size, corners, names under posters and wide posters, set on the TV (kept apart from the phone's sizes). */
class PrismTvPoster(val widthDp: Int, val cornerRadiusDp: Int, val labels: Boolean, val landscape: Boolean)

/** One kind of text's font and font options, made on the phone (Customise > Text). */
class PrismTvTextStyle(
    val font: String?,
    val size: Float,
    val weight: Int?,
    val italic: Boolean?,
    val spacing: Float,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** "shadow" or "glow", or null. */
    val effect: String? = null,
    val effectColor: Int = 0xFF000000.toInt(),
    val outline: Boolean = false,
    val highlight: Int? = null,
    /** Its own colour, fading into [colour2] if there is one. */
    val colour: Int? = null,
    val colour2: Int? = null,
)

class PrismTvLook(
    val name: String,
    val theme: String?,
    val customColor: Int?,
    val amoled: Boolean?,
    val background: PrismTvPicture?,
    val backgroundDim: Float,
    val opening: PrismTvPicture?,
    val openingSeconds: Int,
    val openingZoom: Boolean,
    val logo: PrismTvPicture?,
    val quotes: List<String>,
    val soundsOn: Boolean,
    val sounds: Map<String, PrismTvSound>,
    val cards: List<PrismTvTitle>,
    val titleFont: String? = null,
    val textFont: String? = null,
    val textSize: Float = 1f,
    /** Every picture in the look (backgrounds and openings), for the screensaver. */
    val pictures: List<PrismTvPicture> = emptyList(),
    /** The one sound picked for a moment; moments not listed play one of theirs at random. */
    val soundChoice: Map<String, String> = emptyMap(),
    /** By area: Display, Headline, Title, Body, Label, Quote. */
    val textStyles: Map<String, PrismTvTextStyle> = emptyMap(),
    val tvPoster: PrismTvPoster? = null,
    val rows: List<PrismTvRow> = emptyList(),
    /** Hero clips: short videos playing silently behind Home (media file names). */
    val clips: List<String> = emptyList(),
    val clipsOn: Boolean = true,
    val clipsInOrder: Boolean = false,
) {
    /** The font one kind of text uses: its own, or else the heading or everyday font. */
    fun fontFor(area: String): String? =
        textStyles[area]?.font ?: if (area == "Display" || area == "Headline" || area == "Title") titleFont else textFont

    /** The sounds kept under [base]: the first one plus any added after it ("base#id"), switched on. */
    fun soundSet(base: String): List<Pair<String, PrismTvSound>> =
        sounds.filter { (key, sound) -> (key == base || key.startsWith("$base#")) && sound.enabled }.toList()

    companion object {
        fun parse(o: JSONObject): PrismTvLook {
            fun JSONObject.str(key: String): String? = optString(key, "").takeIf { has(key) && !isNull(key) && it.isNotBlank() }
            fun crop(c: JSONObject?): PrismTvCrop? = c?.let {
                PrismTvCrop(
                    it.optDouble("left", 0.0).toFloat(),
                    it.optDouble("top", 0.0).toFloat(),
                    it.optDouble("right", 1.0).toFloat(),
                    it.optDouble("bottom", 1.0).toFloat(),
                    it.optBoolean("whole", false),
                )
            }
            fun picture(p: JSONObject?): PrismTvPicture? = p?.str("file")?.let { PrismTvPicture(it, crop(p.optJSONObject("wide"))) }
            fun picture(key: String): PrismTvPicture? = picture(o.optJSONObject(key))
            fun pictures(key: String): List<PrismTvPicture> = buildList {
                o.optJSONArray(key)?.let { a -> for (i in 0 until a.length()) picture(a.optJSONObject(i))?.let(::add) }
            }
            // A look can have several backgrounds, opening pictures and logos: the one picked on the phone
            // shows, or (on Random) one of each is picked per launch.
            val choice = o.optJSONObject("pictureChoice")
            fun launch(slot: String, set: List<PrismTvPicture>): PrismTvPicture? =
                choice?.str(slot)?.let { file -> set.firstOrNull { it.file == file } } ?: set.randomOrNull()
            val backgrounds = listOfNotNull(picture("background")) + pictures("backgrounds")
            val openings = listOfNotNull(picture("opening")) + pictures("openings")
            val logos = listOfNotNull(picture("logo")) + pictures("logos")
            val sounds = mutableMapOf<String, PrismTvSound>()
            o.optJSONObject("sounds")?.let { s ->
                s.keys().forEach { key ->
                    val v = s.optJSONObject(key) ?: return@forEach
                    v.str("file")?.let { sounds[key] = PrismTvSound(it, v.optBoolean("enabled", true), v.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f), v.optInt("startMs", 0), v.optInt("endMs", 0)) }
                }
            }
            fun title(t: JSONObject?): PrismTvTitle? = t?.let {
                val id = it.str("id") ?: return@let null
                PrismTvTitle(id, it.str("type") ?: "series", it.str("name") ?: "", it.str("poster"))
            }
            val cards = buildList {
                o.optJSONArray("showCards")?.let { a -> for (i in 0 until a.length()) title(a.optJSONObject(i))?.let(::add) }
                if (isEmpty()) title(o.optJSONObject("showCard"))?.let(::add)
            }
            val quotes = buildList {
                o.optJSONArray("quotes")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
            }
            return PrismTvLook(
                name = o.str("name") ?: "Look",
                theme = o.str("theme"),
                customColor = if (o.has("customColor") && !o.isNull("customColor")) o.optInt("customColor") else null,
                amoled = if (o.has("amoled") && !o.isNull("amoled")) o.optBoolean("amoled") else null,
                background = launch("background", backgrounds),
                backgroundDim = o.optDouble("backgroundDim", 0.35).toFloat(),
                opening = launch("opening", openings),
                openingSeconds = o.optInt("openingSeconds", 5),
                openingZoom = o.optBoolean("openingZoom", true),
                logo = launch("logo", logos),
                quotes = quotes,
                soundsOn = o.optBoolean("soundsOn", true),
                sounds = sounds,
                cards = cards,
                titleFont = o.str("titleFont"),
                textFont = o.str("textFont"),
                textSize = o.optDouble("textSize", 1.0).toFloat(),
                pictures = (backgrounds + openings).distinctBy { it.file },
                textStyles = buildMap {
                    o.optJSONObject("textStyles")?.let { t ->
                        t.keys().forEach { area ->
                            val st = t.optJSONObject(area) ?: return@forEach
                            put(area, PrismTvTextStyle(
                                font = st.str("font"),
                                size = st.optDouble("size", 1.0).toFloat(),
                                weight = if (st.has("weight") && !st.isNull("weight")) st.optInt("weight") else null,
                                italic = if (st.has("italic") && !st.isNull("italic")) st.optBoolean("italic") else null,
                                spacing = st.optDouble("spacing", 0.0).toFloat(),
                                underline = st.optBoolean("underline", false),
                                strike = st.optBoolean("strike", false),
                                effect = st.str("effect"),
                                effectColor = st.optLong("effectColor", 0xFF000000).toInt(),
                                outline = st.optBoolean("outline", false),
                                highlight = if (st.has("highlight") && !st.isNull("highlight")) st.optLong("highlight").toInt() else null,
                                colour = if (st.has("colour") && !st.isNull("colour")) st.optLong("colour").toInt() else null,
                                colour2 = if (st.has("colour2") && !st.isNull("colour2")) st.optLong("colour2").toInt() else null,
                            ))
                        }
                    }
                },
                rows = PrismTvRows.parse(o),
                clips = buildList { o.optJSONArray("clips")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.str("file")?.let(::add) } },
                clipsOn = o.optBoolean("clipsOn", true),
                clipsInOrder = o.optBoolean("clipsInOrder", false),
                tvPoster = o.optJSONObject("tvPoster")?.let { p ->
                    PrismTvPoster(p.optInt("widthDp", 126), p.optInt("cornerRadiusDp", 12), p.optBoolean("labels", true), p.optBoolean("landscape", false))
                },
                soundChoice = buildMap {
                    o.optJSONObject("soundChoice")?.let { c -> c.keys().forEach { key -> c.str(key)?.let { put(key, it) } } }
                },
            )
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface PrismTvEntryPoint {
    fun themeDataStore(): com.nuvio.tv.data.local.ThemeDataStore
    fun libraryPreferences(): com.nuvio.tv.data.local.LibraryPreferences
    fun profileManager(): com.nuvio.tv.core.profile.ProfileManager
    fun layoutPreferences(): com.nuvio.tv.data.local.LayoutPreferenceDataStore
}

object PrismTv {
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var look by mutableStateOf<PrismTvLook?>(null)
        private set

    /** One quote per launch. */
    val quote: String? by lazy { look?.quotes?.randomOrNull() }

    private fun dir(context: Context) = File(context.filesDir, "prism").apply { mkdirs() }
    fun mediaFile(name: String): File? = appContext?.let { File(dir(it), "media/$name") }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        PrismTvSync.startAuto(context)
        look = activeJson(context)?.let { runCatching { PrismTvLook.parse(it) }.getOrNull() }
        PrismTvAvatars.url(null)
        // The seasonal or profile look, at start and whenever the profile changes.
        scope.launch {
            runCatching {
                dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, PrismTvEntryPoint::class.java)
                    .profileManager().activeProfileId.collect { id -> runCatching { autoSwitch(context.applicationContext, id) } }
            }
        }
    }

    fun soundsEnabled(context: Context): Boolean =
        context.getSharedPreferences("prism", Context.MODE_PRIVATE).getBoolean("sounds", true)

    fun setSoundsEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("prism", Context.MODE_PRIVATE).edit().putBoolean("sounds", value).apply()
        if (!value) PrismTvSounds.stop()
    }

    // ---- The TV's looks: looks.json holds them all ({"activeId", "looks": [...]}, each like the phone's), and
    // their pictures, sounds and fonts share the media folder. An empty activeId is Prism's built-in look. ----

    /** Goes up by one whenever the looks change, so screens showing them redraw. */
    var changes by mutableStateOf(0)
        private set

    private fun stateFile(context: Context) = File(dir(context), "looks.json")

    private fun readState(context: Context): JSONObject {
        runCatching { JSONObject(stateFile(context).readText()) }.getOrNull()?.let { return it }
        // Before several looks: the one look.json becomes the first look.
        val old = runCatching { JSONObject(File(dir(context), "look.json").readText()) }.getOrNull()
        val state = JSONObject().put("activeId", "").put("looks", org.json.JSONArray())
        if (old != null) {
            if (!old.has("id")) old.put("id", newId())
            state.put("activeId", old.optString("id")).put("looks", org.json.JSONArray().put(old))
        }
        return state
    }

    private fun writeState(context: Context, state: JSONObject) {
        stateFile(context).writeText(state.toString())
        File(dir(context), "look.json").delete()
        cleanMedia(context, state)
        val active = activeOf(state)
        PrismTvImages.clear()
        PrismTvFonts.clear()
        look = active?.let { runCatching { PrismTvLook.parse(it) }.getOrNull() }
        changes++
    }

    private fun activeOf(state: JSONObject): JSONObject? {
        val id = state.optString("activeId", "")
        if (id.isEmpty()) return null
        val all = state.optJSONArray("looks") ?: return null
        for (i in 0 until all.length()) all.optJSONObject(i)?.takeIf { it.optString("id") == id }?.let { return it }
        return null
    }

    private fun activeJson(context: Context): JSONObject? = activeOf(readState(context))

    /** Every look on this TV, as (id, name). */
    fun looks(context: Context): List<Pair<String, String>> {
        val all = readState(context).optJSONArray("looks") ?: return emptyList()
        return (0 until all.length()).mapNotNull { i -> all.optJSONObject(i)?.let { it.optString("id") to it.optString("name", "Look") } }
    }

    fun activeId(context: Context): String = readState(context).optString("activeId", "")

    /** The look showing, for changing on the TV itself; null with the built-in look. */
    fun lookJson(context: Context): JSONObject? = activeJson(context)

    /** Saves the look showing after a change (its pictures, sounds and fonts already in [mediaDir]); with the
     *  built-in look showing, it becomes a new look of this TV's own. */
    fun saveLook(context: Context, o: JSONObject) {
        init(context)
        PrismTvLook.parse(o)
        val state = readState(context)
        val all = state.optJSONArray("looks") ?: org.json.JSONArray().also { state.put("looks", it) }
        if (!o.has("id") || o.optString("id").isEmpty()) o.put("id", newId())
        val id = o.optString("id")
        val index = (0 until all.length()).firstOrNull { all.optJSONObject(it)?.optString("id") == id }
        if (index != null) all.put(index, o) else all.put(o)
        state.put("activeId", id)
        writeState(context, state)
    }

    fun switchTo(context: Context, id: String) {
        val state = readState(context)
        state.put("activeId", id)
        writeState(context, state)
        look?.let { applyColours(context, it) }
    }

    /** A new look, blank or a copy of the one showing, and shows it. */
    fun newLook(context: Context, name: String, copy: Boolean) {
        val base = if (copy) activeJson(context)?.let { JSONObject(it.toString()) } else null
        val o = (base ?: JSONObject()).put("id", newId()).put("name", name)
        o.remove("fromPhone")
        saveLook(context, o)
    }

    // ---- Which look opens: by date (seasonal), then by profile, as on the phone ----

    /** The look ids each profile opens in ("p<number>" to look id). */
    fun profileLooks(context: Context): Map<String, String> {
        val o = readState(context).optJSONObject("profileLooks") ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotBlank() }
    }

    /** Turns this look on for a profile (or off, if it already is). */
    fun toggleProfileLook(context: Context, profileId: Int, lookId: String) {
        val state = readState(context)
        val o = state.optJSONObject("profileLooks") ?: JSONObject().also { state.put("profileLooks", it) }
        val key = "p$profileId"
        if (o.optString(key) == lookId) o.remove(key) else o.put(key, lookId)
        writeState(context, state)
    }

    /** A look's dates as (from month, from day, to month, to day), or null. */
    fun season(context: Context, lookId: String): IntArray? {
        val all = readState(context).optJSONArray("looks") ?: return null
        for (i in 0 until all.length()) all.optJSONObject(i)?.takeIf { it.optString("id") == lookId }?.optJSONObject("season")?.let { s ->
            return intArrayOf(s.optInt("fromMonth"), s.optInt("fromDay"), s.optInt("toMonth"), s.optInt("toDay"))
        }
        return null
    }

    fun setSeason(context: Context, lookId: String, dates: IntArray?) {
        val state = readState(context)
        val all = state.optJSONArray("looks") ?: return
        for (i in 0 until all.length()) all.optJSONObject(i)?.takeIf { it.optString("id") == lookId }?.let { l ->
            if (dates == null) l.remove("season")
            else l.put("season", JSONObject().put("fromMonth", dates[0]).put("fromDay", dates[1]).put("toMonth", dates[2]).put("toDay", dates[3]))
        }
        writeState(context, state)
    }

    private fun inSeason(s: JSONObject, month: Int, day: Int): Boolean {
        val today = month * 100 + day
        val from = s.optInt("fromMonth") * 100 + s.optInt("fromDay")
        val to = s.optInt("toMonth") * 100 + s.optInt("toDay")
        return if (from <= to) today in from..to else today >= from || today <= to
    }

    /** Switches to today's seasonal look or the profile's look, if one is set and it isn't showing. */
    fun autoSwitch(context: Context, profileId: Int?) {
        val state = readState(context)
        val all = state.optJSONArray("looks") ?: return
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val ids = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
        val id = ids.firstOrNull { l -> l.optJSONObject("season")?.let { inSeason(it, month, day) } == true }?.optString("id")
            ?: profileId?.let { state.optJSONObject("profileLooks")?.optString("p$it") }?.takeIf { p -> ids.any { it.optString("id") == p } }
            ?: return
        if (id != state.optString("activeId", "")) switchTo(context, id)
    }

    fun renameLook(context: Context, id: String, name: String) {
        val state = readState(context)
        val all = state.optJSONArray("looks") ?: return
        for (i in 0 until all.length()) all.optJSONObject(i)?.takeIf { it.optString("id") == id }?.put("name", name)
        writeState(context, state)
    }

    fun deleteLook(context: Context, id: String) {
        val state = readState(context)
        val all = state.optJSONArray("looks") ?: return
        val kept = org.json.JSONArray()
        for (i in 0 until all.length()) all.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { kept.put(it) }
        state.put("looks", kept)
        if (state.optString("activeId") == id) state.put("activeId", "")
        writeState(context, state)
    }

    /** Loads a look sent from the phone (a .prism file, or copied over Wi-Fi) as the TV's "from the phone" look,
     *  replacing the last one, and shows it. Returns its name, or null if it isn't a look. */
    fun importLook(context: Context, bytes: ByteArray): String? {
        init(context)
        val root = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull() ?: return null
        val lookJson = root.optJSONObject("look") ?: return null
        val parsed = runCatching { PrismTvLook.parse(lookJson) }.getOrNull() ?: return null
        val media = mediaDir(context)
        root.optJSONObject("media")?.let { m ->
            m.keys().forEach { name ->
                if (name.contains('/') || name.contains("..")) return@forEach
                val data = runCatching { Base64.decode(m.optString(name), Base64.DEFAULT) }.getOrNull() ?: return@forEach
                File(media, name).writeBytes(data)
            }
        }
        val state = readState(context)
        val all = state.optJSONArray("looks") ?: org.json.JSONArray().also { state.put("looks", it) }
        val old = (0 until all.length()).firstOrNull { all.optJSONObject(it)?.optBoolean("fromPhone") == true }
        lookJson.put("id", old?.let { all.optJSONObject(it).optString("id") } ?: newId()).put("fromPhone", true)
        if (old != null) all.put(old, lookJson) else all.put(lookJson)
        state.put("activeId", lookJson.optString("id"))
        writeState(context, state)
        applyColours(context, parsed)
        return parsed.name
    }

    fun mediaDir(context: Context) = File(dir(context), "media").apply { mkdirs() }

    /** Prism's folder on this TV, once the app has started. */
    fun folder(): File? = appContext?.let { dir(it) }

    /** Goes back to Prism's built-in opening and no sounds (the TV's looks are kept, to switch back to). */
    fun clear(context: Context) {
        val state = readState(context)
        state.put("activeId", "")
        writeState(context, state)
    }

    /** Removes media no look uses. Names are random, so a name found anywhere in the looks counts as used. */
    private fun cleanMedia(context: Context, state: JSONObject) {
        val text = state.toString()
        mediaDir(context).listFiles()?.forEach { f ->
            if (!text.contains("\"${f.name}\"") && !text.contains(":${f.name}\"")) f.delete()
        }
    }

    /** Every look on this TV with the active one, for a backup. */
    internal fun stateForBackup(context: Context): JSONObject = readState(context)

    /** Replaces every look with a backup's (their media already in [mediaDir]). */
    internal fun restoreState(context: Context, state: JSONObject) {
        writeState(context, state)
        applyColoursNow(context)
    }

    fun newId(): String = buildString { repeat(12) { append("abcdefghijklmnopqrstuvwxyz0123456789"[kotlin.random.Random.nextInt(36)]) } }

    /** Sets the TV's colours from a look (its theme, own colour and pure black). */
    fun applyColoursNow(context: Context) {
        look?.let { applyColours(context, it) }
    }

    private fun applyColours(context: Context, l: PrismTvLook) {
        val store = dagger.hilt.android.EntryPointAccessors
            .fromApplication(context.applicationContext, PrismTvEntryPoint::class.java)
            .themeDataStore()
        scope.launch {
            runCatching {
                val theme = l.theme?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
                if (theme == AppTheme.CUSTOM && l.customColor != null) {
                    store.setCustomTheme(CustomThemeColors.solid(l.customColor and 0xFFFFFF))
                } else if (theme != null && theme != AppTheme.CUSTOM) {
                    store.setTheme(theme)
                }
                l.amoled?.let { store.setAmoledMode(it) }
            }
        }
        l.tvPoster?.let { p ->
            val layout = dagger.hilt.android.EntryPointAccessors
                .fromApplication(context.applicationContext, PrismTvEntryPoint::class.java)
                .layoutPreferences()
            scope.launch {
                runCatching {
                    layout.setPosterCardWidthDp(p.widthDp)
                    layout.setPosterCardHeightDp(p.widthDp * 3 / 2)
                    layout.setPosterCardCornerRadiusDp(p.cornerRadiusDp)
                    layout.setPosterLabelsEnabled(p.labels)
                    layout.setModernLandscapePostersEnabled(p.landscape)
                }
            }
        }
    }
}

/** Pictures from the look, cut to the TV's wide shape (or whole, on a soft blurred copy of itself). */
object PrismTvImages {
    private val cache = mutableMapOf<String, Bitmap>()

    fun clear() = synchronized(cache) { cache.clear() }

    fun wide(picture: PrismTvPicture, width: Int = 1920, height: Int = 1080): Bitmap? {
        val key = "${picture.file}|$width"
        synchronized(cache) { cache[key]?.let { return it } }
        val file = PrismTv.mediaFile(picture.file)?.takeIf { it.isFile } ?: return null
        val source = decode(file, max(width, height)) ?: return null
        val crop = picture.wide
        val out = if (crop?.whole == true) whole(source, width, height) else cut(source, crop, width, height)
        synchronized(cache) { cache[key] = out }
        return out
    }

    /** The logo as it is (see-through edges kept). */
    fun logo(picture: PrismTvPicture): Bitmap? {
        val key = "logo|${picture.file}"
        synchronized(cache) { cache[key]?.let { return it } }
        val file = PrismTv.mediaFile(picture.file)?.takeIf { it.isFile } ?: return null
        val out = decode(file, 1024) ?: return null
        synchronized(cache) { cache[key] = out }
        return out
    }

    private fun decode(file: File, longest: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun cut(source: Bitmap, crop: PrismTvCrop?, width: Int, height: Int): Bitmap {
        val aspect = width.toFloat() / height
        val c = crop ?: run {
            val a = source.width.toFloat() / source.height
            if (a > aspect) {
                val w = aspect / a
                PrismTvCrop((1 - w) / 2, 0f, (1 + w) / 2, 1f, false)
            } else {
                val h = a / aspect
                PrismTvCrop(0f, (1 - h) / 2, 1f, (1 + h) / 2, false)
            }
        }
        val src = Rect(
            (c.left * source.width).roundToInt().coerceIn(0, source.width - 1),
            (c.top * source.height).roundToInt().coerceIn(0, source.height - 1),
            (c.right * source.width).roundToInt().coerceIn(1, source.width),
            (c.bottom * source.height).roundToInt().coerceIn(1, source.height),
        )
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, src, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun whole(source: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        // The fill: the middle of the picture shrunk in halves, then stretched back up in doubles, for a smooth blur.
        var small = cut(source, null, width, height)
        while (small.width > 60) small = Bitmap.createScaledBitmap(small, small.width / 2, max(1, small.height / 2), true)
        while (small.width * 2 <= width) small = Bitmap.createScaledBitmap(small, small.width * 2, small.height * 2, true)
        canvas.drawBitmap(small, null, Rect(0, 0, width, height), paint)
        canvas.drawColor(android.graphics.Color.argb(115, 0, 0, 0))
        val s = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val dw = source.width * s
        val dh = source.height * s
        canvas.drawBitmap(source, null, RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2), paint)
        return out
    }
}

/** The look's sounds. A moment with several sounds plays the one picked, or one at random (never the same twice in a row). */
object PrismTvSounds {
    private var player: MediaPlayer? = null
    private val last = mutableMapOf<String, String>()
    private val main = Handler(Looper.getMainLooper())

    fun play(context: Context, base: String, then: () -> Unit = {}) {
        val l = PrismTv.look
        if (l == null || !l.soundsOn || !PrismTv.soundsEnabled(context)) return then()
        val choices = l.soundSet(base)
        if (choices.isEmpty()) return then()
        val pick = l.soundChoice[base]?.let { key -> choices.firstOrNull { it.first == key } }
            ?: choices.filter { it.first != last[base] }.ifEmpty { choices }.random()
        last[base] = pick.first
        playSound(pick.second, then)
    }

    /** Plays one sound now, trimmed and at its volume (also used to try a sound in Customise). */
    fun playSound(sound: PrismTvSound, then: () -> Unit = {}) {
        val file = PrismTv.mediaFile(sound.file)?.takeIf { it.isFile } ?: return then()
        stop()
        val p = MediaPlayer()
        val ok = runCatching {
            p.setDataSource(file.path)
            p.setVolume(sound.volume, sound.volume)
            p.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                then()
            }
            p.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (player === mp) player = null
                then()
                true
            }
            p.prepare()
            if (sound.startMs > 0) p.seekTo(sound.startMs)
            p.start()
            if (sound.endMs > sound.startMs) {
                main.postDelayed({
                    if (player === p) {
                        stop()
                        then()
                    }
                }, (sound.endMs - sound.startMs).toLong())
            }
        }.isSuccess
        if (ok) player = p else {
            p.release()
            then()
        }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }

    fun later(millis: Long, block: () -> Unit) {
        main.postDelayed(block, millis)
    }
}
