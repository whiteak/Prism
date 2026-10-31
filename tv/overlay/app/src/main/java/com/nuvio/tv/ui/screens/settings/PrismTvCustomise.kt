package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.ui.components.PrismTv
import com.nuvio.tv.ui.components.PrismTvCrop
import com.nuvio.tv.ui.components.PrismTvImages
import com.nuvio.tv.ui.components.PrismTvPicture
import com.nuvio.tv.ui.components.PrismTvSound
import com.nuvio.tv.ui.components.PrismTvSounds
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Prism: Customise on the TV, the same steps as on the phone, done with the remote (Settings > Appearance >
// Customise). Each TV look is kept on this TV (PrismTv: looks.json plus the media folder); copying from the phone
// adds the phone's look as one more.

@Composable
internal fun PrismTvCustomise() {
    val context = LocalContext.current
    PrismTv.init(context)
    var open by remember { mutableStateOf(false) }
    SettingsActionRow(
        title = "Customise",
        subtitle = "This TV's own looks: colours, pictures, opening, quotes and sounds, set up with the remote",
        value = PrismTv.look?.name ?: "Built-in",
        onClick = { open = true },
    )
    if (open) PrismTvCustomiseScreen(onClose = { open = false })
}

/** The steps, numbered in the order a look is usually set up. */
private enum class Step(val title: String, val about: String) {
    Looks("Looks", "Keep several looks and switch between them"),
    Colours("Colours", "The colour theme, your own colour, pure black"),
    Pictures("Pictures and logos", "Home backgrounds, opening pictures and logos, cropped for the TV"),
    Profiles("Profile photos", "A photo of your own for each profile, kept on this TV"),
    Fonts("Fonts", "Heading and everyday fonts, each kind of text styled, and how big text is"),
    Posters("Posters and shapes", "Poster size, rounded corners, names under posters"),
    Home("Home", "Your own rows of movies and shows, show cards for Play random episode, and hero clips"),
    Opening("Opening", "How long the opening shows"),
    Quotes("Quotes", "One shows on the opening each time Prism starts"),
    Sounds("Sounds", "Sounds for moments in the app, trimmed and at their own volume"),
    Files("Save and share", "Back up everything, and send your library to another device"),
}

/** The picture slots: the look's list of pictures, the older single one, and the slot's name for pictureChoice. */
internal enum class Slot(val list: String, val single: String, val label: String, val crops: Boolean) {
    Background("backgrounds", "background", "Home background", true),
    Opening("openings", "opening", "Opening picture", true),
    Logo("logos", "logo", "Logo", false),
}

private val MOMENTS = listOf(
    "Opening" to "Opening (when Prism starts)",
    "Profile" to "Profile picked",
    "PickEpisode" to "Play random episode",
    "NoResults" to "Nothing found",
    "StreamFailed" to "Can't play",
    "PlayStarting" to "Play pressed",
    "EpisodeFinished" to "Finished watching",
    "AddedToLibrary" to "Added to Library",
    "Removed" to "Removed from Library",
)

private sealed class Sub {
    class Pictures(val slot: Slot) : Sub()
    class Crop(val slot: Slot, val file: String) : Sub()
    class Moment(val key: String, val label: String) : Sub()
    class Trim(val moment: String, val key: String) : Sub()
    class TextArea(val area: String, val label: String) : Sub()
    class Row(val id: String) : Sub()
    object Cards : Sub()
}

@Composable
private fun PrismTvCustomiseScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step?>(null) }
    var sub by remember { mutableStateOf<Sub?>(null) }
    val o = remember(PrismTv.changes) { PrismTv.lookJson(context) }
    val look = o ?: JSONObject()

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun edit(change: (JSONObject) -> Unit) {
        val next = PrismTv.lookJson(context) ?: JSONObject().put("name", "This TV")
        change(next)
        runCatching { PrismTv.saveLook(context, next) }.onFailure { toast("That couldn't be saved") }
    }
    fun back() {
        when {
            sub is Sub.Crop -> sub = Sub.Pictures((sub as Sub.Crop).slot)
            sub is Sub.Trim -> sub = (sub as Sub.Trim).moment.let { m -> MOMENTS.firstOrNull { it.first == m }?.let { Sub.Moment(it.first, it.second) } ?: Sub.Moment(m, "Card sound") }
            sub != null -> sub = null
            step != null -> step = null
            else -> onClose()
        }
    }

    var pending by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var pendingKind by remember { mutableStateOf("picture") }
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

    Dialog(
        onDismissRequest = { back() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background)
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            val current = sub
            when {
                current is Sub.Crop -> PrismTvCropEditor(look, current.slot, current.file, onDone = { crop ->
                    edit { l -> PrismTvMedia.setCrop(l, current.slot, current.file, crop) }
                    sub = Sub.Pictures(current.slot)
                })
                current is Sub.Trim -> PrismTvTrimEditor(look, current.key, onDone = { start, end ->
                    edit { l -> l.optJSONObject("sounds")?.optJSONObject(current.key)?.put("startMs", start)?.put("endMs", end) }
                    sub = MOMENTS.firstOrNull { it.first == current.moment }?.let { Sub.Moment(it.first, it.second) } ?: Sub.Moment(current.moment, "Card sound")
                })
                else -> Row(Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                    val heading = when (current) {
                        is Sub.Pictures -> current.slot.label + "s"
                        is Sub.Moment -> "Sound: " + current.label
                        is Sub.TextArea -> "Text: " + current.label
                        is Sub.Row -> "Row: " + PrismTvHome.rows(look).firstOrNull { it.optString("id") == current.id }?.optString("title").orEmpty()
                        is Sub.Cards -> "Show cards"
                        else -> step?.let { "${it.ordinal + 1}. ${it.title}" } ?: "Customise"
                    }
                    Text(heading, color = NuvioTheme.colors.TextPrimary, fontSize = 28.sp)
                    Text(
                        (o?.optString("name")?.let { "Look: $it" } ?: "Showing Prism's built-in look; any change starts a look of this TV's own") +
                            "   ·   Back to go back",
                        color = NuvioTheme.colors.TextSecondary,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    when {
                        current is Sub.Pictures -> PrismTvPicturesPage(look, current.slot, ::edit, ::pick, ::toast, openCrop = { sub = Sub.Crop(current.slot, it) })
                        current is Sub.TextArea -> PrismTvTextAreaPage(look, current.area, ::edit)
                        current is Sub.Row -> PrismTvRowPage(look, current.id, ::edit, onGone = { sub = null })
                        current is Sub.Cards -> PrismTvCardsPage(look, ::edit, openSound = { key, label -> sub = Sub.Moment(key, label) })
                        current is Sub.Moment -> PrismTvMomentPage(look, current.key, current.label, ::edit, ::pick, ::toast, openTrim = { sub = Sub.Trim(current.key, it) })
                        step == null -> LazyColumn {
                            Step.entries.forEach { s ->
                                item(s.name) {
                                    SettingsActionRow(title = "${s.ordinal + 1}. ${s.title}", subtitle = s.about, onClick = { step = s })
                                }
                            }
                        }
                        step == Step.Looks -> PrismTvLooksPage(context, ::toast)
                        step == Step.Colours -> PrismTvColoursPage(context, look, ::edit)
                        step == Step.Pictures -> LazyColumn {
                            Slot.entries.forEach { slot ->
                                item(slot.name) {
                                    val count = PrismTvMedia.pictures(look, slot).size
                                    SettingsActionRow(
                                        title = slot.label + "s",
                                        subtitle = if (slot.crops) "Add, crop, pick one or let one be picked at random each start" else "Shown on the opening; best with a see-through background (PNG)",
                                        value = if (count == 0) "None" else "$count",
                                        onClick = { sub = Sub.Pictures(slot) },
                                    )
                                }
                            }
                            item("dim") {
                                val dims = listOf(0.0, 0.2, 0.35, 0.5, 0.7)
                                val dim = dims.minBy { kotlin.math.abs(it - look.optDouble("backgroundDim", 0.35)) }
                                SettingsActionRow(
                                    title = "Darken the Home background",
                                    subtitle = "So writing stays easy to read over the picture",
                                    value = if (dim == 0.0) "Off" else "${(dim * 100).toInt()}%",
                                    onClick = { edit { it.put("backgroundDim", dims[(dims.indexOf(dim) + 1) % dims.size]) } },
                                )
                            }
                        }
                        step == Step.Opening -> LazyColumn {
                            item("secs") {
                                val seconds = listOf(3, 5, 8, 10, 15)
                                val secs = look.optInt("openingSeconds", 5).let { s -> seconds.minBy { kotlin.math.abs(it - s) } }
                                SettingsActionRow(
                                    title = "Opening shows for",
                                    subtitle = "How long the opening picture stays when Prism starts (any key skips it)",
                                    value = "$secs seconds",
                                    onClick = { edit { it.put("openingSeconds", seconds[(seconds.indexOf(secs) + 1) % seconds.size]) } },
                                )
                            }
                            item("pics") {
                                SettingsActionRow(title = "Opening pictures", subtitle = "Under 3. Pictures and logos", value = "${PrismTvMedia.pictures(look, Slot.Opening).size}", onClick = { sub = Sub.Pictures(Slot.Opening) })
                            }
                            item("logos") {
                                SettingsActionRow(title = "Logos", subtitle = "Under 3. Pictures and logos", value = "${PrismTvMedia.pictures(look, Slot.Logo).size}", onClick = { sub = Sub.Pictures(Slot.Logo) })
                            }
                            item("sound") {
                                SettingsActionRow(title = "Opening sound", subtitle = "Under ${Step.Sounds.ordinal + 1}. Sounds", onClick = { sub = Sub.Moment("Opening", MOMENTS.first().second) })
                            }
                        }
                        step == Step.Fonts -> PrismTvFontsPage(look, ::edit, ::pick, ::toast, openArea = { a, l -> sub = Sub.TextArea(a, l) })
                        step == Step.Posters -> PrismTvPostersPage(context, look, ::edit)
                        step == Step.Profiles -> PrismTvProfilesPage(::pick, ::toast)
                        step == Step.Home -> PrismTvHomePage(look, ::edit, ::pick, ::toast, openRow = { sub = Sub.Row(it) }, openCards = { sub = Sub.Cards })
                        step == Step.Quotes -> PrismTvQuotesPage(context, look, ::edit)
                        step == Step.Files -> PrismTvFilesPage(::toast)
                        step == Step.Sounds -> LazyColumn {
                            item("on") {
                                SettingsToggleRow(
                                    title = "Sounds in this look",
                                    subtitle = "Off keeps the sounds but doesn't play them",
                                    checked = look.optBoolean("soundsOn", true),
                                    onToggle = { edit { it.put("soundsOn", !look.optBoolean("soundsOn", true)) } },
                                )
                            }
                            MOMENTS.forEach { (key, label) ->
                                item(key) {
                                    val n = PrismTvMedia.soundKeys(look, key).size
                                    SettingsActionRow(title = label, subtitle = null, value = if (n == 0) "None" else "$n", onClick = { sub = Sub.Moment(key, label) })
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(36.dp))
                PrismTvLivePreview(
                    mode = when {
                        current is Sub.Pictures -> PrismTvPreviewMode.Picture
                        step == Step.Opening || step == Step.Quotes -> PrismTvPreviewMode.Opening
                        step == Step.Profiles -> PrismTvPreviewMode.Profiles
                        else -> PrismTvPreviewMode.Home
                    },
                    modifier = Modifier.weight(0.42f).padding(top = 48.dp),
                )
                }
            }
        }
    }
}

// ---- 1. Looks ----

/** Picks the profiles a look opens for, one at a time (each press turns one on or off). */
private fun prismProfilesFor(context: Context, lookId: String, lookName: String) {
    val profiles = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, com.nuvio.tv.ui.components.PrismTvEntryPoint::class.java)
        .profileManager().profiles.value
    if (profiles.isEmpty()) {
        Toast.makeText(context, "No profiles yet", Toast.LENGTH_LONG).show()
        return
    }
    val on = PrismTv.profileLooks(context)
    prismChoose(context, "\"$lookName\" opens for…", profiles.map { p -> (if (on["p${p.id}"] == lookId) "✓ " else "") + p.name }) { i ->
        PrismTv.toggleProfileLook(context, profiles[i].id, lookId)
        PrismTv.autoSwitch(context, null)
        prismProfilesFor(context, lookId, lookName)
    }
}

/** Sets the dates a look opens between each year, like 1/12 to 26/12 for Christmas. */
private fun prismDatesFor(context: Context, lookId: String, lookName: String) {
    val now = PrismTv.season(context, lookId)
    val current = now?.let { "${it[1]}/${it[0]} to ${it[3]}/${it[2]}" } ?: "1/12 to 26/12"
    prismAsk(context, "\"$lookName\" opens between (day/month to day/month, or none)", current) { text ->
        if (text.lowercase() in setOf("none", "never", "off", "no")) {
            PrismTv.setSeason(context, lookId, null)
            Toast.makeText(context, "No dates", Toast.LENGTH_SHORT).show()
            return@prismAsk
        }
        val n = Regex("""\d+""").findAll(text).map { it.value.toInt() }.toList()
        val ok = n.size == 4 && n[1] in 1..12 && n[3] in 1..12 && n[0] in 1..31 && n[2] in 1..31
        if (!ok) {
            Toast.makeText(context, "Type it like 1/12 to 26/12", Toast.LENGTH_LONG).show()
            return@prismAsk
        }
        PrismTv.setSeason(context, lookId, intArrayOf(n[1], n[0], n[3], n[2]))
        PrismTv.autoSwitch(context, null)
        Toast.makeText(context, "Opens ${n[0]}/${n[1]} to ${n[2]}/${n[3]} each year", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun PrismTvLooksPage(context: Context, toast: (String) -> Unit) {
    val looks = remember(PrismTv.changes) { PrismTv.looks(context) }
    val active = remember(PrismTv.changes) { PrismTv.activeId(context) }
    LazyColumn {
        looks.forEach { (id, name) ->
            item(id) {
                SettingsActionRow(
                    title = name,
                    subtitle = listOfNotNull(
                        if (id == active) "Showing now" else "Press to show it, set when it opens, rename or delete it",
                        PrismTv.season(context, id)?.let { d -> "Opens ${d[1]}/${d[0]} to ${d[3]}/${d[2]}" },
                        PrismTv.profileLooks(context).count { it.value == id }.takeIf { it > 0 }?.let { "Opens for $it profile${if (it == 1) "" else "s"}" },
                    ).joinToString(" · "),
                    value = if (id == active) "Showing" else null,
                    onClick = {
                        val options = listOfNotNull(if (id != active) "Show this look" else null, "Opens for these profiles", "Opens between dates", "Rename", "Delete")
                        prismChoose(context, name, options) { i ->
                            when (options[i]) {
                                "Show this look" -> PrismTv.switchTo(context, id)
                                "Opens for these profiles" -> prismProfilesFor(context, id, name)
                                "Opens between dates" -> prismDatesFor(context, id, name)
                                "Rename" -> prismAsk(context, "New name", name) { PrismTv.renameLook(context, id, it) }
                                "Delete" -> prismConfirm(context, "Delete \"$name\"?", "Its pictures and sounds go too.") { PrismTv.deleteLook(context, id) }
                            }
                        }
                    },
                )
            }
        }
        item("new") {
            SettingsActionRow(title = "New blank look", subtitle = "Starts from Prism's built-in look", onClick = {
                prismAsk(context, "Name of the new look", "Look ${looks.size + 1}") { PrismTv.newLook(context, it, copy = false) }
            })
        }
        if (active.isNotEmpty()) {
            item("copy") {
                SettingsActionRow(title = "Copy this look", subtitle = "A new look starting from the one showing", onClick = {
                    prismAsk(context, "Name of the copy", "Look ${looks.size + 1}") { PrismTv.newLook(context, it, copy = true) }
                })
            }
            item("builtin") {
                SettingsActionRow(title = "Show Prism's built-in look", subtitle = "Your looks are kept, to switch back to", onClick = {
                    PrismTv.clear(context)
                    toast("Showing Prism's built-in look")
                })
            }
        }
    }
}

// ---- 2. Colours ----

private val OWN_COLOURS = listOf(
    "Violet" to 0x8E6CFF, "Opal" to 0x7FD8D0, "Gold" to 0xE0B84B, "Crimson" to 0xD8334A, "Rose" to 0xF07AA0,
    "Ocean" to 0x2E86DE, "Emerald" to 0x2ECC71, "Amber" to 0xF39C12, "Ice" to 0xA8D8FF, "Silver" to 0xC0C4CC,
)

@Composable
private fun PrismTvColoursPage(context: Context, look: JSONObject, edit: ((JSONObject) -> Unit) -> Unit) {
    val themes = AppTheme.entries.filter { it != AppTheme.CUSTOM }
    val theme = look.optString("theme", "").takeIf { it.isNotEmpty() }
    val custom = if (look.has("customColor") && !look.isNull("customColor")) look.optInt("customColor") and 0xFFFFFF else null
    fun save(change: (JSONObject) -> Unit) {
        edit(change)
        PrismTv.applyColoursNow(context)
    }
    LazyColumn {
        item("theme") {
            val now = themes.firstOrNull { it.name == theme }
            SettingsActionRow(
                title = "Colour theme",
                subtitle = "Press to try the next one",
                value = if (theme == AppTheme.CUSTOM.name) "Your own colour" else now?.displayName ?: "The TV's own",
                onClick = {
                    val next = themes[((now?.let { themes.indexOf(it) } ?: -1) + 1) % themes.size]
                    save { it.put("theme", next.name) }
                },
            )
        }
        item("own") {
            val index = OWN_COLOURS.indexOfFirst { it.second == custom }
            SettingsActionRow(
                title = "Your own colour",
                subtitle = "Press to try the next one, or type a colour code (like #8E6CFF) with Type a colour",
                value = OWN_COLOURS.getOrNull(index)?.first ?: custom?.let { "#%06X".format(it) } ?: "Not used",
                onClick = {
                    val next = OWN_COLOURS[(index + 1) % OWN_COLOURS.size].second
                    save { it.put("theme", AppTheme.CUSTOM.name).put("customColor", next) }
                },
            )
        }
        item("type") {
            SettingsActionRow(title = "Type a colour", subtitle = "A six-digit colour code", onClick = {
                prismAsk(context, "Colour code", custom?.let { "#%06X".format(it) } ?: "#") { typed ->
                    typed.removePrefix("#").toIntOrNull(16)?.takeIf { typed.removePrefix("#").length == 6 }?.let { c ->
                        save { it.put("theme", AppTheme.CUSTOM.name).put("customColor", c) }
                    } ?: Toast.makeText(context, "That isn't a colour code", Toast.LENGTH_SHORT).show()
                }
            })
        }
        item("amoled") {
            SettingsToggleRow(
                title = "Pure black backgrounds",
                subtitle = "Black behind everything, good on OLED TVs",
                checked = look.optBoolean("amoled", false),
                onToggle = { save { it.put("amoled", !look.optBoolean("amoled", false)) } },
            )
        }
    }
}

// ---- 3. Pictures ----

@Composable
private fun PrismTvPicturesPage(
    look: JSONObject,
    slot: Slot,
    edit: ((JSONObject) -> Unit) -> Unit,
    pick: (String, String, (String) -> Unit) -> Unit,
    toast: (String) -> Unit,
    openCrop: (String) -> Unit,
) {
    val context = LocalContext.current
    val pictures = PrismTvMedia.pictures(look, slot)
    val chosen = look.optJSONObject("pictureChoice")?.optString(slot.single, "")?.takeIf { it.isNotEmpty() }
    var focused by remember(slot) { mutableStateOf(pictures.firstOrNull()) }
    // The newest copy of the picture, so a new crop shows straight away.
    LaunchedEffect(focused?.file, slot, PrismTv.changes) {
        PrismTvPreviewState.picture = pictures.firstOrNull { it.file == focused?.file } ?: pictures.firstOrNull()
        PrismTvPreviewState.pictureIsLogo = slot == Slot.Logo
    }
    Row(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxHeight()) {
            item("add") {
                SettingsActionRow(title = "Add a picture", subtitle = "From a file on the TV (sent with Send Anywhere, or the Prism folder)", onClick = {
                    pick("image/*", "picture") { name ->
                        edit { l -> PrismTvMedia.addPicture(l, slot, name) }
                        toast("Added")
                    }
                })
            }
            if (pictures.size > 1) {
                item("random") {
                    SettingsToggleRow(
                        title = "A different one each start",
                        subtitle = "Off shows the one picked below every time",
                        checked = chosen == null,
                        onToggle = {
                            edit { l ->
                                val choice = l.optJSONObject("pictureChoice") ?: JSONObject().also { l.put("pictureChoice", it) }
                                if (chosen == null) choice.put(slot.single, (focused ?: pictures.first()).file) else choice.remove(slot.single)
                            }
                        },
                    )
                }
            }
            pictures.forEachIndexed { i, p ->
                item(p.file) {
                    val crop = p.wide
                    SettingsActionRow(
                        title = "Picture ${i + 1}",
                        subtitle = if (chosen == p.file) "Shown every time" else null,
                        value = when {
                            !slot.crops -> null
                            crop?.whole == true -> "Whole"
                            crop != null -> "Cropped"
                            else -> "Fills the screen"
                        },
                        onFocused = { focused = p },
                        onClick = {
                            val options = buildList {
                                add("Show this one every time")
                                if (slot.crops) {
                                    add("Crop it")
                                    add(if (crop?.whole == true) "Fill the screen" else "Show it whole")
                                }
                                add("Remove")
                            }
                            prismChoose(context, "Picture ${i + 1}", options) { which ->
                                when (options[which]) {
                                    "Show this one every time" -> edit { l ->
                                        (l.optJSONObject("pictureChoice") ?: JSONObject().also { l.put("pictureChoice", it) }).put(slot.single, p.file)
                                    }
                                    "Crop it" -> openCrop(p.file)
                                    "Show it whole" -> edit { l -> PrismTvMedia.setCrop(l, slot, p.file, PrismTvCrop(0f, 0f, 1f, 1f, true)) }
                                    "Fill the screen" -> edit { l -> PrismTvMedia.setCrop(l, slot, p.file, null) }
                                    "Remove" -> edit { l -> PrismTvMedia.removePicture(l, slot, p.file) }
                                }
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(32.dp))
        // What the focused picture looks like on the TV.
        Box(modifier = Modifier.weight(1f).aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            val shown = focused?.takeIf { f -> pictures.any { it.file == f.file } }?.let { f -> pictures.first { it.file == f.file } }
            var image by remember { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(shown?.file, shown?.wide?.let { "${it.left},${it.top},${it.right},${it.bottom},${it.whole}" }) {
                image = shown?.let { pic ->
                    withContext(Dispatchers.IO) {
                        if (slot.crops) PrismTvImages.wide(pic, 960, 540)?.asImageBitmap() else PrismTvImages.logo(pic)?.asImageBitmap()
                    }
                }
            }
            image?.let { androidx.compose.foundation.Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: Text(if (pictures.isEmpty()) "No pictures yet" else "", color = NuvioTheme.colors.TextSecondary)
        }
    }
}

/** Moves a 16:9 frame over the picture with the remote: arrows move it, OK makes it smaller (then full again), Back keeps it. */
@Composable
private fun PrismTvCropEditor(look: JSONObject, slot: Slot, file: String, onDone: (PrismTvCrop?) -> Unit) {
    val start = PrismTvMedia.pictures(look, slot).firstOrNull { it.file == file }?.wide?.takeIf { !it.whole }
    var source by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        source = withContext(Dispatchers.IO) {
            PrismTv.mediaFile(file)?.let { f ->
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
                android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        }
    }
    val img = source
    if (img == null) {
        Text("Opening the picture…", color = NuvioTheme.colors.TextSecondary)
        return
    }
    // The frame as fractions of the picture: its width w and height h keep the TV's 16:9 shape.
    val aspect = img.width.toFloat() / img.height
    val fullW = minOf(1f, (16f / 9f) / aspect)
    var size by remember { mutableFloatStateOf(start?.let { ((it.right - it.left) / fullW).coerceIn(0.3f, 1f) } ?: 1f) }
    val w = fullW * size
    val h = w * aspect * 9f / 16f
    var left by remember { mutableFloatStateOf(start?.left ?: ((1 - w) / 2)) }
    var top by remember { mutableFloatStateOf(start?.top ?: ((1 - h) / 2)) }
    val cl = left.coerceIn(0f, (1f - w).coerceAtLeast(0f))
    val ct = top.coerceIn(0f, (1f - h).coerceAtLeast(0f))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun crop() = PrismTvCrop(cl, ct, cl + w, ct + h, false)

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Crop for the TV", color = NuvioTheme.colors.TextPrimary, fontSize = 28.sp)
        Text("Arrows move the frame · OK makes it smaller (and back to full size) · Back keeps it", color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    // Back is taken on its way up too, so it doesn't also close Customise.
                    if (e.key == Key.Back || e.key == Key.Escape) {
                        if (e.type == KeyEventType.KeyUp) onDone(if (size >= 0.999f) null else crop())
                        return@onPreviewKeyEvent true
                    }
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = 0.025f
                    when (e.key) {
                        Key.DirectionLeft -> { left = cl - step; true }
                        Key.DirectionRight -> { left = cl + step; true }
                        Key.DirectionUp -> { top = ct - step; true }
                        Key.DirectionDown -> { top = ct + step; true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val cx = cl + w / 2
                            val cy = ct + h / 2
                            size = if (size <= 0.35f) 1f else size - 0.15f
                            val nw = fullW * size
                            val nh = nw * aspect * 9f / 16f
                            left = cx - nw / 2
                            top = cy - nh / 2
                            true
                        }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
                drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()))
                val fx = cl * this.size.width
                val fy = ct * this.size.height
                val fw = w * this.size.width
                val fh = h * this.size.height
                val shade = Color.Black.copy(alpha = 0.6f)
                drawRect(shade, Offset(0f, 0f), Size(this.size.width, fy))
                drawRect(shade, Offset(0f, fy + fh), Size(this.size.width, this.size.height - fy - fh))
                drawRect(shade, Offset(0f, fy), Size(fx, fh))
                drawRect(shade, Offset(fx + fw, fy), Size(this.size.width - fx - fw, fh))
                drawRect(Color.White, Offset(fx, fy), Size(fw, fh), style = Stroke(width = 4f))
            }
        }
    }
}

// ---- 4. Fonts ----

@Composable
private fun PrismTvFontsPage(
    look: JSONObject,
    edit: ((JSONObject) -> Unit) -> Unit,
    pick: (String, String, (String) -> Unit) -> Unit,
    toast: (String) -> Unit,
    openArea: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val fontFiles = remember(PrismTv.changes) { PrismTvMedia.fontFiles(context) }
    val fonts = kotlin.collections.listOf<String?>(null, "sans", "serif", "mono", "cursive") + fontFiles.map { "file:$it" }
    fun label(key: String?) = when (key) {
        null -> "App's own"
        "sans" -> "Clean"
        "serif" -> "Classic"
        "mono" -> "Typewriter"
        "cursive" -> "Handwritten"
        else -> key.removePrefix("file:").removePrefix("sys:").substringBeforeLast('.')
    }
    LazyColumn {
        kotlin.collections.listOf("titleFont" to "Heading font", "textFont" to "Everyday font").forEach { (field, title) ->
            item(field) {
                val current = look.optString(field, "").takeIf { it.isNotBlank() }
                SettingsActionRow(title = title, subtitle = "Press to try the next font", value = label(current), onClick = {
                    val list = if (current in fonts) fonts else fonts + current
                    val chosen = list[(list.indexOf(current) + 1) % list.size]
                    edit { l -> if (chosen == null) l.remove(field) else l.put(field, chosen) }
                })
            }
        }
        item("add") {
            SettingsActionRow(title = "Add a font file", subtitle = "A .ttf or .otf font; it joins the choices above", value = if (fontFiles.isEmpty()) "None" else "${fontFiles.size}", onClick = {
                pick("*/*", "font") { name ->
                    edit { it.put("titleFont", "file:$name") }
                    toast("Added the font and used it for headings")
                }
            })
        }
        TEXT_AREAS.forEach { (area, label, about) ->
            item("area-$area") {
                val styled = look.optJSONObject("textStyles")?.optJSONObject(area)?.length()?.let { it > 0 } == true
                SettingsActionRow(title = label, subtitle = about, value = if (styled) "Styled" else null, onClick = { openArea(area, label) })
            }
        }
        item("size") {
            val sizes = kotlin.collections.listOf(0.85, 1.0, 1.15, 1.3)
            val size = look.optDouble("textSize", 1.0).let { s -> sizes.minBy { kotlin.math.abs(it - s) } }
            SettingsActionRow(
                title = "Text size",
                subtitle = "Every bit of writing in Prism",
                value = when (size) { 0.85 -> "Small"; 1.0 -> "Normal"; 1.15 -> "Large"; else -> "Largest" },
                onClick = { edit { it.put("textSize", sizes[(sizes.indexOf(size) + 1) % sizes.size]) } },
            )
        }
    }
}

/** The kinds of text that can each be styled, as on the phone. */
private val TEXT_AREAS = listOf(
    Triple("Display", "Big titles", "The largest text, like titles on the details page"),
    Triple("Headline", "Page headings", "Headings at the top of pages and big sections"),
    Triple("Title", "Row and card titles", "Home row names, show names and list titles"),
    Triple("Body", "Everyday text", "Descriptions, settings and most other text"),
    Triple("Label", "Buttons and labels", "Buttons, chips, tabs and small labels"),
    Triple("Quote", "Quotes", "Your quotes on the opening screen and the screensaver"),
)

private val TEXT_COLOURS = listOf(
    "White" to 0xFFFFFFFFL, "Black" to 0xFF000000L, "Violet" to 0xFF8E6CFFL, "Opal" to 0xFF7FD8D0L, "Gold" to 0xFFE0B84BL,
    "Crimson" to 0xFFD8334AL, "Rose" to 0xFFF07AA0L, "Ocean" to 0xFF2E86DEL, "Emerald" to 0xFF2ECC71L, "Amber" to 0xFFF39C12L,
)

@Composable
private fun PrismTvTextAreaPage(look: JSONObject, area: String, edit: ((JSONObject) -> Unit) -> Unit) {
    val context = LocalContext.current
    val st = look.optJSONObject("textStyles")?.optJSONObject(area) ?: JSONObject()
    fun change(block: (JSONObject) -> Unit) = edit { l ->
        val all = l.optJSONObject("textStyles") ?: JSONObject().also { l.put("textStyles", it) }
        val one = all.optJSONObject(area) ?: JSONObject().also { all.put(area, it) }
        block(one)
    }
    fun <T> cycle(options: List<T>, current: T): T = options[(options.indexOf(current) + 1) % options.size]
    fun colourName(v: Long?) = v?.let { c -> TEXT_COLOURS.firstOrNull { it.second == c }?.first ?: "#%06X".format(c and 0xFFFFFF) }
    fun colourOf(key: String): Long? = if (st.has(key) && !st.isNull(key)) st.optLong(key) else null
    fun nextColour(current: Long?): Long? {
        val i = TEXT_COLOURS.indexOfFirst { it.second == current }
        return if (i == TEXT_COLOURS.size - 1) null else TEXT_COLOURS[i + 1].second
    }
    val fontFiles = remember(PrismTv.changes) { PrismTvMedia.fontFiles(context) }
    val fonts = kotlin.collections.listOf<String?>(null, "sans", "serif", "mono", "cursive") + fontFiles.map { "file:$it" }
    // A sample in this kind of text, as it looks now.
    val sample = TEXT_AREAS.firstOrNull { it.first == area }?.second ?: area
    Column {
        val base = androidx.tv.material3.MaterialTheme.typography.headlineSmall
        Text(
            if (area == "Quote") "\u201CA quote you love\u201D" else sample,
            style = com.nuvio.tv.ui.components.prismTvStyled(base, area),
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        LazyColumn {
            item("font") {
                val current = st.optString("font", "").takeIf { it.isNotBlank() }
                SettingsActionRow(title = "Font", subtitle = "Press to try the next one", value = current?.removePrefix("file:")?.substringBeforeLast('.') ?: "The look's own", onClick = {
                    val list = if (current in fonts) fonts else fonts + current
                    val next = cycle(list, current)
                    change { if (next == null) it.remove("font") else it.put("font", next) }
                })
            }
            item("size") {
                val sizes = kotlin.collections.listOf(0.8, 0.9, 1.0, 1.15, 1.3, 1.5)
                val size = st.optDouble("size", 1.0).let { v -> sizes.minBy { kotlin.math.abs(it - v) } }
                SettingsActionRow(title = "Size", subtitle = null, value = "${(size * 100).toInt()}%", onClick = { change { it.put("size", cycle(sizes, size)) } })
            }
            item("weight") {
                val weights = kotlin.collections.listOf<Int?>(null, 300, 400, 500, 700, 900)
                val names = mapOf(null to "As designed", 300 to "Light", 400 to "Regular", 500 to "Medium", 700 to "Bold", 900 to "Black")
                val w = if (st.has("weight") && !st.isNull("weight")) st.optInt("weight") else null
                SettingsActionRow(title = "Thickness", subtitle = null, value = names[w] ?: "$w", onClick = {
                    val next = cycle(weights, w.takeIf { it in weights })
                    change { if (next == null) it.remove("weight") else it.put("weight", next) }
                })
            }
            item("italic") {
                val i: Boolean? = if (st.has("italic") && !st.isNull("italic")) st.optBoolean("italic") else null
                SettingsActionRow(title = "Slanted (italic)", subtitle = null, value = when (i) { null -> "As designed"; true -> "On"; false -> "Off" }, onClick = {
                    change { when (i) { null -> it.put("italic", true); true -> it.put("italic", false); false -> it.remove("italic") } }
                })
            }
            item("spacing") {
                val gaps = kotlin.collections.listOf(0.0, 0.05, 0.1, 0.2, -0.03)
                val g = st.optDouble("spacing", 0.0).let { v -> gaps.minBy { kotlin.math.abs(it - v) } }
                SettingsActionRow(title = "Space between letters", subtitle = null, value = if (g == 0.0) "Normal" else if (g < 0) "Tighter" else "Wider ${(g * 100).toInt()}", onClick = { change { it.put("spacing", cycle(gaps, g)) } })
            }
            item("underline") {
                SettingsToggleRow(title = "Underlined", subtitle = null, checked = st.optBoolean("underline", false), onToggle = { change { it.put("underline", !st.optBoolean("underline", false)) } })
            }
            item("strike") {
                SettingsToggleRow(title = "Line through", subtitle = null, checked = st.optBoolean("strike", false), onToggle = { change { it.put("strike", !st.optBoolean("strike", false)) } })
            }
            item("outline") {
                SettingsToggleRow(title = "Hollow outlined letters", subtitle = null, checked = st.optBoolean("outline", false), onToggle = { change { it.put("outline", !st.optBoolean("outline", false)) } })
            }
            item("effect") {
                val effects = kotlin.collections.listOf<String?>(null, "shadow", "glow")
                val e = st.optString("effect", "").takeIf { it.isNotBlank() }
                SettingsActionRow(title = "Shadow or glow", subtitle = null, value = when (e) { "shadow" -> "Shadow"; "glow" -> "Glow"; else -> "Neither" }, onClick = {
                    val next = cycle(effects, e)
                    change { if (next == null) it.remove("effect") else it.put("effect", next) }
                })
            }
            item("effectColour") {
                val c = colourOf("effectColor") ?: 0xFF000000L
                SettingsActionRow(title = "Shadow or glow colour", subtitle = null, value = colourName(c), onClick = { change { it.put("effectColor", nextColour(c) ?: TEXT_COLOURS.first().second) } })
            }
            item("colour") {
                val c = colourOf("colour")
                SettingsActionRow(title = "Colour", subtitle = "Over the app's own colour", value = colourName(c) ?: "The app's own", onClick = {
                    val next = nextColour(c)
                    change { if (next == null) { it.remove("colour"); it.remove("colour2") } else it.put("colour", next) }
                })
            }
            item("colour2") {
                val c = colourOf("colour2")
                SettingsActionRow(title = "Fades into", subtitle = "A second colour across the letters", value = colourName(c) ?: "No fade", enabled = colourOf("colour") != null, onClick = {
                    val next = nextColour(c)
                    change { if (next == null) it.remove("colour2") else it.put("colour2", next) }
                })
            }
            item("highlight") {
                val c = colourOf("highlight")
                SettingsActionRow(title = "Highlight behind it", subtitle = null, value = colourName(c) ?: "None", onClick = {
                    val next = nextColour(c)
                    change { if (next == null) it.remove("highlight") else it.put("highlight", next) }
                })
            }
            item("reset") {
                SettingsActionRow(title = "Back to the look's own", subtitle = "Clears everything set here for this kind of text", onClick = {
                    edit { l -> l.optJSONObject("textStyles")?.remove(area) }
                })
            }
        }
    }
}

// ---- 5. Posters and shapes ----

@Composable
private fun PrismTvPostersPage(context: Context, look: JSONObject, edit: ((JSONObject) -> Unit) -> Unit) {
    val p = look.optJSONObject("tvPoster")
    val width = p?.optInt("widthDp", 126) ?: 126
    val radius = p?.optInt("cornerRadiusDp", 12) ?: 12
    val labels = p?.optBoolean("labels", true) ?: true
    val landscape = p?.optBoolean("landscape", false) ?: false
    fun change(block: (JSONObject) -> Unit) {
        edit { l ->
            val one = l.optJSONObject("tvPoster")
                ?: JSONObject().put("widthDp", width).put("cornerRadiusDp", radius).put("labels", labels).put("landscape", landscape).also { l.put("tvPoster", it) }
            block(one)
        }
        PrismTv.applyColoursNow(context)
    }
    LazyColumn {
        item("info") {
            SettingsActionRow(title = "Kept with this look", subtitle = "Switching looks switches these too. Until one is changed here, the TV's own Layout settings are used", value = if (p == null) "TV's own" else "This look's", onClick = {})
        }
        item("width") {
            val widths = kotlin.collections.listOf(104, 116, 126, 140, 156, 172)
            val w = widths.minBy { kotlin.math.abs(it - width) }
            SettingsActionRow(title = "Poster size", subtitle = null, value = when (w) { 104 -> "Smallest"; 116 -> "Small"; 126 -> "Normal"; 140 -> "Large"; 156 -> "Larger"; else -> "Largest" }, onClick = {
                change { it.put("widthDp", widths[(widths.indexOf(w) + 1) % widths.size]) }
            })
        }
        item("radius") {
            val radii = kotlin.collections.listOf(0, 6, 12, 20, 28)
            val r = radii.minBy { kotlin.math.abs(it - radius) }
            SettingsActionRow(title = "Rounded corners", subtitle = null, value = when (r) { 0 -> "Square"; 6 -> "Slight"; 12 -> "Normal"; 20 -> "Round"; else -> "Very round" }, onClick = {
                change { it.put("cornerRadiusDp", radii[(radii.indexOf(r) + 1) % radii.size]) }
            })
        }
        item("labels") {
            SettingsToggleRow(title = "Names under posters", subtitle = null, checked = labels, onToggle = { change { it.put("labels", !labels) } })
        }
        item("landscape") {
            SettingsToggleRow(title = "Wide posters", subtitle = "Landscape pictures instead of tall posters, on the Modern Home layout", checked = landscape, onToggle = { change { it.put("landscape", !landscape) } })
        }
        if (p != null) {
            item("reset") {
                SettingsActionRow(title = "Use the TV's own settings", subtitle = "This look stops setting posters", onClick = { edit { it.remove("tvPoster") } })
            }
        }
    }
}

// ---- Profile photos ----

@Composable
private fun PrismTvProfilesPage(pick: (String, String, (String) -> Unit) -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val profiles by remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, com.nuvio.tv.ui.components.PrismTvEntryPoint::class.java)
            .profileManager().profiles
    }.collectAsState()
    val avatars = com.nuvio.tv.ui.components.PrismTvAvatars
    LazyColumn {
        if (profiles.isEmpty()) item("none") { SettingsActionRow(title = "No profiles yet", subtitle = "Add a profile first, then give it a photo", onClick = {}) }
        profiles.forEach { p ->
            item("p${p.id}") {
                val photos = avatars.photos(p.id)
                SettingsActionRow(
                    title = p.name,
                    subtitle = if (photos.size > 1) "${photos.size} photos kept" else null,
                    value = if (avatars.inUse(p.id) != null) "Own photo" else "Account picture",
                    onClick = {
                        val options = buildList {
                            add("Pick a new photo")
                            if (photos.size > 1 || (photos.size == 1 && avatars.inUse(p.id) == null)) add("Use a kept photo")
                            if (avatars.inUse(p.id) != null) add("Back to the account picture")
                            if (photos.isNotEmpty()) add("Delete a kept photo")
                        }
                        prismChoose(context, p.name, options) { which ->
                            when (options[which]) {
                                "Pick a new photo" -> pick("image/*", "picture") { name -> avatars.add(p.id, name); toast("${p.name} has a new photo") }
                                "Use a kept photo" -> prismChoose(context, "Use which photo?", photos.mapIndexed { i, f -> "Photo ${i + 1}" + if (f == avatars.inUse(p.id)) " (in use)" else "" }) { avatars.use(p.id, photos[it]) }
                                "Back to the account picture" -> avatars.use(p.id, null)
                                "Delete a kept photo" -> prismChoose(context, "Delete which photo?", photos.mapIndexed { i, f -> "Photo ${i + 1}" + if (f == avatars.inUse(p.id)) " (in use)" else "" }) { i ->
                                    prismConfirm(context, "Delete photo ${i + 1}?", "It goes from this TV.") { avatars.forget(p.id, photos[i]) }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

// ---- Save and share ----

@Composable
private fun PrismTvFilesPage(toast: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = com.nuvio.tv.ui.components.PrismTvBackup
    var then by remember { mutableStateOf<((ByteArray) -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val use = then ?: return@rememberLauncherForActivityResult
        then = null
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (bytes == null) toast("That file couldn't be opened") else use(bytes)
        }
    }
    fun open(use: (ByteArray) -> Unit) {
        then = use
        runCatching { picker.launch("*/*") }.onFailure {
            then = null
            toast("No file picker found. Install a file manager such as ES File Explorer, then try again.")
        }
    }
    LazyColumn {
        item("save") {
            SettingsActionRow(
                title = "Save a backup",
                subtitle = "Everything in Prism in one file: every look, font, picture, sound and profile photo (clips aren't included, they're too big). The phone and PC can restore it too",
                onClick = {
                    toast("Saving the backup…")
                    scope.launch {
                        val where = withContext(Dispatchers.IO) {
                            runCatching { backup.backup(context) }.getOrNull()?.let { backup.saveToDownloads(context, "Prism backup.prismbackup", it) }
                        }
                        toast(if (where != null) "Backup saved in $where" else "The backup couldn't be saved")
                    }
                },
            )
        }
        item("restore") {
            SettingsActionRow(
                title = "Restore a backup",
                subtitle = "A .prismbackup file from this TV, the phone or the PC. Everything in Prism here is replaced",
                onClick = {
                    open { bytes ->
                        if (!backup.isBackup(bytes)) {
                            toast("That file isn't a Prism backup")
                        } else {
                            prismConfirm(context, "Restore this backup?", "Everything in Prism on this TV is replaced by what's in the backup.") {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { runCatching { backup.restore(context, bytes) }.getOrDefault(false) }
                                    toast(if (ok) "Backup restored. Restart Prism to see the new opening." else "That backup couldn't be restored")
                                }
                            }
                        }
                    }
                },
            )
        }
        item("libSave") {
            SettingsActionRow(
                title = "Save library to a file",
                subtitle = "This profile's library as a .prismlib file, to add on another device",
                onClick = {
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { runCatching { backup.libraryFile(context) }.getOrNull() }
                        if (bytes == null) {
                            toast("The library is empty")
                        } else {
                            val where = withContext(Dispatchers.IO) { backup.saveToDownloads(context, "Prism-library.prismlib", bytes) }
                            toast(if (where != null) "Library saved in $where" else "The library couldn't be saved")
                        }
                    }
                },
            )
        }
        item("libAdd") {
            SettingsActionRow(
                title = "Add library from a file",
                subtitle = "A .prismlib file from the phone, PC or another TV. Titles already here are skipped",
                onClick = {
                    open { bytes ->
                        if (com.nuvio.tv.ui.components.PrismTvLibraryImport.isLibraryFile(bytes)) com.nuvio.tv.ui.components.PrismTvLibraryImport.import(context, bytes)
                        else toast("That file isn't a Prism library")
                    }
                },
            )
        }
        item("folder") {
            SettingsActionRow(
                title = "The Prism folder",
                subtitle = "Files saved here go to Download/Prism. Send Anywhere puts files it receives in Download, where the file picker finds them",
                onClick = {},
            )
        }
    }
}

// ---- 6. Home ----

/** Rows and show cards in the look's JSON, as on the phone: rows [{id, title, items [{id, type, name, poster, year}]}],
 *  showCards [{id, type, name}]. */
internal object PrismTvHome {
    fun rows(o: JSONObject): List<JSONObject> = buildList { o.optJSONArray("rows")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::add) } }
    fun cards(o: JSONObject): List<JSONObject> = buildList { o.optJSONArray("showCards")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::add) } }
    fun clips(o: JSONObject): List<JSONObject> = buildList { o.optJSONArray("clips")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::add) } }
    fun items(row: JSONObject): List<JSONObject> = buildList { row.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let(::add) } }

    /** Moves [from] by [by] places in a list, returning it as an array. */
    fun moved(list: List<JSONObject>, from: Int, by: Int): JSONArray {
        val m = list.toMutableList()
        val to = (from + by).coerceIn(0, m.size - 1)
        m.add(to, m.removeAt(from))
        return JSONArray(m)
    }
}

/** Searches Cinemeta for what's typed and offers the results to pick from. */
private fun prismFindTitle(context: Context, scope: kotlinx.coroutines.CoroutineScope, seriesOnly: Boolean, then: (com.nuvio.tv.ui.components.PrismTvRowItem) -> Unit) {
    prismAsk(context, if (seriesOnly) "Find a show" else "Find a movie or show", "") { query ->
        scope.launch {
            val found = withContext(Dispatchers.IO) { com.nuvio.tv.ui.components.PrismTvRows.search(query) }.filter { !seriesOnly || it.type == "series" }
            if (found.isEmpty()) {
                Toast.makeText(context, "Nothing found for \"$query\"", Toast.LENGTH_LONG).show()
                return@launch
            }
            prismChoose(context, "Pick one", found.map { "${it.name}${it.year?.let { y -> " ($y)" } ?: ""} · ${if (it.type == "series") "show" else "movie"}" }) { then(found[it]) }
        }
    }
}

@Composable
private fun PrismTvHomePage(look: JSONObject, edit: ((JSONObject) -> Unit) -> Unit, pick: (String, String, (String) -> Unit) -> Unit, toast: (String) -> Unit, openRow: (String) -> Unit, openCards: () -> Unit) {
    val context = LocalContext.current
    val rows = PrismTvHome.rows(look)
    LazyColumn {
        item("new") {
            SettingsActionRow(title = "New row", subtitle = "A row of your own picks, shown first on Home", onClick = {
                prismAsk(context, "Name of the row", "My favourites") { title ->
                    val id = PrismTv.newId()
                    edit { l -> (l.optJSONArray("rows") ?: JSONArray().also { l.put("rows", it) }).put(JSONObject().put("id", id).put("title", title).put("items", JSONArray())) }
                    openRow(id)
                }
            })
        }
        rows.forEach { r ->
            item(r.optString("id")) {
                SettingsActionRow(title = r.optString("title"), subtitle = null, value = "${PrismTvHome.items(r).size} titles", onClick = { openRow(r.optString("id")) })
            }
        }
        item("cards") {
            SettingsActionRow(title = "Show cards", subtitle = "The shows Play random episode picks from (in the side menu)", value = "${PrismTvHome.cards(look).size}", onClick = openCards)
        }
        val clips = PrismTvHome.clips(look)
        item("clipsOn") {
            SettingsToggleRow(
                title = "Hero clips",
                subtitle = "Short videos playing silently behind Home. Short MP4s work best (720p or 1080p, no sound needed).",
                checked = look.optBoolean("clipsOn", true),
                onToggle = { edit { it.put("clipsOn", !look.optBoolean("clipsOn", true)) } },
            )
        }
        item("clipsAdd") {
            SettingsActionRow(title = "Add a clip", subtitle = "From a USB stick, the Download folder or a file manager", value = "${clips.size} clips", onClick = {
                pick("video/*", "clip") { name ->
                    edit { l -> (l.optJSONArray("clips") ?: JSONArray().also { l.put("clips", it) }).put(JSONObject().put("file", name).put("name", "Clip ${clips.size + 1}")) }
                    toast("Clip added")
                }
            })
        }
        if (clips.size > 1) {
            item("clipsOrder") {
                SettingsToggleRow(
                    title = "Play clips in order",
                    subtitle = "Off shuffles them, each playing once before any plays again",
                    checked = look.optBoolean("clipsInOrder", false),
                    onToggle = { edit { it.put("clipsInOrder", !look.optBoolean("clipsInOrder", false)) } },
                )
            }
        }
        if (clips.size > 1) {
            item("clipsSort") {
                SettingsActionRow(title = "Sort…", subtitle = "Put the clips in order by name, reversed or shuffled", onClick = {
                    prismChoose(context, "Sort the clips", PRISM_SORTS) { which ->
                        edit { l -> l.put("clips", JSONArray(prismSorted(PrismTvHome.clips(l), which) { it.optString("name") })) }
                    }
                })
            }
        }
        clips.forEachIndexed { i, c ->
            item("clip$i") {
                SettingsActionRow(title = c.optString("name").ifBlank { "Clip ${i + 1}" }, subtitle = null, onClick = {
                    prismChoose(context, c.optString("name").ifBlank { "Clip ${i + 1}" }, listOf("Move earlier", "Move later", "Remove")) { which ->
                        edit { l ->
                            val list = PrismTvHome.clips(l)
                            l.put("clips", if (which == 2) JSONArray(list.filterIndexed { j, _ -> j != i }) else PrismTvHome.moved(list, i, if (which == 0) -1 else 1))
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun PrismTvRowPage(look: JSONObject, id: String, edit: ((JSONObject) -> Unit) -> Unit, onGone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = PrismTvHome.rows(look)
    val index = rows.indexOfFirst { it.optString("id") == id }
    val row = rows.getOrNull(index) ?: return
    val items = PrismTvHome.items(row)
    fun changeRow(block: (JSONObject) -> Unit) = edit { l ->
        PrismTvHome.rows(l).firstOrNull { it.optString("id") == id }?.let(block)
    }
    LazyColumn {
        item("add") {
            SettingsActionRow(title = "Add a movie or show", subtitle = "Type part of its name", onClick = {
                prismFindTitle(context, scope, seriesOnly = false) { t ->
                    changeRow { r ->
                        (r.optJSONArray("items") ?: JSONArray().also { r.put("items", it) }).put(
                            JSONObject().put("id", t.id).put("type", t.type).put("name", t.name).put("poster", t.poster ?: JSONObject.NULL).put("year", t.year ?: JSONObject.NULL)
                        )
                    }
                }
            })
        }
        item("rename") {
            SettingsActionRow(title = "Rename the row", subtitle = null, value = row.optString("title"), onClick = {
                prismAsk(context, "Name of the row", row.optString("title")) { t -> changeRow { it.put("title", t) } }
            })
        }
        if (rows.size > 1) {
            item("move") {
                SettingsActionRow(title = "Move the row", subtitle = "Rows show on Home in this order", value = "${index + 1} of ${rows.size}", onClick = {
                    prismChoose(context, "Move the row", listOf("Higher", "Lower")) { which ->
                        edit { l -> l.put("rows", PrismTvHome.moved(PrismTvHome.rows(l), index, if (which == 0) -1 else 1)) }
                    }
                })
            }
        }
        item("delete") {
            SettingsActionRow(title = "Delete the row", subtitle = null, onClick = {
                prismConfirm(context, "Delete \"${row.optString("title")}\"?", "The movies and shows stay in Prism; only this row goes.") {
                    edit { l -> l.put("rows", JSONArray(PrismTvHome.rows(l).filter { it.optString("id") != id })) }
                    onGone()
                }
            })
        }
        if (items.size > 1) {
            item("sort") {
                SettingsActionRow(title = "Sort…", subtitle = "By name, by year, reversed or shuffled", onClick = {
                    prismChoose(context, "Sort the row", PRISM_SORTS + listOf("Newest year first", "Oldest year first")) { which ->
                        changeRow { r ->
                            val list = PrismTvHome.items(r)
                            fun year(o: JSONObject) = o.optString("year").take(4).toIntOrNull()
                            r.put("items", JSONArray(when (which) {
                                4 -> list.sortedByDescending { year(it) ?: 0 }
                                5 -> list.sortedBy { year(it) ?: 9999 }
                                else -> prismSorted(list, which) { it.optString("name") }
                            }))
                        }
                    }
                })
            }
        }
        items.forEachIndexed { i, t ->
            item("t$i") {
                SettingsActionRow(title = t.optString("name"), subtitle = t.optString("year").takeIf { it.isNotBlank() && it != "null" }, value = if (t.optString("type") == "series") "Show" else "Movie", onClick = {
                    prismChoose(context, t.optString("name"), listOf("Move earlier", "Move later", "Remove")) { which ->
                        changeRow { r ->
                            val list = PrismTvHome.items(r)
                            r.put("items", if (which == 2) JSONArray(list.filterIndexed { j, _ -> j != i }) else PrismTvHome.moved(list, i, if (which == 0) -1 else 1))
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun PrismTvCardsPage(look: JSONObject, edit: ((JSONObject) -> Unit) -> Unit, openSound: (String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cards = PrismTvHome.cards(look)
    LazyColumn {
        item("add") {
            SettingsActionRow(title = "Add a show", subtitle = "Play random episode picks one of these shows", onClick = {
                prismFindTitle(context, scope, seriesOnly = true) { t ->
                    edit { l ->
                        if (PrismTvHome.cards(l).isEmpty()) l.remove("showCard")
                        (l.optJSONArray("showCards") ?: JSONArray().also { l.put("showCards", it) }).put(JSONObject().put("id", t.id).put("type", t.type).put("name", t.name).put("poster", t.poster ?: JSONObject.NULL).put("year", t.year ?: JSONObject.NULL))
                    }
                }
            })
        }
        if (cards.size > 1) {
            item("sort") {
                SettingsActionRow(title = "Sort…", subtitle = "Put the shows in order by name, reversed or shuffled", onClick = {
                    prismChoose(context, "Sort the shows", PRISM_SORTS) { which ->
                        edit { l ->
                            l.remove("showCard")
                            l.put("showCards", JSONArray(prismSorted(PrismTvHome.cards(l), which) { it.optString("name") }))
                        }
                    }
                })
            }
        }
        cards.forEachIndexed { i, c ->
            item("c$i") {
                val soundKey = "card:${c.optString("type", "series")}:${c.optString("id")}"
                val ownSounds = PrismTvMedia.soundKeys(look, soundKey).size
                SettingsActionRow(title = c.optString("name"), subtitle = if (ownSounds > 0) "Its own sound plays first" else "Plays the Play random episode sound", onClick = {
                    prismChoose(context, c.optString("name"), listOf("Move earlier", "Move later", "Remove", "Its own sound")) { which ->
                        if (which == 3) { openSound(soundKey, "Sound for " + c.optString("name")); return@prismChoose }
                        edit { l ->
                            val list = PrismTvHome.cards(l)
                            l.remove("showCard")
                            l.put("showCards", if (which == 2) JSONArray(list.filterIndexed { j, _ -> j != i }) else PrismTvHome.moved(list, i, if (which == 0) -1 else 1))
                        }
                    }
                })
            }
        }
    }
}

// ---- 8. Quotes ----

@Composable
private fun PrismTvQuotesPage(context: Context, look: JSONObject, edit: ((JSONObject) -> Unit) -> Unit) {
    val quotes = buildList { look.optJSONArray("quotes")?.let { a -> for (i in 0 until a.length()) add(a.optString(i)) } }
    fun change(block: (MutableList<String>) -> Unit) = edit { l ->
        val list = buildList { l.optJSONArray("quotes")?.let { a -> for (i in 0 until a.length()) add(a.optString(i)) } }.toMutableList()
        block(list)
        l.put("quotes", JSONArray(list))
    }
    LazyColumn {
        item("add") {
            SettingsActionRow(title = "Add a quote", subtitle = "Typed with the TV's keyboard (or a phone remote app)", onClick = {
                prismAsk(context, "New quote", "") { q -> change { it.add(q) } }
            })
        }
        if (quotes.size > 1) {
            item("sort") {
                SettingsActionRow(title = "Sort…", subtitle = "Put the quotes in order A to Z, reversed or shuffled", onClick = {
                    prismChoose(context, "Sort the quotes", PRISM_SORTS) { which ->
                        change { list -> val sorted = prismSorted(list.toList(), which) { it }; list.clear(); list.addAll(sorted) }
                    }
                })
            }
        }
        quotes.forEachIndexed { i, q ->
            item("q$i") {
                SettingsActionRow(title = q, subtitle = null, onClick = {
                    prismChoose(context, "Quote", listOf("Change it", "Remove it")) { which ->
                        if (which == 0) prismAsk(context, "Quote", q) { new -> change { it[i] = new } }
                        else change { it.removeAt(i) }
                    }
                })
            }
        }
    }
}

// ---- 9. Sounds ----

@Composable
private fun PrismTvMomentPage(
    look: JSONObject,
    moment: String,
    label: String,
    edit: ((JSONObject) -> Unit) -> Unit,
    pick: (String, String, (String) -> Unit) -> Unit,
    toast: (String) -> Unit,
    openTrim: (String) -> Unit,
) {
    val context = LocalContext.current
    val keys = PrismTvMedia.soundKeys(look, moment)
    val chosen = look.optJSONObject("soundChoice")?.optString(moment, "")?.takeIf { it.isNotEmpty() }
    val sounds = look.optJSONObject("sounds")
    LazyColumn {
        item("add") {
            SettingsActionRow(title = "Add a sound", subtitle = "An MP3, WAV, OGG or M4A file; trim it after", onClick = {
                pick("audio/*", "sound") { name ->
                    edit { l ->
                        val all = l.optJSONObject("sounds") ?: JSONObject().also { l.put("sounds", it) }
                        val key = if (all.has(moment)) "$moment#${PrismTv.newId()}" else moment
                        all.put(key, JSONObject().put("file", name).put("label", "Sound").put("enabled", true).put("volume", 1.0))
                    }
                    toast("Added. Press it to trim it or set its volume")
                }
            })
        }
        if (keys.size > 1) {
            item("random") {
                SettingsToggleRow(
                    title = "A different one each time",
                    subtitle = "Off plays the one picked below every time",
                    checked = chosen == null,
                    onToggle = {
                        edit { l ->
                            val choice = l.optJSONObject("soundChoice") ?: JSONObject().also { l.put("soundChoice", it) }
                            if (chosen == null) choice.put(moment, keys.first()) else choice.remove(moment)
                        }
                    },
                )
            }
        }
        keys.forEachIndexed { i, key ->
            item(key) {
                val s = sounds?.optJSONObject(key) ?: return@item
                val volume = (s.optDouble("volume", 1.0) * 100).toInt()
                val trimmed = s.optInt("endMs", 0) > 0
                SettingsActionRow(
                    title = s.optString("label", "Sound").ifBlank { "Sound" } + " ${i + 1}",
                    subtitle = listOfNotNull(
                        if (chosen == key) "Plays every time" else null,
                        if (!s.optBoolean("enabled", true)) "Switched off" else null,
                        if (trimmed) "Trimmed" else null,
                    ).joinToString(" · ").ifBlank { null },
                    value = "$volume%",
                    onClick = {
                        val options = listOf("Play it", "Trim it", "Volume: next step", "Play this one every time", if (s.optBoolean("enabled", true)) "Switch it off" else "Switch it on", "Remove")
                        prismChoose(context, "$label ${i + 1}", options) { which ->
                            when (which) {
                                0 -> PrismTvSounds.playSound(PrismTvMedia.sound(s))
                                1 -> openTrim(key)
                                2 -> {
                                    val steps = listOf(25, 50, 75, 100)
                                    val next = steps.firstOrNull { it > volume } ?: steps.first()
                                    edit { l -> l.optJSONObject("sounds")?.optJSONObject(key)?.put("volume", next / 100.0) }
                                    PrismTvSounds.playSound(PrismTvMedia.sound(s, volume = next / 100f))
                                }
                                3 -> edit { l -> (l.optJSONObject("soundChoice") ?: JSONObject().also { l.put("soundChoice", it) }).put(moment, key) }
                                4 -> edit { l -> l.optJSONObject("sounds")?.optJSONObject(key)?.put("enabled", !s.optBoolean("enabled", true)) }
                                5 -> edit { l ->
                                    l.optJSONObject("sounds")?.remove(key)
                                    if (l.optJSONObject("soundChoice")?.optString(moment) == key) l.optJSONObject("soundChoice")?.remove(moment)
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Sets where a sound starts and ends: left and right move the start or end (the row in focus), OK plays it. */
@Composable
private fun PrismTvTrimEditor(look: JSONObject, key: String, onDone: (Int, Int) -> Unit) {
    val s = look.optJSONObject("sounds")?.optJSONObject(key) ?: return
    val length = remember(key) { PrismTvMedia.durationMs(s.optString("file")) }
    var start by remember { mutableStateOf(s.optInt("startMs", 0)) }
    var end by remember { mutableStateOf(s.optInt("endMs", 0).takeIf { it > 0 } ?: length) }
    var editingEnd by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun play() = PrismTvSounds.playSound(PrismTvMedia.sound(s, start = start, end = end))
    fun secs(ms: Int) = "%.1f s".format(ms / 1000f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) {
                    if (e.type == KeyEventType.KeyUp) {
                        PrismTvSounds.stop()
                        onDone(start, if (end >= length) 0 else end)
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp, Key.DirectionDown -> { editingEnd = !editingEnd; true }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val d = if (e.key == Key.DirectionLeft) -100 else 100
                        if (editingEnd) end = (end + d).coerceIn(start + 200, length) else start = (start + d).coerceIn(0, end - 200)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { play(); true }
                    else -> false
                }
            },
    ) {
        Text("Trim the sound", color = NuvioTheme.colors.TextPrimary, fontSize = 28.sp)
        Text("Up and down pick the start or the end · left and right move it a tenth of a second · OK plays it · Back keeps it", color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(32.dp))
        Text((if (!editingEnd) "▶ " else "   ") + "Starts at ${secs(start)}", color = if (!editingEnd) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary, fontSize = 24.sp)
        Spacer(Modifier.height(12.dp))
        Text((if (editingEnd) "▶ " else "   ") + "Ends at ${secs(end)}", color = if (editingEnd) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary, fontSize = 24.sp)
        Spacer(Modifier.height(24.dp))
        // The whole sound, with the part kept lit.
        Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            val total = length.coerceAtLeast(1).toFloat()
            drawRect(Color.White.copy(alpha = 0.15f), size = this.size)
            drawRect(Color.White.copy(alpha = 0.7f), Offset(this.size.width * start / total, 0f), Size(this.size.width * (end - start) / total, this.size.height))
        }
        Spacer(Modifier.height(8.dp))
        Text("Plays ${secs(end - start)} of ${secs(length)}", color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp)
    }
}

// ---- Small dialogs (the TV's own, which work with the remote) ----

/** "Sort…" for Customise's lists, as on the phone: each choice puts the list in that order once. */
private val PRISM_SORTS = listOf("A to Z", "Z to A", "Reverse the order", "Shuffle")

private fun <T> prismSorted(list: List<T>, which: Int, name: (T) -> String): List<T> = when (which) {
    0 -> list.sortedBy { name(it).lowercase() }
    1 -> list.sortedByDescending { name(it).lowercase() }
    2 -> list.reversed()
    else -> list.shuffled()
}

internal fun prismChoose(context: Context, title: String, options: List<String>, then: (Int) -> Unit) {
    android.app.AlertDialog.Builder(context)
        .setTitle(title)
        .setItems(options.toTypedArray()) { _, which -> then(which) }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun prismAsk(context: Context, title: String, start: String, then: (String) -> Unit) {
    val box = android.widget.EditText(context).apply { setText(start) }
    android.app.AlertDialog.Builder(context)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("Save") { _, _ -> box.text.toString().trim().takeIf { it.isNotEmpty() }?.let(then) }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun prismConfirm(context: Context, title: String, message: String, then: () -> Unit) {
    android.app.AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("Yes") { _, _ -> then() }
        .setNegativeButton("No", null)
        .show()
}

/** Files and look changes for Customise on the TV. */
internal object PrismTvMedia {
    /** The picked file's own name, as the file manager shows it. */
    fun shownName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    fun copyIn(context: Context, uri: Uri, kind: String): String? = runCatching {
        val shown = shownName(context, uri)
        val ext = shown?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..4 }
            ?: when (kind) { "picture" -> "jpg"; "sound" -> "mp3"; "clip" -> "mp4"; else -> "ttf" }
        val name = PrismTv.newId() + "." + ext
        val out = File(PrismTv.mediaDir(context), name)
        // Streamed, so a long clip never has to fit in memory.
        val copied = context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        if (copied == null) { out.delete(); return null }
        name
    }.getOrNull()

    private fun crop(c: JSONObject?): PrismTvCrop? = c?.let {
        PrismTvCrop(it.optDouble("left", 0.0).toFloat(), it.optDouble("top", 0.0).toFloat(), it.optDouble("right", 1.0).toFloat(), it.optDouble("bottom", 1.0).toFloat(), it.optBoolean("whole", false))
    }

    /** All of a slot's pictures, the older single one first. */
    fun pictures(o: JSONObject, slot: Slot): List<PrismTvPicture> = buildList {
        o.optJSONObject(slot.single)?.let { p -> p.optString("file").takeIf { it.isNotBlank() }?.let { add(PrismTvPicture(it, crop(p.optJSONObject("wide")))) } }
        o.optJSONArray(slot.list)?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { p -> p.optString("file").takeIf { it.isNotBlank() }?.let { add(PrismTvPicture(it, crop(p.optJSONObject("wide")))) } }
        }
    }

    /** Moves the older single picture into the slot's list, so every picture is edited the same way. */
    private fun slotList(o: JSONObject, slot: Slot): JSONArray {
        val list = o.optJSONArray(slot.list) ?: JSONArray().also { o.put(slot.list, it) }
        o.optJSONObject(slot.single)?.let { single ->
            val merged = JSONArray().put(single)
            for (i in 0 until list.length()) merged.put(list.get(i))
            o.remove(slot.single)
            o.put(slot.list, merged)
            return merged
        }
        return list
    }

    fun addPicture(o: JSONObject, slot: Slot, file: String) {
        slotList(o, slot).put(JSONObject().put("file", file))
    }

    fun removePicture(o: JSONObject, slot: Slot, file: String) {
        val list = slotList(o, slot)
        val kept = JSONArray()
        for (i in 0 until list.length()) list.optJSONObject(i)?.takeIf { it.optString("file") != file }?.let { kept.put(it) }
        o.put(slot.list, kept)
        if (o.optJSONObject("pictureChoice")?.optString(slot.single) == file) o.optJSONObject("pictureChoice")?.remove(slot.single)
    }

    fun setCrop(o: JSONObject, slot: Slot, file: String, crop: PrismTvCrop?) {
        val list = slotList(o, slot)
        for (i in 0 until list.length()) list.optJSONObject(i)?.takeIf { it.optString("file") == file }?.let { p ->
            if (crop == null) p.remove("wide") else p.put(
                "wide",
                JSONObject().put("left", crop.left.toDouble()).put("top", crop.top.toDouble()).put("right", crop.right.toDouble())
                    .put("bottom", crop.bottom.toDouble()).put("whole", crop.whole),
            )
        }
    }

    fun soundKeys(o: JSONObject, moment: String): List<String> =
        o.optJSONObject("sounds")?.keys()?.asSequence()?.filter { it == moment || it.startsWith("$moment#") }?.toList().orEmpty()

    fun sound(s: JSONObject, volume: Float? = null, start: Int? = null, end: Int? = null) = PrismTvSound(
        s.optString("file"),
        true,
        volume ?: s.optDouble("volume", 1.0).toFloat(),
        start ?: s.optInt("startMs", 0),
        end ?: s.optInt("endMs", 0),
    )

    fun fontFiles(context: Context): List<String> =
        PrismTv.mediaDir(context).listFiles()?.map { it.name }?.filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }?.sorted().orEmpty()

    fun durationMs(file: String): Int = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(PrismTv.mediaFile(file)?.path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        } finally {
            r.release()
        }
    }.getOrDefault(0).coerceAtLeast(1000)
}
