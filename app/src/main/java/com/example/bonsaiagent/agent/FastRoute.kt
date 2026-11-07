package com.example.bonsaiagent.agent

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快路径：高频指令不经过模型，直接命中。
 *
 * 为什么需要
 * ----------
 * 实测一轮 Agent 交互 36 秒，其中 prefill 占 33 秒 —— 让 8B 模型读完 270 多个
 * token 的提示词，只为了决定"打开相册"该走哪条路。这个代价完全不合理：
 * 这些指令的意图是**确定**的，根本不需要"理解"，查表就够。
 *
 * v10.0 的关键转变
 * --------------
 * 以前只认 9 个硬编码动作，用户说「打开微信」就抓瞎 —— 因为「微信」不在表里。
 * 现在先查系统动作表，查不到就去 [AppIndex] 里按手机上真实装的应用找。
 * 手机装了什么就能开什么，不用维护映射表，也不用让模型记协议名。
 *
 * 策略
 * ----
 * 只拦**高置信度、无歧义**的短指令（打开相册/相机/设置/任意 App、问时间、问电量），
 * 命中就 0 秒完成；只要有一点模糊（比如"帮我看看相册里最近的截图里有没有猫"），
 * 就返回 null 交给模型处理 —— 保证能力不打折，只把最常见的路走快。
 */
object FastRoute {

    sealed class Hit {
        /** arg 用于给动作传参（打开应用时传包名/应用名） */
        data class Act(val action: String, val arg: String? = null) : Hit()
        data class Answer(val text: String) : Hit()
    }

    private val OPEN_MAP = mapOf(
        "相册" to "gallery", "相片" to "gallery", "图库" to "gallery",
        "照片" to "gallery", "相簿" to "gallery",
        "相机" to "camera", "照相机" to "camera", "拍照" to "camera",
        "设置" to "settings", "系统设置" to "settings",
        "应用列表" to "apps", "应用管理" to "apps",
        "wifi" to "wifi", "无线网" to "wifi", "无线局域网" to "wifi",
        "拨号" to "dialer", "拨号盘" to "dialer",
        "浏览器" to "browser", "百度" to "browser"
    )

    /** 去掉客套前缀，便于后面的模式匹配 */
    private fun clean(s: String): String {
        var t = s.trim().replace(" ", "").replace("　", "")
        // 反复剥离，因为「麻烦你帮我把微信打开」是多层前缀
        val prefixes = listOf(
            "麻烦你", "麻烦", "能不能", "可不可以", "可以", "能不能帮我", "帮我", "帮忙",
            "请你", "请", "替我", "给我", "你", "我想", "我要", "我想要", "麻烦帮我",
            "把", "将"
        )
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixes) {
                if (t.startsWith(p) && t.length > p.length) {
                    t = t.substring(p.length)
                    changed = true
                }
            }
        }
        return t.removeSuffix("吧").removeSuffix("。").removeSuffix("！").removeSuffix("!")
            .removeSuffix("一下").removeSuffix("可以吗").removeSuffix("好吗")
    }

    /** 从"打开X"里认出目标：系统动作优先，其次手机真实安装的应用 */
    private fun resolveOpenTarget(ctx: Context, target: String): Hit.Act? {
        val t = target.trim()
        if (t.isEmpty()) return null
        OPEN_MAP[t]?.let { return Hit.Act(it) }
        AppIndex.find(ctx, t)?.let { return Hit.Act("openapp", it.label) }
        return null
    }

    fun match(ctx: Context, raw: String): Hit? {
        val t = clean(raw)
        if (t.isEmpty() || t.length > 16) return null   // 太长的句子很可能带附加意图，交给模型

        // 1) 打开某物：正装「打开相册」和倒装「相册开一下」「把微信打开」都要认
        Regex("^(打开|启动|进入|开|调出|唤出)(.{1,10})$").find(t)?.let { m ->
            resolveOpenTarget(ctx, m.groupValues[2])?.let { return it }
        }
        Regex("^(.{1,10}?)(开一下|打开一下|开开|打开|启动|进入|开)$").find(t)?.let { m ->
            resolveOpenTarget(ctx, m.groupValues[1])?.let { return it }
        }

        // 2) 纯问时间
        if (Regex("^(现在)?(几点了?|几点|什么时间|当前时间|现在时间|今天几号|今天几号了|今天星期几|日期)$")
                .matches(t)
        ) {
            val now = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date())
            return Hit.Answer("现在是 $now")
        }

        // 3) 纯问电量
        if (Regex("^(手机)?(还有|剩|剩余)?(多少|几)?电(量)?(了|还剩多少|还有多少)?$").matches(t) ||
            t == "电量" || t == "电池"
        ) {
            return Hit.Act("battery")
        }

        // 4) 光说一个应用名：「微信」「B站」
        if (t.length <= 8) {
            AppIndex.find(ctx, t)?.let { return Hit.Act("openapp", it.label) }
        }

        return null
    }
}
