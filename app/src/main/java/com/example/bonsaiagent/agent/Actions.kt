package com.example.bonsaiagent.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.provider.MediaStore
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 内置动作。
 *
 * 为什么不用 shell
 * ----------------
 * 普通 Android App 通过 Runtime.exec 跑的是自己 uid 的 sh，
 * `am` / `pm` 这类命令属于 shell 用户权限，App 调不动（会 Permission denied）。
 * 所以「打开相册」「打开设置」必须走 Intent，而不是命令行。
 *
 * 这里提供一组固定动作，模型只负责挑名字，不负责编命令 ——
 * 既可靠（不存在"命令不存在"），也安全（动作白名单，没有破坏性操作）。
 */
object Actions {

    /** 给 system prompt 用的动作清单 */
    val CATALOG = """
        time     → 当前日期时间
        battery  → 电量与充电状态
        gallery  → 打开相册
        camera   → 打开相机
        settings → 打开系统设置
        dialer   → 打开拨号盘
        browser  → 打开浏览器（可带网址）
        wifi     → 打开 WiFi 设置
        apps     → 打开应用列表（系统设置-应用）
    """.trimIndent()

    /**
     * @return 给用户看的执行结果
     */
    fun run(ctx: Context, name: String, arg: String?): String {
        return try {
            when (name) {
                "time", "date", "time_now" -> {
                    SimpleDateFormat("yyyy年M月d日 EEEE HH:mm:ss", Locale.CHINA).format(Date())
                }

                "battery" -> battery(ctx)

                "gallery", "photos", "album" -> {
                    val i = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(i)
                    "已为你打开相册。"
                }

                "camera" -> {
                    val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(i)
                    "已为你打开相机。"
                }

                "settings" -> {
                    ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "已打开系统设置。"
                }

                "apps" -> {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "已打开应用管理。"
                }

                "wifi" -> {
                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "已打开 WiFi 设置。"
                }

                "dialer" -> {
                    val num = arg?.takeIf { it.isNotBlank() && it.none { c -> c.isLetter() } }
                    val uri = if (num != null) Uri.parse("tel:$num") else Uri.parse("tel:")
                    ctx.startActivity(
                        Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    "已打开拨号盘" + if (num != null) "（$num）" else ""
                }

                "browser", "open_url" -> {
                    val url = arg?.takeIf { it.isNotBlank() } ?: "https://www.baidu.com"
                    val full = if (url.startsWith("http")) url else "https://$url"
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(full)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    "已用浏览器打开 $full"
                }

                else -> "[未知动作] $name\n可用：" + CATALOG.replace("\n", " / ")
            }
        } catch (e: Throwable) {
            "[动作执行失败] $name：${e.javaClass.simpleName} ${e.message}"
        }
    }

    private fun battery(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "电量 $level%" + if (charging) "，正在充电" else "，未充电"
    }
}
