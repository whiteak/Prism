package com.nuvio.app.prism

import kotlin.random.Random

// Prism: the phone is where everything is set up; the TV and PC copy it over the home Wi-Fi. While Prism is open
// on the phone it answers on port 47800, only to a device that gives its four-digit code (Customise > Save and
// share). Nothing leaves the house. Clips stay where they are (they can be gigabytes).

object PrismShare {
    const val PORT = 47800

    private fun file(name: String) = Prism.platform?.let { "${it.dataDir}/$name" }

    /** This phone's code, made once. */
    val code: String by lazy {
        val p = Prism.platform
        val path = file("share-code.txt")
        val saved = path?.let { p?.readBytes(it)?.decodeToString()?.trim() }?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        saved ?: Random.nextInt(0, 10_000).toString().padStart(4, '0').also { fresh ->
            path?.let { runCatching { p?.writeBytes(it, fresh.encodeToByteArray()) } }
        }
    }

    /** Changes whenever anything worth copying changes: looks, fonts, profile photos or the library. */
    fun stamp(): String {
        val p = Prism.platform ?: return "0"
        var h = 0xcbf29ce484222325uL
        fun add(bytes: ByteArray?) {
            bytes?.forEach { b -> h = (h xor (b.toULong() and 0xffuL)) * 0x100000001b3uL }
        }
        listOf("looks.json", "avatars.json", "avatar-photos.json").forEach { name -> add(file(name)?.let { p.readBytes(it) }) }
        add(com.nuvio.app.EditionLibraryImport.exportFile())
        return h.toString(16)
    }

    /** What the phone sends back for [path], or null to refuse. */
    fun answer(path: String, givenCode: String?): ByteArray? {
        if (givenCode != code) return null
        return when (path) {
            "/prism/hello" -> "prism\n${stamp()}\n${PrismStore.look.name}".encodeToByteArray()
            "/prism/look" -> PrismStore.exportLook()?.second
            "/prism/backup" -> PrismStore.backup()
            "/prism/library" -> com.nuvio.app.EditionLibraryImport.exportFile() ?: ByteArray(0)
            else -> null
        }
    }
}
