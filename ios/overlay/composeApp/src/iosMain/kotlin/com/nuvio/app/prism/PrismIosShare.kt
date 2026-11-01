@file:OptIn(ExperimentalForeignApi::class)

package com.nuvio.app.prism

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

// Prism on the iPhone answering the TV and PC over the home Wi-Fi (PrismShare, port 47800), while the app is on screen.
// The same tiny web answer as the Android phone, on plain sockets.
internal object PrismIosShare {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listening = -1
    private var watching = false

    /** Starts now and follows the app: on while it's on screen, off in the background. */
    fun follow() {
        start()
        if (watching) return
        watching = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, `object` = null, queue = NSOperationQueue.mainQueue) { _ -> start() }
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, `object` = null, queue = NSOperationQueue.mainQueue) { _ -> stop() }
    }

    private fun intOption(fd: Int, option: Int, on: Int = 1) = memScoped {
        val v = alloc<IntVar>()
        v.value = on
        setsockopt(fd, SOL_SOCKET, option, v.ptr, sizeOf<IntVar>().convert())
    }

    fun start() {
        if (listening >= 0) return
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return
        intOption(fd, SO_REUSEADDR)
        val ok = memScoped {
            val addr = alloc<sockaddr_in>()
            memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            val port = PrismShare.PORT
            addr.sin_port = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).convert()
            addr.sin_addr.s_addr = 0u
            bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0 && listen(fd, 8) == 0
        }
        if (!ok) {
            close(fd)
            return
        }
        listening = fd
        scope.launch {
            while (listening == fd) {
                val client = accept(fd, null, null)
                if (client < 0) break
                scope.launch { serve(client) }
            }
        }
    }

    fun stop() {
        val fd = listening
        listening = -1
        if (fd >= 0) close(fd)
    }

    private fun serve(client: Int) {
        try {
            intOption(client, SO_NOSIGPIPE)
            memScoped {
                val t = alloc<timeval>()
                t.tv_sec = 10
                t.tv_usec = 0
                setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, t.ptr, sizeOf<timeval>().convert())
            }
            val head = readHead(client) ?: return
            val target = head.lineSequence().firstOrNull().orEmpty().split(' ').getOrNull(1).orEmpty()
            val path = target.substringBefore('?')
            val code = target.substringAfter("code=", "").substringBefore('&')
            val body = runCatching { PrismShare.answer(path, code) }.getOrNull()
            val status = if (body != null) "200 OK" else "403 Forbidden"
            val bytes = body ?: ByteArray(0)
            sendAll(client, "HTTP/1.0 $status\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
            sendAll(client, bytes)
        } finally {
            close(client)
        }
    }

    /** The request up to the blank line (at most 16 KB). */
    private fun readHead(fd: Int): String? {
        val buffer = ByteArray(16 * 1024)
        var got = 0
        while (got < buffer.size) {
            val n = buffer.usePinned { recv(fd, it.addressOf(got), (buffer.size - got).convert(), 0) }.toInt()
            if (n <= 0) break
            got += n
            val text = buffer.decodeToString(0, got)
            if (text.contains("\r\n\r\n") || text.contains("\n\n")) return text
        }
        return if (got > 0) buffer.decodeToString(0, got) else null
    }

    private fun sendAll(fd: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        var off = 0
        bytes.usePinned { pinned ->
            while (off < bytes.size) {
                val n = send(fd, pinned.addressOf(off), (bytes.size - off).convert(), 0).toInt()
                if (n <= 0) return
                off += n
            }
        }
    }
}
