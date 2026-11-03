package com.example.fatfishphone

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * 极简 HTTP 配置服务：跑一个 ServerSocket，手表端通过 GET 拉取配置。
 * 端点：
 *   GET /config        -> 完整配置 JSON
 *   GET / /ping /health -> {"ok":true,"app":"FatFishPhone"}
 *   其他                -> 404 {"error":"not found"}
 */
class ConfigServer(private val configProvider: () -> String) {

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null

    /** 启动服务（阻塞在后台线程），失败返回 false（如端口被占用） */
    fun start(port: Int): Boolean {
        if (running) return true
        return try {
            serverSocket = ServerSocket(port)
            running = true
            Thread({ acceptLoop() }, "fatfish-config-server").start()
            true
        } catch (_: Exception) {
            serverSocket = null
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (running) {
            try {
                val client = socket.accept()
                Thread({ handle(client) }, "fatfish-config-handler").start()
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val line = reader.readLine() ?: return
            val parts = line.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1) ?: "/"

            val status: String
            val body: String
            when {
                method == "GET" && path == "/config" -> {
                    status = "200 OK"
                    body = configProvider() ?: "{}"
                }
                method == "GET" && (path == "/" || path == "/ping" || path == "/health") -> {
                    status = "200 OK"
                    body = "{\"ok\":true,\"app\":\"FatFishPhone\"}"
                }
                else -> {
                    status = "404 Not Found"
                    body = "{\"error\":\"not found\"}"
                }
            }

            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            val header = "HTTP/1.1 $status\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            val os = client.getOutputStream()
            os.write(header.toByteArray(StandardCharsets.UTF_8))
            os.write(bodyBytes)
            os.flush()
        } catch (_: Exception) {
            // 单个连接失败不影响服务
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }
}
