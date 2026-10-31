package com.nuvio.tv.ui.components

import android.content.Context
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview

// Prism: sort for every catalog's "see all" page on the TV, as on the phone. It sorts what has loaded so far;
// more loads in as you scroll and takes its place. Each catalog remembers its own choice, and choosing one goes
// back to the top.

enum class PrismTvCatalogOrder(val label: String) {
    Catalog("Catalog order"),
    AToZ("A to Z"),
    ZToA("Z to A"),
    Newest("Newest"),
    Oldest("Oldest"),
    Rating("Highest rated"),
}

object PrismTvCatalogSort {
    private const val PREFS = "prism_catalog_sort"
    private val orders = mutableStateMapOf<String, PrismTvCatalogOrder>()
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            PrismTvCatalogOrder.entries.firstOrNull { it.name == value }?.let { orders[key] = it }
        }
    }

    fun orderFor(key: String): PrismTvCatalogOrder = orders[key] ?: PrismTvCatalogOrder.Catalog

    fun choose(context: Context, key: String, order: PrismTvCatalogOrder) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (order == PrismTvCatalogOrder.Catalog) {
            orders.remove(key); prefs.remove(key)
        } else {
            orders[key] = order; prefs.putString(key, order.name)
        }
        prefs.apply()
    }

    private fun MetaPreview.year(): Int? = releaseInfo?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

    /** The row with its items in the catalog's chosen order. */
    fun apply(key: String, row: CatalogRow?): CatalogRow? {
        if (row == null) return null
        val items = row.items
        val sorted = when (orderFor(key)) {
            PrismTvCatalogOrder.Catalog -> return row
            PrismTvCatalogOrder.AToZ -> items.sortedBy { it.name.lowercase() }
            PrismTvCatalogOrder.ZToA -> items.sortedByDescending { it.name.lowercase() }
            // Titles without a year or rating go last either way.
            PrismTvCatalogOrder.Newest -> items.sortedWith(compareBy<MetaPreview> { it.year() == null }.thenByDescending { it.year() })
            PrismTvCatalogOrder.Oldest -> items.sortedWith(compareBy<MetaPreview> { it.year() == null }.thenBy { it.year() })
            PrismTvCatalogOrder.Rating -> items.sortedWith(compareBy<MetaPreview> { it.imdbRating == null }.thenByDescending { it.imdbRating })
        }
        return row.copy(items = sorted)
    }

    /** Loads the saved choices, and goes back to the top when this catalog's order changes. */
    @Composable
    fun Effect(key: String, grid: LazyGridState) {
        val context = LocalContext.current
        remember { load(context) }
        val order = orderFor(key)
        var last by remember(key) { mutableStateOf(order) }
        LaunchedEffect(key, order) {
            if (order != last) {
                last = order
                grid.scrollToItem(0)
            }
        }
    }

    /** The "Sort" button beside a catalog's title. */
    @Composable
    fun SortButton(key: String) {
        val context = LocalContext.current
        val order = orderFor(key)
        Button(onClick = {
            android.app.AlertDialog.Builder(context)
                .setTitle("Sort")
                .setSingleChoiceItems(PrismTvCatalogOrder.entries.map { it.label }.toTypedArray(), order.ordinal) { d, which ->
                    choose(context, key, PrismTvCatalogOrder.entries[which])
                    d.dismiss()
                }
                .show()
        }) {
            Text("Sort: " + order.label)
        }
    }
}
