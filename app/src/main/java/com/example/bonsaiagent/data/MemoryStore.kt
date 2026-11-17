package com.example.bonsaiagent.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条关于用户的长期记忆。
 *
 * v10.87：小模型上下文只有 1K~4K，聊几轮就忘。把「关于用户本人的稳定事实」
 * 单独存起来，每轮只挑最相关的几条塞进 prompt —— 相当于用 100 token 记住几十轮。
 */
data class MemoryFact(
    val id: String,
    val key: String,          // 身份 / 偏好 / 项目 / 设备 / 习惯
    val content: String,
    val source: String,       // 从哪句话抽出来的，可回溯
    val createdAt: Long,
    val pinned: Boolean = false
)

/**
 * 记忆存储。用 SharedPreferences 存 JSON 字符串，避免引入数据库依赖。
 *
 * 设计原则：宁可漏记，不可错记 —— 所有写入都要经过用户确认。
 */
object MemoryStore {

    private const val SP = "bonsai_memory"
    private const val K_FACTS = "facts"
    private const val K_IGNORED = "ignored"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<MemoryFact> = try {
        val s = sp(ctx).getString(K_FACTS, "[]") ?: "[]"
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val content = o.optString("content")
            if (content.isBlank()) return@mapNotNull null
            MemoryFact(
                id = o.optString("id"),
                key = o.optString("key"),
                content = content,
                source = o.optString("source"),
                createdAt = o.optLong("createdAt"),
                pinned = o.optBoolean("pinned")
            )
        }
    } catch (t: Throwable) {
        emptyList()
    }

    fun save(ctx: Context, list: List<MemoryFact>) {
        try {
            val arr = JSONArray()
            list.forEach { f ->
                arr.put(JSONObject().apply {
                    put("id", f.id)
                    put("key", f.key)
                    put("content", f.content)
                    put("source", f.source)
                    put("createdAt", f.createdAt)
                    put("pinned", f.pinned)
                })
            }
            sp(ctx).edit().putString(K_FACTS, arr.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    fun ignored(ctx: Context): Set<String> = try {
        sp(ctx).getStringSet(K_IGNORED, emptySet()) ?: emptySet()
    } catch (t: Throwable) {
        emptySet()
    }

    fun addIgnored(ctx: Context, text: String) {
        try {
            val s = ignored(ctx).toMutableSet()
            val t = text.trim()
            if (t.isNotEmpty()) s.add(t)
            sp(ctx).edit().putStringSet(K_IGNORED, s).apply()
        } catch (_: Throwable) {
        }
    }

    /** 极简去重：完全相同、互相包含、或前 6 字相同，都算重复 */
    fun isDuplicate(list: List<MemoryFact>, content: String): Boolean {
        val c = content.trim()
        if (c.isEmpty()) return true
        return list.any { f ->
            val e = f.content.trim()
            e == c || e.contains(c) || c.contains(e) ||
                    (c.length >= 6 && e.length >= 6 && c.take(6) == e.take(6))
        }
    }

    /** 忽略过的事实，相似内容不再打扰 */
    fun isIgnored(ctx: Context, content: String): Boolean {
        val c = content.trim()
        if (c.isEmpty()) return true
        return ignored(ctx).any { it.isNotEmpty() && (it == c || c.contains(it) || it.contains(c)) }
    }
}
