package com.nuvio.tv.ui.components

import android.graphics.Typeface
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.Density
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Typography

// Prism on the TV: the loaded look's fonts (headings, everyday text and each kind of text's own) and text size, applied through
// NuvioTheme (see prism/tv/apply.sh). The fonts come inside the look file from the phone.

object PrismTvFonts {
    private val loaded = mutableMapOf<String, FontFamily?>()

    /** The font a look names: a built-in style, "file:<font in the look>", or null for the app's own. */
    fun family(key: String?): FontFamily? = when (key) {
        null -> null
        "sans" -> FontFamily.SansSerif
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> when {
            key.startsWith("file:") -> file(key.removePrefix("file:"))
            // Android's own extra families (condensed, thin, casual...), picked on the phone.
            key.startsWith("sys:") -> loaded.getOrPut(key) {
                runCatching { FontFamily(Typeface.create(key.removePrefix("sys:"), Typeface.NORMAL)) }.getOrNull()
            }
            else -> null
        }
    }

    private fun file(name: String): FontFamily? = loaded.getOrPut(name) {
        runCatching {
            val f = PrismTv.mediaFile(name)?.takeIf { it.isFile } ?: return@runCatching null
            val typeface = if (Build.VERSION.SDK_INT >= 26) Typeface.Builder(f).build() else Typeface.createFromFile(f)
            // A file Android can't read comes back empty or as the standard font.
            typeface?.takeIf { it != Typeface.DEFAULT }?.let { FontFamily(it) }
        }.getOrNull()
    }

    fun clear() = loaded.clear()

    /** Android's own font styles, the same ones the phone offers (stored as "sys:<family>"). */
    val SYSTEM = listOf(
        "casual" to "Casual",
        "sans-serif-condensed" to "Condensed",
        "sans-serif-light" to "Light",
        "sans-serif-thin" to "Thin",
        "sans-serif-medium" to "Medium",
        "sans-serif-black" to "Black",
        "sans-serif-smallcaps" to "Small caps",
        "serif-monospace" to "Old typewriter",
    )

    /** Every font Customise can pick from, in order: the app's own, the built-in styles, Android's styles, then font files. */
    fun choices(files: List<String>): List<String?> =
        listOf<String?>(null, "sans", "serif", "mono", "cursive") + SYSTEM.map { "sys:${it.first}" } + files.map { "file:$it" }

    fun label(key: String?, none: String = "App's own"): String = when (key) {
        null -> none
        "sans" -> "Clean"
        "serif" -> "Classic"
        "mono" -> "Typewriter"
        "cursive" -> "Handwritten"
        else -> SYSTEM.firstOrNull { "sys:${it.first}" == key }?.second
            ?: key.removePrefix("file:").removePrefix("sys:").substringBeforeLast('.')
    }
}

/** A style with one kind of text's font and font options from the look ("Display", "Title", "Quote"...). */
fun prismTvStyled(base: TextStyle, area: String, look: PrismTvLook? = PrismTv.look): TextStyle {
    look ?: return base
    var out = base
    PrismTvFonts.family(look.fontFor(area))?.let { out = out.copy(fontFamily = it) }
    val st = look.textStyles[area] ?: return out
    if (st.size != 1f) {
        val size = st.size.coerceIn(0.6f, 2f)
        if (out.fontSize.isSpecified) out = out.copy(fontSize = out.fontSize * size)
        if (out.lineHeight.isSpecified) out = out.copy(lineHeight = out.lineHeight * size)
    }
    st.weight?.let { out = out.copy(fontWeight = FontWeight(it.coerceIn(100, 900))) }
    st.italic?.let { out = out.copy(fontStyle = if (it) FontStyle.Italic else FontStyle.Normal) }
    if (st.spacing != 0f) out = out.copy(letterSpacing = st.spacing.coerceIn(-0.1f, 0.5f).em)
    val lines = listOfNotNull(TextDecoration.Underline.takeIf { st.underline }, TextDecoration.LineThrough.takeIf { st.strike })
    if (lines.isNotEmpty()) out = out.copy(textDecoration = TextDecoration.combine(lines))
    when (st.effect) {
        "shadow" -> out = out.copy(shadow = Shadow(Color(st.effectColor), Offset(3f, 3f), 6f))
        "glow" -> out = out.copy(shadow = Shadow(Color(st.effectColor), Offset.Zero, 18f))
    }
    if (st.outline) out = out.copy(drawStyle = Stroke(width = 2.5f))
    st.highlight?.let { out = out.copy(background = Color(it)) }
    // A brush wins over the colour each screen gives its text (a plain colour brush would turn back into a colour).
    st.colour?.let { c -> out = out.copy(brush = Brush.horizontalGradient(listOf(Color(c), Color(st.colour2 ?: c)))) }
    return out
}

/** The app's type with the look's fonts and font options for each kind of text. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun prismTvTypography(fallback: FontFamily, build: (FontFamily) -> Typography): Typography {
    PrismTv.init(LocalContext.current)
    val look = PrismTv.look
    val base = build(PrismTvFonts.family(look?.textFont) ?: fallback)
    look ?: return base
    fun TextStyle.as_(area: String) = prismTvStyled(this, area, look)
    return base.copy(
        displayLarge = base.displayLarge.as_("Display"),
        displayMedium = base.displayMedium.as_("Display"),
        displaySmall = base.displaySmall.as_("Display"),
        headlineLarge = base.headlineLarge.as_("Headline"),
        headlineMedium = base.headlineMedium.as_("Headline"),
        headlineSmall = base.headlineSmall.as_("Headline"),
        titleLarge = base.titleLarge.as_("Title"),
        titleMedium = base.titleMedium.as_("Title"),
        titleSmall = base.titleSmall.as_("Title"),
        bodyLarge = base.bodyLarge.as_("Body"),
        bodyMedium = base.bodyMedium.as_("Body"),
        bodySmall = base.bodySmall.as_("Body"),
        labelLarge = base.labelLarge.as_("Label"),
        labelMedium = base.labelMedium.as_("Label"),
        labelSmall = base.labelSmall.as_("Label"),
    )
}

/** Makes all text the size the look asks for. */
@Composable
fun PrismTvTextSize(content: @Composable () -> Unit) {
    val scale = PrismTv.look?.textSize?.coerceIn(0.8f, 1.5f) ?: 1f
    if (scale == 1f) {
        content()
        return
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * scale), content = content)
}
