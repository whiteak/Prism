package com.nuvio.app.prism

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Prism: small pictures of files in lists (pictures, clips, sounds, fonts), and a big preview when you tap one:
// pictures whole, clips playing, sounds playing, fonts written out.

/** What sort of file a name is, for its preview. */
internal fun previewKind(name: String): PrismFileKind? = when {
    PrismFileKind.Picture.matches(name) -> PrismFileKind.Picture
    PrismFileKind.Video.matches(name) && !name.endsWith(".m4a", ignoreCase = true) -> PrismFileKind.Video
    PrismFileKind.Sound.matches(name) -> PrismFileKind.Sound
    PrismFileKind.Font.matches(name) -> PrismFileKind.Font
    else -> null
}

@Composable
private fun rememberFontPreview(ref: String): FontFamily? {
    val font by produceState<FontFamily?>(null, ref) {
        value = withContext(Dispatchers.Default) { runCatching { Prism.platform?.loadFont(ref) }.getOrNull() }
    }
    return font
}

/** A small picture of the file at [ref] (a stored file or a device address), or a sign for its kind. */
@Composable
internal fun PrismThumb(ref: String, name: String, modifier: Modifier = Modifier, width: Dp = 64.dp, height: Dp = 48.dp, onClick: (() -> Unit)? = null) {
    val tokens = MaterialTheme.nuvio
    val kind = previewKind(name)
    val thumb by produceState<ImageBitmap?>(null, ref) {
        if (kind == PrismFileKind.Picture || kind == PrismFileKind.Video) {
            value = runCatching { Prism.platform?.thumbnail(ref, 256) }.getOrNull()
        }
    }
    Box(
        modifier
            .size(width, height)
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.colors.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val image = thumb
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (kind == PrismFileKind.Video) Text("▶", color = Color.White, fontSize = 16.sp)
        } else {
            when (kind) {
                PrismFileKind.Font -> Text("Aa", color = tokens.colors.textPrimary, fontSize = 20.sp, fontFamily = rememberFontPreview(ref))
                PrismFileKind.Sound -> Text("♪", color = tokens.colors.accent, fontSize = 22.sp)
                PrismFileKind.Video -> Text("▶", color = tokens.colors.accent, fontSize = 18.sp)
                else -> Text("•", color = tokens.colors.textMuted, fontSize = 18.sp)
            }
        }
    }
}

/** The big preview: tap outside it or Close to go back. */
@Composable
internal fun PrismPreviewDialog(ref: String, name: String, onClose: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val platform = Prism.platform ?: return
    val kind = previewKind(name)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(tokens.colors.surfaceDialog)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                PrismButton("Close", onClick = onClose)
            }
            when (kind) {
                PrismFileKind.Picture -> {
                    val picture by produceState<ImageBitmap?>(null, ref) { value = runCatching { platform.thumbnail(ref, 2048) }.getOrNull() }
                    Box(Modifier.fillMaxWidth().aspectRatio(picture?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }?.coerceIn(0.4f, 3f) ?: 1f), contentAlignment = Alignment.Center) {
                        picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                            ?: CircularProgressIndicator(color = tokens.colors.accent)
                    }
                }
                PrismFileKind.Video -> {
                    // Plays over and over until closed.
                    var round by remember { mutableIntStateOf(0) }
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                        androidx.compose.runtime.key(round) {
                            platform.ClipPlayer(ref, Modifier.fillMaxSize()) { round++ }
                        }
                    }
                    Text("Clips play without sound in Prism.", color = tokens.colors.textMuted, fontSize = 12.sp)
                }
                PrismFileKind.Sound -> {
                    var playing by remember { mutableStateOf(true) }
                    LaunchedEffect(playing) { if (playing) platform.playFile(ref) { playing = false } else platform.stopSound() }
                    DisposableEffect(Unit) { onDispose { platform.stopSound() } }
                    PrismButton(if (playing) "Stop" else "Play", Modifier.fillMaxWidth(), primary = true) { playing = !playing }
                }
                PrismFileKind.Font -> {
                    val font = rememberFontPreview(ref)
                    listOf(30, 20, 14).forEach { size ->
                        Text("The quick brown fox jumps over the lazy dog", color = tokens.colors.textPrimary, fontSize = size.sp, fontFamily = font)
                    }
                    Text("ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789", color = tokens.colors.textSecondary, fontSize = 16.sp, fontFamily = font)
                }
                else -> Text("There's no preview for this kind of file.", color = tokens.colors.textSecondary)
            }
        }
    }
}
