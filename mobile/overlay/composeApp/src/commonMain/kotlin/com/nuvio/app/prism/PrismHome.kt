package com.nuvio.app.prism

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.buildContinueWatchingEpisodeSubtitle
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// Prism: what the look adds to Home — the background picture, the favourite show card and the user's own rows.

private val LocalPrismBackdrop = compositionLocalOf { false }

/** Drawn over the screen's background colour and under its content; only on Home (see [PrismHomeScreen]). */
fun prismBackdropModifier(): Modifier = Modifier.composed {
    if (!LocalPrismBackdrop.current || Prism.platform == null) return@composed Modifier
    val look = PrismStore.look
    val shape = if (Prism.platform?.wideScreen == true) PrismShape.Wide else PrismShape.Tall
    val image by rememberPrismPicture(PrismStore.launchBackground(look), shape)
    val bitmap = image ?: return@composed Modifier
    val dim = look.backgroundDim
    Modifier.drawWithContent {
        // Fill the screen from the middle of the chosen part.
        val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
        val srcW = (size.width / scale).roundToInt().coerceIn(1, bitmap.width)
        val srcH = (size.height / scale).roundToInt().coerceIn(1, bitmap.height)
        drawImage(
            image = bitmap,
            srcOffset = IntOffset((bitmap.width - srcW) / 2, (bitmap.height - srcH) / 2),
            srcSize = IntSize(srcW, srcH),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
        drawContent()
    }
}

/** [NuvioScreen] with the look's background behind it. */
@Composable
internal fun PrismHomeScreen(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = MaterialTheme.nuvio.spacing.screenHorizontal,
    topPadding: Dp? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    CompositionLocalProvider(LocalPrismBackdrop provides true) {
        NuvioScreen(
            modifier = modifier,
            horizontalPadding = horizontalPadding,
            topPadding = topPadding,
            listState = listState,
            content = content,
        )
    }
}

// ---- The favourite show card ----

internal fun LazyListScope.prismShowCard(
    continueWatchingItems: List<ContinueWatchingItem>,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)?,
) {
    item(key = "prism_show_card", contentType = "prism_show_card") {
        val cards = PrismStore.look.cards
        when (cards.size) {
            0 -> Unit
            1 -> PrismShowCard(cards[0], continueWatchingItems, onPosterClick, onContinueWatchingClick, Modifier.padding(horizontal = 16.dp))
            else -> {
                // Several cards: swipe across them; the next one peeks in at the edge.
                val wide = Prism.platform?.wideScreen == true
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(cards, key = { it.type + ":" + it.id }) { title ->
                        PrismShowCard(
                            title,
                            continueWatchingItems,
                            onPosterClick,
                            onContinueWatchingClick,
                            Modifier.fillParentMaxWidth(if (wide) 0.48f else 0.88f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrismShowCard(
    pinned: PrismTitle,
    continueWatchingItems: List<ContinueWatchingItem>,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val accent = tokens.colors.accent
    var meta by remember(pinned.id) { mutableStateOf(MetaDetailsRepository.peek(pinned.type, pinned.id)) }
    LaunchedEffect(pinned.id) {
        if (meta == null) {
            meta = try {
                MetaDetailsRepository.fetch(pinned.type, pinned.id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        }
    }
    val show = meta ?: return
    val episodes = remember(show) { show.videos.filter { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 } }
    val isSeries = episodes.isNotEmpty()
    val resume = continueWatchingItems.firstOrNull { it.parentMetaId == show.id }
    val first = remember(episodes) { episodes.minWithOrNull(compareBy({ it.season }, { it.episode })) }
    val open = { onPosterClick?.invoke(MetaPreview(id = show.id, type = show.type, name = show.name, poster = show.poster, logo = show.logo)) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0C0C12))
            .clickable(enabled = onPosterClick != null) { open() },
    ) {
        AsyncImage(
            model = show.background ?: show.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.55f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.92f)),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (show.logo != null) {
                AsyncImage(
                    model = show.logo,
                    contentDescription = show.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.height(44.dp).widthIn(max = 240.dp),
                )
            } else {
                Text(show.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = when {
                    resume != null -> "Up next · ${resume.subtitle}"
                    isSeries -> "Start from the first episode"
                    else -> listOfNotNull(show.releaseInfo, show.genres.take(2).joinToString(", ").ifBlank { null }).joinToString(" · ")
                },
                color = Color(0xFFE6E2EC),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        when {
                            resume != null -> onContinueWatchingClick?.invoke(resume)
                            first != null -> onContinueWatchingClick?.invoke(show.episodeItem(first))
                            else -> open()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = tokens.colors.onAccent),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (resume != null) "Continue" else "Watch", fontWeight = FontWeight.SemiBold) }
                if (isSeries) {
                    // The episode starts once the card's sound has finished (if there is one).
                    var picking by remember { mutableStateOf(false) }
                    LaunchedEffect(picking) {
                        if (picking) {
                            delay(20_000)
                            picking = false
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (!picking) {
                                picking = true
                                PrismSounds.playCard(pinned) {
                                    picking = false
                                    onContinueWatchingClick?.invoke(show.episodeItem(episodes.random()))
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.9f)),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(if (picking) "Picking…" else "Play random episode", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

private fun MetaDetails.episodeItem(video: MetaVideo): ContinueWatchingItem = ContinueWatchingItem(
    parentMetaId = id,
    parentMetaType = type,
    videoId = video.id.takeIf { it.isNotBlank() } ?: buildPlaybackVideoId(
        parentMetaId = id,
        seasonNumber = video.season,
        episodeNumber = video.episode,
        fallbackVideoId = video.id,
    ),
    title = name,
    subtitle = buildContinueWatchingEpisodeSubtitle(video.season, video.episode, video.title),
    imageUrl = video.thumbnail ?: background ?: poster,
    logo = logo,
    poster = poster,
    background = background,
    seasonNumber = video.season,
    episodeNumber = video.episode,
    episodeTitle = video.title,
    episodeThumbnail = video.thumbnail,
    pauseDescription = video.overview,
    released = video.released,
    resumePositionMs = 0L,
    durationMs = 0L,
    progressFraction = 0f,
)

// ---- The user's own rows ----

internal fun LazyListScope.prismRows(
    sectionPadding: Dp,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) {
    // One item for all rows, so the list updates as soon as the look changes.
    item(key = "prism_rows", contentType = "prism_rows") {
        Column {
            PrismStore.look.rows.filter { it.items.isNotEmpty() }.forEach { row ->
                val items = remember(row) {
                    row.items.map { MetaPreview(id = it.id, type = it.type, name = it.name, poster = it.poster, releaseInfo = it.year) }
                }
                HomeCatalogRowSection(
                    section = HomeCatalogSection(
                        key = "prism_${row.id}",
                        title = row.title,
                        subtitle = "Prism",
                        addonName = "Prism",
                        target = CatalogTarget.Addon(
                            manifestUrl = "https://v3-cinemeta.strem.io/manifest.json",
                            contentType = items.first().type,
                            catalogId = "prism_${row.id}",
                        ),
                        items = items,
                    ),
                    entries = items,
                    modifier = Modifier.padding(bottom = 12.dp),
                    sectionPadding = sectionPadding,
                    watchedKeys = watchedKeys,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                    onPosterClick = onPosterClick,
                    onPosterLongClick = onPosterLongClick,
                )
            }
        }
    }
}

/** Extra long-press options for a poster: take it out of any of your own Home rows it's in. */
fun prismPosterActions(preview: MetaPreview): List<com.nuvio.app.core.ui.PosterZoomOverlayAction> =
    PrismStore.look.rows.filter { row -> row.items.any { it.id == preview.id } }.map { row ->
        com.nuvio.app.core.ui.PosterZoomOverlayAction(
            icon = Icons.Default.DeleteOutline,
            label = "Remove from ${row.title}",
            isDestructive = true,
            onSelected = {
                PrismSounds.play(PrismMoment.Removed)
                PrismStore.update { look ->
                    look.copy(rows = look.rows.map { r -> if (r.id == row.id) r.copy(items = r.items.filterNot { it.id == preview.id }) else r })
                }
            },
        )
    }
