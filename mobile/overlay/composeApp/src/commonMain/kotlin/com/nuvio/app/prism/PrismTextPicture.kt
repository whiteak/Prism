package com.nuvio.app.prism

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.nuvio
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// Prism: Customise > Fonts > Make a text picture. Type some words, style them (font, colour, background, bold,
// italic, lines, capitals, spacing, shadow, glow, outline), give single words or parts their own colour, font,
// size and style, and keep the result as a logo or save it as a PNG.

/** Styling for some of the words, [start] to [end] (characters). Spans never overlap. Null or 1 = as the rest. */
private data class ArtSpan(
    val start: Int,
    val end: Int,
    val colour: Long? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val size: Float = 1f,
    val font: String? = null,
)

/** [spans] with [change] made to everything from [s] to [e], splitting spans at the edges. */
private fun List<ArtSpan>.restyle(s: Int, e: Int, change: ((ArtSpan) -> ArtSpan)?): List<ArtSpan> {
    val cut = flatMap { sp ->
        (listOf(sp.start, sp.end) + listOf(s, e).filter { it in sp.start..sp.end }).distinct().sorted()
            .zipWithNext { a, b -> sp.copy(start = a, end = b) }
    }.filter { it.end > it.start }
    val (inside, outside) = cut.partition { it.start >= s && it.end <= e }
    if (change == null) return outside
    val gaps = mutableListOf<ArtSpan>()
    var pos = s
    inside.sortedBy { it.start }.forEach { c ->
        if (c.start > pos) gaps += change(ArtSpan(pos, c.start))
        pos = maxOf(pos, c.end)
    }
    if (pos < e) gaps += change(ArtSpan(pos, e))
    return (outside + inside.map(change) + gaps).sortedBy { it.start }
}

/** Keeps [spans] on the same words when the text changes from [old] to [new]. */
private fun List<ArtSpan>.follow(old: String, new: String): List<ArtSpan> {
    val prefix = old.commonPrefixWith(new).length
    val suffix = minOf(old.length - prefix, new.length - prefix).let { room -> old.reversed().commonPrefixWith(new.reversed()).length.coerceAtMost(room) }
    val changedEnd = old.length - suffix
    val delta = new.length - old.length
    fun move(i: Int) = when {
        i >= changedEnd -> i + delta
        i > prefix -> prefix
        else -> i
    }
    return mapNotNull { sp -> sp.copy(start = move(sp.start), end = move(sp.end)).takeIf { it.end > it.start } }
}

private data class TextArt(
    val text: String = "PRISM",
    val font: String? = "sans",
    val size: Float = 64f,
    val colour: Long = 0xFFFFFFFF,
    val background: Long? = null,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val caps: Boolean = false,
    val spacing: Float = 0f,
    /** "shadow" or "glow", or null. */
    val effect: String? = null,
    val effectColour: Long = 0xFFB79CFF,
    val outline: Boolean = false,
    val outlineColour: Long = 0xFF000000,
    val spans: List<ArtSpan> = emptyList(),
)

/**
 * The words with their own styling, for the picture ([withColours] false for the outline underneath).
 * [inField] is for the typing box: the text exactly as typed (so places line up), without size changes.
 */
private fun TextArt.annotated(withColours: Boolean = true, inField: Boolean = false): AnnotatedString {
    val text = if (inField) text else (if (caps) text.uppercase() else text).ifEmpty { " " }
    return buildAnnotatedString {
        append(text)
        spans.forEach { sp ->
            val end = sp.end.coerceAtMost(text.length)
            if (sp.start >= end) return@forEach
            addStyle(
                SpanStyle(
                    color = if (withColours) sp.colour?.let { Color(it.toInt()) } ?: Color.Unspecified else Color.Unspecified,
                    fontWeight = sp.bold?.let { if (it) FontWeight.Bold else FontWeight.Normal },
                    fontStyle = sp.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
                    textDecoration = if (sp.underline == true) TextDecoration.Underline else null,
                    fontSize = if (sp.size != 1f && !inField) (size * sp.size).sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    fontFamily = sp.font?.let { PrismFonts.family(it) },
                ),
                sp.start,
                end,
            )
        }
    }
}

/** The words as they'll be saved. */
@Composable
private fun ArtText(art: TextArt) {
    val px = with(LocalDensity.current) { art.size.sp.toPx() }
    val lines = listOfNotNull(TextDecoration.Underline.takeIf { art.underline }, TextDecoration.LineThrough.takeIf { art.strike })
    val shadow = when (art.effect) {
        "shadow" -> Shadow(Color(art.effectColour.toInt()), Offset(px * 0.06f, px * 0.06f), px * 0.08f)
        "glow" -> Shadow(Color(art.effectColour.toInt()), Offset.Zero, px * 0.3f)
        else -> null
    }
    val style = TextStyle(
        fontFamily = PrismFonts.family(art.font),
        fontSize = art.size.sp,
        lineHeight = (art.size * 1.15f).sp,
        fontWeight = if (art.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (art.italic) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = art.spacing.em,
        textAlign = TextAlign.Center,
        textDecoration = if (lines.isEmpty()) null else TextDecoration.combine(lines),
    )
    Box(
        Modifier
            .then(art.background?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
            .padding((art.size * 0.35f).dp),
        contentAlignment = Alignment.Center,
    ) {
        // The outline is the same words drawn as a thick line underneath.
        if (art.outline) {
            Text(art.annotated(withColours = false), style = style.copy(color = Color(art.outlineColour.toInt()), drawStyle = Stroke(width = px * 0.16f, join = StrokeJoin.Round), shadow = shadow))
        }
        Text(art.annotated(), style = style.copy(color = Color(art.colour.toInt()), shadow = if (art.outline) null else shadow))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrismTextPicturePanel(onLogo: (ImageBitmap) -> Unit, onMessage: (String) -> Unit, onClose: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val platform = Prism.platform ?: return
    val scope = rememberCoroutineScope()
    var art by remember { mutableStateOf(TextArt()) }
    var working by remember { mutableStateOf(false) }
    val layer = rememberGraphicsLayer()

    fun capture(then: suspend (ImageBitmap) -> Unit) {
        if (working) return
        scope.launch {
            working = true
            runCatching { then(layer.toImageBitmap()) }.onFailure { onMessage("The picture couldn't be made") }
            working = false
        }
    }

    PrismPanel(
        title = "Make a text picture",
        onClose = onClose,
        actions = {
            PrismButton("Use as a logo", primary = true, enabled = !working && art.text.isNotBlank()) { capture { onLogo(it) } }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1C1828)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
                ) { ArtText(art) }
            }
            var field by remember { mutableStateOf(TextFieldValue(art.text)) }
            OutlinedTextField(
                value = field,
                onValueChange = { next ->
                    val text = next.text.take(200)
                    if (text != art.text) art = art.copy(text = text, spans = art.spans.follow(art.text, text))
                    field = next.copy(text = text)
                },
                label = { Text("Your words") },
                // The box shows each word's own colour and style too.
                visualTransformation = VisualTransformation { typed -> TransformedText(art.copy(text = typed.text).annotated(inField = true), OffsetMapping.Identity) },
                modifier = Modifier.fillMaxWidth(),
            )
            val sel = field.selection
            val from = minOf(sel.start, sel.end).coerceIn(0, art.text.length)
            val to = maxOf(sel.start, sel.end).coerceIn(0, art.text.length)
            if (from == to) {
                Text(
                    "To colour or style just some words, press and hold them in the box above to select them.",
                    color = tokens.colors.textMuted,
                    fontSize = 12.sp,
                )
            } else {
                SelectedWords(art, from, to) { change -> art = art.copy(spans = art.spans.restyle(from, to, change)) }
            }
            Text("The rest of the words", color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            FontPicker("Font", art.font, false, { key -> art = art.copy(font = key) }, {})
            Column {
                Text("Size: ${art.size.roundToInt()}", color = tokens.colors.textPrimary, fontSize = 14.sp)
                Slider(value = art.size, onValueChange = { art = art.copy(size = it) }, valueRange = 20f..140f)
            }
            Text("Style", color = tokens.colors.textPrimary, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(label = "Bold", selected = art.bold, onClick = { art = art.copy(bold = !art.bold) })
                Chip(label = "Italic", selected = art.italic, onClick = { art = art.copy(italic = !art.italic) })
                Chip(label = "Underline", selected = art.underline, onClick = { art = art.copy(underline = !art.underline) })
                Chip(label = "Strike-through", selected = art.strike, onClick = { art = art.copy(strike = !art.strike) })
                Chip(label = "ALL CAPS", selected = art.caps, onClick = { art = art.copy(caps = !art.caps) })
            }
            Column {
                Text("Letter spacing", color = tokens.colors.textPrimary, fontSize = 14.sp)
                Slider(value = art.spacing, onValueChange = { art = art.copy(spacing = it) }, valueRange = -0.1f..0.5f)
            }
            Text("Text colour", color = tokens.colors.textPrimary, fontSize = 14.sp)
            PrismColourRow(art.colour, allowNone = false) { c -> art = art.copy(colour = c ?: 0xFFFFFFFF) }
            Text("Background (None keeps it see-through)", color = tokens.colors.textPrimary, fontSize = 14.sp)
            PrismColourRow(art.background, allowNone = true) { c -> art = art.copy(background = c) }
            Text("Shadow or glow", color = tokens.colors.textPrimary, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "None", "shadow" to "Shadow", "glow" to "Glow").forEach { (e, label) ->
                    Chip(label = label, selected = art.effect == e, onClick = { art = art.copy(effect = e) })
                }
            }
            if (art.effect != null) PrismColourRow(art.effectColour, allowNone = false) { c -> art = art.copy(effectColour = c ?: 0xFF000000) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(label = "Outline", selected = art.outline, onClick = { art = art.copy(outline = !art.outline) })
            }
            if (art.outline) PrismColourRow(art.outlineColour, allowNone = false) { c -> art = art.copy(outlineColour = c ?: 0xFF000000) }
            PrismButton("Save as a picture on this device…", Modifier.fillMaxWidth(), enabled = !working && art.text.isNotBlank()) {
                capture { image ->
                    val bytes = platform.encodeImage(image, keepTransparency = true)
                    val name = art.text.lines().first().trim().take(40).replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "text" } + ".png"
                    if (bytes != null && platform.saveFile(name, bytes)) onMessage("Saved $name")
                }
            }
        }
    }
}

/** Colour, font, size and style for just the selected words; [onChange] null clears them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedWords(art: TextArt, from: Int, to: Int, onChange: (((ArtSpan) -> ArtSpan)?) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val current = art.spans.firstOrNull { from >= it.start && from < it.end } ?: ArtSpan(from, to)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tokens.colors.surfaceCard).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Just \u201c${art.text.substring(from, to).take(40)}\u201d", color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
        PrismColourRow(current.colour, allowNone = true) { c -> onChange { it.copy(colour = c) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = "Bold", selected = (current.bold ?: art.bold), onClick = { val v = !(current.bold ?: art.bold); onChange { it.copy(bold = v) } })
            Chip(label = "Italic", selected = (current.italic ?: art.italic), onClick = { val v = !(current.italic ?: art.italic); onChange { it.copy(italic = v) } })
            Chip(label = "Underline", selected = current.underline == true, onClick = { val v = current.underline != true; onChange { it.copy(underline = v) } })
            Chip(label = "Smaller", selected = false, onClick = { onChange { it.copy(size = (it.size * 0.8f).coerceIn(0.4f, 3f)) } })
            Chip(label = "Bigger", selected = false, onClick = { onChange { it.copy(size = (it.size * 1.25f).coerceIn(0.4f, 3f)) } })
        }
        FontPicker("Font for these words", current.font, false, { key -> onChange { it.copy(font = key) } }, {}, defaultLabel = "Same as the rest")
        PrismButton("Clear these words' styling") { onChange(null) }
    }
}
