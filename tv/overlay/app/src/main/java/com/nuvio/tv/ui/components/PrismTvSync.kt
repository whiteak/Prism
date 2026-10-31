package com.nuvio.tv.ui.components

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Prism on the TV has its own look (Settings > Appearance > Customise this TV), and can copy what's set up on the
// phone (the phone's PrismShare): the look in use and the library, when asked, or by itself (if switched on) when
// Prism starts here and every two minutes, if Prism is open on the phone and something changed there.
object PrismTvSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var started = false

    private fun prefs(context: Context) = context.getSharedPreferences("prism", Context.MODE_PRIVATE)

    fun code(context: Context): String? = prefs(context).getString("phone_code", null)

    fun auto(context: Context): Boolean = prefs(context).getBoolean("phone_auto", false)
    fun setAuto(context: Context, on: Boolean) = prefs(context).edit().putBoolean("phone_auto", on).apply()

    @Volatile var status: String? = null
        private set

    fun startAuto(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            delay(5_000)
            while (true) {
                if (auto(app) && code(app) != null) copy(app, null, quiet = true)
                delay(120_000)
            }
        }
    }

    /** Copies from the phone; [newCode] when typed in. Returns what happened, in words. */
    suspend fun copy(context: Context, newCode: String?, quiet: Boolean = false): String = lock.withLock {
        val p = prefs(context)
        val c = newCode ?: code(context) ?: return@withLock "Type the phone's code first"
        val hello = PrismPhoneLink.find(c, p.getString("phone_host", null))
            ?: return@withLock "Couldn't find it. Open Prism on the phone, on the same Wi-Fi, and check the code.".also { if (!quiet) status = it }
        if (newCode == null && p.getString("phone_stamp", null) == hello.stamp) {
            p.edit().putString("phone_host", hello.host).apply()
            return@withLock "Already up to date with the phone".also { status = it }
        }
        val look = PrismPhoneLink.get(hello.host, "/prism/look", c, 120_000)
        val library = PrismPhoneLink.get(hello.host, "/prism/library", c)
        val name = look?.let { PrismTv.importLook(context, it) }
        if (library != null && library.isNotEmpty()) PrismTvLibraryImport.import(context, library, quiet = true)
        if (name == null) return@withLock "The phone answered but sent nothing usable. Try again.".also { status = it }
        p.edit().putString("phone_code", c).putString("phone_host", hello.host).putString("phone_stamp", hello.stamp).apply()
        "Copied \"$name\" from the phone. Restart Prism to see the new opening.".also { status = it }
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
