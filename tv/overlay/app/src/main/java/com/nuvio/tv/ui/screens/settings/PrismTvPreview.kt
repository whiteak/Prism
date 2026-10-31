package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.PrismTv
import com.nuvio.tv.ui.components.PrismTvAvatars
import com.nuvio.tv.ui.components.PrismTvFonts
import com.nuvio.tv.ui.components.PrismTvImages
import com.nuvio.tv.ui.components.PrismTvLook
import com.nuvio.tv.ui.components.PrismTvPicture
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Prism on the TV: the preview beside Customise, as on the phone. A small copy of Home (background, logo, a row
// of posters in the look's shape, headings and everyday text in its fonts, a quote) that changes as you change
// things; the opening for 6. Opening and 8. Quotes; and the picture you're on while choosing pictures.

internal enum class PrismTvPreviewMode { Home, Opening, Picture, Profiles }

internal object PrismTvPreviewState {
    /** The picture the remote is on in a pictures list, and whether it's a logo. */
    var picture by mutableStateOf<PrismTvPicture?>(null)
    var pictureIsLogo by mutableStateOf(false)
}

@Composable
private fun rememberPreviewPicture(picture: PrismTvPicture?, logo: Boolean): ImageBitmap? {
    val state by produceState<ImageBitmap?>(null, picture?.file, picture?.wide, logo, PrismTv.changes) {
        value = picture?.let { p ->
            withContext(Dispatchers.IO) {
                runCatching { (if (logo) PrismTvImages.logo(p) else PrismTvImages.wide(p, 960, 540))?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return state
}

/** A sample of one kind of text in the look's style for it. */
@Composable
private fun Sample(text: String, look: PrismTvLook?, area: String, size: TextUnit, colour: Color, lines: Int = 1, align: TextAlign? = null) {
    val style = look?.textStyles?.get(area)
    val scale = (look?.textSize ?: 1f) * (style?.size ?: 1f)
    Text(
        text,
        color = style?.colour?.let { Color(it) } ?: colour,
        fontSize = size * scale,
        fontFamily = PrismTvFonts.family(look?.fontFor(area)),
        fontWeight = style?.weight?.let { FontWeight(it.coerceIn(100, 900)) },
        fontStyle = if (style?.italic == true) FontStyle.Italic else null,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
    )
}

@Composable
internal fun PrismTvLivePreview(mode: PrismTvPreviewMode, modifier: Modifier = Modifier) {
    val look = PrismTv.look
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when (mode) {
                PrismTvPreviewMode.Home -> "Preview: Home"
                PrismTvPreviewMode.Opening -> "Preview: the opening"
                PrismTvPreviewMode.Picture -> "Preview: this picture"
                PrismTvPreviewMode.Profiles -> "Preview: profiles"
            },
            color = NuvioTheme.colors.TextSecondary,
            fontSize = 14.sp,
        )
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                .background(NuvioTheme.colors.Background)
                .border(1.dp, NuvioTheme.colors.TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        ) {
            when (mode) {
                PrismTvPreviewMode.Home -> HomePreview(look)
                PrismTvPreviewMode.Opening -> OpeningPreview(look)
                PrismTvPreviewMode.Picture -> PicturePreview()
                PrismTvPreviewMode.Profiles -> ProfilesPreview()
            }
        }
        if (look == null) {
            Text("Showing Prism's built-in look. Your changes show here as you make them.", color = NuvioTheme.colors.TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HomePreview(look: PrismTvLook?) {
    val background = rememberPreviewPicture(look?.background, logo = false)
    val logo = rememberPreviewPicture(look?.logo, logo = true)
    background?.let {
        Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (look?.backgroundDim ?: 0.35f).coerceIn(0f, 0.85f))))
    }
    if (look?.clipsOn == true && look.clips.isNotEmpty()) {
        Text("▶ ${look.clips.size} hero clips play here", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
    val poster = look?.tvPoster
    // About a third of the size on the TV, so a row fits.
    val width = ((poster?.widthDp ?: 126) * 0.32f).dp
    val height = if (poster?.landscape == true) width * 9f / 16f else width * 1.5f
    val radius = ((poster?.cornerRadiusDp ?: 12) * 0.32f).dp
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (logo != null) {
            Image(logo, contentDescription = null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.height(26.dp).width(110.dp))
        } else {
            Sample("Prism", look, "Display", 18.sp, NuvioTheme.colors.TextPrimary)
        }
        Spacer(Modifier.height(2.dp))
        Sample(look?.rows?.firstOrNull()?.title ?: "Popular movies", look, "Title", 12.sp, NuvioTheme.colors.TextPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(6) { i ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        Modifier.size(width, height).clip(RoundedCornerShape(radius))
                            .background(NuvioTheme.colors.Surface)
                            .then(if (i == 0) Modifier.border(2.dp, NuvioTheme.colors.FocusRing, RoundedCornerShape(radius)) else Modifier),
                    )
                    if (poster?.labels != false) Box(Modifier.width(width)) { Sample("Title ${i + 1}", look, "Label", 8.sp, NuvioTheme.colors.TextSecondary) }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Sample("Everyday text, like descriptions and settings, looks like this.", look, "Body", 10.sp, NuvioTheme.colors.TextSecondary, lines = 2)
    }
}

@Composable
private fun OpeningPreview(look: PrismTvLook?) {
    val opening = rememberPreviewPicture(look?.opening, logo = false)
    val logo = rememberPreviewPicture(look?.logo, logo = true)
    opening?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (logo != null) Image(logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.height(40.dp).width(160.dp))
        else if (opening == null) Sample("Prism", look, "Display", 26.sp, NuvioTheme.colors.TextPrimary)
        Spacer(Modifier.weight(1f))
        val quote = look?.quotes?.firstOrNull()
        Sample(quote?.let { "“$it”" } ?: "Your quotes show here", look, "Quote", 11.sp, Color.White, lines = 3, align = TextAlign.Center)
    }
    Text(
        "Shows for ${look?.openingSeconds ?: 5} seconds",
        color = Color.White.copy(alpha = 0.75f),
        fontSize = 10.sp,
        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
    )
}

@Composable
private fun PicturePreview() {
    val picture = PrismTvPreviewState.picture
    val logo = PrismTvPreviewState.pictureIsLogo
    val image = rememberPreviewPicture(picture, logo)
    when {
        image == null -> Text(
            if (picture == null) "Add a picture to see it here" else "Loading…",
            color = NuvioTheme.colors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.Center),
        )
        logo -> Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(24.dp))
        // Already cut to the TV's shape, as it will show.
        else -> Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ProfilesPreview() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val profiles by androidx.compose.runtime.remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, com.nuvio.tv.ui.components.PrismTvEntryPoint::class.java)
            .profileManager().profiles
    }.collectAsState()
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        profiles.take(5).forEach { p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val file = PrismTvAvatars.inUse(p.id)?.let { PrismTvAvatars.file(it) }
                val photo by produceState<ImageBitmap?>(null, file?.path) {
                    value = file?.let { f -> withContext(Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() } }
                }
                Box(Modifier.size(48.dp).clip(CircleShape).background(NuvioTheme.colors.Surface), contentAlignment = Alignment.Center) {
                    photo?.let { Image(it, contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        ?: Text(p.name.take(1).uppercase(), color = NuvioTheme.colors.TextPrimary, fontSize = 20.sp)
                }
                Text(p.name, color = NuvioTheme.colors.TextPrimary, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
