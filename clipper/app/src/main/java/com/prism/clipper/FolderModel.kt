package com.prism.clipper

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [folder] is the folder inside the Prism folder it sits in (Phone, TV, Icons...), or "" for the top. */
data class FolderItem(val uri: Uri, val name: String, val mime: String, val bytes: Long, val modified: Long, val folder: String = "")

/**
 * Lists what's in the Prism folder. Android only shows other apps' files (Send Anywhere, Prism) through a
 * folder you link once, so this keeps that link.
 */
class FolderModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("clipper", Context.MODE_PRIVATE)

    var tree by mutableStateOf(prefs.getString("prismTree", null)?.let(Uri::parse)); private set
    var items by mutableStateOf<List<FolderItem>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    /** Which folder to show: null for everything. */
    var showFolder by mutableStateOf<String?>(null)
    val folders get() = items.map { it.folder }.distinct().sorted()
    val shown get() = showFolder?.let { f -> items.filter { it.folder == f } } ?: items

    /** Where the folder picker opens, so you land on Download/Prism. */
    val startAt: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/Prism")

    fun link(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        tree = uri
        prefs.edit { putString("prismTree", uri.toString()) }
        refresh()
    }

    fun refresh() {
        val t = tree ?: return
        val ctx = getApplication<Application>()
        loading = true
        viewModelScope.launch {
            items = withContext(Dispatchers.IO) {
                runCatching {
                    // The top of the Prism folder plus one level of folders (Phone, TV, Icons and any others).
                    val all = DocumentFile.fromTreeUri(ctx, t)?.listFiles().orEmpty()
                    val top = all.filter { it.isFile }.map { it to "" }
                    val inner = all.filter { it.isDirectory }.flatMap { dir ->
                        dir.listFiles().filter { it.isFile }.map { it to (dir.name ?: "") }
                    }
                    (top + inner)
                        .map { (f, folder) -> FolderItem(f.uri, f.name ?: "?", f.type ?: "", f.length(), f.lastModified(), folder) }
                        .sortedByDescending { it.modified }
                }.getOrElse {
                    message = "Can't read that folder any more. Link it again."
                    emptyList()
                }
            }
            loading = false
        }
    }

    fun rename(item: FolderItem, newName: String) {
        val ctx = getApplication<Application>()
        val clean = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { DocumentsContract.renameDocument(ctx.contentResolver, item.uri, clean) != null }.getOrDefault(false)
            }
            message = if (ok) "Renamed to $clean" else "Couldn't rename ${item.name}"
            refresh()
        }
    }

    fun delete(item: FolderItem) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, item.uri) }.getOrDefault(false)
            }
            message = if (ok) "Deleted ${item.name}, freeing ${megabytes(item.bytes)}" else "Couldn't delete ${item.name}"
            refresh()
        }
    }

    fun totalBytes(): Long = shown.sumOf { it.bytes }

    private val thumbs = LruCache<Uri, Bitmap>(80)

    /** A small picture of the file: Android's own thumbnail, or one made from the picture or video. */
    suspend fun thumbnail(item: FolderItem): Bitmap? {
        thumbs.get(item.uri)?.let { return it }
        val ctx = getApplication<Application>()
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(item.uri, Size(320, 320), null) }.getOrNull()
                ?: when {
                    item.mime.startsWith("image/") -> runCatching { decodeSmall(item.uri, 320) }.getOrNull()
                    item.mime.startsWith("video/") -> runCatching {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(ctx, item.uri)
                            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                        } finally {
                            r.release()
                        }
                    }.getOrNull()
                    else -> null
                }
        }
        if (bitmap != null) thumbs.put(item.uri, bitmap)
        return bitmap
    }

    /** The picture at up to [maxSide] pixels, for previews. */
    fun decodeSmall(uri: Uri, maxSide: Int): Bitmap {
        val ctx = getApplication<Application>()
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val biggest = maxOf(info.size.width, info.size.height)
            if (biggest > maxSide) {
                val scale = maxSide.toFloat() / biggest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
    }
}
