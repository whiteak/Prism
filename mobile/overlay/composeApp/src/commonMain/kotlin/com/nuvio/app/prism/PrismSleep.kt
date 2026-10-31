package com.nuvio.app.prism

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Prism on the phone and PC: "Still watching?", as on the TV. While the player is open with no touch, click,
// mouse move or key for the chosen hours, it asks; with no answer in a minute it closes the player, so a
// show left playing overnight stops and the device can sleep.

object PrismSleep {
    val STILL_WATCHING_OPTIONS = listOf(0, 1, 2, 3, 4)
    internal const val ASK_SECONDS = 60
    private val path get() = Prism.platform?.let { "${it.dataDir}/still-watching.txt" }

    /** Hours of playback with no touch before asking; 0 never asks. */
    var stillWatchingHours by mutableIntStateOf(
        path?.let { p -> Prism.platform?.readBytes(p)?.decodeToString()?.trim()?.toIntOrNull() } ?: 3,
    )
        private set

    fun changeStillWatching(hours: Int) {
        stillWatchingHours = hours
        path?.let { p -> runCatching { Prism.platform?.writeBytes(p, hours.toString().encodeToByteArray()) } }
    }

    fun hoursLabel(hours: Int) = when (hours) {
        0 -> "Off"
        1 -> "After 1 hour"
        else -> "After $hours hours"
    }
}

/** Wraps the player: counts the minutes with no input, asks "Still watching?", and calls [onStop] unanswered. */
@Composable
fun PrismStillWatching(onStop: () -> Unit, content: @Composable () -> Unit) {
    val hours = PrismSleep.stillWatchingHours
    var idleMinutes by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(PrismSleep.ASK_SECONDS) }
    fun touched() {
        idleMinutes = 0
        asking = false
    }
    LaunchedEffect(hours) {
        if (hours == 0) return@LaunchedEffect
        while (true) {
            delay(60_000)
            idleMinutes++
            if (!asking && idleMinutes >= hours * 60) {
                secondsLeft = PrismSleep.ASK_SECONDS
                asking = true
            }
        }
    }
    LaunchedEffect(asking) {
        if (!asking) return@LaunchedEffect
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
        asking = false
        onStop()
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        // While asking, only the buttons answer, so Stop can be pressed.
                        if (!asking) idleMinutes = 0
                    }
                }
            }
            .onPreviewKeyEvent { if (!asking) idleMinutes = 0; false },
    ) {
        content()
        if (asking) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.background(Color(0xFF16121F), RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Still watching?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("Stopping in $secondsLeft seconds.", color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrismButton("Keep watching", primary = true) { touched() }
                        PrismButton("Stop") { asking = false; onStop() }
                    }
                }
            }
        }
    }
}
