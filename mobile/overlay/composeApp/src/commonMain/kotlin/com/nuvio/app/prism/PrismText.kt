package com.nuvio.app.prism

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.app.core.ui.Chip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import kotlin.math.roundToInt

// Prism: Customise > Text. Fonts for headings and everyday text, fonts added from files, text size, and each
// kind of text's own font, size, weight, slant and spacing.

/** One choice, written in its own font so you can see it before picking. */
@Composable
private fun FontChoice(label: String, key: String?, selected: Boolean, removing: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(10.dp)
    Text(
        text = if (removing) "✕  $label" else label,
        fontFamily = PrismFonts.family(key),
        fontSize = 15.sp,
        color = if (selected) tokens.colors.onAccent else tokens.colors.textPrimary,
        modifier = Modifier
            .clip(shape)
            .then(if (selected) Modifier.background(tokens.colors.accent) else Modifier.border(BorderStroke(1.dp, tokens.colors.textSecondary.copy(alpha = 0.4f)), shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FontPicker(title: String, current: String?, removing: Boolean, onPick: (String?) -> Unit, onRemove: (PrismFont) -> Unit, defaultLabel: String = "Nuvio") {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!removing) {
                FontChoice(defaultLabel, null, current == null, false) { onPick(null) }
                PrismFonts.builtIn.forEach { (key, label) ->
                    FontChoice(label, key, current == key, false) { onPick(key) }
                }
            }
            PrismStore.fonts.forEach { font ->
                FontChoice(font.name, font.key, !removing && current == font.key, removing) {
                    if (removing) onRemove(font) else onPick(font.key)
                }
            }
        }
    }
}

@Composable
internal fun PrismTextSettings(look: PrismLook, onAddFont: () -> Unit, onTextPicture: () -> Unit, onMessage: (String) -> Unit) {
    val tokens = MaterialTheme.nuvio
    var removing by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (removing) {
            Text("Tap a font to remove it from this device.", color = tokens.colors.textSecondary, fontSize = 13.sp)
            FontPicker("Added fonts", null, true, {}, onRemove = { PrismStore.removeFont(it) })
        } else {
            FontPicker("Headings", look.titleFont, false, { key -> PrismStore.update { it.copy(titleFont = key) } }, {})
            FontPicker("Everyday text", look.textFont, false, { key -> PrismStore.update { it.copy(textFont = key) } }, {})
            Text(
                "Add your own .ttf or .otf fonts. Each file is one style, so add the regular one for everyday text; bold is drawn from it.",
                color = tokens.colors.textSecondary,
                fontSize = 13.sp,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!removing) PrismButton("Add a font file", onClick = onAddFont)
            if (!removing) PrismButton(if (browsing) "Hide this device's fonts" else "Fonts on this device") { browsing = !browsing }
            if (PrismStore.fonts.isNotEmpty() || removing) {
                PrismButton(if (removing) "Done" else "Remove fonts") { removing = !removing }
            }
            if (!removing) PrismButton("Make a text picture…", onClick = onTextPicture)
        }
        if (browsing && !removing) DeviceFonts(onMessage)
        if (!removing) {
            var size by remember(look.id) { mutableStateOf(look.textSize) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Text size: ${(size * 100).roundToInt()}%", color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("A", fontSize = 13.sp, color = tokens.colors.textSecondary, modifier = Modifier.width(20.dp))
                    Slider(
                        value = size,
                        onValueChange = { size = it },
                        // Applied when you let go, so the screen doesn't jump while you slide.
                        onValueChangeFinished = { PrismStore.update { it.copy(textSize = (size * 20).roundToInt() / 20f) } },
                        valueRange = 0.8f..1.5f,
                        steps = 13,
                        modifier = Modifier.weight(1f),
                    )
                    Text("A", fontSize = 22.sp, color = tokens.colors.textSecondary, modifier = Modifier.padding(start = 8.dp))
                }
            }
            PrismTextAreas(look)
        }
    }
}

private val WEIGHTS = listOf(null to "As designed", 300 to "Light", 400 to "Regular", 500 to "Medium", 700 to "Bold", 900 to "Heavy")
private val SLANTS = listOf(null to "As designed", false to "Upright", true to "Italic")

/** A slider that saves when you let go; [label] shows the value while sliding. */
@Composable
private fun StyleSlider(key: Any, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, label: (Float) -> String, onDone: (Float) -> Unit) {
    val tokens = MaterialTheme.nuvio
    var v by remember(key, value) { mutableStateOf(value) }
    Column {
        Text(label(v), color = tokens.colors.textPrimary, fontSize = 14.sp)
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onDone(v) }, valueRange = range, steps = steps)
    }
}

/** Each kind of text (titles, headings, buttons, quotes...) with its own font and font options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrismTextAreas(look: PrismLook) {
    val tokens = MaterialTheme.nuvio
    var area by remember { mutableStateOf(PrismTextArea.Title) }
    val st = look.textStyle(area)
    fun set(change: (PrismTextStyle) -> PrismTextStyle) = PrismStore.update { l ->
        val next = change(l.textStyle(area))
        l.copy(textStyles = if (next == PrismTextStyle()) l.textStyles - area.name else l.textStyles + (area.name to next))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Each kind of text", color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(
            "Give any kind of text its own font, size, weight, slant and spacing. Anything left alone follows the fonts above. A dot marks the ones you've changed.",
            color = tokens.colors.textMuted,
            fontSize = 12.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismTextArea.entries.forEach { a ->
                Chip(label = a.label + if (look.textStyles.containsKey(a.name)) " •" else "", selected = a == area, onClick = { area = a })
            }
        }
        Text(area.description, color = tokens.colors.textSecondary, fontSize = 13.sp)
        // The part of the app being changed, drawn with the app's own text so it shows exactly what you'll get.
        PrismAreaPreview(area, look)
        PrismButton("Peek at the app") { PrismCustomise.peek("Fonts") }
        FontPicker(
            "Font",
            st.font,
            false,
            { key -> set { it.copy(font = key) } },
            {},
            defaultLabel = if (area.heading) "Same as headings" else "Same as everyday text",
        )
        StyleSlider(area, st.size, 0.6f..2f, 27, { "Size: ${(it * 100).roundToInt()}%" }) { v -> set { it.copy(size = (v * 20).roundToInt() / 20f) } }
        Text("Weight", color = tokens.colors.textPrimary, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WEIGHTS.forEach { (w, label) -> Chip(label = label, selected = st.weight == w, onClick = { set { it.copy(weight = w) } }) }
        }
        Text("Slant", color = tokens.colors.textPrimary, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SLANTS.forEach { (i, label) -> Chip(label = label, selected = st.italic == i, onClick = { set { it.copy(italic = i) } }) }
        }
        StyleSlider(area, st.spacing, -0.05f..0.3f, 6, {
            when {
                it < -0.01f -> "Letter spacing: tighter"
                it < 0.01f -> "Letter spacing: normal"
                else -> "Letter spacing: wider (${(it * 100).roundToInt()})"
            }
        }) { v -> set { it.copy(spacing = (v * 20).roundToInt() / 20f) } }
        Text("Lines", color = tokens.colors.textPrimary, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = "Underline", selected = st.underline, onClick = { set { it.copy(underline = !it.underline) } })
            Chip(label = "Strike-through", selected = st.strike, onClick = { set { it.copy(strike = !it.strike) } })
            Chip(label = "Hollow outline", selected = st.outline, onClick = { set { it.copy(outline = !it.outline) } })
        }
        Text("Shadow or glow", color = tokens.colors.textPrimary, fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "None", "shadow" to "Shadow", "glow" to "Glow").forEach { (e, label) ->
                Chip(label = label, selected = st.effect == e, onClick = { set { it.copy(effect = e) } })
            }
        }
        if (st.effect != null) PrismColourRow(st.effectColor, allowNone = false) { c -> set { it.copy(effectColor = c ?: 0xFF000000) } }
        Text("Colour", color = tokens.colors.textPrimary, fontSize = 14.sp)
        PrismColourRow(st.colour, allowNone = true) { c -> set { it.copy(colour = c, colour2 = if (c == null) null else it.colour2) } }
        if (st.colour != null) {
            Text("Fade into a second colour (optional)", color = tokens.colors.textPrimary, fontSize = 14.sp)
            PrismColourRow(st.colour2, allowNone = true) { c -> set { it.copy(colour2 = c) } }
        }
        Text("Highlight behind the text", color = tokens.colors.textPrimary, fontSize = 14.sp)
        PrismColourRow(st.highlight, allowNone = true, translucent = true) { c -> set { it.copy(highlight = c) } }
        Text(
            "Colour None keeps the colours from 2. Colours.",
            color = tokens.colors.textMuted,
            fontSize = 12.sp,
        )
        if (look.textStyles.containsKey(area.name)) {
            PrismButton("Reset ${area.label.lowercase()}") { set { PrismTextStyle() } }
        }
    }
}

/** Colours to pick from, ARGB. */
internal val PRISM_SWATCHES = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFB79CFF, 0xFF6C4BFF, 0xFF7FD4FF, 0xFF2B7CFF, 0xFF7CFFB2, 0xFF2EA84F,
    0xFFFFE07A, 0xFFD4A537, 0xFFFFA45C, 0xFFFF6B8B, 0xFFD72638, 0xFFE0C097, 0xFF8A8A8A, 0xFF3A3A3A,
)

/** "#RRGGBB" or "#AARRGGBB" as ARGB, or null if it isn't one. */
internal fun parseHexColour(text: String): Long? {
    val hex = text.trim().removePrefix("#")
    val value = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        6 -> 0xFF000000 or value
        8 -> value
        else -> null
    }
}

internal fun hexOf(colour: Long): String = "#" + (colour and 0xFFFFFFFF).toString(16).padStart(8, '0').uppercase().removePrefix("FF")

/** A row of colour dots plus a box for any colour as hex; [translucent] softens picked dots (for highlights). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrismColourRow(selected: Long?, allowNone: Boolean, translucent: Boolean = false, onPick: (Long?) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (allowNone) Chip(label = "None", selected = selected == null, onClick = { onPick(null) })
            PRISM_SWATCHES.forEach { swatch ->
                val colour = if (translucent) (swatch and 0x00FFFFFFL) or 0x99000000L else swatch
                val picked = selected == colour
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(colour.toInt()))
                        .border(BorderStroke(if (picked) 3.dp else 1.dp, if (picked) tokens.colors.accent else tokens.colors.textSecondary.copy(alpha = 0.4f)), CircleShape)
                        .clickable { onPick(colour) },
                )
            }
        }
        var hex by remember(selected) { mutableStateOf(selected?.let(::hexOf) ?: "") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hex,
                onValueChange = { hex = it.take(9) },
                singleLine = true,
                label = { Text("Any colour, like #FFAA00") },
                modifier = Modifier.weight(1f),
            )
            PrismButton("Use", enabled = parseHexColour(hex) != null) { onPick(parseHexColour(hex)) }
        }
    }
}

/** The fonts installed on this device; picking one copies it into Prism so looks, backups and the TV can use it. */
@Composable
private fun DeviceFonts(onMessage: (String) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val platform = Prism.platform ?: return
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<PrismFolderFile>?>(null) }
    var filter by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf<PrismFolderFile?>(null) }
    previewing?.let { f -> PrismPreviewDialog(f.ref, f.name) { previewing = null } }
    LaunchedEffect(Unit) { all = runCatching { platform.deviceFonts() }.getOrDefault(emptyList()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val list = all
        when {
            list == null -> Text("Looking for fonts…", color = tokens.colors.textSecondary, fontSize = 13.sp)
            list.isEmpty() -> Text("No font files could be found on this device.", color = tokens.colors.textSecondary, fontSize = 13.sp)
            else -> {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { Text("Search ${list.size} fonts") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val shown = list.filter { it.name.contains(filter.trim(), ignoreCase = true) }
                shown.take(40).forEach { font ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrismThumb(font.ref, font.name, width = 48.dp, height = 40.dp, onClick = { previewing = font })
                        Text(PrismFonts.nameFor(font.name), color = tokens.colors.textPrimary, modifier = Modifier.weight(1f).clickable { previewing = font })
                        PrismButton("Add") {
                            scope.launch {
                                val bytes = platform.readBytes(font.ref)
                                val extension = font.name.substringAfterLast('.', "ttf").lowercase()
                                if (bytes == null) {
                                    onMessage("That font couldn't be read")
                                    return@launch
                                }
                                val added = PrismStore.addFont(PrismFonts.nameFor(font.name), bytes, extension)
                                if (PrismFonts.canLoad(added.file)) {
                                    onMessage("Added ${added.name}. Pick it above.")
                                } else {
                                    PrismStore.removeFont(added)
                                    onMessage("That font couldn't be used")
                                }
                            }
                        }
                    }
                }
                if (shown.size > 40) Text("Showing 40 of ${shown.size}. Search to find others.", color = tokens.colors.textMuted, fontSize = 12.sp)
            }
        }
    }
}
