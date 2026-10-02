package com.netmusiclite.data

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 底层背景图：用户从相册选一张图作为全局背景（高斯玻璃材质的底图）。
 * 图片复制进应用私有目录，重启仍在；ready 驱动全屏背景层与玻璃材质。
 */
object BackgroundStore {
    private lateinit var prefs: SharedPreferences

    var ready by mutableStateOf(false)
        private set

    /**
     * 背景图版本号：每次成功换图/清空 +1。
     * 2026-10-01 修「不重置直接重选背景图 → 背景颜色不变，只有玻璃卡片变色」：
     * 背景层用 Coil 加载同一个文件路径 `bg.jpg`，Coil 默认以「路径」作缓存 key，
     * 换图后路径不变 ⇒ 命中旧图；而玻璃卡片读的是 blurredImage 状态位图（已更新），
     * 于是只有卡片变色。version 既驱动背景层重组，也参与 Coil 的 cacheKey。
     */
    var version by mutableStateOf(0)
        private set

    /** 模糊底图(466x466，与物理屏 1:1)：卡片按自身屏幕位置取样 = 真毛玻璃（固定半径=最大强度） */
    var blurredImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set

    /** 卡片毛玻璃强度 0..1（1 = 当前最大；用取样透明度混合实现，免重算） */
    var cardBlur by mutableStateOf(1f)
        private set

    /**
     * 卡片材质（2026-10-01 用户要求可在设置里切换）：
     *  · `true`  = **玻璃卡片** —— 从模糊底图按屏幕位置取样（真毛玻璃），强度由 [cardBlur] 调；
     *  · `false` = **黑色半透明卡片** —— 纯黑 45%，不取样、不做糊，功耗更低、文字对比更强。
     *
     * 设为 false 时设置页会隐藏「卡片模糊强度」滑杆（对黑色卡片没有意义），
     * 由 `frostedGlass()` 统一按本开关分流，所以全站卡片（含播放器等）一起生效。
     */
    var cardGlass by mutableStateOf(true)
        private set

    /**
     * 卡片玻璃透明度 0..1（2026-10-02 新增）：仅浅色主题无背景图的「透底轻玻璃」使用。
     * 值越大越透 —— 轻玻璃填充 alpha = 1 - 本值；默认 0.28 = LightPalette 白 72% 的原观感。
     * 滑杆往右拖 = 更透（卡片更「轻」，底色透出更多）；往左 = 更实、文字对比更强。
     * 有背景图时的玻璃不走这个值（那边的形态控制是「卡片模糊强度」滑杆）。
     */
    var cardTransparency by mutableStateOf(0.28f)
        private set

    /** 背景模糊强度 0..1（0 = 清晰；>0 时用独立模糊底图替换清晰背景） */
    var bgBlur by mutableStateOf(0f)
        private set
    var blurredBgImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set
    var dim by mutableStateOf(0.5f)      // 背景压暗系数(0~0.85)，保证文字可读
        private set
    var blur by mutableStateOf(true)     // 高斯模糊开关(API31+ 生效)
        private set

    /** 播放页/歌词页保留取色（2026-09-13）：设置背景图后，这两页仍用歌曲主色底而非透出背景图 */
    var keepAmbient by mutableStateOf(true)
        private set

    /**
     * 背景图主色（2026-10-01 新增）。
     *
     * 用途：**Android 11 及以下的动态取色种子来源**。本机（OWW261 / Android 11）实测
     * 连 `wallpaper` 服务都不存在（`service list` 里没有、也没有 Monet 的 RRO overlay），
     * 系统级壁纸取色在这块表上物理不可用；而用户自己设置的这张背景图，就是这台设备上
     * 唯一可自定义的「壁纸」，取它的主色在语义与观感上都等价于「跟随壁纸取色」。
     *
     * 取色算法复用 `data/ColorExtract.kt` 的 `dominantColorOf()`，与封面环境色同一套实现。
     * `null` = 没有背景图或尚未算出（此时主题回落到用户选的预设种子色，不会空转）。
     */
    var seedColor by mutableStateOf<Color?>(null)
        private set

    val file: File get() = File(ctx0.filesDir, "bg.jpg")
    private lateinit var ctx0: Context

    fun init(ctx: Context) {
        ctx0 = ctx.applicationContext
        prefs = ctx0.getSharedPreferences("ncm_bg", Context.MODE_PRIVATE)
        dim = prefs.getFloat("bg_dim", 0.5f)
        cardBlur = prefs.getFloat("card_blur", 1f)
        cardGlass = prefs.getBoolean("card_glass", true)
        cardTransparency = prefs.getFloat("card_transparency", 0.28f)
        bgBlur = prefs.getFloat("bg_blur_v", 0f)
        keepAmbient = prefs.getBoolean("bg_keep_ambient", true)
        ready = prefs.getBoolean("bg_on", false) && file.exists() && file.length() > 0
        if (ready) {
            loadCachedBlurred()
            extractSeed()
        }
    }

    /** 启动即时毛玻璃：直接解码缓存底图（几十毫秒），缺失/过期才重新生成 */
    private fun loadCachedBlurred() {
        genExecutor.execute {
            val srcTime = file.lastModified()
            val cards = java.io.File(ctx0.cacheDir, "bg_blur_cards_v2.jpg")
            if (cards.exists() && cards.lastModified() >= srcTime) {
                android.graphics.BitmapFactory.decodeFile(cards.absolutePath)?.let {
                    val ib = it.asImageBitmap()
                    android.os.Handler(android.os.Looper.getMainLooper()).post { blurredImage = ib }
                }
            } else {
                regenerateBlurred()
            }
            if (bgBlur > 0.01f) {
                val bgf = java.io.File(ctx0.cacheDir, "bg_blur_bg.jpg")
                if (bgf.exists() && bgf.lastModified() >= srcTime) {
                    android.graphics.BitmapFactory.decodeFile(bgf.absolutePath)?.let {
                        val ib = it.asImageBitmap()
                        android.os.Handler(android.os.Looper.getMainLooper()).post { blurredBgImage = ib }
                    }
                } else {
                    regenerateBgBlurred(bgBlur)
                }
            }
        }
    }

    /** 从相册 Uri 复制为背景图（覆盖旧图），成功返回 true */
    fun setFrom(uri: Uri, resolver: ContentResolver): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return false
        file.length() > 0
    }.getOrDefault(false).also { ok ->
        if (ok) {
            prefs.edit().putBoolean("bg_on", true).apply()
            ready = true
            version++          // 通知背景层换 key 重载（否则命中 Coil 旧缓存）
            regenerateBlurred()
            extractSeed()      // 换图后同步刷新主色（Android 11 动态取色的种子来源）
            if (bgBlur > 0.01f) regenerateBgBlurred(bgBlur)
        }
    }

    fun updateDim(v: Float) {
        dim = v.coerceIn(0f, 0.85f)
        prefs.edit().putFloat("bg_dim", dim).apply()
    }

    fun updateCardBlur(v: Float) {
        cardBlur = v.coerceIn(0f, 1f)
        prefs.edit().putFloat("card_blur", cardBlur).apply()
    }

    /** 切换卡片材质：玻璃 ⇄ 黑色半透明（2026-10-01） */
    fun updateCardGlass(v: Boolean) {
        cardGlass = v
        prefs.edit().putBoolean("card_glass", v).apply()
    }

    /** 浅色轻玻璃透明度滑杆（2026-10-02）：值越大越透，填充 alpha = 1 - 本值 */
    fun updateCardTransparency(v: Float) {
        cardTransparency = v.coerceIn(0f, 0.85f)
        prefs.edit().putFloat("card_transparency", cardTransparency).apply()
    }

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 背景模糊滑杆：去抖 160ms 后重生成对应半径的模糊底图 */
    fun updateBgBlur(v: Float) {
        bgBlur = v.coerceIn(0f, 1f)
        prefs.edit().putFloat("bg_blur_v", bgBlur).apply()
        if (bgBlur <= 0.01f) { blurredBgImage = null; return }
        val strength = bgBlur
        uiHandler.postDelayed({ regenerateBgBlurred(strength) }, 160)
    }

    fun updateBlur(v: Boolean) {
        blur = v
        prefs.edit().putBoolean("bg_blur", v).apply()
    }

    fun updateKeepAmbient(v: Boolean) {
        keepAmbient = v
        prefs.edit().putBoolean("bg_keep_ambient", v).apply()
    }

    /** 恢复默认纯色背景 */
    fun clear() {
        file.delete()
        prefs.edit().putBoolean("bg_on", false).apply()
        ready = false
        version++
        blurredImage = null
        blurredBgImage = null
        seedColor = null      // 无图即无种子，主题回落预设种子色
    }

    private val genExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 提取背景图主色（2026-10-01）：后台线程，降采样解码后走主桶均值统计。
     * 466 屏上用 `inSampleSize = 8` —— 一张 4000px 的图只需解到 500px 级别，
     * 4096 个量化桶的统计结果完全够稳，且不会在选图/冷启动瞬间造成解码卡顿。
     */
    private fun extractSeed() {
        if (!ready) {
            seedColor = null
            return
        }
        val f = file
        genExecutor.execute {
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                    ?: return@execute
                val c = dominantColorOf(bmp, Color(0xFFEC4141))   // fallback 只在空图时触发
                bmp.recycle()
                uiHandler.post { seedColor = c }
            }
        }
    }

    /** 后台生成卡片取样底图：固定半径 26 三轮盒式模糊（= 最大强度，对齐 iOS 控制中心玻璃观感） */
    fun regenerateBlurred() = generateInto(radius = 26, passes = 3, cacheName = "bg_blur_cards_v2.jpg") { ib ->
        blurredImage = ib
    }

    /** 背景模糊底图：二次曲线 2..14 —— 滑杆前半段几乎无感，轻滑不再剧变 */
    fun regenerateBgBlurred(strength: Float) {
        val radius = (2 + strength * strength * 12).toInt().coerceIn(2, 14)
        generateInto(radius = radius, cacheName = "bg_blur_bg.jpg") { ib -> blurredBgImage = ib }
    }

    private fun generateInto(
        radius: Int,
        cacheName: String,
        passes: Int = 2,
        assign: (androidx.compose.ui.graphics.ImageBitmap) -> Unit,
    ) {
        val f = file
        genExecutor.execute {
            runCatching {
                val src = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return@execute
                val side = minOf(src.width, src.height)
                val square = android.graphics.Bitmap.createBitmap(
                    src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                val scaled = android.graphics.Bitmap.createScaledBitmap(square, 466, 466, true)
                if (scaled != square) square.recycle()
                val w = scaled.width; val h = scaled.height
                val px = IntArray(w * h)
                scaled.getPixels(px, 0, w, 0, 0, w, h)
                boxBlur(px, w, h, radius = radius, passes = passes)
                val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                out.setPixels(px, 0, w, 0, 0, w, h)
                java.io.FileOutputStream(java.io.File(ctx0.cacheDir, cacheName)).use {
                    out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
                }
                val ib = out.asImageBitmap()
                android.os.Handler(android.os.Looper.getMainLooper()).post { assign(ib) }
                src.recycle()
            }
        }
    }

    /** 分离式盒式模糊（滑窗累加，纯 Kotlin，API 无关） */
    private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int) {
        val tmp = IntArray(px.size)
        repeat(passes) {
            blurDir(px, tmp, w, h, radius, true)
            blurDir(tmp, px, w, h, radius, false)
        }
    }

    private fun blurDir(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val other = if (horizontal) h else w
        val win = r * 2 + 1
        for (o in 0 until other) {
            var aS = 0; var rS = 0; var gS = 0; var bS = 0
            for (i in -r..r) {
                val c = i.coerceIn(0, len - 1)
                val p = if (horizontal) src[o * w + c] else src[c * w + o]
                aS += p ushr 24; rS += (p shr 16) and 0xFF; gS += (p shr 8) and 0xFF; bS += p and 0xFF
            }
            for (x in 0 until len) {
                dst[if (horizontal) o * w + x else x * w + o] =
                    ((aS / win) shl 24) or ((rS / win) shl 16) or ((gS / win) shl 8) or (bS / win)
                val outP = if (horizontal) src[o * w + (x - r).coerceIn(0, len - 1)]
                           else src[(x - r).coerceIn(0, len - 1) * w + o]
                val inP = if (horizontal) src[o * w + (x + r + 1).coerceIn(0, len - 1)]
                          else src[(x + r + 1).coerceIn(0, len - 1) * w + o]
                aS += (inP ushr 24) - (outP ushr 24)
                rS += ((inP shr 16) and 0xFF) - ((outP shr 16) and 0xFF)
                gS += ((inP shr 8) and 0xFF) - ((outP shr 8) and 0xFF)
                bS += (inP and 0xFF) - (outP and 0xFF)
            }
        }
    }
}
