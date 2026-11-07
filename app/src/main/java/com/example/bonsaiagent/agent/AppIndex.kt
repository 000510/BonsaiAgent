package com.example.bonsaiagent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/**
 * 手机已装应用的索引。
 *
 * 为什么要自己扫
 * ------------
 * 之前"打开微信"打不开，是因为动作表只硬编码了 9 个系统动作，没有"微信"。
 * 靠人肉维护映射表不可能覆盖用户装的几十个 App —— 直接读系统清单，
 * 用户装了什么就能开什么，一个字都不用改。
 *
 * 模型在这里的角色降级了：它不用记协议名，只要说"用户想开微信"，
 * 甚至根本不用参与 —— 关键词扫到就直接开。
 *
 * Android 11 的坑
 * --------------
 * API 30 起 package visibility 收紧，没在 manifest 里声明 <queries> 的话，
 * queryIntentActivities 会返回空列表（不是报错，是静默返回空，最难查的一种）。
 */
object AppIndex {

    data class Entry(val label: String, val pkg: String)

    @Volatile
    private var entries: List<Entry> = emptyList()

    @Volatile
    private var built = false

    private const val PREF = "app_index"
    private const val KEY = "entries"

    /**
     * 口语 → 系统标签。
     *
     * 只放"系统标签里没有用户那个词"的情况：
     *   B站 / 哔哩哔哩   —— 标签是"哔哩哔哩"，口头上没人这么说
     *   网易云 / 云音乐  —— 标签是"网易云音乐"
     * 像"微信""淘宝"这种标签本身就对得上的，不用写。
     */
    private val ALIAS = mapOf(
        "b站" to "哔哩哔哩", "bilibili" to "哔哩哔哩", "哔哩" to "哔哩哔哩",
        "bili" to "哔哩哔哩", "小破站" to "哔哩哔哩", "哔站" to "哔哩哔哩",
        "哔哩哔哩" to "哔哩哔哩", "bilibili动画" to "哔哩哔哩",
        "网易云" to "网易云音乐", "云音乐" to "网易云音乐",
        "qq音乐" to "QQ音乐",
        "酷狗" to "酷狗音乐",
        "高德" to "高德地图", "地图" to "高德地图",
        "贴吧" to "百度贴吧",
        "wps" to "WPS Office", "office" to "WPS Office",
        "小红书" to "小红书",
        "闲鱼" to "闲鱼",
        "知乎" to "知乎",
        "携程" to "携程旅行",
        "饿了么" to "饿了么",
        "打的车" to "滴滴出行", "滴滴" to "滴滴出行",
        "银行" to "中国工商银行",
        "拍照" to "相机",
        "照相机" to "相机",
        "文件" to "文件管理", "文件夹" to "文件管理",
        "计算器" to "计算器",
        "日历" to "日历",
        "时钟" to "时钟", "闹钟" to "时钟"
    )

    private fun norm(s: String): String = s.trim().lowercase()
        .replace(" ", "")
        .replace("　", "")
        .replace('（', '(').replace('）', ')')

    /** 只保留中文、字母、数字，便于包含匹配 */
    private fun core(s: String): String = s.filter { it.isLetterOrDigit() }

    /**
     * 建立/刷新索引。
     *
     * 一次 queryIntentActivities 约 80~300ms，放在后台线程做，
     * 结果缓存到 SharedPreferences —— 下次冷启动直接读缓存，不用等。
     */
    fun refresh(ctx: Context): Int {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val seen = HashSet<String>()
        val out = ArrayList<Entry>(list.size)
        for (ri in list) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName) continue          // 不索引自己
            if (!seen.add(pkg)) continue                   // 一个包可能有多个入口
            val label = try {
                ri.loadLabel(pm)?.toString()?.trim().orEmpty()
            } catch (_: Throwable) {
                ""
            }
            if (label.isEmpty()) continue
            out.add(Entry(label, pkg))
        }
        out.sortBy { it.label }

        entries = out
        built = true
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, out.joinToString("\n") { "${it.label}\t${it.pkg}" })
                .apply()
        } catch (_: Throwable) { /* 缓存失败不影响功能 */ }
        return out.size
    }

    /** 懒加载：优先内存，其次缓存，最后现扫 */
    private fun ensure(ctx: Context) {
        if (built && entries.isNotEmpty()) return
        synchronized(this) {
            if (built && entries.isNotEmpty()) return
            val cached = try {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            } catch (_: Throwable) {
                null
            }
            if (!cached.isNullOrEmpty()) {
                val parsed = cached.split('\n').mapNotNull { line ->
                    val t = line.indexOf('\t')
                    if (t <= 0) null else Entry(line.substring(0, t), line.substring(t + 1))
                }.filter { it.label.isNotEmpty() && it.pkg.isNotEmpty() }
                if (parsed.isNotEmpty()) {
                    entries = parsed
                    built = true
                    return
                }
            }
            refresh(ctx)
        }
    }

    fun all(ctx: Context): List<Entry> {
        ensure(ctx)
        return entries
    }

    /** 包名 → 标签，给执行回执用 */
    fun labelOf(ctx: Context, pkg: String): String {
        ensure(ctx)
        return entries.firstOrNull { it.pkg == pkg }?.label ?: pkg
    }

    /**
     * 从一句话里认出应用。
     *
     * 分级，越靠前越可信：
     *   1. 标签完全相等        「微信」      → 微信
     *   2. 别名命中            「B站」       → 哔哩哔哩
     *   3. 标签以查询开头      「抖音」      → 抖音 / 抖音极速版（取最短标签）
     *   4. 标签包含查询        「微信」      → 微信读书
     *   5. 查询包含标签        「打开哔哩哔哩看看」→ 哔哩哔哩
     *
     * 注意第 3 步要取"最短标签"：手机里同时有「微信」和「微信读书」时，
     * 说「微信」必须命中前者而不是后者。
     */
    fun find(ctx: Context, spoken: String, fuzzy: Boolean = false): Entry? {
        ensure(ctx)
        if (entries.isEmpty()) return null

        val q = norm(spoken)
        if (q.isEmpty()) return null
        val qc = core(q)
        if (qc.isEmpty()) return null
        if (qc.length > 14) return null                   // 太长的话基本是整句话，别乱认

        // 1) 标签完全相等
        entries.firstOrNull { norm(it.label) == q }?.let { return it }

        // 2) 别名 → 再按标签精确/前缀找
        ALIAS[q]?.let { target ->
            val tn = norm(target)
            entries.firstOrNull { norm(it.label) == tn }?.let { return it }
            entries.filter { norm(it.label).startsWith(tn) }
                .minByOrNull { it.label.length }?.let { return it }
        }

        // 3) 标签以查询开头，取最短
        entries.filter { core(norm(it.label)).startsWith(qc) }
            .minByOrNull { it.label.length }?.let { return it }

        // 4) 标签包含查询，取最短
        entries.filter { core(norm(it.label)).contains(qc) }
            .minByOrNull { it.label.length }?.let { return it }

        // 5) 查询包含标签（用户说了整句，标签藏在里面）
        entries.filter { core(norm(it.label)).length >= 2 && qc.contains(core(norm(it.label))) }
            .minByOrNull { it.label.length }?.let { return it }

        // 6) 模糊：字符重合度。
        //    「哔站」和「哔哩哔哩」硬匹配对不上，但"哔"是重合的。
        //    只在 fuzzy=true（明确的"打开某应用"句式）时启用 ——
        //    用户随口说「南京」时手机里装着「南京银行」，绝不能靠模糊去猜。
        if (fuzzy && qc.length <= 4) {
            entries.map { it to overlap(qc, it.label) }
                .filter { it.second >= 0.5 }
                .sortedWith(compareByDescending<Pair<Entry, Double>> { it.second }
                    .thenBy { it.first.label.length })
                .firstOrNull()?.let { return it.first }
        }

        return null
    }

    /** q 里有百分之多少的字符出现在 label 里 */
    private fun overlap(qc: String, label: String): Double {
        val ls = core(norm(label)).toSet()
        if (ls.isEmpty() || qc.isEmpty()) return 0.0
        val hit = qc.toSet().count { it in ls }
        return hit.toDouble() / qc.toSet().size
    }

    /** 拉起应用 */
    fun launch(ctx: Context, pkg: String): Boolean {
        return try {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
