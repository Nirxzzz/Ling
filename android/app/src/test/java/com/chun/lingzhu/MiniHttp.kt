package com.chun.lingzhu

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** 测试用极简 HTTP 服务：读请求体 → 交给 handler → 按 (状态码, 响应体) 回写。每连接一请求 */
class MiniHttp(private val handler: (path: String, body: String) -> Pair<Int, String>) {
    private val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = ss.localPort
    @Volatile private var open = true

    init {
        thread(isDaemon = true) {
            while (open) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    s.use { sock ->
                        val input = BufferedInputStream(sock.getInputStream())
                        fun readLine(): String {
                            val sb = StringBuilder()
                            while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
                            return sb.toString()
                        }
                        val req = readLine()
                        var len = 0
                        while (true) { val h = readLine(); if (h.isEmpty()) break; if (h.lowercase().startsWith("content-length:")) len = h.substring(15).trim().toInt() }
                        val buf = ByteArray(len); var off = 0
                        while (off < len) { val n = input.read(buf, off, len - off); if (n < 0) break; off += n }
                        val (code, resp) = handler(req.split(' ').getOrElse(1) { "/" }, String(buf, Charsets.UTF_8))
                        val bytes = resp.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 $code X\r\nContent-Type: ${if (code == 200) "text/event-stream" else "application/json"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        sock.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                    }
                }
            }
        }
    }

    fun stop() { open = false; ss.close() }
}
