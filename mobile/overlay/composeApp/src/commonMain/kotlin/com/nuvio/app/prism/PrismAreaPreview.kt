package com.nuvio.app.prism

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.nuvio

// Prism: Customise > Fonts. A small copy of the part of the app whose text is being changed (a row of posters,
// a details page title, a settings heading, buttons, the opening quote), drawn with the app's own text styles,
// which already carry the look, so it changes as you change it.

@Composable
internal fun PrismAreaPreview(area: PrismTextArea, look: PrismLook) {
    val tokens = MaterialTheme.nuvio
    val type = MaterialTheme.typography
    val frame = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.background)
    when (area) {
        PrismTextArea.Display -> Box(
            frame.height(170.dp).background(Brush.verticalGradient(listOf(tokens.colors.accent.copy(alpha = 0.45f), tokens.colors.background))),
            contentAlignment = Alignment.BottomStart,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Your Favourite Show", style = type.displaySmall, color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("2005 · Drama · 15 seasons", style = type.bodyMedium, color = tokens.colors.textSecondary)
            }
        }
        PrismTextArea.Headline -> Column(frame.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Settings", style = type.headlineMedium, color = tokens.colors.textPrimary)
            Text("Appearance", style = type.headlineSmall, color = tokens.colors.textPrimary)
            Text("Theme, fonts and how Home looks", style = type.bodyMedium, color = tokens.colors.textSecondary)
        }
        PrismTextArea.Title -> Column(frame.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Continue Watching", style = type.titleMedium, color = tokens.colors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("The Long Road", "Night Shift", "Old Friends").forEachIndexed { i, name ->
                    Column(Modifier.width(84.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.size(84.dp, 120.dp).clip(RoundedCornerShape(10.dp)).background(
                                Brush.verticalGradient(listOf(tokens.colors.accent.copy(alpha = 0.25f + i * 0.2f), tokens.colors.surfaceCard)),
                            ),
                        )
                        Text(name, style = type.titleSmall, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        PrismTextArea.Body -> Column(frame.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Two brothers drive across the country in a black classic car, looking for their father and taking on whatever they meet along the way.",
                style = type.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            Text("Download over Wi-Fi only", style = type.bodyLarge, color = tokens.colors.textPrimary)
            Text("Saves your mobile data", style = type.bodySmall, color = tokens.colors.textMuted)
        }
        PrismTextArea.Label -> Column(frame.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {},
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = tokens.colors.accent, contentColor = tokens.colors.onAccent),
                ) { Text("Play", style = type.labelLarge) }
                OutlinedButton(onClick = {}, shape = RoundedCornerShape(12.dp)) { Text("Trailer", style = type.labelLarge) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(label = "Movies", selected = true, onClick = {})
                Chip(label = "Series", selected = false, onClick = {})
            }
        }
        PrismTextArea.Quote -> {
            val shape = if (Prism.platform?.wideScreen == true) PrismShape.Wide else PrismShape.Tall
            val picture by rememberPrismPicture(look.opening, shape)
            Box(frame.height(180.dp).background(Color(0xFF080612)), contentAlignment = Alignment.Center) {
                picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                // Drawn the way the opening draws it (EditionLoadingExtras).
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        look.quotes.firstOrNull() ?: "\"Saving people, hunting things.\"",
                        color = Color.White.copy(alpha = 0.9f),
                        style = PrismFonts.styled(TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontStyle = FontStyle.Italic), PrismTextArea.Quote, look),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
    }
}
