package com.nuvio.app.prism

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.nuvio.app.features.profiles.NuvioProfile

// Prism: the Hero section (the look's own clips, playing silently at the top of Home) and profile photos.

/**
 * Shuffled order with no repeats: every clip plays once before any plays again, and a new round never
 * starts with the clip that just ended.
 */
object PrismClipBag {
    private var queue = ArrayDeque<String>()
    private var last: String? = null

    fun next(clips: List<PrismClip>, inOrder: Boolean = PrismStore.look.clipsInOrder): PrismClip? {
        if (clips.isEmpty()) return null
        if (inOrder) {
            // The one after the clip that played last, round and round.
            val at = clips.indexOfFirst { it.file == last }
            return clips[(at + 1) % clips.size].also { last = it.file }
        }
        val files = clips.map { it.file }.toSet()
        queue.removeAll { it !in files }
        if (queue.isEmpty()) {
            val round = files.shuffled().toMutableList()
            if (round.size > 1 && round.first() == last) round.add(round.removeAt(0))
            queue.addAll(round)
        }
        val file = queue.removeFirst()
        last = file
        return clips.first { it.file == file }
    }
}

internal fun LazyListScope.prismHero() {
    item(key = "prism_hero", contentType = "prism_hero") {
        val look = PrismStore.look
        if (!look.clipsOn || look.clips.isEmpty()) return@item
        PrismHero(look)
    }
}

@Composable
private fun PrismHero(look: PrismLook) {
    val platform = Prism.platform ?: return
    var clip by remember(look.id) { mutableStateOf(PrismClipBag.next(look.clips)) }
    val current = clip ?: return
    val logo by rememberPrismOriginal(PrismStore.launchLogo(look)?.file)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 16:9 across the screen, but never taller than about half of a PC window.
        val height = min(maxWidth * 9f / 16f, 460.dp)
        Box(Modifier.fillMaxWidth().height(height).clipToBounds().background(Color.Black)) {
            platform.ClipPlayer(
                path = PrismStore.mediaPath(current.file),
                modifier = Modifier.fillMaxSize(),
                onEnded = { clip = PrismClipBag.next(PrismStore.look.clips) },
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)),
                ),
            )
            logo?.let {
                Image(
                    it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.align(Alignment.BottomStart).padding(18.dp).height(56.dp).widthIn(max = 280.dp),
                )
            }
        }
    }
}

/** The key a profile photo is kept under: the profile's number, which stays the same on this device. */
fun prismProfileKey(profile: NuvioProfile): String = "p${profile.profileIndex}"

/** The profile's own photo, if one was chosen in Customise (see prism/mobile/apply.sh: profileAvatarImageUrl). */
fun prismAvatarUrl(profile: NuvioProfile): String? {
    val file = PrismStore.avatars[prismProfileKey(profile)] ?: return null
    val p = Prism.platform ?: return null
    return p.fileUrl(PrismStore.mediaPath(file))
}
