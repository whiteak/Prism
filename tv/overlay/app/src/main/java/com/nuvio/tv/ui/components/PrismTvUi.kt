package com.nuvio.tv.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Prism on the TV: the opening, Home background, "Play random episode" and the library from a file.

/**
 * Holds the opening up for the look's seconds on each cold start, with its sound. A remote press skips it,
 * but only one that starts after the opening is up: the release of the button that opened Prism from the
 * TV's home screen arrives here too and used to cut the opening short straight away.
 */
object PrismTvIntro {
    var holding by mutableStateOf(false)
        private set
    private var started = false
    private var pressed = false
    private var observing = false
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context) {
        PrismTv.init(context)
        if (!observing) {
            var c: Context? = context
            while (c is ContextWrapper && c !is ComponentActivity) c = c.baseContext
            (c as? ComponentActivity)?.lifecycle?.addObserver(
                LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { PrismTvSounds.stop(); stop() } },
            )
            observing = true
        }
        if (started) return
        started = true
        val look = PrismTv.look ?: return
        holding = true
        pressed = false
        PrismTvSounds.play(context, "Opening")
        main.postDelayed({ holding = false }, look.openingSeconds.coerceIn(1, 30) * 1000L)
    }

    fun skipOnKey(event: android.view.KeyEvent): Boolean {
        if (!holding) return false
        when (event.action) {
            android.view.KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) pressed = true
            android.view.KeyEvent.ACTION_UP -> if (pressed) stop()
        }
        return true
    }

    fun stop() {
        if (holding) PrismTvSounds.stop()
        holding = false
    }
}

@Composable
internal fun rememberWide(picture: PrismTvPicture?): ImageBitmap? {
    val state by produceState<ImageBitmap?>(null, picture?.file) {
        value = picture?.let { withContext(Dispatchers.IO) { PrismTvImages.wide(it)?.asImageBitmap() } }
    }
    return state
}

/** The opening: your own picture (faded in as soon as it's ready) or Prism's art, held still. */
@Composable
fun PrismTvOpening() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PrismTvIntro.start(context) }
    PrismTv.init(context)
    val look = PrismTv.look
    val custom = rememberWide(look?.opening)
    val fade = remember { Animatable(if (look?.opening == null) 1f else 0f) }
    LaunchedEffect(custom != null) { if (custom != null) fade.animateTo(1f, tween(350)) }
    val layer = Modifier.fillMaxSize()
    Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        if (custom != null) {
            Image(custom, contentDescription = null, modifier = layer, contentScale = ContentScale.Crop)
        } else if (look?.opening == null) {
            Image(
                painter = painterResource(id = R.drawable.edition_loading_art),
                contentDescription = null,
                modifier = layer,
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, -0.4f),
            )
        }
    }
}

/**
 * The look's logo. Without one, nothing on the opening (the built-in art already has PRISM across it, and your
 * own picture stays clear), or the Prism wordmark where [brandFallback] asks for it.
 */
@Composable
fun PrismTvWordmark(modifier: Modifier = Modifier, contentDescription: String? = null, drawableOverride: Int? = null, brandFallback: Boolean = false) {
    val context = LocalContext.current
    PrismTv.init(context)
    val look = PrismTv.look
    val logo = look?.logo
    if (logo != null) {
        val image by produceState<ImageBitmap?>(null, logo.file) {
            value = withContext(Dispatchers.IO) { PrismTvImages.logo(logo)?.asImageBitmap() }
        }
        image?.let { Image(it, contentDescription = contentDescription, modifier = modifier, contentScale = ContentScale.Fit) }
    } else if (brandFallback) {
        BrandWordmark(modifier = modifier, contentDescription = contentDescription, drawableOverride = drawableOverride)
    }
}

/** The look's quote, under the logo (no spinner over the opening). */
@Composable
fun PrismTvLoadingExtras() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PrismTv.quote?.let { quote ->
            BasicText(
                text = quote,
                modifier = Modifier.padding(horizontal = 96.dp),
                style = prismTvStyled(TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center), "Quote"),
            )
        }
    }
}

/** The look's Home background, darkened so text stays readable. */
fun Modifier.prismBackdrop(): Modifier = composed {
    val context = LocalContext.current
    PrismTv.init(context)
    val look = PrismTv.look
    val image = rememberWide(look?.background) ?: return@composed this
    val dim = (look?.backgroundDim ?: 0.35f).coerceIn(0f, 0.85f)
    drawBehind {
        drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero)
        drawRect(Color.Black.copy(alpha = dim))
    }
}

// ---- Play random episode (side menu) ----

const val PRISM_RANDOM_ROUTE = "prism/random"
val prismRandomIcon: ImageVector get() = Icons.Filled.Shuffle

private const val CINEMETA = "https://v3-cinemeta.strem.io"

private fun getJson(url: String): JSONObject? {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        if (connection.responseCode !in 200..299) null
        else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
        connection.disconnect()
    }
}

object PrismTvRandom {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busySince = 0L

    /** Picks one of the look's show cards and a random episode, plays the card's sound, then opens the streams. */
    /** A poster in the show cards' row on Home: plays a random episode of that show. */
    fun handled(context: Context, addonBaseUrl: String?, id: String, open: (String) -> Unit): Boolean {
        if (addonBaseUrl != PrismTvRows.CARD_BASE) return false
        go(context, only = id, open = open)
        return true
    }

    fun go(context: Context, only: String? = null, open: (String) -> Unit) {
        PrismTv.init(context)
        val cards = PrismTv.look?.cards.orEmpty().filter { only == null || it.id == only }
        if (cards.isEmpty()) {
            Toast.makeText(context, "Add show cards in Customise > Home first", Toast.LENGTH_LONG).show()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - busySince < 20_000) return
        busySince = now
        val card = cards.random()
        scope.launch {
            val route = async(Dispatchers.IO) { runCatching { routeFor(card) }.getOrNull() }
            val base = "card:${card.type}:${card.id}".takeIf { PrismTv.look?.soundSet(it)?.isNotEmpty() == true } ?: "PickEpisode"
            val soundDone = kotlinx.coroutines.CompletableDeferred<Unit>()
            PrismTvSounds.play(context, base) { soundDone.complete(Unit) }
            soundDone.await()
            busySince = 0L
            val target = route.await()
            if (target != null) open(target)
            else Toast.makeText(context, "Couldn't reach Cinemeta to pick an episode", Toast.LENGTH_LONG).show()
        }
    }

    private fun routeFor(card: PrismTvTitle): String? {
        val meta = getJson("$CINEMETA/meta/${card.type}/${card.id}.json")?.optJSONObject("meta")
        val name = meta?.optString("name")?.takeIf { it.isNotBlank() } ?: card.name
        if (card.type != "series") {
            return Screen.Stream.createRoute(videoId = card.id, contentType = card.type, title = name, contentId = card.id, contentName = name)
        }
        val videos = meta?.optJSONArray("videos") ?: return null
        val episodes = buildList {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val season = v.optInt("season", 0)
                val episode = v.optInt("episode", v.optInt("number", 0))
                if (season > 0 && episode > 0) add(Triple(season, episode, v.optString("name").takeIf { it.isNotBlank() }))
            }
        }
        val (season, episode, episodeName) = episodes.randomOrNull() ?: return null
        return Screen.Stream.createRoute(
            videoId = "${card.id}:$season:$episode",
            contentType = "series",
            title = name,
            season = season,
            episode = episode,
            episodeName = episodeName,
            contentId = card.id,
            contentName = name,
        )
    }
}

// ---- Library from a file (made on the phone: Customise > Library) ----

object PrismTvLibraryImport {
    private const val FILE_HEADER = "prism-library"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isLibraryFile(bytes: ByteArray): Boolean = bytes.decodeToString().trimStart().startsWith(FILE_HEADER)

    fun import(context: Context, bytes: ByteArray, quiet: Boolean = false) {
        val app = context.applicationContext
        val wanted = bytes.decodeToString().trim().removePrefix(FILE_HEADER).trim().split('~').mapNotNull { entry ->
            val type = when (entry.substringBefore('.')) { "m" -> "movie"; "s" -> "series"; else -> null }
            val id = entry.substringAfter('.', "").trim()
            if (type == null || id.isBlank()) null else type to id
        }
        if (wanted.isEmpty()) return
        val main = Handler(Looper.getMainLooper())
        fun toast(text: String) { if (!quiet) main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() } }
        toast("Adding ${wanted.size} titles to your library…")
        scope.launch {
            val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(app, PrismTvEntryPoint::class.java)
            val library = deps.libraryPreferences()
            val profileId = deps.profileManager().activeProfileId.value
            val missing = wanted.filterNot { (type, id) -> library.containsItem(id, type, profileId) }
            val lookups = Semaphore(6)
            val found = coroutineScope {
                missing.map { (type, id) -> async { lookups.withPermit { runCatching { resolve(type, id) }.getOrNull() } } }.awaitAll()
            }
            // The list is newest first, so the top of it gets the latest time.
            val now = System.currentTimeMillis()
            var added = 0
            found.forEachIndexed { index, item ->
                if (item != null && !library.containsItem(item.id, item.type, profileId)) {
                    library.addItem(item.copy(addedAt = now - index * 1000L), profileId)
                    added++
                }
            }
            val failed = found.count { it == null }
            val skipped = wanted.size - missing.size
            toast(buildString {
                append("Added $added titles to your library")
                if (skipped > 0) append(", $skipped were already there")
                if (failed > 0) append(", $failed couldn't be found (try again to retry)")
                append('.')
            })
        }
    }

    private fun resolve(type: String, id: String): SavedLibraryItem? {
        val meta = getJson("$CINEMETA/meta/$type/$id.json")?.optJSONObject("meta") ?: return null
        fun str(key: String): String? = meta.optString(key, "").takeIf { it.isNotBlank() && it != "null" }
        return SavedLibraryItem(
            id = id,
            type = type,
            name = str("name") ?: return null,
            poster = str("poster"),
            posterShape = PosterShape.POSTER,
            background = str("background"),
            description = str("description"),
            releaseInfo = str("releaseInfo"),
            imdbRating = str("imdbRating")?.toFloatOrNull(),
            genres = emptyList(),
            addonBaseUrl = null,
            logo = str("logo"),
        )
    }
}
