package com.nuvio.app.features.home

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.unit.Dp
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.prism.prismHero
import com.nuvio.app.prism.prismRows
import com.nuvio.app.prism.prismShowCard

// Prism: the look's Home items, named here so HomeScreen can call them (see prism/mobile/apply.sh).

internal fun LazyListScope.prismHomeShowCard(
    continueWatchingItems: List<ContinueWatchingItem>,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)?,
) = prismShowCard(continueWatchingItems, onPosterClick, onContinueWatchingClick)

internal fun LazyListScope.prismHomeRows(
    sectionPadding: Dp,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) = prismRows(sectionPadding, watchedKeys, fullyWatchedSeriesKeys, onPosterClick, onPosterLongClick)

internal fun LazyListScope.prismHomeHero() = prismHero()
