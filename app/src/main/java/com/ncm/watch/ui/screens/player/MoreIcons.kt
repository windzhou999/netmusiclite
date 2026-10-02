package com.ncm.watch.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** More 二级菜单专用线性图标，避免改动其他页面共用的 NcmIcons。 */
internal object MoreIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private fun ImageVector.Builder.line(width: Float = 1.8f, block: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.White),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        fill = null,
    ) { block() }

    private fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) = path(
        fill = SolidColor(Color.White),
        stroke = null,
    ) { block() }

    val Loop = icon("MoreLoop") {
        // 重绘 v2（2026-09-25）：双箭头循环圆环（上下半弧 + 两枚直 chevron），与旧版双横线剪影完全区分
        line { moveTo(5.5f, 12f); curveTo(5.5f, 8.41f, 8.41f, 5.5f, 12f, 5.5f); curveTo(15.59f, 5.5f, 18.5f, 8.41f, 18.5f, 12f) }
        line { moveTo(16.3f, 12.7f); lineTo(18.5f, 14.8f); lineTo(20.7f, 12.7f) }
        line { moveTo(18.5f, 12f); curveTo(18.5f, 15.59f, 15.59f, 18.5f, 12f, 18.5f); curveTo(8.41f, 18.5f, 5.5f, 15.59f, 5.5f, 12f) }
        line { moveTo(7.7f, 11.3f); lineTo(5.5f, 9.2f); lineTo(3.3f, 11.3f) }
    }

    val Shuffle = icon("MoreShuffle") {
        // 重绘 v2（2026-09-25）：X 交叉双直箭头 + 圆头 chevron，与旧版缓波纹剪影完全区分
        line { moveTo(3.8f, 6.6f); lineTo(6.2f, 6.6f); lineTo(17.7f, 17.3f) }
        line { moveTo(17.5f, 15.2f); lineTo(19.9f, 17.3f); lineTo(17.5f, 19.4f) }
        line { moveTo(3.8f, 17.4f); lineTo(6.2f, 17.4f); lineTo(17.7f, 6.7f) }
        line { moveTo(17.5f, 4.6f); lineTo(19.9f, 6.7f); lineTo(17.5f, 8.8f) }
    }

    val Single = icon("MoreSingleLoop") {
        // 重绘 v2（2026-09-25）：完整圆环顶部缺口 + 直 chevron 箭头 + 加粗居中「1」
        line {
            moveTo(9.7f, 5.6f); curveTo(5.6f, 7.0f, 4.6f, 9.5f, 4.6f, 12f)
            curveTo(4.6f, 15.8f, 7.9f, 18.9f, 12f, 18.9f)
            curveTo(16.1f, 18.9f, 19.4f, 15.8f, 19.4f, 12f)
            curveTo(19.4f, 9.5f, 18.4f, 7.0f, 14.3f, 5.6f)
        }
        line { moveTo(14.6f, 3.4f); lineTo(17.1f, 5.7f); lineTo(14.6f, 8.0f) }
        line(1.9f) { moveTo(12f, 9.0f); lineTo(12f, 15.0f) }
        line(1.9f) { moveTo(12f, 9.0f); quadTo(10.9f, 9.6f, 9.9f, 9.8f) }
        line(1.9f) { moveTo(10.5f, 15.0f); lineTo(13.5f, 15.0f) }
    }

    val Heart = icon("MoreHeartMode") {
        // 重绘 v2（2026-09-25）：心形轮廓 + 全宽心电脉冲，与旧版内嵌笑弧剪影完全区分
        line { moveTo(12f, 19.4f); curveTo(8.4f, 16.2f, 5f, 13.5f, 5f, 9.9f); curveTo(5f, 7.2f, 6.8f, 5.5f, 9.1f, 5.5f); curveTo(10.5f, 5.5f, 11.5f, 6.3f, 12f, 7.3f); curveTo(12.5f, 6.3f, 13.5f, 5.5f, 14.9f, 5.5f); curveTo(17.2f, 5.5f, 19f, 7.2f, 19f, 9.9f); curveTo(19f, 13.5f, 15.6f, 16.2f, 12f, 19.4f); close() }
        line { moveTo(7.4f, 11.6f); lineTo(9.9f, 11.6f); lineTo(11.1f, 9.8f); lineTo(12.6f, 13.6f); lineTo(13.9f, 11.6f); lineTo(16.6f, 11.6f) }
    }

    val Download = icon("MoreDownload") {
        line(width = 2f) { moveTo(12f, 4f); lineTo(12f, 14.5f) }
        line(width = 2f) { moveTo(8f, 10.8f); lineTo(12f, 14.8f); lineTo(16f, 10.8f) }
        line { moveTo(5f, 16.5f); lineTo(5f, 19f); curveTo(5f, 20f, 5.8f, 20.5f, 6.8f, 20.5f); lineTo(17.2f, 20.5f); curveTo(18.2f, 20.5f, 19f, 20f, 19f, 19f); lineTo(19f, 16.5f) }
    }

    val Queue = icon("MoreQueue") {
        line { moveTo(4.5f, 6f); lineTo(14.5f, 6f) }
        line { moveTo(4.5f, 10.5f); lineTo(14.5f, 10.5f) }
        line { moveTo(4.5f, 15f); lineTo(10.5f, 15f) }
        fill { moveTo(16f, 13.5f); lineTo(21f, 16.5f); lineTo(16f, 19.5f); close() }
    }

    val Sleep = icon("MoreSleepTimer") {
        // 重绘 v2（2026-09-25）：整圆钟体 + 中心指针 + 右上角圆头 z，与旧版剪影完全区分
        line {
            moveTo(18.3f, 12.6f); curveTo(18.3f, 16.41f, 15.21f, 19.5f, 11.4f, 19.5f)
            curveTo(7.59f, 19.5f, 4.5f, 16.41f, 4.5f, 12.6f)
            curveTo(4.5f, 8.79f, 7.59f, 5.7f, 11.4f, 5.7f)
            curveTo(15.21f, 5.7f, 18.3f, 8.79f, 18.3f, 12.6f); close()
        }
        line { moveTo(11.4f, 12.6f); lineTo(11.4f, 8.6f) }
        line { moveTo(11.4f, 12.6f); lineTo(14.2f, 14.2f) }
        line(1.9f) { moveTo(17.6f, 3.8f); lineTo(20.3f, 3.8f); lineTo(17.6f, 6.1f); lineTo(20.3f, 6.1f) }
    }

    val Collect = icon("MoreCollect") {
        line { moveTo(7f, 4.5f); lineTo(17f, 4.5f); curveTo(18f, 4.5f, 18.5f, 5f, 18.5f, 6f); lineTo(18.5f, 20f); lineTo(12f, 16.2f); lineTo(5.5f, 20f); lineTo(5.5f, 6f); curveTo(5.5f, 5f, 6f, 4.5f, 7f, 4.5f); close() }
        line(width = 1.5f) { moveTo(12f, 7.5f); lineTo(12f, 12.2f) }
        line(width = 1.5f) { moveTo(9.7f, 9.8f); lineTo(14.3f, 9.8f) }
    }

    val Share = icon("MoreShare") {
        line { moveTo(8f, 12f); lineTo(16.2f, 7.6f) }
        line { moveTo(8f, 12f); lineTo(16.2f, 16.4f) }
        line { moveTo(8f, 12f); curveTo(8f, 13.4f, 6.9f, 14.5f, 5.5f, 14.5f); curveTo(4.1f, 14.5f, 3f, 13.4f, 3f, 12f); curveTo(3f, 10.6f, 4.1f, 9.5f, 5.5f, 9.5f); curveTo(6.9f, 9.5f, 8f, 10.6f, 8f, 12f); close() }
        line { moveTo(21f, 6.5f); curveTo(21f, 7.9f, 19.9f, 9f, 18.5f, 9f); curveTo(17.1f, 9f, 16f, 7.9f, 16f, 6.5f); curveTo(16f, 5.1f, 17.1f, 4f, 18.5f, 4f); curveTo(19.9f, 4f, 21f, 5.1f, 21f, 6.5f); close() }
        line { moveTo(21f, 17.5f); curveTo(21f, 18.9f, 19.9f, 20f, 18.5f, 20f); curveTo(17.1f, 20f, 16f, 18.9f, 16f, 17.5f); curveTo(16f, 16.1f, 17.1f, 15f, 18.5f, 15f); curveTo(19.9f, 15f, 21f, 16.1f, 21f, 17.5f); close() }
    }
}
