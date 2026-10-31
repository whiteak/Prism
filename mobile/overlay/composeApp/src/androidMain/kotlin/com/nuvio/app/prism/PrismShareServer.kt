package com.nuvio.app.prism

import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

// Prism: the phone's side of copying to the TV and PC (PrismShare), running while Prism is on screen.
internal object PrismShareServer {
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        val socket = runCatching { ServerSocket(PrismShare.PORT) }.getOrNull() ?: return
        server = socket
        thread(name = "prism-share", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve(client: Socket) = runCatching {
        client.use { s ->
            s.soTimeout = 10_000
            val reader = s.getInputStream().bufferedReader()
            val request = reader.readLine().orEmpty()
            while (reader.readLine()?.isNotEmpty() == true) Unit
            val target = request.split(' ').getOrNull(1).orEmpty()
            val path = target.substringBefore('?')
            val code = target.substringAfter("code=", "").substringBefore('&')
            val body = runCatching { PrismShare.answer(path, code) }.getOrNull()
            val out = s.getOutputStream()
            val status = if (body != null) "200 OK" else "403 Forbidden"
            val bytes = body ?: ByteArray(0)
            out.write("HTTP/1.0 $status\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
            out.write(bytes)
            out.flush()
        }
    }
}
