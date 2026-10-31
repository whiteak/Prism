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
import androidx.compose.runtime.DisposableEffect
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

    // ---- The PC's screensaver ----
    val SAVER_OPTIONS = listOf(5, 10, 15, 30, 60, 0)
    val DARK_OPTIONS = listOf(15, 30, 60, 120, 0)
    private fun setting(name: String, default: Int) =
        Prism.platform?.let { p -> p.readBytes("${p.dataDir}/$name")?.decodeToString()?.trim()?.toIntOrNull() } ?: default
    private fun keep(name: String, value: Int) {
        Prism.platform?.let { p -> runCatching { p.writeBytes("${p.dataDir}/$name", value.toString().encodeToByteArray()) } }
    }

    /** Minutes idle on a menu before the screensaver; 0 never. */
    var saverMinutes by mutableIntStateOf(setting("screensaver.txt", 10))
        private set
    /** Minutes the screensaver shows before going dark; 0 keeps it on. */
    var darkMinutes by mutableIntStateOf(setting("screensaver-dark.txt", 30))
        private set
    fun changeSaver(minutes: Int) { saverMinutes = minutes; keep("screensaver.txt", minutes) }
    fun changeDark(minutes: Int) { darkMinutes = minutes; keep("screensaver-dark.txt", minutes) }

    /** The player is open: no screensaver over it ("Still watching?" looks after that). */
    internal var playing by mutableStateOf(false)

    fun minutesLabel(minutes: Int) = when {
        minutes == 0 -> "Never"
        minutes < 60 -> "$minutes minutes"
        minutes == 60 -> "1 hour"
        else -> "${minutes / 60} hours"
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
    DisposableEffect(Unit) {
        PrismSleep.playing = true
        onDispose { PrismSleep.playing = false }
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

/**
 * The PC's screensaver, as on the TV: after minutes with no click, mouse move or key on a menu (never over the
 * player), the look's pictures and quotes fade through; after its own time it goes dark. Any input ends it.
 */
@Composable
fun PrismScreensaverHost(content: @Composable () -> Unit) {
    var idleMinutes by remember { mutableIntStateOf(0) }
    var showing by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }
    fun wake() {
        idleMinutes = 0
        showing = false
        dark = false
    }
    val saver = PrismSleep.saverMinutes
    val darkAfter = PrismSleep.darkMinutes
    LaunchedEffect(saver, darkAfter) {
        while (true) {
            delay(60_000)
            if (PrismSleep.playing) { idleMinutes = 0; continue }
            idleMinutes++
            if (saver > 0 && idleMinutes >= saver) showing = true
            if (showing && darkAfter > 0 && idleMinutes >= saver + darkAfter) dark = true
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        // The press that ends the screensaver does only that.
                        if (showing) e.changes.forEach { it.consume() }
                        wake()
                    }
                }
            }
            .onPreviewKeyEvent { val was = showing; wake(); was },
    ) {
        content()
        if (showing) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (!dark) PrismSaverSlides()
            }
        }
    }
}

@Composable
private fun PrismSaverSlides() {
    val look = PrismStore.look
    val pictures = remember(look) { (listOfNotNull(look.background, look.opening) + look.backgrounds + look.openings).map { it.file }.distinct() }
    val quotes = look.quotes
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(pictures, quotes) {
        while (true) {
            delay(15_000)
            index++
        }
    }
    val picture by rememberPrismOriginal(pictures.takeIf { it.isNotEmpty() }?.let { it[index % it.size] })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        picture?.let {
            androidx.compose.foundation.Image(it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
        }
        quotes.takeIf { it.isNotEmpty() }?.let { q ->
            Text(
                q[index % q.size],
                color = Color.White,
                fontSize = 26.sp,
                modifier = Modifier.padding(bottom = 64.dp, start = 48.dp, end = 48.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
