package com.nuvio.tv.ui.components

import androidx.compose.runtime.snapshotFlow
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.legacyKey
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.json.JSONObject

// Prism: the look's own Home rows (made in Customise > Home, on the TV or the phone), first on Home in every Home
// layout. The titles carry their posters already, so nothing has to be looked up to show them.

class PrismTvRowItem(val id: String, val type: String, val name: String, val poster: String?, val year: String?)

class PrismTvRow(val id: String, val title: String, val items: List<PrismTvRowItem>)

object PrismTvRows {
    const val CINEMETA = "https://v3-cinemeta.strem.io"
    /** Cinemeta too (addresses lose their end slashes before use), but marks the show cards' row, whose posters
     *  play a random episode instead of opening the show. */
    const val CARD_BASE = "$CINEMETA//"

    fun parse(o: JSONObject): List<PrismTvRow> = buildList {
        val rows = o.optJSONArray("rows") ?: return@buildList
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val items = buildList {
                r.optJSONArray("items")?.let { a ->
                    for (j in 0 until a.length()) a.optJSONObject(j)?.let { t ->
                        val id = t.optString("id").takeIf { it.isNotBlank() } ?: return@let
                        add(PrismTvRowItem(id, t.optString("type", "movie"), t.optString("name"), t.optString("poster").takeIf { it.isNotBlank() && it != "null" }, t.optString("year").takeIf { it.isNotBlank() && it != "null" }))
                    }
                }
            }
            add(PrismTvRow(r.optString("id").ifBlank { "row$i" }, r.optString("title", "My row"), items))
        }
    }

    /** Asks Home to redo its rows whenever the look changes, while [owner] lives. */
    fun start(owner: CoroutineScope, onChange: () -> Unit) {
        owner.launch {
            yield()
            snapshotFlow { PrismTv.changes }.drop(1).collect { onChange() }
        }
    }

    /** Puts the look's rows first among Home's catalog rows. */
    fun inject(state: Pair<List<String>, Map<String, CatalogRow>>): Pair<List<String>, Map<String, CatalogRow>> {
        val rows = PrismTv.look?.rows.orEmpty().filter { it.items.isNotEmpty() }
        val cards = PrismTv.look?.cards.orEmpty()
        if (rows.isEmpty() && cards.isEmpty()) return state
        val cardRow = cards.takeIf { it.isNotEmpty() }?.let { list ->
            CatalogRow(
                addonId = "com.linvo.cinemeta",
                addonName = "Prism",
                addonBaseUrl = CARD_BASE,
                catalogId = "prism_cards",
                catalogName = "Play random episode",
                type = ContentType.SERIES,
                items = list.map { c ->
                    val poster = c.poster ?: c.id.takeIf { it.startsWith("tt") }?.let { "https://images.metahub.space/poster/medium/$it/img" }
                    PrismTvRowItem(c.id, c.type, c.name, poster, null).toPreview()
                },
                hasMore = false,
            )
        }
        val made = listOfNotNull(cardRow) + rows.map { r ->
            val items = r.items.map { it.toPreview() }
            CatalogRow(
                addonId = "com.linvo.cinemeta",
                addonName = "Prism",
                addonBaseUrl = CINEMETA,
                catalogId = "prism_row_${r.id}",
                catalogName = r.title,
                type = if (items.count { it.type == ContentType.SERIES } > items.size / 2) ContentType.SERIES else ContentType.MOVIE,
                items = items,
                hasMore = false,
            )
        }
        val keys = made.map { it.legacyKey() }
        return (keys + state.first.filter { it !in keys }) to (state.second + made.associateBy { it.legacyKey() })
    }

    private fun PrismTvRowItem.toPreview() = MetaPreview(
        id = id,
        type = ContentType.fromString(type).takeIf { it != ContentType.UNKNOWN } ?: ContentType.MOVIE,
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = year,
        imdbRating = null,
        genres = emptyList(),
        imdbId = id.takeIf { it.startsWith("tt") },
    )

    /** Finds movies and shows by name on Cinemeta, for adding to a row or a show card. */
    fun search(query: String): List<PrismTvRowItem> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return listOf("movie", "series").flatMap { type ->
            val metas = get("$CINEMETA/catalog/$type/top/search=$q.json")?.optJSONArray("metas") ?: return@flatMap emptyList()
            (0 until minOf(metas.length(), 10)).mapNotNull { i ->
                val m = metas.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("imdb_id").ifBlank { m.optString("id") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PrismTvRowItem(id, type, m.optString("name"), m.optString("poster").takeIf { it.isNotBlank() }, m.optString("releaseInfo").takeIf { it.isNotBlank() })
            }
        }
    }

    private fun get(url: String): JSONObject? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (connection.responseCode !in 200..299) null
            else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
