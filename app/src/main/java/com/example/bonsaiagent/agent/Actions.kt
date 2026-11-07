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
    /**
     * v7.6：动作名归一化。
     * 实测模型会输出 [ACTION:相册] 这种中文名字（提示词里写的是英文 gallery，
     * 但它按语义翻译了）。所以这里中英都认，避免掉进"未知动作"。
     */
    private fun normalize(name: String): String {
        val n = name.trim().lowercase()
        return when {
            n in listOf("相册", "图库", "照片", "相片", "相簿", "album", "photos", "picture") -> "gallery"
            n in listOf("相机", "照相机", "拍照", "摄像") -> "camera"
            n in listOf("设置", "系统设置", "设定") -> "settings"
            n in listOf("应用", "应用列表", "应用管理", "程序") -> "apps"
            n in listOf("无线网", "无线局域网", "无线") -> "wifi"
            n in listOf("拨号", "拨号盘", "电话", "打电话") -> "dialer"
            n in listOf("浏览器", "网页", "上网") -> "browser"
            n in listOf("时间", "日期", "现在几点") -> "time"
            n in listOf("电量", "电池", "剩余电量") -> "battery"
            else -> n
        }
    }

    fun run(ctx: Context, name: String, arg: String?): String {
        val action = normalize(name)
        return try {
            when (action) {
                "time", "date", "time_now" -> {
                    SimpleDateFormat("yyyy年M月d日 EEEE HH:mm:ss", Locale.CHINA).format(Date())
                }

                "battery" -> battery(ctx)

                "gallery", "photos", "album" -> openGallery(ctx)

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

                // v10.0：打开任意已装应用。arg 可以是包名，也可以是用户口中的名字
                // （"微信"/"B站"），统一交给 AppIndex 去认。
                "openapp", "launch", "start" -> {
                    val who = arg?.trim().orEmpty()
                    if (who.isEmpty()) return "[打开失败] 没说要开哪个应用"

                    // 先当包名试（含点号且不像中文）
                    val direct = if (who.contains('.') && who.none { it.code > 127 }) {
                        if (AppIndex.launch(ctx, who)) AppIndex.labelOf(ctx, who) else null
                    } else null

                    if (direct != null) {
                        "已为你打开 $direct。"
                    } else {
                        val hit = AppIndex.find(ctx, who)
                        if (hit != null && AppIndex.launch(ctx, hit.pkg)) {
                            "已为你打开 ${hit.label}。"
                        } else {
                            val sample = AppIndex.all(ctx).take(12).joinToString("、") { it.label }
                            "[找不到应用] $who" +
                                if (sample.isEmpty()) "（应用清单是空的，去设置页点一下「刷新应用列表」）"
                                else "\n手机里装着的有：$sample …"
                        }
                    }
                }

                else -> "[未知动作] $name\n可用：" + CATALOG.replace("\n", " / ")
            }
        } catch (e: Throwable) {
            "[动作执行失败] $name：${e.javaClass.simpleName} ${e.message}"
        }
    }

    /**
     * 打开相册。
     *
     * 注意：不能用 Intent(ACTION_VIEW).setType("image/…") —— 那是"打开某张图片"，
     * 没给具体文件时系统会弹出文件选择器（实测表现就是跑到文件管理器里翻文件夹，
     * 最后报"打开目标文件失败"）。
     *
     * 正确入口是 CATEGORY_APP_GALLERY（API 15+ 的系统标准方式），
     * 找不到再按常见厂商包名兜底。
     */
    private fun openGallery(ctx: Context): String {
        // 1) 系统标准入口
        try {
            val i = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_GALLERY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (i.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(i)
                return "已为你打开相册。"
            }
        } catch (_: Throwable) { /* 继续兜底 */ }

        // 2) 常见厂商相册包名
        val candidates = listOf(
            "com.vivo.gallery", "com.android.gallery3d", "com.miui.gallery",
            "com.coloros.gallery3d", "com.oppo.gallery3d", "com.huawei.photos",
            "com.google.android.apps.photos", "com.samsung.android.gallery3d"
        )
        for (pkg in candidates) {
            try {
                val li = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: continue
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(li)
                return "已为你打开相册。"
            } catch (_: Throwable) { /* 试下一个 */ }
        }

        // 3) 兜底：图库内容 provider
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("content://media/internal/images/media"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "已尝试打开相册。"
        } catch (e: Throwable) {
            "[打开相册失败] 这台设备上没找到可用的相册入口：${e.message}"
        }
    }

    private fun battery(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "电量 $level%" + if (charging) "，正在充电" else "，未充电"
    }
}
