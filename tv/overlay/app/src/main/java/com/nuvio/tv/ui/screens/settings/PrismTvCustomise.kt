package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.components.PrismTv
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Prism: customise this TV on its own (Settings > Appearance), separate from the phone and the PC: pictures,
// quotes, sounds, fonts and sizes, saved in the TV's own look. Colours are the TV's theme picker above.
@Composable
internal fun PrismTvCustomise() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    PrismTv.init(context)
    // Bumped after each change so the rows show the new values.
    var changes by remember { mutableIntStateOf(0) }
    val o = remember(changes) { PrismTv.lookJson(context) ?: JSONObject().put("name", "This TV") }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun save(edit: (JSONObject) -> Unit) {
        val next = PrismTv.lookJson(context) ?: JSONObject().put("name", "This TV")
        edit(next)
        runCatching { PrismTv.saveLook(context, next) }
            .onSuccess { changes++ }
            .onFailure { toast("That couldn't be saved") }
    }

    // One picker for every kind of file; [pending] says what the picked file is for.
    var pending by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var pendingKind by remember { mutableStateOf("file") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val then = pending ?: return@rememberLauncherForActivityResult
        pending = null
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { PrismTvMedia.copyIn(context, uri, pendingKind) }
            if (name == null) toast("That file couldn't be opened") else then(name)
        }
    }
    fun pick(type: String, kind: String, then: (String) -> Unit) {
        pending = then
        pendingKind = kind
        runCatching { picker.launch(type) }.onFailure {
            pending = null
            toast("No file picker found. Install a file manager such as ES File Explorer, then try again.")
        }
    }

    fun ask(title: String, start: String, then: (String) -> Unit) {
        val box = android.widget.EditText(context).apply { setText(start) }
        android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> box.text.toString().trim().takeIf { it.isNotEmpty() }?.let(then) }
            .setNegativeButton("Cancel", null)
            .show()
    }
    fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1) % options.size]

    SettingsActionRow(
        title = "Customise this TV",
        subtitle = "This TV's own look, separate from the phone and the PC. Colours are the theme choices above. " +
            "Copying from the phone replaces it",
        value = o.optString("name", "This TV"),
        onClick = { ask("Name of this TV's look", o.optString("name", "This TV")) { name -> save { it.put("name", name) } } },
    )

    // Pictures: several of each can be added; one is picked at random each time Prism starts.
    listOf(
        Triple("backgrounds", "background", "Home background"),
        Triple("openings", "opening", "Opening picture"),
        Triple("logos", "logo", "Logo"),
    ).forEach { (list, single, label) ->
        val count = PrismTvMedia.pictures(o, list, single).size
        SettingsActionRow(
            title = "$label: add a picture",
            subtitle = if (list == "logos") "Shown on the opening, best with a see-through background (PNG)"
            else "Fills the TV's wide screen; with more than one, a different one each time Prism starts",
            value = if (count == 0) "None" else "$count",
            onClick = {
                pick("image/*", "picture") { name ->
                    save { look ->
                        val array = look.optJSONArray(list) ?: JSONArray().also { look.put(list, it) }
                        array.put(JSONObject().put("file", name))
                        look.optJSONObject("pictureChoice")?.remove(single)
                    }
                    toast("Added. Restart Prism to see a new opening.")
                }
            },
        )
        if (count > 0) {
            SettingsActionRow(
                title = "$label: remove all",
                subtitle = "Takes the ${label.lowercase()} pictures off this TV",
                onClick = {
                    val files = PrismTvMedia.pictures(o, list, single)
                    save { look ->
                        look.remove(list)
                        look.remove(single)
                        look.optJSONObject("pictureChoice")?.remove(single)
                    }
                    PrismTvMedia.forget(context, files)
                },
            )
        }
    }

    val dims = listOf(0.0, 0.2, 0.35, 0.5, 0.7)
    val dim = dims.minBy { kotlin.math.abs(it - o.optDouble("backgroundDim", 0.35)) }
    SettingsActionRow(
        title = "Darken the background",
        subtitle = "So writing stays easy to read over the Home picture",
        value = if (dim == 0.0) "Off" else "${(dim * 100).toInt()}%",
        onClick = { save { it.put("backgroundDim", next(dims, dim)) } },
    )
    val seconds = listOf(3, 5, 8, 10)
    val secs = o.optInt("openingSeconds", 5).let { s -> seconds.minBy { kotlin.math.abs(it - s) } }
    SettingsActionRow(
        title = "Opening shows for",
        subtitle = "How long the opening picture stays when Prism starts",
        value = "$secs seconds",
        onClick = { save { it.put("openingSeconds", next(seconds, secs)) } },
    )

    val quotes = o.optJSONArray("quotes")?.length() ?: 0
    SettingsActionRow(
        title = "Quotes: add one",
        subtitle = "One shows on the opening each time Prism starts",
        value = if (quotes == 0) "None" else "$quotes",
        onClick = {
            ask("New quote", "") { quote ->
                save { look -> (look.optJSONArray("quotes") ?: JSONArray().also { look.put("quotes", it) }).put(quote) }
            }
        },
    )
    if (quotes > 0) {
        SettingsActionRow(
            title = "Quotes: remove all",
            subtitle = "Takes this TV's quotes away",
            onClick = { save { it.remove("quotes") } },
        )
    }

    // Sounds by moment; adding more than one to a moment plays one of them at random.
    PrismTvMedia.MOMENTS.forEach { (moment, label) ->
        val keys = PrismTvMedia.soundKeys(o, moment)
        SettingsActionRow(
            title = "Sound: $label",
            subtitle = if (keys.isEmpty()) "Press to add a sound (MP3, WAV, OGG)" else "Press to add another; one plays at random",
            value = if (keys.isEmpty()) "None" else "${keys.size}",
            onClick = {
                pick("audio/*", "sound") { name ->
                    save { look ->
                        val sounds = look.optJSONObject("sounds") ?: JSONObject().also { look.put("sounds", it) }
                        val key = if (sounds.has(moment)) "$moment#${System.currentTimeMillis()}" else moment
                        sounds.put(key, JSONObject().put("file", name).put("enabled", true).put("volume", 1.0))
                        look.optJSONObject("soundChoice")?.remove(moment)
                    }
                    toast("Added the sound for $label")
                }
            },
        )
        if (keys.isNotEmpty()) {
            SettingsActionRow(
                title = "Sound: $label, remove",
                subtitle = "Takes the $label sounds off this TV",
                onClick = {
                    val files = keys.mapNotNull { o.optJSONObject("sounds")?.optJSONObject(it)?.optString("file") }
                    save { look ->
                        keys.forEach { look.optJSONObject("sounds")?.remove(it) }
                        look.optJSONObject("soundChoice")?.remove(moment)
                    }
                    PrismTvMedia.forget(context, files)
                },
            )
        }
    }

    // Fonts: the built-in styles plus any font file added here.
    val fontFiles = PrismTvMedia.fontFiles(context)
    val fonts = listOf<String?>(null, "sans", "serif", "mono", "cursive") + fontFiles.map { "file:$it" }
    fun fontLabel(key: String?) = when (key) {
        null -> "App's own"
        "sans" -> "Clean"
        "serif" -> "Classic"
        "mono" -> "Typewriter"
        "cursive" -> "Handwritten"
        else -> key.removePrefix("file:").removePrefix("sys:").substringBeforeLast('.')
    }
    listOf("titleFont" to "Heading font", "textFont" to "Everyday font").forEach { (field, label) ->
        val current = o.optString(field, "").takeIf { it.isNotBlank() }
        SettingsActionRow(
            title = label,
            subtitle = "Press to try the next font",
            value = fontLabel(current),
            onClick = {
                val list = if (current in fonts) fonts else fonts + current
                save { look ->
                    val chosen = next(list, current)
                    if (chosen == null) look.remove(field) else look.put(field, chosen)
                }
            },
        )
    }
    SettingsActionRow(
        title = "Add a font file",
        subtitle = "A .ttf or .otf font; it joins the choices above",
        value = if (fontFiles.isEmpty()) "None" else "${fontFiles.size}",
        onClick = {
            pick("*/*", "font") { name ->
                save { it.put("titleFont", "file:$name") }
                toast("Added the font and used it for headings")
            }
        },
    )
    val sizes = listOf(0.85, 1.0, 1.15, 1.3)
    val size = o.optDouble("textSize", 1.0).let { s -> sizes.minBy { kotlin.math.abs(it - s) } }
    SettingsActionRow(
        title = "Text size",
        subtitle = "Every bit of writing in Prism",
        value = when (size) { 0.85 -> "Small"; 1.0 -> "Normal"; 1.15 -> "Large"; else -> "Largest" },
        onClick = { save { it.put("textSize", next(sizes, size)) } },
    )
}

/** Files for this TV's own look, kept with the rest of the look's media. */
internal object PrismTvMedia {
    val MOMENTS = listOf(
        "Opening" to "opening",
        "Profile" to "choosing a profile",
        "PickEpisode" to "random episode",
        "NoResults" to "nothing found",
        "StreamFailed" to "can't play",
    )

    fun copyIn(context: Context, uri: Uri, kind: String): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val shown = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val ext = shown?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..4 }
            ?: when (kind) { "picture" -> "jpg"; "sound" -> "mp3"; else -> "ttf" }
        val base = if (kind == "font" && shown != null) shown.substringBeforeLast('.').filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim() else ""
        val name = (if (base.isNotEmpty()) "tv-$base" else "tv-$kind-${System.currentTimeMillis()}") + ".$ext"
        File(PrismTv.mediaDir(context), name).writeBytes(bytes)
        name
    }.getOrNull()

    fun pictures(o: JSONObject, list: String, single: String): List<String> = buildList {
        o.optJSONObject(single)?.optString("file")?.takeIf { it.isNotBlank() }?.let(::add)
        o.optJSONArray(list)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("file")?.takeIf { it.isNotBlank() }?.let(::add) }
    }

    fun soundKeys(o: JSONObject, moment: String): List<String> =
        o.optJSONObject("sounds")?.keys()?.asSequence()?.filter { it == moment || it.startsWith("$moment#") }?.toList().orEmpty()

    fun fontFiles(context: Context): List<String> =
        PrismTv.mediaDir(context).listFiles()?.map { it.name }?.filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }?.sorted().orEmpty()

    /** Deletes files no longer in the look. */
    fun forget(context: Context, files: List<String>) {
        val still = PrismTv.lookJson(context)?.toString().orEmpty()
        files.filter { !it.contains('/') && !still.contains("\"$it\"") }.forEach { File(PrismTv.mediaDir(context), it).delete() }
    }
}
