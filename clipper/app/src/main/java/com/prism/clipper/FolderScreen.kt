package com.prism.clipper

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** See, play, rename and delete what's in the Prism folder; open videos and pictures in the other tabs. */
@Composable
fun FolderScreen(folder: FolderModel, clips: ClipperModel, pictures: PictureModel) {
    val context = LocalContext.current
    val link = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) folder.link(uri)
    }
    LaunchedEffect(folder.tree) { folder.refresh() }

    var renaming by remember { mutableStateOf<FolderItem?>(null) }
    var deleting by remember { mutableStateOf<FolderItem?>(null) }
    var previewing by remember { mutableStateOf<FolderItem?>(null) }
    previewing?.let { item ->
        Preview(folder, item, onClose = { previewing = null }) {
            previewing = null
            if (item.mime.startsWith("image/")) {
                pictures.open(item.uri); clips.tab = Section.Pictures
            } else {
                clips.open(item.uri); clips.tab = Section.Clips
            }
        }
    }

    renaming?.let { item ->
        var name by remember(item) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { folder.rename(item, name); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${item.name}?") },
            text = { Text("This permanently deletes it from your phone.") },
            confirmButton = { TextButton(onClick = { folder.delete(item); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it") } },
        )
    }

    if (folder.tree == null) {
        Text("Link your Prism folder once (Download/Prism) to see everything in it, including files from Send Anywhere. Tap the button, then \"Use this folder\".")
        Button(onClick = { link.launch(folder.startAt) }, Modifier.fillMaxWidth()) { Text("Link the Prism folder") }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (folder.loading) "Loading…" else "${folder.shown.size} ${if (folder.shown.size == 1) "file" else "files"}, ${megabytes(folder.totalBytes())}",
            Modifier.weight(1f),
        )
        TextButton(onClick = folder::refresh) { Text("Refresh") }
        TextButton(onClick = { link.launch(folder.startAt) }) { Text("Change folder") }
    }
    folder.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }

    if (folder.folders.size > 1 || folder.folders.firstOrNull()?.isNotEmpty() == true) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(folder.showFolder == null, { folder.showFolder = null }, label = { Text("All") })
            folder.folders.forEach { f ->
                FilterChip(folder.showFolder == f, { folder.showFolder = f }, label = { Text(f.ifEmpty { "Prism folder" }) })
            }
        }
    }

    folder.shown.forEach { item ->
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumbnail(folder, item, Modifier.clickable { previewing = item })
                    Column(Modifier.weight(1f).clickable { previewing = item }) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${item.folder.ifEmpty { "" }.let { if (it.isEmpty()) "" else "$it · " }}${kind(item.mime)} · ${megabytes(item.bytes)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.modified))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime.ifBlank { "*/*" })
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(view)
                        } catch (e: ActivityNotFoundException) {
                            folder.message = "No app on this phone can open ${item.name}"
                        }
                    }) { Text("Open") }
                    if (item.mime.startsWith("video/") || item.mime.startsWith("audio/")) {
                        TextButton(onClick = { clips.open(item.uri); clips.tab = Section.Clips }) { Text("Edit") }
                    } else if (item.mime.startsWith("image/")) {
                        TextButton(onClick = { pictures.open(item.uri); clips.tab = Section.Pictures }) { Text("Edit") }
                    }
                    TextButton(onClick = { renaming = item }) { Text("Rename") }
                    TextButton(onClick = { deleting = item }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(folder: FolderModel, item: FolderItem, modifier: Modifier) {
    val thumb by produceState<Bitmap?>(null, item.uri) { value = folder.thumbnail(item) }
    Box(
        modifier
            .size(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(if (item.mime.startsWith("audio/")) "♪" else kind(item.mime), style = MaterialTheme.typography.titleLarge)
        }
        if (item.mime.startsWith("video/") || item.mime.startsWith("audio/")) {
            Text("▶", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** Big preview: pictures shown whole, videos and sounds playing right here. */
@Composable
private fun Preview(folder: FolderModel, item: FolderItem, onClose: () -> Unit, onEdit: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (item.mime.startsWith("image/")) {
                        val picture by produceState<Bitmap?>(null, item.uri) {
                            value = withContext(Dispatchers.IO) { runCatching { folder.decodeSmall(item.uri, 2048) }.getOrNull() }
                        }
                        picture?.let {
                            Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        } ?: Text("Loading…", color = Color.White)
                    } else if (item.mime.startsWith("video/") || item.mime.startsWith("audio/")) {
                        val context = LocalContext.current
                        val player = remember(item.uri) {
                            ExoPlayer.Builder(context).build().apply {
                                setMediaItem(MediaItem.fromUri(item.uri))
                                repeatMode = Player.REPEAT_MODE_ONE
                                prepare()
                                play()
                            }
                        }
                        DisposableEffect(player) { onDispose { player.release() } }
                        AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("No preview for this kind of file.", color = Color.White)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.mime.startsWith("image/") || item.mime.startsWith("video/") || item.mime.startsWith("audio/")) {
                        Button(onClick = onEdit, Modifier.weight(1f)) { Text("Edit") }
                    }
                    OutlinedButton(onClick = onClose, Modifier.weight(1f)) { Text("Close") }
                }
            }
        }
    }
}

private fun kind(mime: String): String = when {
    mime.startsWith("video/") -> "Video"
    mime.startsWith("audio/") -> "Sound"
    mime.startsWith("image/") -> "Picture"
    else -> "File"
}
