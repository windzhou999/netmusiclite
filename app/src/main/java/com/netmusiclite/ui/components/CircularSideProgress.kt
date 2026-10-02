package com.netmusiclite.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.netmusiclite.ui.theme.Accent
import kotlin.math.cos
import kotlin.math.sin

/**
 * 右缘弯曲进度条：贴着圆表右弧的 40° 弧段。
 * progress 0..1；track 为 8% 白，进度为主题色。
 *
 * 流畅度关键（2026-09-04 trace 实测）：Canvas 只占右缘条带（约 16dp 宽），
 * 不再 fillMaxSize——progress 每帧变化时的失效/重录范围从全屏缩到条带，
 * 消除「Record View#draw() 全窗重录」（实测 267ms 尖帧的元凶）。
 * progress 用 provider 在绘制期读取：滚动/播放进度变化只重绘弧线，不触发任何重组。
 */
@Composable
fun CircularSideProgress(progress: () -> Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val screenW = with(density) { maxWidth.toPx() }
        val r = minOf(screenW, with(density) { maxHeight.toPx() }) / 2f * 0.94f
        val stroke = with(density) { 3.dp.toPx() }
        // 弧段（-14°..+14°，纵向长度为原 40° 弧的 70%）包围盒：宽 = r(1-cos14°)+2·stroke，高 = 2·r·sin14°+2·stroke
        val halfSweep = Math.toRadians(14.0)
        val bandWpx = r * (1f - cos(halfSweep).toFloat()) + stroke * 2f
        val bandHpx = 2f * r * sin(halfSweep).toFloat() + stroke * 2f
        val bandW = with(density) { bandWpx.toDp() }
        val bandH = with(density) { bandHpx.toDp() }

        Box(modifier.fillMaxSize()) {
            Canvas(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(bandW)
                    .height(bandH),
            ) {
                val progress = progress().coerceIn(0f, 1f)
                // 条带右对齐屏幕右缘、垂直居中；圆心 = 屏幕中心
                // 条带左缘屏幕 x = screenW - bandWpx → 圆心本地 x = screenW/2 - (screenW - bandWpx)
                val cX = screenW / 2f - (screenW - bandWpx)
                val cY = bandHpx / 2f
                val topLeft = Offset(cX - r, cY - r)

                // track（圆头：两端 StrokeCap.Round 半圆收尾，上下等宽）
                drawArc(
                    // 轨道：跟随主题的弱色（深色主题→白 10%，浅色主题→黑 10%），
                    // 否则浅色页面上轨道完全消失
                    color = com.netmusiclite.ui.theme.TextPrimary.copy(alpha = 0.10f),
                    startAngle = -14f, sweepAngle = 28f, useCenter = false,
                    topLeft = topLeft, size = Size(r * 2, r * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                // progress
                if (progress > 0.01f) {
                    drawArc(
                        color = Accent,
                        startAngle = -14f, sweepAngle = 28f * progress, useCenter = false,
                        topLeft = topLeft, size = Size(r * 2, r * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}
