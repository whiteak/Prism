package com.nuvio.app.prism

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.home.MetaPreview

// Prism: sort for every catalog's "see all" page (any add-on's, including ones added later). It sorts what has
// loaded so far; more loads in as you scroll and takes its place. Each catalog remembers its own choice, and
// choosing one goes back to the top.

enum class PrismCatalogOrder(val label: String) {
    Catalog("Catalog order"),
    AToZ("A to Z"),
    ZToA("Z to A"),
    Newest("Newest"),
    Oldest("Oldest"),
    Rating("Highest rated"),
}

object PrismCatalogSort {
    private val path get() = Prism.platform?.let { "${it.dataDir}/catalog-sort.txt" }

    /** The catalog on screen, for the chips in its header. */
    internal var showing by mutableStateOf<String?>(null)

    // Read once, the first time a catalog opens: one "catalog<TAB>order" line each.
    private val orders by lazy {
        val saved = path?.let { p -> Prism.platform?.readBytes(p)?.decodeToString() }.orEmpty()
        mutableStateMapOf<String, PrismCatalogOrder>().apply {
            saved.lines().forEach { line ->
                val key = line.substringBeforeLast('\t', "")
                val order = PrismCatalogOrder.entries.firstOrNull { it.name == line.substringAfterLast('\t') }
                if (key.isNotEmpty() && order != null) put(key, order)
            }
        }
    }

    fun key(target: CatalogTarget): String = target.toString().replace('\t', ' ').replace('\n', ' ')

    fun orderFor(key: String?): PrismCatalogOrder = key?.let { orders[it] } ?: PrismCatalogOrder.Catalog

    fun choose(key: String, order: PrismCatalogOrder) {
        if (order == PrismCatalogOrder.Catalog) orders.remove(key) else orders[key] = order
        val text = orders.entries.joinToString("\n") { "${it.key}\t${it.value.name}" }
        path?.let { p -> runCatching { Prism.platform?.writeBytes(p, text.encodeToByteArray()) } }
    }

    private fun MetaPreview.year(): Int? =
        (rawReleaseDate ?: releaseInfo)?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

    private fun MetaPreview.rating(): Double? = imdbRating?.toDoubleOrNull()

    fun apply(target: CatalogTarget, items: List<MetaPreview>): List<MetaPreview> = when (orderFor(key(target))) {
        PrismCatalogOrder.Catalog -> items
        PrismCatalogOrder.AToZ -> items.sortedBy { it.name.lowercase() }
        PrismCatalogOrder.ZToA -> items.sortedByDescending { it.name.lowercase() }
        // Titles without a year or rating go last either way.
        PrismCatalogOrder.Newest -> items.sortedWith(compareBy<MetaPreview> { it.year() == null }.thenByDescending { it.year() })
        PrismCatalogOrder.Oldest -> items.sortedWith(compareBy<MetaPreview> { it.year() == null }.thenBy { it.year() })
        PrismCatalogOrder.Rating -> items.sortedWith(compareBy<MetaPreview> { it.rating() == null }.thenByDescending { it.rating() })
    }
}

/** Tells the header which catalog is showing, and goes back to the top when its order changes. */
@Composable
fun PrismCatalogSortEffect(target: CatalogTarget, grid: LazyGridState) {
    val key = PrismCatalogSort.key(target)
    SideEffect { PrismCatalogSort.showing = key }
    val order = PrismCatalogSort.orderFor(key)
    // Opening a catalog keeps its place; only a new choice jumps to the top.
    var last by remember(key) { mutableStateOf(order) }
    LaunchedEffect(key, order) {
        if (order != last) {
            last = order
            grid.scrollToItem(0)
        }
    }
}

/** The sort chips under a catalog's title. */
@Composable
fun PrismCatalogSortRow() {
    val key = PrismCatalogSort.showing ?: return
    val order = PrismCatalogSort.orderFor(key)
    Row(
        Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrismCatalogOrder.entries.forEach { o ->
            Chip(label = o.label, selected = order == o, onClick = { PrismCatalogSort.choose(key, o) })
        }
    }
}
