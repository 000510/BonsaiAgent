package com.example.bonsaiagent.agent

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
 * 策略
 * ----
 * 只拦**高置信度、无歧义**的短指令（打开相册/相机/设置、问时间、问电量），
 * 命中就 0 秒完成；只要有一点模糊（比如"帮我看看相册里最近的截图里有没有猫"），
 * 就返回 null 交给模型处理 —— 保证能力不打折，只把最常见的路走快。
 */
object FastRoute {

    sealed class Hit {
        data class Act(val action: String) : Hit()
        data class Answer(val text: String) : Hit()
    }

    private val OPEN_MAP = mapOf(
        "相册" to "gallery", "相片" to "gallery", "图库" to "gallery",
        "照片" to "gallery", "相簿" to "gallery",
        "相机" to "camera", "照相机" to "camera", "拍照" to "camera",
        "设置" to "settings", "系统设置" to "settings",
        "应用列表" to "apps", "应用管理" to "apps",
        "wifi" to "wifi", "无线网" to "wifi", "无线局域网" to "wifi",
        "拨号" to "dialer", "拨号盘" to "dialer"
    )

    /** 去掉客套前缀，便于后面的模式匹配 */
    private fun clean(s: String): String = s
        .trim()
        .replace(" ", "")
        .removePrefix("帮我").removePrefix("请").removePrefix("麻烦")
        .removePrefix("能不能").removePrefix("可以").removePrefix("你")
        .removeSuffix("吧").removeSuffix("。").removeSuffix("！").removeSuffix("!")

    fun match(raw: String): Hit? {
        val t = clean(raw)
        if (t.isEmpty() || t.length > 12) return null   // 太长的句子很可能带附加意图，交给模型

        // 1) 打开某物：正装「打开相册」和倒装「相册开一下」都要认
        Regex("^(打开|启动|进入|开|调出|唤出)(.{1,6})$").find(t)?.let { m ->
            OPEN_MAP[m.groupValues[2].trim()]?.let { return Hit.Act(it) }
        }
        Regex("^(.{1,6}?)(开一下|打开一下|开开|打开|启动|进入|开)$").find(t)?.let { m ->
            OPEN_MAP[m.groupValues[1].trim()]?.let { return Hit.Act(it) }
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

        return null
    }
}
