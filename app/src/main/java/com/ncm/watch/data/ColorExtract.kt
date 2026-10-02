package com.ncm.watch.data

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color

/**
 * 图片主色提取（纯 Kotlin，无 Palette 依赖），永不返回 null：
 * RGB 4bit 量化桶统计，剔除近黑/近白/低饱和块 → 饱和主桶均值；
 * 无饱和主桶 → 有信息像素均值；全灰图 → 整图均值。
 *
 * 2026-10-01 从 `ui/components/NcmBackground.kt` 的 private `dominantOf()` 抽到 data 层。
 * 抽出的原因：现在有**两处**需要这套算法 ——
 *  1. `SongAmbient`（封面主色 → 播放页环境色）
 *  2. `BackgroundStore.seedColor`（背景图主色 → Android 11 的动态取色种子）
 * 留两份实现必然漂移，所以合并到一处。
 *
 * ⚠ 原实现里 `bmp` 为空时直接返回 ui 层的 `Bg`，抽到 data 层后不能再反向依赖 ui 包，
 *   改为由调用方传入 [fallback]。
 */
internal fun dominantColorOf(bmp: Bitmap, fallback: Color): Color {
    val w = bmp.width
    val h = bmp.height
    val n = w * h
    if (n == 0) return fallback
    val px = IntArray(n)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val count = IntArray(4096)
    val sumR = IntArray(4096)
    val sumG = IntArray(4096)
    val sumB = IntArray(4096)
    var used = 0L
    var allR = 0L
    var allG = 0L
    var allB = 0L
    for (p in px) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        allR += r
        allG += g
        allB += b
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val sat = if (max == 0) 0 else (max - min) * 255 / max
        if (max < 36 || sat < 26 || (max > 232 && sat < 40)) continue
        val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        count[key]++
        sumR[key] += r
        sumG[key] += g
        sumB[key] += b
        used++
    }
    var best = -1
    for (i in 0 until 4096) if (count[i] > (if (best < 0) 0 else count[best])) best = i
    if (best >= 0 && used > 0 && count[best] * 8 >= used) {
        return Color(
            red = sumR[best] / count[best],
            green = sumG[best] / count[best],
            blue = sumB[best] / count[best],
        )
    }
    if (used > 0) {
        return Color(
            red = (allR / used).toInt(),
            green = (allG / used).toInt(),
            blue = (allB / used).toInt(),
        )
    }
    return Color(
        red = (allR / n).toInt(),
        green = (allG / n).toInt(),
        blue = (allB / n).toInt(),
    )
}
