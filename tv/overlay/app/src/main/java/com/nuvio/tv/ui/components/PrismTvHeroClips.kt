package com.nuvio.tv.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

// Prism: the look's hero clips on the TV, as on the phone, playing silently behind Home and darkened like the
// Home background so the rows stay readable. Shuffled with no repeats until all have played, or in order.

private object PrismTvClipBag {
    private val queue = ArrayDeque<String>()
    private var last: String? = null

    fun next(clips: List<String>, inOrder: Boolean): String? {
        if (clips.isEmpty()) return null
        if (inOrder) return clips[(clips.indexOf(last) + 1) % clips.size].also { last = it }
        queue.removeAll { it !in clips }
        if (queue.isEmpty()) {
            val round = clips.distinct().shuffled().toMutableList()
            if (round.size > 1 && round.first() == last) round.add(round.removeAt(0))
            queue.addAll(round)
        }
        return queue.removeFirst().also { last = it }
    }
}

@Composable
fun PrismTvHeroClips() {
    val look = PrismTv.look ?: return
    if (!look.clipsOn || look.clips.isEmpty()) return
    var clip by remember(look.clips, look.clipsInOrder) { mutableStateOf(PrismTvClipBag.next(look.clips, look.clipsInOrder)) }
    val file = clip?.let { PrismTv.mediaFile(it) }?.takeIf { it.exists() } ?: return
    val player = remember { MediaPlayer() }
    var surface by remember { mutableStateOf<Surface?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() }; surface?.release() } }
    DisposableEffect(file, surface) {
        val s = surface
        if (s != null) runCatching {
            player.reset()
            player.setSurface(s)
            player.setDataSource(file.path)
            player.setVolume(0f, 0f)
            // One clip loops on its own; with several, the next starts when one ends.
            player.isLooping = look.clips.size == 1
            player.setOnCompletionListener { clip = PrismTvClipBag.next(PrismTv.look?.clips.orEmpty(), PrismTv.look?.clipsInOrder == true) }
            player.setOnErrorListener { _, _, _ -> clip = PrismTvClipBag.next(PrismTv.look?.clips.orEmpty(), PrismTv.look?.clipsInOrder == true); true }
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
        }
        onDispose { runCatching { player.stop() } }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st) }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { surface = null; return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = look.backgroundDim.coerceIn(0.2f, 0.85f))))
    }
}
