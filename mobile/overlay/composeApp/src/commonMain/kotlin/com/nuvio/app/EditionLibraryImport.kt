package com.nuvio.app

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Prism (shared with the Supernatural edition): the TV's "Send to phone" shows the library as QR codes. Scanning one opens the
// return page, which hands it on as nuvio://auth/trakt?library=m.tt123~s.tt456 (m = movie, s = series).
// The titles are looked up on Cinemeta and added to this profile's library, skipping ones already there.

internal object EditionLibraryImport {
    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True when [url] carried a library list (so it isn't a Trakt sign-in). */
    fun handle(url: String): Boolean {
        val list = runCatching { Url(url).parameters["library"] }.getOrNull() ?: return false
        importList(list)
        return true
    }

    private const val FILE_HEADER = "prism-library"

    /** This profile's library as a small text file, for "Save library to a file". Null if it's empty. */
    fun exportFile(): ByteArray? {
        val items = LibraryRepository.uiState.value.items
        if (items.isEmpty()) return null
        val list = items.mapNotNull { item ->
            when (item.type) { "movie" -> "m"; "series" -> "s"; else -> null }?.let { "$it.${item.id}" }
        }.joinToString("~")
        return "$FILE_HEADER\n$list\n".encodeToByteArray()
    }

    /** Adds the titles in a library file. False if the file isn't one. */
    fun importFile(bytes: ByteArray): Boolean {
        val text = bytes.decodeToString().trim()
        if (!text.startsWith(FILE_HEADER)) return false
        importList(text.removePrefix(FILE_HEADER).trim())
        return true
    }

    private fun importList(list: String) {
        val wanted = list.split('~').mapNotNull { entry ->
            val type = when (entry.substringBefore('.')) { "m" -> "movie"; "s" -> "series"; else -> null }
            val id = entry.substringAfter('.', "").trim()
            if (type == null || id.isBlank()) null else type to id
        }
        if (wanted.isEmpty()) return
        scope.launch { import(wanted) }
    }

    private suspend fun import(wanted: List<Pair<String, String>>) {
        NuvioToastController.show("Adding ${wanted.size} titles to your library…")
        LibraryRepository.ensureLoaded()
        val missing = wanted.filterNot { (type, id) -> LibraryRepository.isSaved(id, type) }
        val lookups = Semaphore(6)
        val found = coroutineScope {
            missing.map { (type, id) ->
                async {
                    lookups.withPermit {
                        try { resolve(type, id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    }
                }
            }.awaitAll()
        }
        // The TV lists newest first, so save from the bottom up to keep that order.
        var added = 0
        for (item in found.asReversed()) {
            if (item == null || LibraryRepository.isSaved(item.id, item.type)) continue
            LibraryRepository.save(item)
            added++
            delay(5)
        }
        val failed = found.count { it == null }
        val skipped = wanted.size - missing.size
        NuvioToastController.show(
            buildString {
                append("Added $added titles to your library")
                if (skipped > 0) append(", $skipped were already there")
                if (failed > 0) append(", $failed couldn't be found (try again to retry)")
                append('.')
            },
        )
    }

    private suspend fun resolve(type: String, id: String): LibraryItem? {
        val response = httpRequestRaw("GET", "$CINEMETA/meta/$type/$id.json", mapOf("Accept" to "application/json"), "")
        if (response.status !in 200..299) return null
        val meta = json.parseToJsonElement(response.body).jsonObject["meta"]?.jsonObject ?: return null
        fun JsonObject.str(key: String): String? =
            get(key)?.jsonPrimitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        return LibraryItem(
            id = id,
            type = type,
            name = meta.str("name") ?: return null,
            poster = meta.str("poster"),
            banner = meta.str("background"),
            logo = meta.str("logo"),
            description = meta.str("description"),
            releaseInfo = meta.str("releaseInfo"),
            imdbRating = meta.str("imdbRating"),
            imdbId = id.takeIf { it.startsWith("tt") },
            savedAtEpochMs = 0L,
        )
    }
}
