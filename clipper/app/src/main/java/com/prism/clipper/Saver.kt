package com.prism.clipper

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Copies a finished file to where you want it. Returns a short description of where it went. */
object Saver {
    const val PRISM_FOLDER = "Download/Prism"

    /** [sub] is a folder inside the Prism folder (Phone, TV, Icons) or null for the Prism folder itself. */
    fun toPrismFolder(context: Context, file: File, name: String, mime: String, sub: String? = null): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Prism" + (sub?.let { "/$it" } ?: ""))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the file in $PRISM_FOLDER")
        try {
            copy(context, file, uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return PRISM_FOLDER + (sub?.let { "/$it" } ?: "")
    }

    fun toFolder(context: Context, file: File, tree: Uri, name: String, mime: String, sub: String? = null): String {
        val root = DocumentFile.fromTreeUri(context, tree) ?: error("That folder can't be opened. Pick it again.")
        val folder = if (sub == null) root else {
            root.findFile(sub)?.takeIf { it.isDirectory } ?: root.createDirectory(sub) ?: error("Couldn't make the $sub folder there.")
        }
        val target = folder.createFile(mime, name) ?: error("Couldn't create the file there. Pick the folder again.")
        copy(context, file, target.uri)
        return listOfNotNull(root.name ?: "your folder", sub).joinToString("/")
    }

    fun copy(context: Context, file: File, target: Uri) {
        val out = context.contentResolver.openOutputStream(target, "wt") ?: error("Couldn't write the file")
        out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
    }

    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        /** Android wants to ask you itself (files from the gallery). */
        data class Ask(val request: IntentSender) : DeleteResult
        data object Failed : DeleteResult
    }

    fun deleteOriginal(context: Context, uri: Uri): DeleteResult {
        val resolver = context.contentResolver
        val deleted = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(resolver, uri)
            else resolver.delete(uri, null, null) > 0
        }
        if (deleted.getOrNull() == true) return DeleteResult.Deleted
        if (uri.authority == MediaStore.AUTHORITY) {
            return DeleteResult.Ask(MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender)
        }
        val error = deleted.exceptionOrNull()
        if (error is RecoverableSecurityException) return DeleteResult.Ask(error.userAction.actionIntent.intentSender)
        return DeleteResult.Failed
    }

    fun folderName(context: Context, tree: Uri): String =
        DocumentFile.fromTreeUri(context, tree)?.name ?: "Chosen folder"
}
