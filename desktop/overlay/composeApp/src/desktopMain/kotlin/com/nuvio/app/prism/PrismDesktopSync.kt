package com.nuvio.app.prism

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Prism on the PC has its own Customise; it can also copy everything set up on the phone (PrismShare), when asked,
// or by itself (if switched on) when it starts and every two minutes while Prism is open on the phone.
object PrismDesktopSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val file get() = File(PrismDesktop.dataDir, "phone-link.txt")

    /** code, last address, last stamp */
    private fun saved(): List<String> = runCatching { file.readText().lines() }.getOrDefault(emptyList())
    private fun save(code: String, host: String, stamp: String) = runCatching { file.writeText("$code\n$host\n$stamp") }

    val code: String? get() = saved().firstOrNull()?.takeIf { it.length == 4 }

    private val autoFile get() = File(PrismDesktop.dataDir, "phone-auto.txt")
    var auto by mutableStateOf(runCatching { autoFile.readText().trim() == "on" }.getOrDefault(false))
        private set

    fun changeAuto(on: Boolean) {
        auto = on
        runCatching { autoFile.writeText(if (on) "on" else "off") }
    }

    var status by mutableStateOf<String?>(null)
        private set

    fun startAuto() {
        scope.launch {
            delay(4_000)
            while (true) {
                if (auto && code != null) copy(null, quiet = true)
                delay(120_000)
            }
        }
    }

    /** Copies from the phone; [newCode] is set when typed in. Returns what happened, in words. */
    suspend fun copy(newCode: String?, quiet: Boolean = false): String = lock.withLock {
        val c = newCode ?: code ?: return@withLock "Type the phone's code first".also { status = it }
        val last = saved()
        if (!quiet) status = "Looking for Prism on the phone…"
        val hello = withContext(Dispatchers.IO) { PrismPhoneLink.find(c, last.getOrNull(1)?.ifBlank { null }) }
        if (hello == null) {
            if (!quiet || newCode != null) status = "Couldn't find it. Open Prism on the phone, on the same Wi-Fi, and check the code."
            return@withLock status.orEmpty()
        }
        if (newCode == null && last.getOrNull(2) == hello.stamp) {
            save(c, hello.host, hello.stamp)
            if (!quiet) status = "Already up to date with the phone"
            return@withLock status.orEmpty()
        }
        val backup = withContext(Dispatchers.IO) { PrismPhoneLink.get(hello.host, "/prism/backup", c, 120_000) }
        val library = withContext(Dispatchers.IO) { PrismPhoneLink.get(hello.host, "/prism/library", c) }
        val restored = backup != null && PrismStore.restore(backup)
        if (library != null && library.isNotEmpty()) com.nuvio.app.EditionLibraryImport.importFile(library)
        if (restored) save(c, hello.host, hello.stamp)
        status = if (restored) "Copied from the phone (look: ${hello.lookName})" else "The phone answered but sent nothing usable. Try again."
        status.orEmpty()
    }
}

// Finds Prism on the phone over the home Wi-Fi and fetches from it (the phone's side is PrismShare). Plain
// sockets, so Android's block on unencrypted web addresses doesn't apply; everything stays on the home network.
object PrismPhoneLink {
    const val PORT = 47800

    class Hello(val host: String, val stamp: String, val lookName: String)

    /** GET [path] from the phone at [host]; null if it isn't there or refused the code. */
    fun get(host: String, path: String, code: String, timeoutMs: Int = 15_000): ByteArray? = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, PORT), minOf(timeoutMs, 1_500))
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write("GET $path?code=$code HTTP/1.0\r\nHost: $host\r\n\r\n".encodeToByteArray())
                flush()
            }
            val all = s.getInputStream().readBytes()
            val split = (0 until all.size - 3).firstOrNull {
                all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() && all[it + 2] == '\r'.code.toByte() && all[it + 3] == '\n'.code.toByte()
            } ?: return null
            val head = all.copyOfRange(0, split).decodeToString()
            if (!head.startsWith("HTTP/1.0 200") && !head.startsWith("HTTP/1.1 200")) return null
            all.copyOfRange(split + 4, all.size)
        }
    }.getOrNull()

    fun hello(host: String, code: String): Hello? {
        val lines = get(host, "/prism/hello", code, 3_000)?.decodeToString()?.lines() ?: return null
        if (lines.firstOrNull() != "prism") return null
        return Hello(host, lines.getOrNull(1).orEmpty(), lines.getOrNull(2).orEmpty())
    }

    /** This device's home-network addresses (192.168.x.y and the like). */
    private fun localAddresses(): List<java.net.Inet4Address> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { it.isSiteLocalAddress }
    }.getOrDefault(emptyList())

    /** Looks for the phone: first where it was last time, then every address on the home network. */
    fun find(code: String, lastHost: String?): Hello? {
        lastHost?.let { hello(it, code) }?.let { return it }
        val hosts = localAddresses().flatMap { a ->
            val b = a.address
            (1..254).map { "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}.$it" }.filter { it != a.hostAddress }
        }.distinct()
        if (hosts.isEmpty()) return null
        val pool = java.util.concurrent.Executors.newFixedThreadPool(48)
        try {
            val open = hosts.map { host ->
                pool.submit<String?> {
                    runCatching {
                        java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(host, PORT), 400) }
                        host
                    }.getOrNull()
                }
            }.mapNotNull { runCatching { it.get() }.getOrNull() }
            return open.firstNotNullOfOrNull { hello(it, code) }
        } finally {
            pool.shutdownNow()
        }
    }
}
