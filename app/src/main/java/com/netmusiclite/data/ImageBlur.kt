package com.netmusiclite.data

import android.graphics.Bitmap

/**
 * 位图高斯模糊工具（纯 Kotlin 分离式盒式模糊，滑窗累加，API 无关）。
 * 与 BackgroundStore 内同源算法保持一致：
 * 设备 API 30（无 RenderEffect / Modifier.blur，需 API 31+），封面查看页的边缘模糊
 * 只能走「预模糊位图」路线——一律先缩到小图再模糊，再放大铺满（性能与观感兼顾）。
 */
object ImageBlur {

    /** 缩放到指定边长（等比，长边 = side），返回新图；原图小于 side 时原样返回 */
    fun scaleToSide(src: Bitmap, side: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= side) return src
        val scale = side.toFloat() / maxSide
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** 三轮盒式模糊（近似高斯）；[passes] 每轮双向滑窗，radius 越大越糊 */
    fun blur(src: Bitmap, radius: Int, passes: Int = 3): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 1 || h <= 1) return src
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(px.size)
        repeat(passes.coerceAtLeast(1)) {
            blurDir(px, tmp, w, h, radius, true)
            blurDir(tmp, px, w, h, radius, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 便捷：缩放 + 模糊一步到位（供封面查看页背景层使用） */
    fun blurScaled(src: Bitmap, side: Int = 220, radius: Int = 7, passes: Int = 2): Bitmap =
        blur(scaleToSide(src, side), radius, passes)

    /**
     * 封面主色（供封面查看页做光晕）：缩到 48px 统计「饱和且不暗」的像素均值，
     * 再按亮度提亮到适合发光的值（深色封面也能透出可见的光晕）。
     * 无饱和像素（纯灰图）时回退白色。
     */
    fun dominantColor(src: Bitmap): androidx.compose.ui.graphics.Color {
        val bmp = scaleToSide(src, 48)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (p in px) {
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            val mx = maxOf(pr, pg, pb); val mn = minOf(pr, pg, pb)
            val sat = if (mx == 0) 0 else (mx - mn) * 255 / mx
            if (mx < 40 || sat < 30) continue // 跳过暗部与灰部，避免光晕发浊
            r += pr; g += pg; b += pb; n++
        }
        if (n == 0) return androidx.compose.ui.graphics.Color.White
        val rr = (r / n).toInt(); val gg = (g / n).toInt(); val bb = (b / n).toInt()
        val peak = maxOf(rr, gg, bb).coerceAtLeast(1)
        val boost = (200f / peak).coerceIn(1f, 2.4f)
        return androidx.compose.ui.graphics.Color(
            (rr * boost).toInt().coerceIn(0, 255),
            (gg * boost).toInt().coerceIn(0, 255),
            (bb * boost).toInt().coerceIn(0, 255),
        )
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
