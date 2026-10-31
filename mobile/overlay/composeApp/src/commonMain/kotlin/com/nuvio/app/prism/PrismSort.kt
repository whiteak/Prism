package com.nuvio.app.prism

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// Prism: "Sort…" for Customise's lists (hero clips, quotes, show cards, Home rows and their titles). Each choice
// puts the list in that order once; it stays that way until you sort, move or add again.

internal class PrismSort<T>(val label: String, val apply: (List<T>) -> List<T>)

/** A to Z, Z to A, reverse and shuffle, by [name]. */
internal fun <T> prismNameSorts(name: (T) -> String): List<PrismSort<T>> = listOf(
    PrismSort("A to Z") { list -> list.sortedBy { name(it).lowercase() } },
    PrismSort("Z to A") { list -> list.sortedByDescending { name(it).lowercase() } },
    PrismSort("Reverse the order") { it.reversed() },
    PrismSort("Shuffle") { it.shuffled() },
)

/** For films and shows: by name, and by year. */
internal fun prismTitleSorts(): List<PrismSort<PrismTitle>> = prismNameSorts<PrismTitle> { it.name } + listOf(
    PrismSort("Newest year first") { list -> list.sortedByDescending { it.year?.take(4)?.toIntOrNull() ?: 0 } },
    PrismSort("Oldest year first") { list -> list.sortedBy { it.year?.take(4)?.toIntOrNull() ?: 9999 } },
)

/** A "Sort…" button with its choices; [onSorted] gets the list in the new order. */
@Composable
internal fun <T> PrismSortButton(items: List<T>, sorts: List<PrismSort<T>>, label: String = "Sort…", onSorted: (List<T>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PrismButton(label, enabled = items.size > 1) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sorts.forEach { sort ->
                DropdownMenuItem(text = { Text(sort.label) }, onClick = {
                    open = false
                    onSorted(sort.apply(items))
                })
            }
        }
    }
}
