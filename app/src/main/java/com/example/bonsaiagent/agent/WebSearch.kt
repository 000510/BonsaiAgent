package com.example.bonsaiagent.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * v10.118：网页搜索 + 正文抓取。
 *
 * 不引入 jsoup（避免加依赖/重新下载），用正则从搜索结果页里提取标题和摘要。
 * 抓取结果喂给 1B 小模型总结，速度远快于让 4B 读长文。
 */
object WebSearch {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 搜索关键词，返回给模型看的"资料文本"（最多取 maxResults 条）。
     * 用 Bing 中文版——返回的 HTML 里每条结果有 <li class="b_algo">...<h2>标题</h2>...<p>摘要</p>。
     */
    fun search(query: String, maxResults: Int = 5): String {
        if (query.isBlank()) return "[搜索词为空]"
        val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&setlang=zh-cn"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .build()
        val html = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "[搜索失败 HTTP ${resp.code}]"
                resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            return "[搜索失败] ${e.message}"
        }
        if (html.length < 200) return "[搜索无结果] 可能是网络问题或搜索引擎拦截"

        val sb = StringBuilder()
        sb.append("搜索「$query」的结果：\n")
        // 提取 b_algo 块里的 <h2>标题</h2> 和 <p>摘要</p>
        val blockRe = Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL)
        val h2Re = Regex("<h2[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL)
        val pRe = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        var count = 0
        for (block in blockRe.findAll(html)) {
            if (count >= maxResults) break
            val title = h2Re.find(block.value)?.groupValues?.get(1)?.let { stripTags(it) }.orEmpty()
            val summary = pRe.find(block.value)?.groupValues?.get(1)?.let { stripTags(it) }.orEmpty()
            if (title.isEmpty() && summary.isEmpty()) continue
            count++
            sb.append("$count. $title\n")
            if (summary.isNotEmpty()) sb.append("   $summary\n")
        }
        if (count == 0) {
            // 兜底：搜索引擎结构变了，退而求其次抓整页纯文本前 800 字
            val plain = stripTags(html).replace(Regex("\\s+"), " ").trim()
            if (plain.length > 30) sb.append(plain.take(800)) else return "[搜索无结果]"
        }
        return sb.toString().take(1500)   // 限制长度，控制 1B 的 prefill 时间
    }

    /** 去掉 HTML 标签、反转义常见实体 */
    private fun stripTags(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
}
