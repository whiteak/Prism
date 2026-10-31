package com.nuvio.app.prism

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioThemeTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import kotlin.math.roundToInt

// Prism: which look opens (by profile and by date), poster style and shapes kept with a look.

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val DAYS_IN_MONTH = listOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

/** Switches to the seasonal look or the profile's look when the app starts and when the profile changes. */
@Composable
internal fun PrismAutoLook() {
    val p = Prism.platform ?: return
    val profiles by ProfileRepository.state.collectAsState()
    val key = profiles.activeProfile?.let { prismProfileKey(it) }
    LaunchedEffect(key) {
        val (month, day) = p.today()
        PrismStore.autoSwitch(key, month, day)
    }
}

@Composable
private fun DateStepper(label: String, month: Int, day: Int, onChange: (Int, Int) -> Unit) {
    val tokens = MaterialTheme.nuvio
    fun clampDay(m: Int, d: Int) = d.coerceIn(1, DAYS_IN_MONTH[m - 1])
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = tokens.colors.textSecondary, modifier = Modifier.width(44.dp))
        PrismButton("−") { onChange(month, if (day > 1) day - 1 else DAYS_IN_MONTH[month - 1]) }
        Text("$day", color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(24.dp))
        PrismButton("+") { onChange(month, if (day < DAYS_IN_MONTH[month - 1]) day + 1 else 1) }
        PrismButton("◀") { val m = if (month > 1) month - 1 else 12; onChange(m, clampDay(m, day)) }
        Text(MONTHS[month - 1], color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp))
        PrismButton("▶") { val m = if (month < 12) month + 1 else 1; onChange(m, clampDay(m, day)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrismWhichLook(look: PrismLook) {
    val tokens = MaterialTheme.nuvio
    val profiles by ProfileRepository.state.collectAsState()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (profiles.profiles.isNotEmpty()) {
            Text("\"${look.name}\" opens for these profiles", color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.profiles.forEach { profile ->
                    val key = prismProfileKey(profile)
                    Chip(label = profile.name, selected = PrismStore.profileLooks[key] == look.id, onClick = { PrismStore.toggleProfileLook(key, look.id) })
                }
            }
            Text("Picking a profile switches to its look. Profiles left out keep whichever look was last showing.", color = tokens.colors.textMuted, fontSize = 12.sp)
        }
        val season = look.season
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Seasonal", color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    season?.let { "On for everyone from ${it.fromDay} ${MONTHS[it.fromMonth - 1]} to ${it.toDay} ${MONTHS[it.toMonth - 1]}" }
                        ?: "Switch to this look by itself between two dates, like all of December",
                    color = tokens.colors.textMuted,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = season != null,
                onCheckedChange = { on -> PrismStore.update { it.copy(season = if (on) PrismSeason(12, 1, 12, 31) else null) } },
            )
        }
        if (season != null) {
            DateStepper("From", season.fromMonth, season.fromDay) { m, d -> PrismStore.update { it.copy(season = season.copy(fromMonth = m, fromDay = d)) } }
            DateStepper("To", season.toMonth, season.toDay) { m, d -> PrismStore.update { it.copy(season = season.copy(toMonth = m, toDay = d)) } }
        }
    }
}

@Composable
private fun SliderLine(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, text: (Float) -> String, onDone: (Float) -> Unit) {
    val tokens = MaterialTheme.nuvio
    var v by remember(value) { mutableStateOf(value) }
    Column {
        Text("$label: ${text(v)}", color = tokens.colors.textPrimary)
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onDone(v) }, valueRange = range, steps = steps)
    }
}

@Composable
internal fun PrismPosterSettings(look: PrismLook) {
    val tokens = MaterialTheme.nuvio
    val style = look.poster
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep poster style with this look", color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text("Switching looks then switches poster size and style too", color = tokens.colors.textMuted, fontSize = 12.sp)
            }
            Switch(checked = style != null, onCheckedChange = { on -> PrismStore.setPoster(if (on) PrismStore.currentPoster() else null) })
        }
        if (style != null) {
            SliderLine("Size", style.widthDp.toFloat(), 90f..180f, text = { "${it.roundToInt()}" }) { PrismStore.setPoster(style.copy(widthDp = it.roundToInt())) }
            SliderLine("Corners", style.cornerRadiusDp.toFloat(), 0f..32f, text = { "${it.roundToInt()}" }) { PrismStore.setPoster(style.copy(cornerRadiusDp = it.roundToInt())) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Titles under posters", color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
                Switch(checked = !style.hideLabels, onCheckedChange = { on -> PrismStore.setPoster(style.copy(hideLabels = !on)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wide pictures instead of posters", color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
                Switch(checked = style.landscape, onCheckedChange = { on -> PrismStore.setPoster(style.copy(landscape = on)) })
            }
        }
    }
}

@Composable
internal fun PrismShapeSettings(look: PrismLook) {
    val tokens = MaterialTheme.nuvio
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SliderLine("Roundness", look.roundness, 0f..2f, steps = 7, text = {
            when {
                it < 0.2f -> "square"
                it < 0.8f -> "a little rounded"
                it <= 1.2f -> "normal"
                else -> "extra round"
            }
        }) { v -> PrismStore.update { it.copy(roundness = (v * 4).roundToInt() / 4f) } }
        Text("Buttons, cards, panels and sheets. Posters have their own corners above.", color = tokens.colors.textMuted, fontSize = 12.sp)
    }
}

/** Nuvio's shapes with the look's roundness (see prism/mobile/apply.sh). */
object PrismShapes {
    fun apply(tokens: NuvioThemeTokens): NuvioThemeTokens {
        if (Prism.platform == null) return tokens
        val r = PrismStore.look.roundness.coerceIn(0f, 2f)
        if (r == 1f) return tokens
        fun round(dp: Int) = RoundedCornerShape((dp * r).dp)
        return tokens.copy(
            shapes = tokens.shapes.copy(
                card = round(24),
                compactCard = round(12),
                sheet = round(24),
                dialog = round(28),
                button = round(16),
                chip = if (r < 0.2f) RoundedCornerShape(0.dp) else tokens.shapes.chip,
                playerPanel = round(24),
            ),
        )
    }
}
