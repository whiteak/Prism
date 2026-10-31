package com.nuvio.tv.ui.components

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Prism: Save and share on the TV, as on the phone. A backup is the same file the phone and PC make (every look,
// font, picture, sound and profile photo; clips stay out, they're too big), so one made on any device restores on
// any other. Files are saved to the TV's Download/Prism folder, where Send Anywhere and file managers find them.

object PrismTvBackup {
    fun isBackup(bytes: ByteArray): Boolean =
        runCatching { JSONObject(bytes.decodeToString()).has("prismBackup") }.getOrDefault(false)

    fun backup(context: Context): ByteArray {
        val state = PrismTv.stateForBackup(context)
        val looks = state.optJSONArray("looks") ?: JSONArray()
        val media = JSONObject()
        // Clips stay out of backups: a look keeps the clips it has on each device.
        val clipFiles = mutableSetOf<String>()
        val bare = JSONArray()
        for (i in 0 until looks.length()) {
            val l = JSONObject(looks.optJSONObject(i)?.toString() ?: continue)
            l.optJSONArray("clips")?.let { a -> for (j in 0 until a.length()) a.optJSONObject(j)?.optString("file")?.let(clipFiles::add) }
            l.put("clips", JSONArray())
            bare.put(l)
        }
        val text = bare.toString()
        PrismTv.mediaDir(context).listFiles()?.forEach { f ->
            if (f.name !in clipFiles && (text.contains("\"${f.name}\"") || text.contains(":${f.name}\"")))
                media.put(f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        val fonts = JSONArray()
        PrismTv.mediaDir(context).listFiles()?.filter { it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true) }
            ?.filter { media.has(it.name) }?.forEach { fonts.put(JSONObject().put("name", it.nameWithoutExtension).put("file", it.name)) }
        val (using, keeping) = PrismTvAvatars.snapshot()
        val avatars = JSONObject()
        val photos = JSONObject()
        using.forEach { (id, file) -> avatars.put("p$id", file) }
        keeping.forEach { (id, files) -> photos.put("p$id", JSONArray(files)) }
        (keeping.values.flatten() + using.values).toSet().forEach { name ->
            PrismTvAvatars.file(name)?.takeIf { it.exists() }?.let { media.put(name, Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) }
        }
        return JSONObject()
            .put("prismBackup", 1)
            .put("state", JSONObject().put("activeId", state.optString("activeId", "")).put("looks", bare).put("fonts", fonts).put("profileLooks", state.optJSONObject("profileLooks") ?: JSONObject()))
            .put("avatars", avatars)
            .put("avatarPhotos", photos)
            .put("media", media)
            .toString().toByteArray()
    }

    /** Replaces everything in Prism on this TV with a backup; false if the file isn't one. */
    fun restore(context: Context, bytes: ByteArray): Boolean {
        val b = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull() ?: return false
        if (!b.has("prismBackup")) return false
        val state = b.optJSONObject("state") ?: return false
        val looks = state.optJSONArray("looks") ?: return false
        if (looks.length() == 0) return false
        fun ids(key: String): Map<Int, Any?> = buildMap {
            b.optJSONObject(key)?.let { o -> o.keys().forEach { k -> k.removePrefix("p").toIntOrNull()?.let { put(it, o.opt(k)) } } }
        }
        val using = ids("avatars").mapNotNull { (id, v) -> (v as? String)?.let { id to it } }.toMap()
        val keeping = ids("avatarPhotos").mapNotNull { (id, v) -> (v as? JSONArray)?.let { a -> id to (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } }.toMap()
        val photoFiles = using.values.toSet() + keeping.values.flatten()
        b.optJSONObject("media")?.let { media ->
            media.keys().forEach { name ->
                if (name.contains('/') || name.contains("..")) return@forEach
                val data = runCatching { Base64.decode(media.optString(name), Base64.DEFAULT) }.getOrNull() ?: return@forEach
                val to = if (name in photoFiles) PrismTvAvatars.file(name) else File(PrismTv.mediaDir(context), name)
                to?.let { runCatching { it.writeBytes(data) } }
            }
        }
        // A look that's still here keeps the clips it has on this TV.
        val here = PrismTv.stateForBackup(context).optJSONArray("looks") ?: JSONArray()
        val clipsHere = (0 until here.length()).mapNotNull { here.optJSONObject(it) }.associate { it.optString("id") to it.optJSONArray("clips") }
        for (i in 0 until looks.length()) looks.optJSONObject(i)?.let { l -> clipsHere[l.optString("id")]?.let { l.put("clips", it) } }
        val active = state.optString("activeId", "").takeIf { id -> (0 until looks.length()).any { looks.optJSONObject(it)?.optString("id") == id } }
            ?: looks.optJSONObject(0)?.optString("id").orEmpty()
        PrismTv.restoreState(context, JSONObject().put("activeId", active).put("looks", looks).put("profileLooks", state.optJSONObject("profileLooks") ?: JSONObject()))
        PrismTvAvatars.restore(using, keeping)
        return true
    }

    /** This profile's library as a .prismlib file, the same kind the phone makes; null when it's empty. */
    suspend fun libraryFile(context: Context): ByteArray? {
        val deps = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, PrismTvEntryPoint::class.java)
        val items = deps.libraryPreferences().getAllItems()
        val list = items.mapNotNull { item ->
            when (item.type) { "movie" -> "m"; "series" -> "s"; else -> null }?.let { "$it.${item.id}" }
        }
        if (list.isEmpty()) return null
        return "prism-library\n${list.joinToString("~")}\n".toByteArray()
    }

    /** Saves a file to Download/Prism on the TV; the folder it went to, or null if it couldn't be saved. */
    fun saveToDownloads(context: Context, name: String, bytes: ByteArray, mime: String = "application/octet-stream"): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Prism")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Prism").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
        }
        "Download/Prism"
    }.getOrNull()
}
