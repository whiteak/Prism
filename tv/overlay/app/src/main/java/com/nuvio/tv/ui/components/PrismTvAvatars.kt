package com.nuvio.tv.ui.components

import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Prism: a photo of your own for each profile, as on the phone. The photos live on this TV only, in
// prism/avatars/ (outside the looks' media, so they stay whichever look is in use), and win over the account's
// avatar wherever the profile's picture shows.

object PrismTvAvatars {
    /** The photo in use for each profile, by profile number. */
    private val inUse = mutableStateMapOf<Int, String>()
    /** Every photo kept for each profile, to switch between. */
    private val kept = mutableStateMapOf<Int, List<String>>()
    private var loaded = false

    private fun dir(): File? = PrismTv.folder()?.let { File(it, "avatars").apply { mkdirs() } }
    private fun listFile() = dir()?.let { File(it, "avatars.json") }

    private fun load() {
        if (loaded) return
        val file = listFile() ?: return
        loaded = true
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        o.keys().forEach { key ->
            val id = key.toIntOrNull() ?: return@forEach
            val v = o.optJSONObject(key) ?: return@forEach
            v.optString("inUse").takeIf { it.isNotBlank() }?.let { inUse[id] = it }
            kept[id] = buildList { v.optJSONArray("kept")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(::add) } }
        }
    }

    private fun save() {
        val o = JSONObject()
        (inUse.keys + kept.keys).forEach { id ->
            o.put(id.toString(), JSONObject().put("inUse", inUse[id] ?: "").put("kept", JSONArray(kept[id].orEmpty())))
        }
        listFile()?.let { f -> runCatching { f.writeText(o.toString()) } }
    }

    /** The profile's own photo as a file address, or null for the account's picture (null reads the saved list in). */
    fun url(profileId: Int?): String? {
        load()
        val name = profileId?.let { inUse[it] } ?: return null
        val file = dir()?.let { File(it, name) }?.takeIf { it.exists() } ?: return null
        return file.toURI().toString()
    }

    fun photos(profileId: Int): List<String> { load(); return (kept[profileId].orEmpty() + listOfNotNull(inUse[profileId])).distinct() }

    fun inUse(profileId: Int): String? { load(); return inUse[profileId] }

    fun file(name: String): File? = dir()?.let { File(it, name) }

    /** Takes a picture just copied into the look's media and makes it the profile's photo. */
    fun add(profileId: Int, mediaName: String) {
        load()
        val from = PrismTv.mediaFile(mediaName) ?: return
        val to = file(mediaName) ?: return
        if (!from.renameTo(to)) { runCatching { from.copyTo(to, overwrite = true); from.delete() } }
        kept[profileId] = (photos(profileId) + mediaName).distinct()
        inUse[profileId] = mediaName
        save()
    }

    fun use(profileId: Int, name: String?) {
        load()
        if (name == null) inUse.remove(profileId) else inUse[profileId] = name
        save()
    }

    fun forget(profileId: Int, name: String) {
        load()
        kept[profileId] = photos(profileId) - name
        if (inUse[profileId] == name) inUse.remove(profileId)
        file(name)?.delete()
        save()
    }

    /** The photo in use and the kept photos of every profile, for a backup. */
    fun snapshot(): Pair<Map<Int, String>, Map<Int, List<String>>> {
        load()
        return inUse.toMap() to (inUse.keys + kept.keys).associateWith { photos(it) }
    }

    /** Replaces every profile's photos with a backup's (their files already in [file]). */
    fun restore(using: Map<Int, String>, keeping: Map<Int, List<String>>) {
        load()
        val wanted = using.values.toSet() + keeping.values.flatten()
        (inUse.values.toSet() + kept.values.flatten()).filter { it !in wanted }.forEach { file(it)?.delete() }
        inUse.clear(); inUse.putAll(using)
        kept.clear(); kept.putAll(keeping)
        save()
    }
}
