package com.netmusiclite.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.netmusiclite.ui.theme.Accent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 播放页右缘进度弧（2026-09-06 由整圈环改为官方手表版样式，几何逐像素对齐截图）：
 * - 轨道 = 以 3 点钟为中心的 53° 弧（−26.5°..+26.5°，0°=3 点钟、正角向下），
 *   中线半径 0.914R（官方 218px 基础上按 2026-09-06 要求左移 5px）、线宽 6dp（官方 8px + 4px）、圆头
 * - 已播段（Accent）锚在弧的下端（+26.5°），随进度逆时针向上生长
 * - progress 用 provider 在绘制期读取：500ms 进度 tick 只重绘弧线，播放页零重组
 *
 * W5 流畅度（2026-09-24）：Canvas 条带化（同 CircularSideProgress 方案）——
 * 弧带只占右缘约 24dp 宽，进度 tick / 拖动 seek 的失效重录范围从全屏缩到条带；
 * seek 手势层独立全屏（判定区域 ±48° 超出弧带，不能跟着裁）。
 *
 * seek 手势（防误触，手动指针仲裁，不依赖 tap 跳进度）：
 * 1. 起始点必须落在弧带（半径 0.83R~1.10R 且角度 −48°..+48°）——同时避开三键区
 *    Prev/Next 按钮（按钮最远角 r≈189px < 0.83R=193px）
 * 2. ★ 手势跑在 Initial 通道（先于页面级 Main 通道手势）：弧带内纵向趋势的位移
 *    在 Initial 即消费，页面下滑进评论页的手感检测看到已消费自动放弃；
 *    横向趋势不消费——左滑歌词/右滑返回不受影响（方案与 rotaryList 滚轮通道同款，真机已验证）
 * 3. 累计位移过 slop 且仍纵向主导 → 接管 seek；拖拽中角度→进度映射，震动分级反馈
 */

/** 弧段半跨角（度）：总弧 53°，中心线在 3 点钟方向 */
private const val ARC_HALF_SPAN_DEG = 26.5f

/** 弧带拖拽判定角（比轨道略宽，±48°） */
private const val ARC_DRAG_ZONE_DEG = 48f

/** 中线半径系数：官方 218px 左移 5px = 213px / 233px 屏幕半径 ≈ 0.914 */
private const val ARC_RADIUS_FACTOR = 0.914f

/** 弧带判定内径系数：0.83R=193px，排除 Prev/Next 按钮（最远角 r≈189px） */
private const val ARC_BAND_INNER = 0.83f

@Composable
fun EdgeProgressRing(
    progress: () -> Float,
    onSeek: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
    accent: Color = Accent,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val r = minOf(screenW, screenH) / 2f * ARC_RADIUS_FACTOR
        val stroke = with(density) { 6.dp.toPx() }
        // 弧段（-26.5°..+26.5°）包围盒：宽 = r(1-cos26.5°)+2·stroke，高 = 2·r·sin26.5°+2·stroke
        val halfSweep = Math.toRadians(ARC_HALF_SPAN_DEG.toDouble())
        val bandWpx = r * (1f - cos(halfSweep).toFloat()) + stroke * 2f
        val bandHpx = 2f * r * sin(halfSweep).toFloat() + stroke * 2f

        // 弧带 Canvas：右缘条带，圆心 = 屏幕中心（圆心在条带左侧外部，drawArc 只画出弧段）
        Canvas(
            Modifier
                .align(Alignment.CenterEnd)
                .width(with(density) { bandWpx.toDp() })
                .height(with(density) { bandHpx.toDp() }),
        ) {
            val p = (if (dragging) dragProgress else progress()).coerceIn(0f, 1f)
            // 条带内圆心：条带右缘贴屏右缘、垂直居中
            val cX = bandWpx - screenW / 2f
            val cY = bandHpx / 2f
            val tl = Offset(cX - r, cY - r)
            // 轨道：−26.5°..+26.5° 全弧灰
            drawArc(
                // 轨道：跟随主题的弱色（深色主题→白 17%，浅色主题→黑 17%）
                com.netmusiclite.ui.theme.TextPrimary.copy(alpha = 0.17f),
                startAngle = -ARC_HALF_SPAN_DEG, sweepAngle = ARC_HALF_SPAN_DEG * 2f, useCenter = false,
                topLeft = tl, size = Size(r * 2, r * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            // 已播：锚在下端(+26.5°)向上生长；start = 26.5° − 53°·p
            drawArc(
                accent,
                startAngle = ARC_HALF_SPAN_DEG - ARC_HALF_SPAN_DEG * 2f * p,
                sweepAngle = ARC_HALF_SPAN_DEG * 2f * p, useCenter = false,
                topLeft = tl, size = Size(r * 2, r * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }

        // seek 手势层：全屏（判定区 ±48° 超出弧带宽度），坐标以全屏为基准（与旧全屏 Canvas 一致）
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(onSeek) {
                    if (onSeek == null) {
                        return@pointerInput
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val downPos = down.position
                        var engaged = false
                        var decided = false
                        var dragProgressLocal = progressAt(downPos, size)
                        val slop = viewConfiguration.touchSlop.toFloat()
                        val band = minOf(size.width, size.height) / 2f
                        val inBand = dist(downPos, size) >= band * ARC_BAND_INNER &&
                            dist(downPos, size) <= band * 1.10f &&
                            abs(angleDeg(downPos, size)) <= ARC_DRAG_ZONE_DEG
                        var lastSeekMs = 0L
                        var lastSeekP = -1f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                if (engaged) onSeek(dragProgressLocal)
                                dragging = false
                                break
                            }
                            if (engaged) {
                                val isUp = change.changedToUp()
                                if (isUp) {
                                    onSeek(dragProgressLocal)
                                    dragging = false
                                    break
                                }
                                change.consume()
                                val p = progressAt(change.position, size)
                                val now = System.currentTimeMillis()
                                if (abs(p - dragProgressLocal) > 0.006f) {
                                    dragProgressLocal = p
                                    dragProgress = p
                                }
                                // 拖动中实时 seek（节流 150ms/2%）：即使 UP 事件异常丢失，进度也已跟着手走
                                if (abs(p - lastSeekP) > 0.02f && now - lastSeekMs > 150) {
                                    lastSeekP = p
                                    lastSeekMs = now
                                    onSeek(p)
                                }
                            } else {
                                if (change.isConsumed) {
                                    dragging = false
                                    break
                                }
                                val dx = change.position.x - downPos.x
                                val dy = change.position.y - downPos.y
                                if (!decided && inBand) {
                                    // 纵向趋势的位移在 Initial 通道先消费掉，抢在页面级手势之前；
                                    // 横向趋势不消费——左滑歌词/右滑返回不受影响
                                    if (abs(dy) >= abs(dx)) change.consume()
                                    if (dx * dx + dy * dy >= slop * slop) {
                                        if (abs(dy) >= abs(dx)) {
                                            decided = true
                                            engaged = true
                                            dragging = true
                                            dragProgressLocal = progressAt(change.position, size)
                                            dragProgress = dragProgressLocal
                                        } else {
                                            decided = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        )
    }
}

private fun dist(p: Offset, s: androidx.compose.ui.unit.IntSize): Float {
    val dx = p.x - s.width / 2f
    val dy = p.y - s.height / 2f
    return sqrt(dx * dx + dy * dy)
}

/** 0°=3 点钟、正角向下（与 drawArc 角度口径一致） */
private fun angleDeg(p: Offset, s: androidx.compose.ui.unit.IntSize): Float =
    Math.toDegrees(atan2(p.y - s.height / 2f, p.x - s.width / 2f).toDouble()).toFloat()

/** 弧上角度 → 进度：下端(+26.5°)=0，上端(−26.5°)=1 */
private fun progressAt(p: Offset, s: androidx.compose.ui.unit.IntSize): Float =
    ((ARC_HALF_SPAN_DEG - angleDeg(p, s)) / (ARC_HALF_SPAN_DEG * 2f)).coerceIn(0f, 1f)
