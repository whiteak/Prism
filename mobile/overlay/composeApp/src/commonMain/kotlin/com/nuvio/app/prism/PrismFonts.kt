package com.nuvio.app.prism

import androidx.compose.material3.Typography
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import com.nuvio.app.core.ui.NuvioTypeScale

// Prism: the look's fonts (headings and everyday text, and each kind of text's own font and options) and
// text size, applied to the whole app
// through NuvioTheme (see prism/mobile/apply.sh).

object PrismFonts {
    /** Fonts every device has, by the key a look stores. Null in a look means Nuvio's own font. */
    private val everywhere: List<Pair<String, String>> = listOf(
        "sans" to "Clean",
        "serif" to "Classic",
        "mono" to "Typewriter",
        "cursive" to "Handwritten",
    )

    /** The fonts above plus the device's own extra families (on Android: condensed, thin, casual...), as "sys:<name>". */
    val builtIn: List<Pair<String, String>>
        get() = everywhere + Prism.platform?.systemFamilies.orEmpty().map { (name, label) -> PRISM_SYSTEM_FONT + name to label }

    private val loaded = mutableMapOf<String, FontFamily?>()

    /** The font for [key], or null for Nuvio's own (or an added font that can't be read). */
    fun family(key: String?): FontFamily? = when (key) {
        null -> null
        "sans" -> FontFamily.SansSerif
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> when {
            key.startsWith(PRISM_FONT_FILE) -> fileFont(key.removePrefix(PRISM_FONT_FILE))
            key.startsWith(PRISM_SYSTEM_FONT) -> loaded.getOrPut(key) { Prism.platform?.systemFamily(key.removePrefix(PRISM_SYSTEM_FONT)) }
            else -> null
        }
    }

    private fun fileFont(file: String): FontFamily? = loaded.getOrPut(file) {
        Prism.platform?.let { p -> p.loadFont(PrismStore.mediaPath(file)) }
    }

    /** Checks a newly added font file can be drawn. */
    fun canLoad(file: String): Boolean {
        loaded.remove(file)
        return fileFont(file) != null
    }

    fun forget(file: String) {
        loaded.remove(file)
    }

    /** A name for a font file: its file name without the ending, tidied. */
    fun nameFor(fileName: String): String =
        fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifBlank { "My font" }

    /** The text size the look asks for (1 = normal). */
    fun scale(): Float = if (Prism.platform == null) 1f else PrismStore.look.textSize.coerceIn(0.8f, 1.5f)

    /** A style with one kind of text's font and font options from the look. */
    fun styled(base: TextStyle, area: PrismTextArea, look: PrismLook = PrismStore.look): TextStyle {
        val st = look.textStyle(area)
        var out = base
        family(look.fontFor(area))?.let { out = out.copy(fontFamily = it) }
        if (st.size != 1f) {
            val size = st.size.coerceIn(0.6f, 2f)
            if (out.fontSize.isSpecified) out = out.copy(fontSize = out.fontSize * size)
            if (out.lineHeight.isSpecified) out = out.copy(lineHeight = out.lineHeight * size)
        }
        st.weight?.let { out = out.copy(fontWeight = FontWeight(it.coerceIn(100, 900))) }
        st.italic?.let { out = out.copy(fontStyle = if (it) FontStyle.Italic else FontStyle.Normal) }
        if (st.spacing != 0f) out = out.copy(letterSpacing = st.spacing.coerceIn(-0.1f, 0.5f).em)
        return decorate(out, st)
    }

    /** Lines, shadow or glow, hollow outline and highlight from [st]. */
    fun decorate(base: TextStyle, st: PrismTextStyle): TextStyle {
        var out = base
        val lines = listOfNotNull(TextDecoration.Underline.takeIf { st.underline }, TextDecoration.LineThrough.takeIf { st.strike })
        if (lines.isNotEmpty()) out = out.copy(textDecoration = TextDecoration.combine(lines))
        when (st.effect) {
            "shadow" -> out = out.copy(shadow = Shadow(Color(st.effectColor.toInt()), Offset(3f, 3f), 6f))
            "glow" -> out = out.copy(shadow = Shadow(Color(st.effectColor.toInt()), Offset.Zero, 18f))
        }
        if (st.outline) out = out.copy(drawStyle = Stroke(width = 2.5f))
        st.highlight?.let { out = out.copy(background = Color(it.toInt())) }
        // A brush, unlike a colour, wins over the colour each screen gives its text, so the look's colour shows everywhere.
        // (One colour is a fade between the same colour twice, as a plain colour brush would be turned back into a colour.)
        st.colour?.let { c -> out = out.copy(brush = Brush.horizontalGradient(listOf(Color(c.toInt()), Color((st.colour2 ?: c).toInt())))) }
        return out
    }

    private fun changed(look: PrismLook) =
        look.titleFont != null || look.textFont != null || look.textStyles.values.any { it != PrismTextStyle() }

    /** Nuvio's own type, with the look's fonts and font options swapped in. */
    fun typography(base: Typography): Typography {
        if (Prism.platform == null) return base
        val look = PrismStore.look
        if (!changed(look)) return base
        fun TextStyle.as_(area: PrismTextArea) = styled(this, area, look)
        return base.copy(
            displayLarge = base.displayLarge.as_(PrismTextArea.Display),
            displayMedium = base.displayMedium.as_(PrismTextArea.Display),
            displaySmall = base.displaySmall.as_(PrismTextArea.Display),
            headlineLarge = base.headlineLarge.as_(PrismTextArea.Headline),
            headlineMedium = base.headlineMedium.as_(PrismTextArea.Headline),
            headlineSmall = base.headlineSmall.as_(PrismTextArea.Headline),
            titleLarge = base.titleLarge.as_(PrismTextArea.Title),
            titleMedium = base.titleMedium.as_(PrismTextArea.Title),
            titleSmall = base.titleSmall.as_(PrismTextArea.Title),
            bodyLarge = base.bodyLarge.as_(PrismTextArea.Body),
            bodyMedium = base.bodyMedium.as_(PrismTextArea.Body),
            bodySmall = base.bodySmall.as_(PrismTextArea.Body),
            labelLarge = base.labelLarge.as_(PrismTextArea.Label),
            labelMedium = base.labelMedium.as_(PrismTextArea.Label),
            labelSmall = base.labelSmall.as_(PrismTextArea.Label),
        )
    }

    fun typeScale(base: NuvioTypeScale): NuvioTypeScale {
        if (Prism.platform == null) return base
        val look = PrismStore.look
        if (!changed(look)) return base
        fun TextStyle.as_(area: PrismTextArea) = styled(this, area, look)
        return base.copy(
            labelXs = base.labelXs.as_(PrismTextArea.Label),
            labelSm = base.labelSm.as_(PrismTextArea.Label),
            bodySm = base.bodySm.as_(PrismTextArea.Body),
            bodyMd = base.bodyMd.as_(PrismTextArea.Body),
            bodyLg = base.bodyLg.as_(PrismTextArea.Body),
            titleSm = base.titleSm.as_(PrismTextArea.Title),
            titleMd = base.titleMd.as_(PrismTextArea.Title),
            titleLg = base.titleLg.as_(PrismTextArea.Title),
            displaySm = base.displaySm.as_(PrismTextArea.Display),
            displayMd = base.displayMd.as_(PrismTextArea.Display),
        )
    }
}
