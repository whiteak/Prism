package com.nuvio.tv.ui.screens.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.ui.components.PrismTvRowItem
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

// Prism on the TV: posters where the phone has posters — the titles found by a search (Customise > Home) and the
// saved show cards. Coil is already set up by the app (memory and disk cache), so nothing new is loaded here.

/**
 * A Cinemeta search's results as a wall of posters rather than a list of text lines, worked with the remote at
 * three metres: the first card holds focus when it opens, OK picks it, Back cancels.
 */
@Composable
internal fun PrismTvPosterPicker(
    title: String,
    items: List<PrismTvRowItem>,
    onPick: (PrismTvRowItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusAfterFrames() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background.copy(alpha = 0.96f))
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            Text(title, color = NuvioTheme.colors.TextPrimary, fontSize = 28.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${items.size} found   ·   Back to cancel", color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                // The focused card grows past its cell, which the grid would otherwise clip.
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items, key = { index, item -> "$index-${item.id}" }) { index, item ->
                    PrismTvPosterCard(item, if (index == 0) first else null) { picked ->
                        onPick(picked)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun PrismTvPosterCard(item: PrismTvRowItem, requester: FocusRequester?, onPick: (PrismTvRowItem) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(150), label = "prism poster card")
    val shape = RoundedCornerShape(10.dp)
    val year = item.year
    Column(
        modifier = Modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation = if (focused) 16.dp else 0.dp, shape = shape, clip = false)
            .then(if (focused) Modifier.border(3.dp, NuvioTheme.colors.FocusRing, shape) else Modifier)
            .clip(shape)
            .then(requester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onPick(item) },
    ) {
        PrismTvPosterThumb(item.poster, item.name, 10.dp, 48.sp, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        Spacer(Modifier.height(6.dp))
        Text(item.name, color = NuvioTheme.colors.TextPrimary, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val type = if (item.type == "series") "Show" else "Movie"
        Text(
            if (year.isNullOrBlank()) type else "$year · $type",
            color = NuvioTheme.colors.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A saved show card as the other Customise rows look, with its poster at the head of it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PrismTvCardRow(name: String, year: String?, poster: String?, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .padding(top = NuvioTheme.spacing.xxs, bottom = NuvioTheme.spacing.xxs)
            .fillMaxWidth(),
        colors = settingsRowColors(),
        border = settingsRowBorder(),
        shape = CardDefaults.shape(settingsRowShape()),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrismTvPosterThumb(poster, name, 6.dp, 22.sp, Modifier.width(54.dp).height(81.dp))
            Spacer(Modifier.width(NuvioTheme.spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = NuvioTheme.colors.TextPrimary, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(NuvioTheme.spacing.xxs))
                Text(
                    listOfNotNull(year, subtitle).joinToString("  ·  "),
                    color = NuvioTheme.colors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * A 2:3 poster with the name's first letter behind it, so there is never an empty box: no poster at all
 * (the older cards kept none), while one loads, or after a load that failed.
 */
@Composable
private fun PrismTvPosterThumb(poster: String?, name: String, radius: Dp, letterSize: TextUnit, modifier: Modifier = Modifier) {
    var loaded by remember(poster) { mutableStateOf(false) }
    Box(
        modifier = modifier.clip(RoundedCornerShape(radius)).background(NuvioTheme.colors.SurfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(name.trim().take(1).ifBlank { "?" }, color = NuvioTheme.colors.TextSecondary, fontSize = letterSize)
        }
        if (!poster.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(poster).crossfade(true).build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
            )
        }
    }
}
