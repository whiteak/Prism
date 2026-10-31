package com.nuvio.tv.ui.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.ui.theme.NuvioTheme

// Prism (shared with the Supernatural edition): "Send to phone" in the Library. The titles are shown as QR codes (about 60 a page);
// the phone camera opens the Trakt return page, which hands them to the phone app (?library=m.tt123~s.tt456).

private const val RETURN_PAGE = "https://whiteak.github.io/Trakt-return/"
private const val PER_CODE = 60

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EditionSendToPhone(items: List<LibraryEntry>) {
    var open by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        Button(
            onClick = { open = true },
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text("Send to phone")
        }
    }
    if (open) SendToPhoneDialog(items, onDismiss = { open = false })
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SendToPhoneDialog(items: List<LibraryEntry>, onDismiss: () -> Unit) {
    // Cinemeta (which the phone uses to fill in posters) only knows IMDb ids.
    val codes = remember(items) {
        items.mapNotNull { entry ->
            val kind = when (entry.type) { "movie" -> "m"; "series" -> "s"; else -> null }
            if (kind == null || !entry.id.startsWith("tt")) null else "$kind.${entry.id}"
        }.distinct().chunked(PER_CODE)
    }
    var page by remember { mutableIntStateOf(0) }
    val skipped = items.size - codes.sumOf { it.size }
    NuvioDialog(
        onDismiss = onDismiss,
        title = "Send to phone",
        subtitle = if (codes.isEmpty()) "Nothing here can be sent." else buildString {
            append("Scan with the phone camera and open the link in Supernatural.")
            if (codes.size > 1) append(" Code ${page + 1} of ${codes.size}: scan each one.")
            if (skipped > 0) append(" $skipped titles without an IMDb id are left out.")
        },
        width = 720.dp,
        titleTextAlign = androidx.compose.ui.text.style.TextAlign.Center,
    ) {
        val chunk = codes.getOrNull(page) ?: return@NuvioDialog
        val bitmap = remember(chunk) {
            QrCodeGenerator.generate(RETURN_PAGE + "?library=" + Uri.encode(chunk.joinToString("~"), "~."), 640, margin = 2)
        }
        // Code on the left, buttons on the right, so it all fits on a TV screen.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(300.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Text("${chunk.size} titles", color = NuvioTheme.colors.TextSecondary)
                if (page < codes.size - 1) Button(onClick = { page++ }) { Text("Next code") }
                else Button(onClick = onDismiss) { Text("Done") }
                if (page > 0) Button(onClick = { page-- }) { Text("Back") }
            }
        }
    }
}
