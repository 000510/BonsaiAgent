package com.example.bonsaiagent.api

import android.util.Log
import com.example.bonsaiagent.llm.Llama
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 本机 HTTP API 服务（类似 Ollama 的最小可用接口）。
 * 监听 127.0.0.1:11434，提供：
 *   POST /api/generate  { "prompt": "...", "system": "...", "max_tokens": N }
 *   ->  { "response": "..." }   （非流式，简单可用）
 * 仅在本进程内运行，外部不可访问，安全。
 */
class LocalApiServer(
    private val port: Int = 11434,
    private val getSettings: () -> Triple<Float, Int, Float> // temp, topK, topP
) {
    private var serverSocket: ServerSocket? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(port, 0, java.net.InetAddress.getByName("127.0.0.1"))
                Log.i("LocalApi", "API 已启动: http://127.0.0.1:$port")
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    handle(client)
                }
            } catch (e: Exception) {
                Log.e("LocalApi", "服务异常: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handle(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)
            val path = parts.getOrNull(1) ?: "/"

            // 读取头部与 body 长度
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                if (line!!.startsWith("Content-Length", ignoreCase = true))
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            var body = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                reader.read(buf, 0, contentLength)
                body = String(buf)
            }

            val (resp, code) = when {
                method == "POST" && path == "/api/generate" -> doGenerate(body)
                method == "GET" && path == "/" -> Pair("{\"status\":\"ok\"}", 200)
                else -> Pair("{\"error\":\"not found\"}", 404)
            }
            sendResponse(socket, code, resp)
        } catch (e: Exception) {
            Log.e("LocalApi", "请求处理失败: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun doGenerate(body: String): Pair<String, Int> {
        if (!Llama.isLoaded()) return Pair("{\"error\":\"模型未加载\"}", 503)
        val json = JSONObject(body)
        val prompt = URLDecoder.decode(json.optString("prompt", ""), "UTF-8")
        val system = json.optString("system", "")
        val maxTokens = json.optInt("max_tokens", 512)
        val (temp, topK, topP) = getSettings()

        val full = StringBuilder()
        // v8.0：generate 增加 sysPrefix 参数（KV 前缀缓存用），这里走不缓存的旧路径
        val out = Llama.generate(
            sysPrefix = "",
            prompt = if (system.isBlank()) prompt else "$system\n\n用户: $prompt\n助手:",
            temp = temp, topK = topK, topP = topP, repPen = 1.0f, maxTokens = maxTokens
        ) { full.append(it) }

        return Pair(JSONObject().put("response", out).toString(), 200)
    }

    private fun sendResponse(socket: Socket, code: Int, body: String) {
        val out: OutputStream = socket.getOutputStream()
        val text = "HTTP/1.1 $code OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" + body
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }
}
