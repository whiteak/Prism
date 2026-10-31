package com.nuvio.app.prism

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.labelRes
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.availableAppThemes
import com.nuvio.app.features.settings.SettingsGroup
import com.nuvio.app.features.settings.SettingsGroupDivider
import com.nuvio.app.features.settings.SettingsNavigationRow
import com.nuvio.app.features.settings.SettingsSection
import com.nuvio.app.features.settings.SettingsSwitchRow
import com.nuvio.app.features.settings.ThemeColorPicker
import com.nuvio.app.features.settings.ThemeSettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// Prism: the Customise screen, opened from Settings > Appearance. Everything here is saved in the
// current look straight away, on this device only.

object PrismCustomise {
    var open by mutableStateOf(false)

    /** Customise is hidden for a moment to look at the app itself, with a button to come back. */
    var peeking by mutableStateOf(false)

    /** The section to reopen after peeking. */
    internal var returnTo: String? = null

    fun peek(section: String?) {
        returnTo = section
        peeking = true
    }
}

/** The Customise entry in Settings > Appearance. */
internal fun LazyListScope.prismSettingsItem(isTablet: Boolean) {
    item {
        SettingsSection(title = "Prism", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Customise",
                    description = "Look: ${PrismStore.look.name}. Looks, colours, fonts, pictures, opening, sounds, Home and more",
                    isTablet = isTablet,
                    onClick = { PrismCustomise.open = true },
                )
            }
        }
    }
}

/** What Customise has open on top of itself. */
private sealed interface PrismTask {
    data class Source(val kind: PrismFileKind, val prefer: String?, val onPicked: (PrismPickedFile) -> Unit) : PrismTask
    data class Crop(
        val title: String,
        val image: ImageBitmap,
        val shapes: List<PrismShape>,
        val initial: Map<PrismShape, PrismCrop?>,
        val onDone: (Map<PrismShape, PrismCrop>) -> Unit,
    ) : PrismTask
    data class Sound(val moment: PrismMoment, val file: PrismPickedFile, val key: String = moment.name, val title: String = moment.label) : PrismTask
    data class Titles(
        val title: String,
        val types: List<String>,
        val multiple: Boolean,
        val initial: List<PrismTitle>,
        val onDone: (List<PrismTitle>) -> Unit,
    ) : PrismTask
    data object TextPicture : PrismTask
}

/** Customise's sections, each on its own page. */
private enum class PrismCategory(val title: String, val description: String) {
    Looks("1. Looks", "Start here: pick or start a look (it holds everything below), and when each look opens"),
    Colours("2. Colours", "Theme colour and pure black"),
    Pictures("3. Pictures and logos", "Home backgrounds and logos"),
    Fonts("4. Fonts", "Fonts for every kind of text, your own font files, size, weight, slant and spacing"),
    Posters("5. Posters and shapes", "Poster style, roundness and seasons"),
    Home("6. Home", "Favourite show cards, rows and hero clips"),
    Opening("7. Opening", "What shows while the app starts"),
    Quotes("8. Quotes", "Lines shown on the opening and while loading"),
    Sounds("9. Sounds", "Sound bites for each moment, chosen or random"),
    Profiles("10. Profile photos", "Photos for each profile"),
    Files("11. Save and share", "Back up everything, send your library to another device, and the Prism folder"),
    Sleep("12. Sleep", "\"Still watching?\" after hours of playing with no touch, so the device can sleep"),
}

private enum class PictureSlot(val label: String) { Background("Background"), Opening("Opening picture"), Logo("Logo") }

/** Replaces, adds (index past the end) or removes (null) one of the extra pictures. */
private fun List<PrismPicture>.edit(index: Int, picture: PrismPicture?): List<PrismPicture> = when {
    picture == null -> filterIndexed { i, _ -> i != index }
    index >= size -> this + picture
    else -> toMutableList().also { it[index] = picture }
}

/** Stores a picture in its slot; [extra] is the place among the slot's extra pictures, null for the first. */
private fun storePicture(slot: PictureSlot, extra: Int?, picture: PrismPicture?) = PrismStore.update { l ->
    // Removing the first picture moves the next one up into its place.
    fun first(extras: List<PrismPicture>): Pair<PrismPicture?, List<PrismPicture>> =
        if (picture == null && extras.isNotEmpty()) extras.first() to extras.drop(1) else picture to extras
    when (slot) {
        PictureSlot.Background -> if (extra == null) first(l.backgrounds).let { (p, rest) -> l.copy(background = p, backgrounds = rest) }
            else l.copy(backgrounds = l.backgrounds.edit(extra, picture))
        PictureSlot.Opening -> if (extra == null) first(l.openings).let { (p, rest) -> l.copy(opening = p, openings = rest) }
            else l.copy(openings = l.openings.edit(extra, picture))
        PictureSlot.Logo -> if (extra == null) first(l.logos).let { (p, rest) -> l.copy(logo = p, logos = rest) }
            else l.copy(logos = l.logos.edit(extra, picture))
    }
}

/** With two or more pictures in a slot: one at random each launch, or always the one picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PictureChoice(look: PrismLook, slot: String) {
    val set = look.pictureSet(slot)
    if (set.size < 2) return
    val tokens = MaterialTheme.nuvio
    val chosen = look.pictureChoice[slot]?.takeIf { file -> set.any { it.file == file } }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Which one shows", color = tokens.colors.textSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = "Random", selected = chosen == null, onClick = { PrismStore.update { it.copy(pictureChoice = it.pictureChoice - slot) } })
            set.forEachIndexed { i, picture ->
                Chip(label = "${i + 1}", selected = chosen == picture.file, onClick = { PrismStore.update { it.copy(pictureChoice = it.pictureChoice + (slot to picture.file)) } })
            }
        }
    }
}

/** Drawn over the whole app (see App.kt) while Customise is open. */
@Composable
fun PrismOverlay() {
    PrismAutoLook()
    if (!PrismCustomise.open || Prism.platform == null) return
    if (PrismCustomise.peeking) {
        PrismPeekButton()
        return
    }
    PrismCustomiseScreen(onClose = { PrismCustomise.open = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrismCustomiseScreen(onClose: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val platform = Prism.platform ?: return
    val look = PrismStore.look
    var task by remember { mutableStateOf<PrismTask?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var category by remember { mutableStateOf(PrismCustomise.returnTo?.let { n -> PrismCategory.entries.firstOrNull { it.name == n } }) }
    var problem by remember { mutableStateOf(platform.readBytes(PrismStore.problemPath)?.decodeToString()) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(3_500)
            message = null
        }
    }
    val shape = if (platform.wideScreen) PrismShape.Wide else PrismShape.Tall

    /** [prefer] is the folder inside the Prism folder to show first (Clipper saves to Phone, TV&PC and Icons). */
    fun choose(kind: PrismFileKind, prefer: String? = null, then: (PrismPickedFile) -> Unit) {
        task = PrismTask.Source(kind, prefer) { picked ->
            task = null
            then(picked)
        }
    }

    fun importLookFile(file: PrismPickedFile) {
        val name = PrismStore.importLook(file.bytes)
        message = if (name != null) "Loaded the look \"$name\"" else "That file isn't a Prism look"
    }

    fun addFont() = choose(PrismFileKind.Font) { file ->
        val extension = file.name.substringAfterLast('.', "ttf").lowercase()
        if (extension !in PrismFileKind.Font.extensions) {
            message = "Choose a .ttf or .otf font file"
            return@choose
        }
        val font = PrismStore.addFont(PrismFonts.nameFor(file.name), file.bytes, extension)
        // Check it draws before anything uses it, so a broken file can't upset the app.
        if (PrismFonts.canLoad(font.file)) {
            message = "Added ${font.name}"
        } else {
            PrismStore.removeFont(font)
            message = "That font file couldn't be read"
        }
    }

    fun choosePicture(slot: PictureSlot, extra: Int? = null) = choose(PrismFileKind.Picture, prefer = if (slot == PictureSlot.Logo) null else if (platform.wideScreen) "TV&PC" else "Phone") { file ->
        scope.launch {
            busy = true
            val logo = slot == PictureSlot.Logo
            val imported = runCatching {
                PrismImages.import(file.bytes, if (logo) PRISM_MAX_LOGO else PRISM_MAX_PICTURE, keepTransparency = logo)
            }.getOrNull()
            busy = false
            if (imported == null) {
                message = "That file couldn't be read as a picture"
                return@launch
            }
            val (name, image) = imported
            if (logo) {
                storePicture(slot, extra, PrismPicture(name))
                return@launch
            }
            // Choose the part to show, for this screen's shape first.
            task = PrismTask.Crop(
                title = slot.label,
                image = image,
                shapes = listOf(shape, if (shape == PrismShape.Wide) PrismShape.Tall else PrismShape.Wide),
                initial = emptyMap(),
            ) { crops ->
                task = null
                val picture = PrismPicture(name, tall = crops[PrismShape.Tall], wide = crops[PrismShape.Wide])
                storePicture(slot, extra, picture)
            }
        }
    }

    fun adjustCrop(slot: PictureSlot, picture: PrismPicture, extra: Int? = null) {
        scope.launch {
            val image = PrismImages.original(picture.file) ?: return@launch
            task = PrismTask.Crop(
                title = slot.label,
                image = image,
                shapes = listOf(shape, if (shape == PrismShape.Wide) PrismShape.Tall else PrismShape.Wide),
                initial = mapOf(PrismShape.Tall to picture.tall, PrismShape.Wide to picture.wide),
            ) { crops ->
                task = null
                val updated = picture.copy(tall = crops[PrismShape.Tall], wide = crops[PrismShape.Wide])
                storePicture(slot, extra, updated)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        PrismPanel(title = category?.title?.substringAfter(". ") ?: "Customise", onClose = { if (category != null) category = null else onClose() }) {
            androidx.compose.runtime.key(category) { LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (category == null) {
                    // ---- What went wrong last time ----
                    problem?.let { text ->
                        item {
                            SettingsSection(title = "Prism closed unexpectedly last time", isTablet = false) {
                                SettingsGroup(isTablet = false) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "Send Claude a screenshot of this so it can be fixed.",
                                            color = tokens.colors.textSecondary,
                                            fontSize = 13.sp,
                                        )
                                        Text(
                                            text.lines().filter { it.isNotBlank() }.take(24).joinToString("\n"),
                                            color = tokens.colors.textPrimary,
                                            fontSize = 10.sp,
                                            lineHeight = 12.sp,
                                        )
                                        PrismButton("Done") {
                                            platform.delete(PrismStore.problemPath)
                                            problem = null
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- The categories, each opening its own page ----
                if (category == null) {
                    item {
                        SettingsSection(title = "Look: ${look.name}", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                PrismCategory.entries.forEachIndexed { i, c ->
                                    if (i > 0) SettingsGroupDivider(isTablet = false)
                                    SettingsNavigationRow(title = c.title, description = c.description, isTablet = false, onClick = { category = c })
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Looks) {
                    // ---- Looks ----
                    item {
                        SettingsSection(title = "Looks", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        "A look keeps all your choices below. Switch between saved looks, or send one to another device as a file.",
                                        color = tokens.colors.textSecondary,
                                        fontSize = 13.sp,
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PrismStore.looks.forEach { l ->
                                            Chip(label = l.name, selected = l.id == PrismStore.activeId, onClick = { PrismStore.switchTo(l.id) })
                                        }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PrismButton("Save as new") {
                                            dialog = { PrismNameDialog("Save as a new look", "Look ${PrismStore.looks.size + 1}", { dialog = null }) { PrismStore.saveAsNew(it); dialog = null } }
                                        }
                                        PrismButton("Start blank") {
                                            dialog = { PrismNameDialog("New blank look", "Look ${PrismStore.looks.size + 1}", { dialog = null }) { PrismStore.newBlank(it); dialog = null } }
                                        }
                                        PrismButton("Rename") {
                                            dialog = { PrismNameDialog("Rename this look", look.name, { dialog = null }) { PrismStore.rename(look.id, it); dialog = null } }
                                        }
                                        PrismButton("Export") {
                                            scope.launch {
                                                val (name, bytes) = PrismStore.exportLook() ?: return@launch
                                                if (platform.saveFile(name, bytes)) message = "Saved $name"
                                            }
                                        }
                                        PrismButton("Import") { choose(PrismFileKind.Look, then = ::importLookFile) }
                                        if (PrismStore.looks.size > 1) {
                                            PrismButton("Delete") {
                                                dialog = {
                                                    PrismConfirmDialog("Delete \"${look.name}\"?", "Its pictures and sounds go too, unless another look uses them.", { dialog = null }) {
                                                        PrismStore.deleteLook(look.id)
                                                        dialog = null
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Looks) {
                    // ---- Which look opens ----
                    item {
                        SettingsSection(title = "When this look opens", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                PrismWhichLook(look)
                            }
                        }
                    }
                }

                if (category == PrismCategory.Colours) {
                    // ---- Colours ----
                    item {
                        val selected by ThemeSettingsRepository.selectedTheme.collectAsState()
                        val amoled by ThemeSettingsRepository.amoledEnabled.collectAsState()
                        val access by MemberAccessRepository.access.collectAsState()
                        SettingsSection(title = "Colours", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        availableAppThemes(access.entitlements).forEach { theme ->
                                            Chip(label = stringResource(theme.labelRes), selected = theme == selected, onClick = {
                                                if (theme == AppTheme.CUSTOM) PrismStore.setCustomColor(look.customColor ?: 0xB75AFF) else PrismStore.setTheme(theme)
                                            })
                                        }
                                    }
                                    if (selected == AppTheme.CUSTOM) {
                                        ThemeColorPicker(
                                            color = look.customColor ?: 0xB75AFF,
                                            colorIndex = 0,
                                            onColorChanged = { PrismStore.setCustomColor(it) },
                                        )
                                    }
                                }
                                SettingsGroupDivider(isTablet = false)
                                SettingsSwitchRow(
                                    title = "Pure black",
                                    description = "Black backgrounds, kinder on OLED screens",
                                    checked = amoled,
                                    isTablet = false,
                                    onCheckedChange = PrismStore::setAmoled,
                                )
                            }
                        }
                    }
                }

                if (category == PrismCategory.Fonts) {
                    // ---- Text ----
                    item {
                        SettingsSection(title = "Fonts and text", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                PrismTextSettings(look, onAddFont = ::addFont, onTextPicture = { task = PrismTask.TextPicture }, onMessage = { message = it })
                            }
                        }
                    }
                }

                if (category == PrismCategory.Posters) {
                    // ---- Posters and shapes ----
                    item {
                        SettingsSection(title = "Posters", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                PrismPosterSettings(look)
                            }
                        }
                    }
                    item {
                        SettingsSection(title = "Shapes", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                PrismShapeSettings(look)
                            }
                        }
                    }
                }

                if (category == PrismCategory.Pictures) {
                    // ---- Pictures ----
                    item {
                        SettingsSection(title = "Pictures", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                PictureRow(
                                    label = "Home background",
                                    hint = "Behind Home. Phones show it tall, PCs and TVs wide.",
                                    picture = look.background,
                                    shape = shape,
                                    onChoose = { choosePicture(PictureSlot.Background) },
                                    onAdjust = look.background?.let { p -> { adjustCrop(PictureSlot.Background, p) } },
                                    onRemove = { storePicture(PictureSlot.Background, null, null) },
                                )
                                if (look.background != null) {
                                    look.backgrounds.forEachIndexed { i, p ->
                                        PictureRow(
                                            label = "Background ${i + 2}",
                                            hint = "Pick which one shows below, or leave it on Random for a different one each time the app starts.",
                                            picture = p,
                                            shape = shape,
                                            onChoose = { choosePicture(PictureSlot.Background, i) },
                                            onAdjust = { adjustCrop(PictureSlot.Background, p, i) },
                                            onRemove = { storePicture(PictureSlot.Background, i, null) },
                                        )
                                    }
                                    PictureChoice(look, "background")
                                    Row(Modifier.padding(horizontal = 16.dp)) {
                                        PrismButton("Add another background") { choosePicture(PictureSlot.Background, look.backgrounds.size) }
                                    }
                                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Darken", color = tokens.colors.textSecondary, modifier = Modifier.width(72.dp))
                                        var dim by remember(look.id) { mutableStateOf(look.backgroundDim) }
                                        Slider(
                                            value = dim,
                                            onValueChange = { dim = it },
                                            onValueChangeFinished = { PrismStore.update { it.copy(backgroundDim = dim) } },
                                            valueRange = 0f..0.8f,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                                SettingsGroupDivider(isTablet = false)
                                PictureRow(
                                    label = "Opening picture",
                                    hint = "Shown while the app starts.",
                                    picture = look.opening,
                                    shape = shape,
                                    onChoose = { choosePicture(PictureSlot.Opening) },
                                    onAdjust = look.opening?.let { p -> { adjustCrop(PictureSlot.Opening, p) } },
                                    onRemove = { storePicture(PictureSlot.Opening, null, null) },
                                )
                                if (look.opening != null) {
                                    look.openings.forEachIndexed { i, p ->
                                        PictureRow(
                                            label = "Opening picture ${i + 2}",
                                            hint = "Pick which one shows below, or leave it on Random for a different one each time the app starts.",
                                            picture = p,
                                            shape = shape,
                                            onChoose = { choosePicture(PictureSlot.Opening, i) },
                                            onAdjust = { adjustCrop(PictureSlot.Opening, p, i) },
                                            onRemove = { storePicture(PictureSlot.Opening, i, null) },
                                        )
                                    }
                                    PictureChoice(look, "opening")
                                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                        PrismButton("Add another opening picture") { choosePicture(PictureSlot.Opening, look.openings.size) }
                                    }
                                }
                                SettingsGroupDivider(isTablet = false)
                                PictureRow(
                                    label = "Logo",
                                    hint = "On the opening screen, kept whole. A PNG with a see-through background looks best.",
                                    picture = look.logo,
                                    shape = null,
                                    onChoose = { choosePicture(PictureSlot.Logo) },
                                    onAdjust = null,
                                    onRemove = { storePicture(PictureSlot.Logo, null, null) },
                                )
                                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                    PrismButton("Make a text logo…") { task = PrismTask.TextPicture }
                                }
                                if (look.logo != null) {
                                    look.logos.forEachIndexed { i, p ->
                                        PictureRow(
                                            label = "Logo ${i + 2}",
                                            hint = "Pick which one shows below, or leave it on Random.",
                                            picture = p,
                                            shape = null,
                                            onChoose = { choosePicture(PictureSlot.Logo, i) },
                                            onAdjust = null,
                                            onRemove = { storePicture(PictureSlot.Logo, i, null) },
                                        )
                                    }
                                    PictureChoice(look, "logo")
                                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                        PrismButton("Add another logo") { choosePicture(PictureSlot.Logo, look.logos.size) }
                                    }
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Opening) {
                    // ---- Opening ----
                    item {
                        SettingsSection(title = "Opening", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    var seconds by remember(look.id) { mutableStateOf(look.openingSeconds.toFloat()) }
                                    Text("Stays up for ${seconds.toInt()} seconds (tap to skip)", color = tokens.colors.textPrimary)
                                    Slider(
                                        value = seconds,
                                        onValueChange = { seconds = it },
                                        onValueChangeFinished = { PrismStore.update { it.copy(openingSeconds = seconds.toInt()) } },
                                        valueRange = 2f..15f,
                                        steps = 12,
                                    )
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Quotes) {
                    // ---- Quotes ----
                    item {
                        SettingsSection(title = "Quotes", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("One is picked at random for the opening screen.", color = tokens.colors.textSecondary, fontSize = 13.sp)
                                    if (look.quotes.size > 1) {
                                        PrismSortButton(
                                            look.quotes,
                                            prismNameSorts<String> { it } + listOf(
                                                PrismSort("Shortest first") { l -> l.sortedBy { it.length } },
                                                PrismSort("Longest first") { l -> l.sortedByDescending { it.length } },
                                            ),
                                        ) { sorted -> PrismStore.update { it.copy(quotes = sorted) } }
                                    }
                                    look.quotes.forEachIndexed { index, quote ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("“$quote”", color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
                                            TextButton(onClick = { PrismStore.update { it.copy(quotes = it.quotes.filterIndexed { i, _ -> i != index }) } }) {
                                                Text("Remove", color = tokens.colors.danger)
                                            }
                                        }
                                    }
                                    var draft by remember { mutableStateOf("") }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = draft,
                                            onValueChange = { draft = it },
                                            placeholder = { Text("Add a quote") },
                                            modifier = Modifier.weight(1f),
                                        )
                                        PrismButton("Add", primary = true, enabled = draft.isNotBlank()) {
                                            val text = draft.trim()
                                            PrismStore.update { it.copy(quotes = it.quotes + text) }
                                            draft = ""
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Sounds) {
                    // ---- Sounds ----
                    item {
                        SettingsSection(title = "Sounds", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                SettingsSwitchRow(
                                    title = "Sounds on",
                                    description = "Plays your sounds at the moments below",
                                    checked = look.soundsOn,
                                    isTablet = false,
                                    onCheckedChange = { on -> PrismStore.update { it.copy(soundsOn = on) } },
                                )
                                PrismMoment.entries.forEach { moment ->
                                    SettingsGroupDivider(isTablet = false)
                                    val sound = look.sound(moment)
                                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(moment.label, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    sound?.label?.let { "${moment.description} · $it" } ?: moment.description,
                                                    color = tokens.colors.textMuted,
                                                    fontSize = 12.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            if (sound != null) {
                                                Switch(
                                                    checked = sound.enabled,
                                                    onCheckedChange = { on ->
                                                        PrismStore.update { it.copy(sounds = it.sounds + (moment.name to sound.copy(enabled = on))) }
                                                    },
                                                )
                                            }
                                        }
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            PrismButton(if (sound == null) "Choose sound" else "Change") {
                                                choose(PrismFileKind.Sound) { file -> task = PrismTask.Sound(moment, file) }
                                            }
                                            if (sound != null) {
                                                PrismButton("▶ Play") { PrismSounds.playFile(PrismStore.mediaPath(sound.file), sound.volume) }
                                                PrismButton("Remove") { PrismStore.update { it.copy(sounds = it.sounds - moment.name) } }
                                            }
                                        }
                                        if (sound != null) {
                                            VolumeLine(sound.volume) { v -> PrismStore.update { it.copy(sounds = it.sounds + (moment.name to sound.copy(volume = v))) } }
                                            // More sounds for this moment: one is picked at random each time.
                                            look.soundSet(moment.name).filter { it.first != moment.name }.forEach { (soundKey, extra) ->
                                                SoundLine(
                                                    extra,
                                                    onPlay = { PrismSounds.playFile(PrismStore.mediaPath(extra.file), extra.volume) },
                                                    onVolume = { v -> PrismStore.update { it.copy(sounds = it.sounds + (soundKey to extra.copy(volume = v))) } },
                                                ) {
                                                    PrismStore.update { it.copy(sounds = it.sounds - soundKey) }
                                                }
                                            }
                                            SoundChoice(look, moment.name)
                                            PrismButton("Add another sound") {
                                                val newKey = "${moment.name}#${PrismStore.newId()}"
                                                choose(PrismFileKind.Sound) { file -> task = PrismTask.Sound(moment, file, newKey, moment.label) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Sleep) {
                    // ---- Still watching? ----
                    item {
                        SettingsSection(title = "Still watching?", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        "While something plays with no touch, click or key press for this long, Prism asks. With no answer in a minute it stops, so nothing plays all night.",
                                        color = tokens.colors.textSecondary,
                                        fontSize = 13.sp,
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PrismSleep.STILL_WATCHING_OPTIONS.forEach { h ->
                                            Chip(label = PrismSleep.hoursLabel(h), selected = PrismSleep.stillWatchingHours == h, onClick = { PrismSleep.changeStillWatching(h) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Files) {
                    // ---- The TV and PC copy from the phone ----
                    item {
                        SettingsSection(title = if (platform.copiesFromPhone) "Copy from the phone" else "TV and PC", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (platform.copiesFromPhone) {
                                        var typed by remember { mutableStateOf(platform.phoneCode.orEmpty()) }
                                        Text(
                                            "This PC has its own Customise. To use the phone's set-up instead, copy it over the home Wi-Fi while Prism is open " +
                                                "on the phone: type the code shown there (Customise > 11. Save and share). Copying replaces what's set up here.",
                                            color = tokens.colors.textSecondary,
                                            fontSize = 13.sp,
                                        )
                                        OutlinedTextField(
                                            value = typed,
                                            onValueChange = { v -> typed = v.filter(Char::isDigit).take(4) },
                                            label = { Text("Phone's code") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        PrismButton("Copy from the phone now", Modifier.fillMaxWidth(), enabled = typed.length == 4 && !busy, primary = true) {
                                            scope.launch {
                                                busy = true
                                                message = platform.copyFromPhone(typed)
                                                busy = false
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "Keep copying from the phone by itself (when Prism starts and every couple of minutes)",
                                                color = tokens.colors.textPrimary,
                                                fontSize = 13.sp,
                                                modifier = Modifier.weight(1f),
                                            )
                                            androidx.compose.material3.Switch(checked = platform.phoneAuto, onCheckedChange = { platform.setPhoneAuto(it) })
                                        }
                                        platform.phoneStatus?.let { Text(it, color = tokens.colors.textMuted, fontSize = 12.sp) }
                                    } else {
                                        Text("Code for the TV and PC: ${PrismShare.code}", color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                        Text(
                                            "The TV and PC have their own customising, and can also copy this phone's set-up over the home Wi-Fi while Prism is " +
                                                "open here: type this code on the TV (Settings > Appearance > Copy from the phone) or the PC (Customise > 11. Save and share). " +
                                                "Nothing leaves your home. Clips stay on each device (copy those with the Prism folder).",
                                            color = tokens.colors.textSecondary,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // ---- Backup ----
                    item {
                        SettingsSection(title = "Backup", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        "Everything in Prism in one file: every look, added font, sound, picture and profile photo. Keep it safe for a new phone or a reset. Clips aren't included (they can be huge); copy those with the Prism folder.",
                                        color = tokens.colors.textSecondary,
                                        fontSize = 13.sp,
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PrismButton("Save a backup") {
                                            scope.launch {
                                                busy = true
                                                val bytes = PrismStore.backup()
                                                busy = false
                                                if (bytes != null && platform.saveFile("Prism backup.prismbackup", bytes)) message = "Backup saved"
                                            }
                                        }
                                        PrismButton("Restore a backup") {
                                            choose(PrismFileKind.Backup) { file ->
                                                dialog = {
                                                    PrismConfirmDialog("Restore this backup?", "Everything in Prism on this device is replaced by what's in the backup.", { dialog = null }) {
                                                        dialog = null
                                                        message = if (PrismStore.restore(file.bytes)) "Backup restored" else "That file isn't a Prism backup"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Files) {
                    // ---- Library ----
                    item {
                        SettingsSection(title = "Library", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        "Send this profile's library to another device: save it as a file, send it with Send Anywhere to that device's Prism folder, then tap \"Add library from a file\" there. Titles already in its library are skipped.",
                                        color = tokens.colors.textSecondary,
                                        fontSize = 13.sp,
                                    )
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PrismButton("Save library to a file") {
                                            val bytes = com.nuvio.app.EditionLibraryImport.exportFile()
                                            if (bytes == null) {
                                                message = "The library is empty"
                                            } else {
                                                scope.launch { if (platform.saveFile("Prism-library.prismlib", bytes)) message = "Library saved" }
                                            }
                                        }
                                        PrismButton("Add library from a file") {
                                            choose(PrismFileKind.Library) { file ->
                                                if (!com.nuvio.app.EditionLibraryImport.importFile(file.bytes)) message = "That file isn't a Prism library"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Home) {
                    // ---- Home ----
                    item {
                        SettingsSection(title = "Home", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Favourite show cards", color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Pinned at the top of Home with Continue and \"Play random episode\". Add as many as you like and swipe across them on Home. Each card can have its own sound before its random episode.",
                                        color = tokens.colors.textMuted,
                                        fontSize = 12.sp,
                                    )
                                    val cards = look.cards
                                    if (cards.size > 1) {
                                        PrismSortButton(cards, prismTitleSorts(), "Sort the cards…") { sorted ->
                                            PrismStore.update { it.copy(showCards = sorted, showCard = null) }
                                        }
                                    }
                                    cards.forEachIndexed { index, title ->
                                        val key = cardSoundKey(title)
                                        val cardSounds = look.soundSet(key)
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            TitleLine(title)
                                            Text(
                                                when (cardSounds.size) {
                                                    0 -> "Sound: the \"Play random episode\" sound"
                                                    1 -> "Sound:"
                                                    else -> "Sounds:"
                                                },
                                                color = tokens.colors.textMuted,
                                                fontSize = 12.sp,
                                            )
                                            cardSounds.forEach { (soundKey, sound) ->
                                                SoundLine(
                                                    sound,
                                                    onPlay = { PrismSounds.playFile(PrismStore.mediaPath(sound.file), sound.volume) },
                                                    onVolume = { v -> PrismStore.update { it.copy(sounds = it.sounds + (soundKey to sound.copy(volume = v))) } },
                                                ) {
                                                    PrismStore.update { it.copy(sounds = it.sounds - soundKey) }
                                                }
                                            }
                                            SoundChoice(look, key)
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                PrismButton(if (cardSounds.isEmpty()) "Choose sound" else "Add another sound") {
                                                    val newKey = if (look.sounds.containsKey(key)) "$key#${PrismStore.newId()}" else key
                                                    choose(PrismFileKind.Sound) { file ->
                                                        task = PrismTask.Sound(PrismMoment.PickEpisode, file, newKey, "Sound for ${title.name}")
                                                    }
                                                }
                                                if (index > 0) {
                                                    PrismButton("▲") {
                                                        val moved = cards.toMutableList().apply { add(index - 1, removeAt(index)) }
                                                        PrismStore.update { it.copy(showCards = moved, showCard = null) }
                                                    }
                                                }
                                                PrismButton("Remove card") {
                                                    PrismStore.update { it.copy(showCards = cards - title, showCard = null, sounds = it.sounds - cardSounds.map { c -> c.first }.toSet()) }
                                                }
                                            }
                                        }
                                        if (index < cards.lastIndex) SettingsGroupDivider(isTablet = false)
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PrismButton(if (cards.isEmpty()) "Choose shows" else "Add or change") {
                                            task = PrismTask.Titles("Favourite shows", listOf("series", "movie"), true, cards) { picked ->
                                                task = null
                                                PrismStore.update { it.copy(showCards = picked, showCard = null) }
                                            }
                                        }
                                        if (cards.size > 1) PrismButton("Remove all") { PrismStore.update { it.copy(showCards = emptyList(), showCard = null) } }
                                    }
                                }
                                look.rows.forEachIndexed { index, row ->
                                    SettingsGroupDivider(isTablet = false)
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(row.title, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            items(row.items, key = { it.id }) { t ->
                                                AsyncImage(
                                                    model = t.poster,
                                                    contentDescription = t.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(width = 52.dp, height = 78.dp).clip(RoundedCornerShape(6.dp)).background(tokens.colors.surface),
                                                )
                                            }
                                        }
                                        Text("${row.items.size} titles", color = tokens.colors.textMuted, fontSize = 12.sp)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            PrismButton("Add or remove titles") {
                                                task = PrismTask.Titles(row.title, listOf("movie", "series"), true, row.items) { picked ->
                                                    task = null
                                                    PrismStore.update { l -> l.copy(rows = l.rows.map { if (it.id == row.id) it.copy(items = picked) else it }) }
                                                }
                                            }
                                            PrismButton("Rename") {
                                                dialog = {
                                                    PrismNameDialog("Rename row", row.title, { dialog = null }) { name ->
                                                        PrismStore.update { l -> l.copy(rows = l.rows.map { if (it.id == row.id) it.copy(title = name) else it }) }
                                                        dialog = null
                                                    }
                                                }
                                            }
                                            if (index > 0) PrismButton("Move up") {
                                                PrismStore.update { l -> l.copy(rows = l.rows.toMutableList().apply { add(index - 1, removeAt(index)) }) }
                                            }
                                            if (row.items.size > 1) PrismSortButton(row.items, prismTitleSorts(), "Sort titles…") { sorted ->
                                                PrismStore.update { l -> l.copy(rows = l.rows.map { if (it.id == row.id) it.copy(items = sorted) else it }) }
                                            }
                                            PrismButton("Delete") { PrismStore.update { l -> l.copy(rows = l.rows.filterNot { it.id == row.id }) } }
                                        }
                                    }
                                }
                                SettingsGroupDivider(isTablet = false)
                                if (look.rows.size > 1) Box(Modifier.padding(start = 16.dp, top = 16.dp)) {
                                    PrismSortButton(look.rows, prismNameSorts<PrismRow> { it.title }, "Sort the rows…") { sorted ->
                                        PrismStore.update { it.copy(rows = sorted) }
                                    }
                                }
                                Box(Modifier.padding(16.dp)) {
                                    PrismButton("Add a row to Home", primary = true) {
                                        dialog = {
                                            PrismNameDialog("New row", "My favourites", { dialog = null }) { name ->
                                                dialog = null
                                                val row = PrismRow(PrismStore.newId(), name)
                                                PrismStore.update { it.copy(rows = it.rows + row) }
                                                task = PrismTask.Titles(name, listOf("movie", "series"), true, emptyList()) { picked ->
                                                    task = null
                                                    PrismStore.update { l -> l.copy(rows = l.rows.map { if (it.id == row.id) it.copy(items = picked) else it }) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Home) {
                    // ---- Hero clips ----
                    item {
                        SettingsSection(title = "Hero clips", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                SettingsSwitchRow(
                                    title = "Play clips at the top of Home",
                                    description = "Silent, in shuffled order: every clip plays once before any repeats",
                                    checked = look.clipsOn,
                                    isTablet = false,
                                    onCheckedChange = { on -> PrismStore.update { it.copy(clipsOn = on) } },
                                )
                                SettingsGroupDivider(isTablet = false)
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        "${look.clips.size} clips. Short MP4s work best (H.265 or H.264, 720p or 1080p, no sound needed). " +
                                            (if (platform.canMakeClips) "Make a clip cuts one from any video for you. " else "") +
                                            "Clips stay on this device and aren't put in exported looks.",
                                        color = tokens.colors.textSecondary,
                                        fontSize = 13.sp,
                                    )
                                    // [originals]: the files are the user's own, so offer to delete them once they're copied in.
                                    fun addClips(files: List<PrismFolderFile>, originals: Boolean = true, after: () -> Unit = {}) {
                                        if (files.isEmpty()) return
                                        scope.launch {
                                            busy = true
                                            val stored = PrismStore.addMediaFiles(files.filterNot { f -> look.clips.any { it.name == f.name } })
                                            busy = false
                                            if (stored.isNotEmpty()) {
                                                PrismStore.update { l -> l.copy(clips = l.clips + stored.map { (f, name) -> PrismClip(name, f.name) }) }
                                            }
                                            message = "Added ${stored.size} clips"
                                            after()
                                            if (originals && stored.isNotEmpty()) {
                                                val count = stored.size
                                                dialog = {
                                                    PrismConfirmDialog(
                                                        "Delete the original${if (count == 1) "" else "s"}?",
                                                        "${if (count == 1) "The clip is" else "The $count clips are"} copied into Prism, so the file${if (count == 1) "" else "s"} you picked can be deleted from this device to free up space.",
                                                        { dialog = null },
                                                    ) {
                                                        dialog = null
                                                        scope.launch {
                                                            val failed = stored.count { (f, _) -> !runCatching { platform.deleteOriginal(f.ref) }.getOrDefault(false) }
                                                            message = if (failed == 0) "Deleted the original${if (count == 1) "" else "s"}" else "$failed couldn't be deleted here; delete ${if (failed == 1) "it" else "them"} in your Files app"
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PrismButton("Add clips…", primary = true) {
                                            scope.launch { addClips(runCatching { platform.pickRefs(PrismFileKind.Video, multiple = true) }.getOrDefault(emptyList())) }
                                        }
                                        if (platform.canMakeClips) {
                                            PrismButton("Make a clip…") {
                                                scope.launch {
                                                    val made = runCatching { platform.makeClip(sound = false) }.getOrNull() ?: return@launch
                                                    addClips(listOf(made.file), originals = false) {
                                                        platform.delete(made.file.ref)
                                                        if (made.originalDeleted == false) message = "Added the clip. The original video couldn't be deleted here; delete it in your Files app"
                                                    }
                                                }
                                            }
                                        }
                                        if (platform.folderLabel != null) {
                                            PrismButton("Add all from Prism folder") {
                                                scope.launch {
                                                    val videos = runCatching { platform.listFolder().filter { PrismFileKind.Video.matches(it.name) } }.getOrDefault(emptyList())
                                                    if (videos.isEmpty()) message = "No clips in the Prism folder yet" else addClips(videos)
                                                }
                                            }
                                        }
                                        if (look.clips.size > 1) {
                                            PrismSortButton(look.clips, prismNameSorts<PrismClip> { it.name }) { sorted -> PrismStore.update { it.copy(clips = sorted) } }
                                        }
                                        if (look.clips.isNotEmpty()) {
                                            PrismButton("Remove all") {
                                                dialog = {
                                                    PrismConfirmDialog("Remove all ${look.clips.size} clips?", "They're deleted from this look.", { dialog = null }) {
                                                        PrismStore.update { it.copy(clips = emptyList()) }
                                                        dialog = null
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (look.clips.size > 1) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Chip(label = "Play shuffled", selected = !look.clipsInOrder, onClick = { PrismStore.update { it.copy(clipsInOrder = false) } })
                                            Chip(label = "Play in this order", selected = look.clipsInOrder, onClick = { PrismStore.update { it.copy(clipsInOrder = true) } })
                                        }
                                    }
                                    look.clips.forEach { clip ->
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            val path = PrismStore.mediaPath(clip.file)
                                            PrismThumb(path, clip.file, width = 80.dp, height = 45.dp, onClick = {
                                                dialog = { PrismPreviewDialog(path, clip.file) { dialog = null } }
                                            })
                                            Text(clip.name, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            TextButton(onClick = { PrismStore.update { l -> l.copy(clips = l.clips.filterNot { it.file == clip.file }) } }) {
                                                Text("Remove", color = tokens.colors.danger)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Profiles) {
                    // ---- Profile photos ----
                    item {
                        val profiles by com.nuvio.app.features.profiles.ProfileRepository.state.collectAsState()
                        if (profiles.profiles.isNotEmpty()) {
                            SettingsSection(title = "Profile photos", isTablet = false) {
                                SettingsGroup(isTablet = false) {
                                    profiles.profiles.forEachIndexed { i, profile ->
                                        if (i > 0) SettingsGroupDivider(isTablet = false)
                                        val key = prismProfileKey(profile)
                                        val file = PrismStore.avatars[key]
                                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                            val photo by rememberPrismOriginal(file)
                                            Box(Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tokens.colors.surface), contentAlignment = Alignment.Center) {
                                                photo?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                                    ?: Text(profile.name.take(1), color = tokens.colors.textPrimary, fontSize = 20.sp)
                                            }
                                            Text(profile.name, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                            PrismButton(if (file == null) "Choose photo" else "Add photo") {
                                                choose(PrismFileKind.Picture) { picked ->
                                                    scope.launch {
                                                        busy = true
                                                        val image = Prism.platform?.decodeImage(picked.bytes, PRISM_MAX_PICTURE)
                                                        busy = false
                                                        if (image == null) {
                                                            message = "That file couldn't be read as a picture"
                                                            return@launch
                                                        }
                                                        task = PrismTask.Crop("Photo for ${profile.name}", image, listOf(PrismShape.Square), emptyMap()) { crops ->
                                                            task = null
                                                            scope.launch {
                                                                busy = true
                                                                val square = PrismImages.crop(image, crops.getValue(PrismShape.Square))
                                                                val small = if (square.width > 512) {
                                                                    PrismImages.scale(square, androidx.compose.ui.unit.IntOffset.Zero, androidx.compose.ui.unit.IntSize(square.width, square.height), androidx.compose.ui.unit.IntSize(512, 512))
                                                                } else {
                                                                    square
                                                                }
                                                                val bytes = Prism.platform?.encodeImage(small, keepTransparency = false)
                                                                busy = false
                                                                if (bytes != null) PrismStore.addAvatarPhoto(key, PrismStore.addMedia(bytes, "jpg"))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            if (file != null) PrismButton("Remove") { PrismStore.removeAvatarPhoto(key, file) }
                                        }
                                        // Photos kept for this profile: tap one to use it.
                                        val kept = PrismStore.photosFor(key)
                                        if (kept.size > 1) {
                                            Row(
                                                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                kept.forEach { keptFile ->
                                                    val thumb by rememberPrismOriginal(keptFile)
                                                    val selected = keptFile == file
                                                    Box(
                                                        Modifier
                                                            .size(44.dp)
                                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                                            .background(tokens.colors.surface)
                                                            .then(if (selected) Modifier.border(2.dp, tokens.colors.accent, androidx.compose.foundation.shape.CircleShape) else Modifier)
                                                            .clickable { PrismStore.setAvatar(key, keptFile) },
                                                    ) {
                                                        thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (category == PrismCategory.Files) {
                    // ---- Prism folder ----
                    item {
                        SettingsSection(title = "Prism folder", isTablet = false) {
                            SettingsGroup(isTablet = false) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    var label by remember { mutableStateOf(platform.folderLabel) }
                                    Text(
                                        label?.let { "Files you put in $it (for example with Send Anywhere) show up whenever you choose a picture, sound or look." }
                                            ?: "Link a folder (Download/Prism is a good one) and files you send there, for example with Send Anywhere, show up whenever you choose a picture, sound or look.",
                                        color = tokens.colors.textSecondary,
                                        fontSize = 13.sp,
                                    )
                                    PrismButton(if (platform.wideScreen) "Open the folder" else if (label == null) "Link the folder" else "Change folder", primary = label == null) {
                                        scope.launch {
                                            platform.linkFolder()
                                            label = platform.folderLabel
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } }
        }

        message?.let { text ->
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text,
                    color = tokens.colors.textPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.colors.surfaceElevated)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }

        when (val t = task) {
            is PrismTask.Source -> PrismSourcePanel(t.kind, t.prefer, t.onPicked) { task = null }
            is PrismTask.Crop -> PrismCropPanel(t.title, t.image, t.shapes, t.initial, t.onDone) { task = null }
            is PrismTask.Sound -> PrismSoundPanel(t.moment, t.file, t.title, onDone = { sound ->
                task = null
                PrismStore.update { it.copy(sounds = it.sounds + (t.key to sound)) }
            }) { task = null }
            is PrismTask.Titles -> PrismTitlePanel(t.title, t.types, t.multiple, t.initial, t.onDone) { task = null }
            PrismTask.TextPicture -> PrismTextPicturePanel(
                onLogo = { image ->
                    scope.launch {
                        val bytes = Prism.platform?.encodeImage(image, keepTransparency = true)
                        val imported = bytes?.let { PrismImages.import(it, PRISM_MAX_LOGO, keepTransparency = true) }
                        if (imported == null) {
                            message = "The picture couldn't be kept"
                            return@launch
                        }
                        val current = PrismStore.look
                        storePicture(PictureSlot.Logo, if (current.logo == null) null else current.logos.size, PrismPicture(imported.first))
                        task = null
                        message = "Added as a logo (3. Pictures and logos)"
                    }
                },
                onMessage = { message = it },
            ) { task = null }
            null -> Unit
        }
        dialog?.invoke()
        if (busy) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.colors.accent)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PictureRow(
    label: String,
    hint: String,
    picture: PrismPicture?,
    shape: PrismShape?,
    onChoose: () -> Unit,
    onAdjust: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val preview by if (shape != null) rememberPrismPicture(picture, shape) else rememberPrismOriginal(picture?.file)
        val (w, h) = when (shape) {
            PrismShape.Tall -> 54.dp to 112.dp
            PrismShape.Wide, PrismShape.Square -> 128.dp to 72.dp
            null -> 96.dp to 72.dp
        }
        var big by remember { mutableStateOf(false) }
        if (big && picture != null) {
            val path = PrismStore.mediaPath(picture.file)
            PrismPreviewDialog(path, picture.file) { big = false }
        }
        Box(
            // Tap it to see the whole picture.
            Modifier.size(w, h).clip(RoundedCornerShape(8.dp)).background(tokens.colors.surface).clickable(enabled = picture != null) { big = true },
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(it, contentDescription = null, contentScale = if (shape == null) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } ?: Text("None", color = tokens.colors.textMuted, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = tokens.colors.textPrimary, fontWeight = FontWeight.SemiBold)
            Text(hint, color = tokens.colors.textMuted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismButton(if (picture == null) "Choose" else "Change", onClick = onChoose)
                if (onAdjust != null) PrismButton("Adjust crop", onClick = onAdjust)
                if (picture != null) PrismButton("Remove", onClick = onRemove)
            }
        }
    }
}

@Composable
private fun SoundLine(sound: PrismSound, onPlay: () -> Unit, onVolume: ((Float) -> Unit)? = null, onRemove: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("♪ ${sound.label}", color = tokens.colors.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onPlay) { Text("▶", color = tokens.colors.accent) }
            androidx.compose.material3.TextButton(onClick = onRemove) { Text("Remove", color = tokens.colors.accent) }
        }
        onVolume?.let { VolumeLine(sound.volume, it) }
    }
}

/** With two or more sounds for a moment: play one at random each time, or always the one picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundChoice(look: PrismLook, base: String) {
    val set = look.soundSet(base)
    if (set.size < 2) return
    val tokens = MaterialTheme.nuvio
    val chosen = look.soundChoice[base]?.takeIf { key -> set.any { it.first == key } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Which sound plays", color = tokens.colors.textSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = "Random", selected = chosen == null, onClick = { PrismStore.update { it.copy(soundChoice = it.soundChoice - base) } })
            set.forEach { (key, sound) ->
                Chip(label = sound.label.take(24), selected = chosen == key, onClick = { PrismStore.update { it.copy(soundChoice = it.soundChoice + (base to key)) } })
            }
        }
    }
}

/** A volume slider for one sound; saved when you let go. */
@Composable
private fun VolumeLine(volume: Float, onChange: (Float) -> Unit) {
    val tokens = MaterialTheme.nuvio
    var v by remember(volume) { mutableStateOf(volume) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Volume ${(v * 100).toInt()}%", color = tokens.colors.textSecondary, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onChange(v) }, valueRange = 0.05f..1f, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TitleLine(title: PrismTitle) {
    val tokens = MaterialTheme.nuvio
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AsyncImage(
            model = title.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(6.dp)).background(tokens.colors.surface),
        )
        Text(listOfNotNull(title.name, title.year).joinToString(" · "), color = tokens.colors.textPrimary)
    }
}

@Composable
private fun PrismDialogFrame(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.colors.surfaceDialog)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun PrismNameDialog(title: String, initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    val tokens = MaterialTheme.nuvio
    var name by remember { mutableStateOf(initial) }
    com.nuvio.app.core.ui.PlatformBackHandler(enabled = true, onBack = onCancel)
    PrismDialogFrame {
        Text(title, color = tokens.colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.align(Alignment.End)) {
            PrismButton("Cancel", onClick = onCancel)
            PrismButton("Save", primary = true, enabled = name.isNotBlank()) { onSave(name.trim()) }
        }
    }
}

@Composable
private fun PrismConfirmDialog(title: String, text: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    com.nuvio.app.core.ui.PlatformBackHandler(enabled = true, onBack = onCancel)
    PrismDialogFrame {
        Text(title, color = tokens.colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(text, color = tokens.colors.textSecondary)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.align(Alignment.End)) {
            PrismButton("Cancel", onClick = onCancel)
            PrismButton("Delete", primary = true, onClick = onConfirm)
        }
    }
}

/** While peeking: the app as it looks now, with one button back to Customise. */
@Composable
private fun PrismPeekButton() {
    val tokens = MaterialTheme.nuvio
    com.nuvio.app.core.ui.PlatformBackHandler(enabled = true) { PrismCustomise.peeking = false }
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.material3.Button(
            onClick = { PrismCustomise.peeking = false },
            shape = RoundedCornerShape(24.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = tokens.colors.accent, contentColor = tokens.colors.onAccent),
        ) { Text("Back to Customise", fontWeight = FontWeight.SemiBold) }
    }
}
