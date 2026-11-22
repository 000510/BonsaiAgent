package com.example.bonsaiagent.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v10.134：云端根治通道。
 *
 * 之前「搜索丢上下文 / 指代追问答非所问」的根子是：本地 4B 靠词表猜要不要搜索，
 * 猜不准（A组型号能中、B组指代全丢、C组误触发、4B 总结还不稳定）。
 * 现在直接把带 function calling 的云端模型接进来——模型**自己判断**「这个问题
 * 要不要联网」，要就调 web_search 工具，我们本地抓 Bing 喂回，它再总结。
 * 本地词表整个退役为「云端挂掉时的回退」。
 *
 * 三个中转站可切换 + 可选模型（用户提供，均为 OpenAI 兼容 /v1）：
 *   小力孩   https://www.ygxnhzy.cn/v1    首字 1.8s，每日 500 万 token，工具调用已验证
 *   77625    https://ly.77625.xyz/v1      慢(8-18s)，每分钟 5 次限流，公益
 *   商汤     https://token.sensenova.cn/v1 模型名必须小写，flash-lite 专属池省额度
 */
object CloudSearch {

    data class Provider(
        val id: String, val label: String, val baseUrl: String,
        val defaultModel: String, val models: List<String>
    )

    val PROVIDERS = listOf(
        Provider("xlh", "小力孩", "https://www.ygxnhzy.cn/v1",
            "DeepSeek-V4.1-Flash 小力孩AI",
            listOf("DeepSeek-V4.1-Flash 小力孩AI")),
        Provider("r77625", "77625路由站", "https://ly.77625.xyz/v1",
            "x-deepseek-v4.1-flash",
            listOf("x-deepseek-v4.1-flash", "gemini-3.8-flash")),
        Provider("st", "商汤SenseNova", "https://token.sensenova.cn/v1",
            "sensenova-6.8-flash-lite",
            listOf("sensenova-6.8-flash-lite", "deepseek-flash", "glm-5.3", "kimi-k3"))
    )

    fun provider(id: String): Provider =
        PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS[0]

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val SYS = """
你是手机上的个人助理，回答一律用简洁中文。
只有当问题需要「实时 / 最新 / 尚未掌握」的信息时，才调用 web_search 工具
（例如最新发布、新闻、股价、天气实况、你拿不准的事物）。
常识、闲聊、你知道的，直接回答，不要调用工具。
绝对不要编造数据。用户追问「那它呢 / 那个呢 / 它电池呢 / 我是说最新」这类指代时，
结合上一轮话题理解，必要时调用 web_search 查最新信息。
""".trimIndent()

    /** 一次云端对话的结果：回复文本 + 本次消耗的 token（跨轮 usage 累加） */
    /**
     * exhausted=true 表示「本站额度用尽 / 限流」——这类错误靠继续重试没用，
     * 只能切换中转站。云端返回 401/402/429 或报错文案含额度关键词时置 true。
     */
    data class ChatResult(val content: String, val tokens: Int, val exhausted: Boolean = false)

    /**
     * 一次云端对话：模型自己决定是否 web_search（function calling）。
     * @param history 最近对话历史 (role, content)
     * @return 模型最终回复 + 消耗 token
     */
    fun chat(baseUrl: String, apiKey: String, model: String,
             history: List<Pair<String, String>>, userMsg: String,
             maxTokens: Int = 1600): ChatResult {
        var totalTokens = 0
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYS))
        history.takeLast(8).forEach { (role, content) ->
            if (content.isNotBlank())
                messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userMsg))

        val tools = JSONArray().put(JSONObject().put("type", "function").put("function",
            JSONObject()
                .put("name", "web_search")
                .put("description", "联网搜索最新信息。搜索词只写核心实体，不写'最新/今天/怎么样'这类修饰词。")
                .put("parameters", JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("query",
                        JSONObject().put("type", "string").put("description", "搜索关键词")))
                    .put("required", JSONArray().put("query")))))

        // 最多两轮工具调用（第二轮一定是总结）
        repeat(2) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("max_tokens", maxTokens)
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.string() ?: return ChatResult("（云端无响应）", totalTokens)
            if (!resp.isSuccessful) {
                // v10.136：区分「额度用尽/限流」与其它错误——额度问题只能靠换中转站解决
                val errMsg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message") ?: ""
                } catch (e: Exception) { text.take(120) }
                val ex = resp.code in intArrayOf(401, 402, 429) || run {
                    val t = errMsg.lowercase()
                    t.contains("quota") || t.contains("limit") || t.contains("exceed") ||
                        t.contains("insufficient") || t.contains("balance") ||
                        t.contains("额度") || t.contains("配额") || t.contains("限流") ||
                        t.contains("未开通") || t.contains("余额")
                }
                return ChatResult("（云端报错 ${resp.code}${if (errMsg.isNotBlank()) " $errMsg" else ""}）",
                    totalTokens, exhausted = ex)
            }
            val j = JSONObject(text)
            // v10.136：有些网关把错误塞进 200 响应体，也要识别成额度问题
            j.optJSONObject("error")?.let { err ->
                return ChatResult("（云端报错：${err.optString("message", "")}）",
                    totalTokens, exhausted = true)
            }
            j.optJSONObject("usage")?.let { u ->
                totalTokens += u.optInt("prompt_tokens") + u.optInt("completion_tokens")
            }
            val msg = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return ChatResult("（云端无消息）", totalTokens)
            val tcs = msg.optJSONArray("tool_calls")
            if (tcs != null && tcs.length() > 0) {
                // 模型决定要搜索：本地抓 Bing，喂回 tool 结果
                messages.put(msg)
                for (i in 0 until tcs.length()) {
                    val tc = tcs.optJSONObject(i) ?: continue
                    val fn = tc.optJSONObject("function") ?: continue
                    if (fn.optString("name") != "web_search") continue
                    val query = try {
                        JSONObject(fn.optString("arguments")).optString("query")
                    } catch (e: Exception) { "" }
                    val result = if (query.isNotBlank()) {
                        try { WebSearch.search(query, 3) } catch (e: Exception) { "搜索失败" }
                    } else "搜索失败"
                    messages.put(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", tc.optString("id"))
                        .put("content", result.take(1800)))
                }
            } else {
                return ChatResult(msg.optString("content"), totalTokens)
            }
        }
        return ChatResult("（搜索循环超限）", totalTokens)
    }
}
