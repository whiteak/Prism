package com.nuvio.tv.ui.components

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.R
import kotlin.random.Random
import kotlinx.coroutines.delay

// Prism on the TV: letting the TV go to sleep. Nuvio's Ambient Mode (its screensaver) keeps the screen on
// for as long as it shows, so Prism gives it an end: after the chosen time it goes dark and lets go of the
// screen, and the Shield's own sleep turns the TV off. While something plays, "Still watching?" asks after
// hours with no remote press, then stops the video and goes dark (see prism/tv/apply.sh).

object PrismTvSleep {
    val SCREEN_OFF_OPTIONS = listOf(15, 30, 60, 90, 120, 0)
    val STILL_WATCHING_OPTIONS = listOf(0, 1, 2, 3, 4)
    internal const val ASK_SECONDS = 60

    /** When the remote was last pressed. */
    var lastInput by mutableLongStateOf(SystemClock.uptimeMillis())
        private set

    /** "Still watching?" is up. */
    var asking by mutableStateOf(false)
        private set

    /** Playback was stopped by "Still watching?": the screen stays dark until a press. */
    var sleeping by mutableStateOf(false)
        private set

    /** The screensaver has run its time and gone dark. */
    var screensaverDark by mutableStateOf(false)
        internal set

    private var swallowedKey: Int? = null

    private fun prefs(context: Context) = context.getSharedPreferences("prism", Context.MODE_PRIVATE)

    /** Prism's pictures and quotes (true) or Nuvio's posters (false) as the screensaver. */
    fun prismScreensaver(context: Context) = prefs(context).getBoolean("screensaver_prism", true)
    fun setPrismScreensaver(context: Context, value: Boolean) = prefs(context).edit().putBoolean("screensaver_prism", value).apply()

    /** Minutes the screensaver shows before going dark; 0 keeps it on. */
    fun screenOffMinutes(context: Context) = prefs(context).getInt("screen_off_minutes", 30)
    fun setScreenOffMinutes(context: Context, value: Int) = prefs(context).edit().putInt("screen_off_minutes", value).apply()

    /** Hours of playback with no remote press before asking; 0 never asks. */
    fun stillWatchingHours(context: Context) = prefs(context).getInt("still_watching_hours", 3)
    fun setStillWatchingHours(context: Context, value: Int) = prefs(context).edit().putInt("still_watching_hours", value).apply()

    fun minutesLabel(minutes: Int) = when {
        minutes == 0 -> "Never"
        minutes < 60 -> "$minutes minutes"
        minutes == 60 -> "1 hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "${minutes / 60}½ hours"
    }

    fun hoursLabel(hours: Int) = when (hours) {
        0 -> "Off"
        1 -> "After 1 hour"
        else -> "After $hours hours"
    }

    /**
     * Every remote press comes here first. A press that wakes the dark screen or answers "Still watching?"
     * does only that (its release is swallowed too), so it doesn't also pause or open something.
     */
    fun onKey(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (event.keyCode == swallowedKey && (event.action == KeyEvent.ACTION_UP || event.repeatCount > 0)) {
            if (event.action == KeyEvent.ACTION_UP) swallowedKey = null
            return true
        }
        swallowedKey = null
        if ((asking || sleeping) && event.action == KeyEvent.ACTION_DOWN) {
            asking = false
            sleeping = false
            swallowedKey = event.keyCode
            return true
        }
        return false
    }

    internal fun stopForTheNight() {
        asking = false
        sleeping = true
    }

    internal fun ask() {
        asking = true
    }

    internal fun cancelAsk() {
        asking = false
    }
}

/**
 * Over the whole app: asks "Still watching?" while something plays, and after no answer stops it ([onStop]
 * takes the app back Home) and keeps the screen dark, without holding it on, so the Shield can sleep.
 */
@Composable
fun PrismTvSleepLayer(playing: Boolean, onStop: () -> Unit) {
    val context = LocalContext.current
    val hours = remember(playing) { PrismTvSleep.stillWatchingHours(context) }
    val lastInput = PrismTvSleep.lastInput
    LaunchedEffect(playing, hours, lastInput) {
        if (!playing || hours <= 0) return@LaunchedEffect
        delay(lastInput + hours * 3_600_000L - SystemClock.uptimeMillis())
        PrismTvSleep.ask()
    }
    LaunchedEffect(playing) { if (!playing) PrismTvSleep.cancelAsk() }
    if (PrismTvSleep.asking) {
        var left by remember { mutableIntStateOf(PrismTvSleep.ASK_SECONDS) }
        LaunchedEffect(Unit) {
            while (left > 0) {
                delay(1_000)
                left--
            }
            PrismTvSleep.stopForTheNight()
            onStop()
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(Color(0xFF15111F), RoundedCornerShape(20.dp))
                    .padding(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BasicText("Still watching?", style = TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold))
                BasicText(
                    "Press any button to keep watching.\nOtherwise Prism stops in $left seconds and lets the TV sleep.",
                    style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp, textAlign = TextAlign.Center),
                )
            }
        }
    }
    if (PrismTvSleep.sleeping) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            BasicText("Press any button to carry on", style = TextStyle(color = Color.White.copy(alpha = 0.3f), fontSize = 16.sp))
        }
    }
}

/** Holds the screen on while the screensaver shows, until its time is up; then it goes dark and lets go. */
@Composable
fun PrismTvKeepAwake() {
    val context = LocalContext.current
    val view = LocalView.current
    val minutes = remember { PrismTvSleep.screenOffMinutes(context) }
    LaunchedEffect(minutes) {
        PrismTvSleep.screensaverDark = false
        if (minutes > 0) {
            delay(minutes * 60_000L)
            PrismTvSleep.screensaverDark = true
        }
    }
    val awake = !PrismTvSleep.screensaverDark && !PrismTvSleep.sleeping
    DisposableEffect(view, awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) {
        onDispose { PrismTvSleep.screensaverDark = false }
    }
}

/** What the screensaver shows: dark once its time is up, else Prism's pictures or Nuvio's posters. */
@Composable
fun PrismTvScreensaver(modifier: Modifier, nuvio: @Composable (Modifier) -> Unit) {
    val context = LocalContext.current
    val prism = remember { PrismTvSleep.prismScreensaver(context) }
    when {
        PrismTvSleep.screensaverDark -> Box(modifier.background(Color.Black))
        prism -> PrismTvPictures(modifier)
        else -> nuvio(modifier)
    }
}

/**
 * The look's pictures one after another with a slow drift, a quote under each, and the logo moving
 * about so nothing stays in one place on the screen. Prism's own art when the look has no pictures.
 */
@Composable
private fun PrismTvPictures(modifier: Modifier) {
    val context = LocalContext.current
    PrismTv.init(context)
    val look = PrismTv.look
    val pictures = look?.pictures.orEmpty()
    val quotes = look?.quotes.orEmpty()
    var index by remember { mutableIntStateOf(if (pictures.isEmpty()) 0 else Random.nextInt(pictures.size)) }
    LaunchedEffect(pictures.size, quotes.size) {
        while (true) {
            delay(20_000)
            index++
        }
    }
    Box(modifier.background(Color.Black)) {
        AnimatedContent(
            targetState = index,
            transitionSpec = { fadeIn(tween(1500)) togetherWith fadeOut(tween(1500)) },
            label = "prism-screensaver",
        ) { i ->
            val drift = remember { Animatable(1f) }
            val left = remember { Random.nextBoolean() }
            LaunchedEffect(Unit) { drift.animateTo(1.12f, tween(23_000, easing = LinearEasing)) }
            val layer = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = drift.value
                    scaleY = drift.value
                    translationX = (drift.value - 1f) * size.width * (if (left) -0.3f else 0.3f)
                }
            Box(Modifier.fillMaxSize()) {
                val picture = pictures.getOrNull(if (pictures.isEmpty()) 0 else i % pictures.size)
                val image = rememberWide(picture)
                if (image != null) {
                    Image(image, contentDescription = null, modifier = layer, contentScale = ContentScale.Crop)
                } else if (picture == null) {
                    Image(
                        painter = painterResource(id = R.drawable.edition_loading_art),
                        contentDescription = null,
                        modifier = layer,
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                val spot = remember { BiasAlignment(Random.nextFloat() * 1.6f - 0.8f, Random.nextFloat() * 0.5f - 0.9f) }
                PrismTvWordmark(modifier = Modifier.align(spot).width(260.dp).height(110.dp), brandFallback = picture != null)
                quotes.getOrNull(if (quotes.isEmpty()) 0 else i % quotes.size)?.let { quote ->
                    BasicText(
                        text = quote,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 140.dp, vertical = 64.dp),
                        style = prismTvStyled(TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 22.sp, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center), "Quote"),
                    )
                }
            }
        }
    }
}
