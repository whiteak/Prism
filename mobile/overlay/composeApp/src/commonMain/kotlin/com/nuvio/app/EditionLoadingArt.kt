package com.nuvio.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.settings.AppBrandWordmark
import com.nuvio.app.prism.Prism
import com.nuvio.app.prism.PrismMoment
import com.nuvio.app.prism.PrismShape
import com.nuvio.app.prism.PrismSounds
import com.nuvio.app.prism.PrismStore
import com.nuvio.app.prism.rememberPrismOriginal
import com.nuvio.app.prism.rememberPrismPicture
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.edition_loading_art
import nuvio.composeapp.generated.resources.edition_loading_art_tall
import org.jetbrains.compose.resources.painterResource

// Prism: this file replaces Ethereal's EditionLoadingArt.kt (see prism/mobile/apply.sh).
// The opening shows the look's own picture, logo, quote and sound, or Prism's built-in art.

private var editionIntroShown = false

/** One quote per launch, the same on every loading screen until the app restarts. */
private val prismQuote: String? by lazy { PrismStore.look.quotes.randomOrNull() }

private val prismShape: PrismShape
    get() = if (Prism.platform?.wideScreen == true) PrismShape.Wide else PrismShape.Tall

/** Full-screen opening picture, held still. */
@Composable
internal fun EditionLoadingArt(modifier: Modifier = Modifier) {
    val look = PrismStore.look
    val custom by rememberPrismPicture(PrismStore.launchOpening(look), prismShape)
    // Your own picture fades in as soon as it's ready, with nothing in between.
    val fade = remember { Animatable(if (look.opening == null) 1f else 0f) }
    LaunchedEffect(custom != null) {
        if (custom != null) fade.animateTo(1f, tween(350))
    }
    Box(modifier = modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        val layer = Modifier.fillMaxSize()
        val picture = custom
        if (picture != null) {
            Image(picture, contentDescription = null, modifier = layer, contentScale = ContentScale.Crop)
        } else if (look.opening == null) {
            // Jason's own pictures with PRISM across them: the wide one fills wide screens from the middle; tall
            // screens use the tall picture, ready-cut to a phone's shape.
            if (prismShape == PrismShape.Wide) {
                Image(painterResource(Res.drawable.edition_loading_art), contentDescription = null, modifier = layer, contentScale = ContentScale.Crop)
            } else {
                Image(painterResource(Res.drawable.edition_loading_art_tall), contentDescription = null, modifier = layer, contentScale = ContentScale.Crop)
            }
        }
    }
}

/**
 * The look's logo on the loading screens. Without one nothing covers the picture: the built-in art already has
 * PRISM across it.
 */
@Composable
internal fun PrismWordmark(contentDescription: String?, modifier: Modifier = Modifier) {
    val logo by rememberPrismOriginal(PrismStore.launchLogo()?.file)
    val image = logo
    if (image != null) {
        Image(image, contentDescription = contentDescription, modifier = modifier, contentScale = ContentScale.Fit)
    }
}

/** The quote under the logo on the loading screens. */
@Composable
internal fun EditionLoadingExtras() {
    val quote = prismQuote ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = quote,
            color = Color.White.copy(alpha = 0.9f),
            style = com.nuvio.app.prism.PrismFonts.styled(
                androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontStyle = FontStyle.Italic),
                com.nuvio.app.prism.PrismTextArea.Quote,
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 36.dp),
        )
    }
}

/** Holds the opening over the app for a few seconds on each cold start while it loads underneath. Tap to skip. */
@Composable
internal fun EditionIntroOverlay() {
    var visible by remember { mutableStateOf(!editionIntroShown) }
    LaunchedEffect(Unit) {
        if (visible) {
            editionIntroShown = true
            PrismSounds.play(PrismMoment.Opening)
            delay(PrismStore.look.openingSeconds * 1000L)
            visible = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { PrismSounds.stop() }
    }
    AnimatedVisibility(
        visible = visible,
        enter = EnterTransition.None,
        exit = fadeOut(tween(450)),
    ) {
        AppLoadingContent(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.nuvio.colors.background)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.type == PointerEventType.Release) {
                                PrismSounds.stop()
                                visible = false
                            }
                        }
                    }
                },
        )
    }
}
