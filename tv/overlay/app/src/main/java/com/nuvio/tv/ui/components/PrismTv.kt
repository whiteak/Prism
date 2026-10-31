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

class PrismTvSound(val file: String, val enabled: Boolean, val volume: Float = 1f)

class PrismTvTitle(val id: String, val type: String, val name: String)

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
                    v.str("file")?.let { sounds[key] = PrismTvSound(it, v.optBoolean("enabled", true), v.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f)) }
                }
            }
            fun title(t: JSONObject?): PrismTvTitle? = t?.let {
                val id = it.str("id") ?: return@let null
                PrismTvTitle(id, it.str("type") ?: "series", it.str("name") ?: "")
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
        val file = File(dir(context), "look.json")
        look = runCatching { PrismTvLook.parse(JSONObject(file.readText())) }.getOrNull()
    }

    fun soundsEnabled(context: Context): Boolean =
        context.getSharedPreferences("prism", Context.MODE_PRIVATE).getBoolean("sounds", true)

    fun setSoundsEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("prism", Context.MODE_PRIVATE).edit().putBoolean("sounds", value).apply()
        if (!value) PrismTvSounds.stop()
    }

    /** Loads a look exported from the phone, replacing the one here. Returns its name, or null if it isn't a look. */
    fun importLook(context: Context, bytes: ByteArray): String? {
        init(context)
        val root = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull() ?: return null
        val lookJson = root.optJSONObject("look") ?: return null
        val parsed = runCatching { PrismTvLook.parse(lookJson) }.getOrNull() ?: return null
        val base = dir(context)
        val media = File(base, "media")
        media.deleteRecursively()
        media.mkdirs()
        root.optJSONObject("media")?.let { m ->
            m.keys().forEach { name ->
                if (name.contains('/') || name.contains("..")) return@forEach
                val data = runCatching { Base64.decode(m.optString(name), Base64.DEFAULT) }.getOrNull() ?: return@forEach
                File(media, name).writeBytes(data)
            }
        }
        File(base, "look.json").writeText(lookJson.toString())
        PrismTvImages.clear()
        PrismTvFonts.clear()
        look = parsed
        applyColours(context, parsed)
        return parsed.name
    }

    /** The look as stored here (look.json), for changing on the TV itself; null with the built-in look. */
    fun lookJson(context: Context): JSONObject? =
        runCatching { JSONObject(File(dir(context), "look.json").readText()) }.getOrNull()

    /** Saves a look changed on the TV (its pictures, sounds and fonts already in [mediaDir]) and shows it. */
    fun saveLook(context: Context, o: JSONObject) {
        init(context)
        val parsed = PrismTvLook.parse(o)
        File(dir(context), "look.json").writeText(o.toString())
        PrismTvImages.clear()
        PrismTvFonts.clear()
        look = parsed
    }

    fun mediaDir(context: Context) = File(dir(context), "media").apply { mkdirs() }

    /** Goes back to Prism's built-in opening and no sounds. */
    fun clear(context: Context) {
        val base = dir(context)
        File(base, "media").deleteRecursively()
        File(base, "look.json").delete()
        PrismTvImages.clear()
        PrismTvFonts.clear()
        look = null
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
        val file = PrismTv.mediaFile(pick.second.file)?.takeIf { it.isFile } ?: return then()
        stop()
        val p = MediaPlayer()
        val ok = runCatching {
            p.setDataSource(file.path)
            p.setVolume(pick.second.volume, pick.second.volume)
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
            p.start()
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
