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

    private val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    // ============================================================
    // v10.123【系统性重构】把搜索做成一条确定性流水线，而不是逐 bug 打补丁：
    //   意图分类(代码判定) → 检索通道(天气走wttr.in，其余走Bing) → 查询词构建(代码提取实体)
    //   → 结果展示。
    // 为什么：模型会编搜索词/编答案，Bing 对口语词/无实体词会跑偏，三者互相污染导致
    //   一类问题反复出现(v10.121口语词→Bing跑偏 / v10.122查询句误开应用 / v10.123无地名→定位城市)。
    //   这里把"意图判定+实体提取+查询词构建"全部变成确定性代码，模型只负责决定"该不该搜"。
    // ============================================================

    /** 天气类意图：命中就直连 wttr.in，模型和 Bing 都不参与 */
    private val WEATHER_REGEX = Regex("天气|气温|温度|下雨|降雨|晴|阴|降温|升温|湿度|雾霾|大风")

    /** 常用城市表（从用户原话里提取地名实体） */
    private val CITY_LIST = listOf(
        "南京", "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "苏州",
        "天津", "重庆", "长沙", "郑州", "合肥", "无锡", "宁波", "青岛", "济南", "福州",
        "厦门", "昆明", "贵阳", "沈阳", "大连", "哈尔滨", "长春", "石家庄", "太原", "南昌",
        "南宁", "海口", "兰州", "西宁", "银川", "乌鲁木齐", "呼和浩特", "拉萨", "佛山", "东莞",
        "珠海", "中山", "惠州", "温州", "嘉兴", "绍兴", "常州", "南通", "徐州", "扬州",
        "镇江", "泰州", "盐城", "淮安", "连云港", "宿迁", "洛阳", "烟台", "潍坊", "日照"
    )

    fun isWeather(msg: String): Boolean = WEATHER_REGEX.containsMatchIn(msg)

    /** 从用户原话提取城市实体；没有则返回 null（走 IP 定位） */
    fun cityOf(msg: String): String? = CITY_LIST.firstOrNull { msg.contains(it) }

    /**
     * v10.123：天气直连 wttr.in。
     * 中文城市名直接可用（实测 wttr.in/南京 返回真实数据），city 为 null 时按 IP 定位。
     * 返回可直接展示的文本，不走模型总结 —— 模型只会编"无法提供未来天气"。
     */
    fun weather(city: String?): String {
        val path = if (!city.isNullOrBlank()) "/" + URLEncoder.encode(city, "UTF-8") else "/"
        val url = "https://wttr.in$path?format=%l:+%c+%t+%C+湿度%h+风%w&lang=zh"
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) "[天气查询失败 HTTP ${resp.code}]"
                else resp.body?.string().orEmpty().trim().ifBlank { "[天气查询无数据]" }
            }
        } catch (e: Exception) {
            "[天气查询失败] ${e.message}"
        }
    }

    /**
     * v10.123：构建搜索词。优先用模型给的词（已清洗），太短/空则回退到用户原话。
     * 模型给的词不可靠（会带口语/漏实体），这里只把"词"当参考，实体提取靠清洗兜底。
     */
    fun buildQuery(rawMsg: String, modelArg: String?): String {
        val fromModel = modelArg?.let { normalizeQuery(it) }.orEmpty()
        if (fromModel.length >= 2) return fromModel
        return normalizeQuery(rawMsg)
    }

    /**
     * 搜索关键词，返回给模型看的"资料文本"（最多取 maxResults 条）。
     * 用 Bing 中文版——返回的 HTML 里每条结果有 <li class="b_algo">...<h2>标题</h2>...<p>摘要</p>。
     */
    fun search(rawQuery: String, maxResults: Int = 5): String {
        val query = normalizeQuery(rawQuery)
        if (query.isBlank()) return "[搜索词为空]"
        val url = "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&setlang=zh-cn"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
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
        return sb.toString().take(1500)   // 限制长度，控制 4B 的 prefill 时间
    }

    /**
     * v10.121：搜索词清洗 —— 这是本功能正确性的关键，实测如下：
     *
     * 模型常把用户整句口语当搜索词（如「南京今天气温多少度」）。Bing 对这类
     * 「带 今天/多少度/怎么样/日期」的长查询会判成历史/百科类，直接返回
     * 南京历史、动物园、日历页，干货 0/5；而短核心词「南京天气预报」能命中
     * 「今日天气：南京市，晴,4℃~15℃」。所以这里把时间词、语气词、冗余动词
     * 全部剥掉，只留实体+主题词，把 Bing 拉回正常结果轨道。
     *
     * 词表按长度降序，避免「多少度」被「多少」「度」拆开残留。
     */
    private fun normalizeQuery(raw: String): String {
        var s = raw.trim()
        if (s.isBlank()) return s
        val fillers = arrayOf(
            // 时间/时效词（最致命，一出现 Bing 就跑偏）
            "天气预报情况", "今天", "明天", "昨天", "现在", "目前", "实时", "最新",
            "近日", "未来", "今晚", "明早", "这周", "下周", "本周", "今日", "明日", "最近",
            // 口语/疑问/冗余
            "怎么样", "咋样", "咋", "啥样", "怎么回事", "可不可以", "能不能", "多少度", "几度", "告诉我", "说一下",
            "了解一下", "查一下", "搜一下", "查一查", "如何", "什么", "多少", "情况",
            "查询", "搜索", "查查", "看看", "了解", "知道", "麻烦", "请问", "帮我",
            "是否", "一下", "吗", "呢", "啊", "吧", "呀", "嘛", "请", "能", "要", "想",
            "可以", "的", "了", "是"
        )
        for (f in fillers) s = s.replace(f, "")
        // 压缩空白、去掉首尾标点
        s = s.replace(Regex("\\s+"), " ").trim(' ', '，', '。', '？', '！', '、', ',', '.', '?', '!', '：', ':')
        return s.ifBlank { raw.trim() }
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
