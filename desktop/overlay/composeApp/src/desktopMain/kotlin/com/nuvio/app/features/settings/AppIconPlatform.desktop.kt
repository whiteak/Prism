package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.desktop.DesktopPaths
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.jetbrains.compose.resources.painterResource

// Prism on the PC: the app icon picked in Settings > Appearance shows on the window and taskbar at once,
// and Prism's shortcuts on the desktop and in the Start menu get it too (where Windows lets them be changed).
internal actual object AppIconPlatform {
    actual val requiresCloseConfirmation: Boolean = false

    private val file: File get() = File(DesktopPaths.dataDir, "prism/app-icon.txt")

    /** The picked icon's name, or null for the standard one. */
    internal var chosen by mutableStateOf(runCatching { file.readText().trim().ifBlank { null } }.getOrNull())
        private set

    // Prism's own file (above) already remembers the pick, so Nuvio's saved key isn't needed: returning null
    // makes Nuvio read the pick from currentIconName().
    actual fun loadSelectedIconKey(): String? = null

    actual fun saveSelectedIconKey(key: String) {}

    actual fun currentIconName(): String? = chosen

    @OptIn(ExperimentalResourceApi::class)
    actual suspend fun activateIcon(name: String?): Boolean {
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(name.orEmpty())
            }.isSuccess
        }
        if (!saved) return false
        chosen = name
        withContext(Dispatchers.IO) {
            runCatching {
                val png = if (name == null) {
                    System.getProperty("compose.application.resources.dir")?.let { File(it, "icon.png") }?.takeIf { it.isFile }?.readBytes()
                } else {
                    getDrawableResourceBytes(getSystemResourceEnvironment(), AppIconOption.fromPlatformName(name).previewResource)
                }
                if (png != null) updateShortcuts(png)
            }
        }
        return true
    }

    /** Writes the icon as a .ico (a PNG inside, as Windows allows) and points Prism's shortcuts at it. */
    private fun updateShortcuts(png: ByteArray) {
        val ico = File(DesktopPaths.dataDir, "prism/app-icon.ico")
        val header = java.nio.ByteBuffer.allocate(22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(1).putShort(1)
            .put(0).put(0).put(0).put(0).putShort(1).putShort(32).putInt(png.size).putInt(22)
        ico.writeBytes(header.array() + png)
        val appName = DesktopPaths.appName.replace("'", "")
        val script = """
            ${'$'}ws = New-Object -ComObject WScript.Shell
            ${'$'}places = @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('CommonDesktopDirectory'), [Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('CommonPrograms'))
            foreach (${'$'}place in ${'$'}places) {
              if (-not ${'$'}place) { continue }
              Get-ChildItem -Path ${'$'}place -Filter '$appName.lnk' -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
                try { ${'$'}s = ${'$'}ws.CreateShortcut(${'$'}_.FullName); ${'$'}s.IconLocation = '${ico.absolutePath.replace("'", "''")},0'; ${'$'}s.Save() } catch {}
              }
            }
        """.trimIndent()
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
            .redirectErrorStream(true)
            .start()
        process.inputStream.readBytes()
        process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
    }
}

/** The window and taskbar icon: the one picked in Settings > Appearance, or Prism's own. */
@Composable
internal fun desktopWindowIcon(): Painter? {
    val name = AppIconPlatform.chosen
    val standard = androidx.compose.runtime.remember { com.nuvio.app.prism.PrismDesktop.windowIcon() }
    return if (name == null) standard else painterResource(AppIconOption.fromPlatformName(name).previewResource)
}
