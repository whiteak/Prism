package com.nuvio.tv.ui.screens.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.components.PrismTv
import com.nuvio.tv.ui.components.PrismTvLibraryImport
import com.nuvio.tv.ui.components.PrismTvSleep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Prism: this TV's own customising, copying from the phone (optional), loading a look or a library sent from
// the phone, the sounds switch, and the screensaver and sleep timers (Settings > Appearance).
@Composable
internal fun PrismTvSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    PrismTv.init(context)
    val look = PrismTv.look
    var soundsOn by remember { mutableStateOf(PrismTv.soundsEnabled(context)) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    // Either kind of file works from either row: a look (.prism) or a library (.prismlib).
    fun load(uri: Uri?) {
        uri ?: return
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            when {
                bytes == null -> toast("That file couldn't be opened")
                PrismTvLibraryImport.isLibraryFile(bytes) -> PrismTvLibraryImport.import(context, bytes)
                else -> {
                    val name = withContext(Dispatchers.IO) { PrismTv.importLook(context, bytes) }
                    toast(if (name != null) "Loaded the look \"$name\". Restart Prism to see the new opening." else "That file isn't a Prism look or library")
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { load(it) }
    fun pick() {
        runCatching { picker.launch("*/*") }.onFailure {
            toast("No file picker found. Install a file manager such as ES File Explorer, then try again.")
        }
    }

    var phoneCode by remember { mutableStateOf(com.nuvio.tv.ui.components.PrismTvSync.code(context)) }
    fun copyFromPhone(code: String?) {
        toast("Looking for Prism on the phone…")
        scope.launch {
            val result = withContext(Dispatchers.IO) { com.nuvio.tv.ui.components.PrismTvSync.copy(context, code) }
            phoneCode = com.nuvio.tv.ui.components.PrismTvSync.code(context)
            toast(result)
        }
    }
    // A plain number box; the TV's own keyboard comes up for it.
    fun askForCode() {
        val box = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            setText(phoneCode.orEmpty())
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("Phone's code")
            .setMessage("On the phone: Prism > Settings > Appearance > Customise > 11. Save and share")
            .setView(box)
            .setPositiveButton("Copy") { _, _ ->
                val typed = box.text.toString().trim()
                if (typed.length == 4) copyFromPhone(typed) else toast("The code has four numbers")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    PrismTvCustomise()
    SettingsActionRow(
        title = "Copy from the phone",
        subtitle = "Optional: use the phone's set-up here instead, over the home Wi-Fi while Prism is open on the phone. Replaces this TV's own look",
        value = phoneCode?.let { "Code $it" } ?: "Not set up",
        onClick = { if (phoneCode == null) askForCode() else copyFromPhone(null) },
    )
    if (phoneCode != null) {
        var auto by remember { mutableStateOf(com.nuvio.tv.ui.components.PrismTvSync.auto(context)) }
        SettingsToggleRow(
            title = "Keep copying from the phone",
            subtitle = "By itself when Prism starts and every couple of minutes. Off keeps this TV's own customising",
            checked = auto,
            onToggle = {
                auto = !auto
                com.nuvio.tv.ui.components.PrismTvSync.setAuto(context, auto)
            },
        )
        SettingsActionRow(
            title = "Change the phone's code",
            subtitle = "If the code on the phone is different",
            onClick = { askForCode() },
        )
    }
    SettingsActionRow(
        title = "Load a look from the phone",
        subtitle = "Export a look in Prism on the phone (Customise > Looks > Export), send it here with Send Anywhere, then pick the .prism file",
        value = look?.name ?: "Built-in",
        onClick = { pick() },
    )
    SettingsActionRow(
        title = "Add library from a file",
        subtitle = "Pick a .prismlib file saved on the phone (Customise > Library). Titles already here are skipped",
        onClick = { pick() },
    )
    SettingsToggleRow(
        title = "Sounds",
        subtitle = "Play the look's sounds (opening, profile, nothing found, can't play, random episode)",
        checked = soundsOn,
        onToggle = {
            soundsOn = !soundsOn
            PrismTv.setSoundsEnabled(context, soundsOn)
        },
    )
    var prismSaver by remember { mutableStateOf(PrismTvSleep.prismScreensaver(context)) }
    var screenOff by remember { mutableStateOf(PrismTvSleep.screenOffMinutes(context)) }
    var stillWatching by remember { mutableStateOf(PrismTvSleep.stillWatchingHours(context)) }
    fun <T> next(options: List<T>, current: T) = options[(options.indexOf(current) + 1) % options.size]
    SettingsActionRow(
        title = "Screensaver",
        subtitle = "What Ambient Mode shows when the TV sits idle on the menus (switch it on and set when it starts under Ambient Mode)",
        value = if (prismSaver) "Prism pictures" else "Nuvio posters",
        onClick = {
            prismSaver = !prismSaver
            PrismTvSleep.setPrismScreensaver(context, prismSaver)
        },
    )
    SettingsActionRow(
        title = "Turn the screen off after",
        subtitle = "How long the screensaver shows before it goes dark and lets the Shield sleep, which turns the TV off",
        value = PrismTvSleep.minutesLabel(screenOff),
        onClick = {
            screenOff = next(PrismTvSleep.SCREEN_OFF_OPTIONS, screenOff)
            PrismTvSleep.setScreenOffMinutes(context, screenOff)
        },
    )
    SettingsActionRow(
        title = "Still watching?",
        subtitle = "While something plays with no remote press for this long, ask; with no answer in a minute, stop and let the TV sleep",
        value = PrismTvSleep.hoursLabel(stillWatching),
        onClick = {
            stillWatching = next(PrismTvSleep.STILL_WATCHING_OPTIONS, stillWatching)
            PrismTvSleep.setStillWatchingHours(context, stillWatching)
        },
    )
    if (look != null) {
        SettingsActionRow(
            title = "Back to the built-in look",
            subtitle = "Removes \"${look.name}\" from this TV",
            onClick = {
                PrismTv.clear(context)
                toast("Back to Prism's built-in look")
            },
        )
    }
}
