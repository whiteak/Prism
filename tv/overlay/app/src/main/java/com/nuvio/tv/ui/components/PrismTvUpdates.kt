package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nuvio.tv.updater.UpdateViewModel
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Nuvio only looks for updates when the app starts, and a TV keeps Prism running in the background, so
 * reopening it never checked. This looks again whenever Prism comes back to the screen (at most every
 * 5 minutes), unless an update has already been found. Same as the phone's PrismUpdateOnReturn.
 */
@Composable
fun PrismTvUpdateOnReturn(viewModel: UpdateViewModel) {
    val lastCheck = remember { arrayOf(TimeSource.Monotonic.markNow()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val state = viewModel.uiState.value
        if (state.isUpdateAvailable || state.isChecking || state.isDownloading) return@LifecycleEventEffect
        if (lastCheck[0].elapsedNow() < 5.minutes) return@LifecycleEventEffect
        lastCheck[0] = TimeSource.Monotonic.markNow()
        viewModel.checkForUpdates(force = false, showNoUpdateFeedback = false)
    }
}
