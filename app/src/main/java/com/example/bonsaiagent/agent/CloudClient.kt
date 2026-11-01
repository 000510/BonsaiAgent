package com.example.bonsaiagent.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端调用：对接 DeepSeek 兼容的 OpenAI 式接口。
 * 接口地址来自设置页（默认 https://api.deepseek.com）。
 */
object CloudClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(java.time.Duration.ofSeconds(60))
        .build()

    fun chat(apiBase: String, apiKey: String, userPrompt: String): String {
        if (apiKey.isBlank()) return "[未配置 DeepSeek Key，请在设置页填写]"
        val base = apiBase.trim().removeSuffix("/")
        val url = "$base/v1/chat/completions"
        val bodyJson = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
            put("stream", false)
        }.toString()

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "[云端错误 ${resp.code}] ${text.take(300)}"
                val json = JSONObject(text)
                json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
        } catch (e: Exception) {
            "[云端调用异常] ${e.message}"
        }
    }
}
