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
        // v10.124【修复】format 必须做完整 URL 编码，否则 OkHttp 会把 %l/%t 等占位符重编码成
        //   %25l，wttr.in 解析 format 失败 → 回退返回整页 HTML（"输出001"笔记实测）。
        //   编码后用竖线分隔 + 中文标签，wttr.in decode 后完全还原（实测 `南京 | ✨ | +10°C | ...`）。
        //   另：无城市时默认南京（用户常驻城市）——wttr.in IP 定位实测会跑到郑州，对"明天天气咋样"这类
        //   无城市查询给出错误城市，默认常驻城市更符合预期。
        val c = if (city.isNullOrBlank()) "南京" else city
        val path = "/" + URLEncoder.encode(c, "UTF-8")
        val encFmt = URLEncoder.encode("%l | %c | %t | %C | 湿度%h | 风%w", "UTF-8").replace("+", "%20")
        val url = "https://wttr.in$path?format=$encFmt&lang=zh"
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
        val base = if (fromModel.length >= 2) fromModel else normalizeQuery(rawMsg)
        // v10.123【方案第1条】限8词：模型容易往词里塞累赘，超长就截断
        val words = base.split(Regex("\\s+")).filter { it.isNotBlank() }
        return if (words.size > 8) words.take(8).joinToString(" ") else base
    }

    /**
     * 搜索关键词，返回给模型看的"资料文本"（最多取 maxResults 条）。
     * v10.123【方案第2+3条】先筛后抓：
     *   主源 Bing 中文版，但先做确定性过滤（排除登录/广告/导航类垃圾，只留前 3 条真内容），
     *   过滤后为 0 或被拦截时自动切 DuckDuckGo html 版备胎 —— 不再把垃圾网页塞给 4B。
     * 正文判废（<500字/登录验证）这层留给以后加"抓正文"时一起做，当前只靠 snippet。
     */
    fun search(rawQuery: String, maxResults: Int = 3): String {
        val query = normalizeQuery(rawQuery)
        if (query.isBlank()) return "[搜索词为空]"
        val enc = URLEncoder.encode(query, "UTF-8")

        val bingHtml = tryFetch("https://www.bing.com/search?q=$enc&setlang=zh-cn")
        if (!bingHtml.isNullOrBlank()) {
            val r = parseBing(bingHtml, query, maxResults)
            if (r != null) return r
        }
        val ddgHtml = tryFetch("https://html.duckduckgo.com/html/?q=$enc")
        if (!ddgHtml.isNullOrBlank()) {
            val r = parseDuck(ddgHtml, query, maxResults)
            if (r != null) return r
        }
        return "[搜索无结果] 可能是网络问题或搜索引擎拦截"
    }

    /** 抓 HTML，HTTP 失败/异常返回 null（让调用方切备胎源） */
    private fun tryFetch(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string().orEmpty()
        }
    } catch (_: Exception) { null }

    /** Bing 结果页 → 过滤后最多 maxResults 条；没剩有用的返回 null（交给备胎源） */
    private fun parseBing(html: String, query: String, maxResults: Int): String? {
        if (html.length < 200) return null
        val sb = StringBuilder()
        sb.append("搜索「$query」的结果：\n")
        val blockRe = Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL)
        val h2Re = Regex("<h2[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL)
        val pRe = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        var count = 0
        for (block in blockRe.findAll(html)) {
            if (count >= maxResults) break
            val title = h2Re.find(block.value)?.groupValues?.get(1)?.let { stripTags(it) }.orEmpty()
            val summary = pRe.find(block.value)?.groupValues?.get(1)?.let { stripTags(it) }.orEmpty()
            if (title.isEmpty() && summary.isEmpty()) continue
            // 【先筛后抓】垃圾直接跳过：登录/注册/验证/广告/导航类，不配占名额
            if (isGarbage(title, summary)) continue
            count++
            sb.append("$count. $title\n")
            if (summary.isNotEmpty()) sb.append("   $summary\n")
        }
        return if (count == 0) null else sb.toString().take(1500)
    }

    /** DuckDuckGo html 版（无JS、反爬松）→ 同样过滤后最多 maxResults 条 */
    private fun parseDuck(html: String, query: String, maxResults: Int): String? {
        if (html.length < 200) return null
        val sb = StringBuilder()
        sb.append("搜索「$query」的结果：\n")
        val titleRe = Regex("<a[^>]*class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snipRe = Regex("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val titles = titleRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        val snips = snipRe.findAll(html).map { stripTags(it.groupValues[1]) }.toList()
        if (titles.isEmpty()) return null
        var count = 0
        for (i in titles.indices) {
            if (count >= maxResults) break
            val t = titles[i]
            if (t.isBlank()) continue
            val s = if (i < snips.size) snips[i] else ""
            if (isGarbage(t, s)) continue
            count++
            sb.append("$count. $t\n")
            if (s.isNotEmpty()) sb.append("   $s\n")
        }
        return if (count == 0) null else sb.toString().take(1500)
    }

    /** 垃圾信号：登录/注册/验证码/广告/推广/下载App/官方首页/优惠红包等 —— 这些不配给 4B 当材料 */
    private val GARBAGE_RE = Regex("登录|注册|验证码|广告|推广|下载App|App下载|官方首页|猜你|优惠|红包|邀请码|立即下载|免费领取")
    private fun isGarbage(title: String, summary: String): Boolean =
        GARBAGE_RE.containsMatchIn(title + " " + summary)

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
