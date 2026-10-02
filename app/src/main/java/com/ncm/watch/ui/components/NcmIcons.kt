package com.ncm.watch.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 全套手绘矢量图标（24 视口，白色绘制，Icon(tint) 统一着色）。
 *
 * ★ 全部 by lazy（2026-09-30 冷启动专项）：NcmIcons 是 object，Kotlin 首次访问任意一个属性
 *   就会执行整个 object 初始化 —— 旧写法 41 个图标会在「第一次用图标」那一刻被一次性
 *   全部构建（每个都要跑 ImageVector.Builder + PathBuilder），是冷启动首帧组合里的一块
 *   可观开销。改成 by lazy 后只构建真正用到的那几个（启动路径不到 10 个）。
 */
object NcmIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply(block).build()

    private fun ImageVector.Builder.s(w: Float = 2f, block: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.White), strokeLineWidth = w,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null,
    ) { block() }

    private fun ImageVector.Builder.f(block: PathBuilder.() -> Unit) = path(
        fill = SolidColor(Color.White), stroke = null,
    ) { block() }

    val Search: ImageVector by lazy { icon("Search") {
        s { moveTo(16.6f, 16.6f); lineTo(21f, 21f) }
        s { moveTo(11f, 4f); curveTo(7.1f, 4f, 4f, 7.1f, 4f, 11f); curveTo(4f, 14.9f, 7.1f, 18f, 11f, 18f); curveTo(14.9f, 18f, 18f, 14.9f, 18f, 11f); curveTo(18f, 7.1f, 14.9f, 4f, 11f, 4f); close() }
    } }

    val NowPlaying: ImageVector by lazy { icon("NowPlaying") {
        s(2.4f) { moveTo(6.5f, 9.5f); lineTo(6.5f, 14.5f) }
        s(2.4f) { moveTo(12f, 5.5f); lineTo(12f, 18.5f) }
        s(2.4f) { moveTo(17.5f, 9.5f); lineTo(17.5f, 14.5f) }
    } }

    val DailyRec: ImageVector by lazy { icon("DailyRec") {
        s { moveTo(4f, 6.5f); curveTo(4f, 5.4f, 4.9f, 4.5f, 6f, 4.5f); lineTo(18f, 4.5f); curveTo(19.1f, 4.5f, 20f, 5.4f, 20f, 6.5f); lineTo(20f, 18f); curveTo(20f, 19.1f, 19.1f, 20f, 18f, 20f); lineTo(6f, 20f); curveTo(4.9f, 20f, 4f, 19.1f, 4f, 18f); close() }
        s { moveTo(4f, 9.5f); lineTo(20f, 9.5f) }
        s { moveTo(8f, 2.8f); lineTo(8f, 5.8f) }
        s { moveTo(16f, 2.8f); lineTo(16f, 5.8f) }
        f { moveTo(12f, 11.5f); lineTo(13f, 13.6f); lineTo(15.2f, 13.9f); lineTo(13.6f, 15.4f); lineTo(14f, 17.6f); lineTo(12f, 16.5f); lineTo(10f, 17.6f); lineTo(10.4f, 15.4f); lineTo(8.8f, 13.9f); lineTo(11f, 13.6f); close() }
    } }

    val Heart: ImageVector by lazy { icon("Heart") {
        s { moveTo(12f, 19.8f); curveTo(7.6f, 16.2f, 4.4f, 13.4f, 4.4f, 9.7f); curveTo(4.4f, 6.9f, 6.4f, 5f, 8.8f, 5f); curveTo(10.2f, 5f, 11.4f, 5.7f, 12f, 6.8f); curveTo(12.6f, 5.7f, 13.8f, 5f, 15.2f, 5f); curveTo(17.6f, 5f, 19.6f, 6.9f, 19.6f, 9.7f); curveTo(19.6f, 13.4f, 16.4f, 16.2f, 12f, 19.8f); close() }
    } }

    val HeartFill: ImageVector by lazy { icon("HeartFill") {
        f { moveTo(12f, 19.8f); curveTo(7.6f, 16.2f, 4.4f, 13.4f, 4.4f, 9.7f); curveTo(4.4f, 6.9f, 6.4f, 5f, 8.8f, 5f); curveTo(10.2f, 5f, 11.4f, 5.7f, 12f, 6.8f); curveTo(12.6f, 5.7f, 13.8f, 5f, 15.2f, 5f); curveTo(17.6f, 5f, 19.6f, 6.9f, 19.6f, 9.7f); curveTo(19.6f, 13.4f, 16.4f, 16.2f, 12f, 19.8f); close() }
    } }

    val Playlist: ImageVector by lazy { icon("Playlist") {
        s { moveTo(4f, 7f); lineTo(13f, 7f) }
        s { moveTo(4f, 12f); lineTo(13f, 12f) }
        s { moveTo(4f, 17f); lineTo(9f, 17f) }
        f { moveTo(16f, 13.4f); lineTo(21f, 16.5f); lineTo(16f, 19.6f); close() }
    } }

    val LocalMusic: ImageVector by lazy { icon("LocalMusic") {
        s { moveTo(11.5f, 17f); lineTo(11.5f, 5.8f) }
        s { moveTo(11.5f, 5.8f); lineTo(17.8f, 7.4f) }
        s { moveTo(17.8f, 7.4f); lineTo(17.8f, 15f) }
        f { moveTo(9f, 17f); moveTo(11.4f, 17f); }
        s(3.6f) { moveTo(9.7f, 17f); lineTo(9.7f, 17.01f) }
        s(3.6f) { moveTo(16f, 15f); lineTo(16f, 15.01f) }
    } }


    val Roam: ImageVector by lazy { icon("Roam") {
        f { moveTo(12f, 3.2f); lineTo(17.2f, 19.4f); lineTo(12f, 15.6f); lineTo(6.8f, 19.4f); close() }
    } }

    val Friends: ImageVector by lazy { icon("Friends") {
        s { moveTo(9f, 5.2f); curveTo(10.9f, 5.2f, 12.4f, 6.7f, 12.4f, 8.6f); curveTo(12.4f, 10.5f, 10.9f, 12f, 9f, 12f); curveTo(7.1f, 12f, 5.6f, 10.5f, 5.6f, 8.6f); curveTo(5.6f, 6.7f, 7.1f, 5.2f, 9f, 5.2f); close() }
        s { moveTo(3.4f, 19.2f); curveTo(3.4f, 16.2f, 5.8f, 14.2f, 9f, 14.2f); curveTo(12.2f, 14.2f, 14.6f, 16.2f, 14.6f, 19.2f) }
        s { moveTo(16.2f, 5.6f); curveTo(17.7f, 5.6f, 18.9f, 6.8f, 18.9f, 8.3f); curveTo(18.9f, 9.8f, 17.7f, 11f, 16.2f, 11f) }
        s { moveTo(16f, 13.9f); curveTo(18.4f, 13.9f, 20.6f, 15.6f, 20.6f, 18.4f) }
    } }

    val Settings: ImageVector by lazy { icon("Settings") {
        s { moveTo(4f, 7f); lineTo(20f, 7f) }
        s { moveTo(4f, 12f); lineTo(20f, 12f) }
        s { moveTo(4f, 17f); lineTo(20f, 17f) }
        f { moveTo(9f, 7f); moveTo(9.01f, 7f) }
        s(3.4f) { moveTo(9f, 7f); lineTo(9f, 7.01f) }
        s(3.4f) { moveTo(15.5f, 12f); lineTo(15.5f, 12.01f) }
        s(3.4f) { moveTo(7.5f, 17f); lineTo(7.5f, 17.01f) }
    } }

    val ChevronLeft: ImageVector by lazy { icon("ChevronLeft") {
        s { moveTo(14.5f, 5f); lineTo(7.8f, 12f); lineTo(14.5f, 19f) }
    } }

    val ChevronRight: ImageVector by lazy { icon("ChevronRight") {
        s { moveTo(9.5f, 5f); lineTo(16.2f, 12f); lineTo(9.5f, 19f) }
    } }

    val Close: ImageVector by lazy { icon("Close") {
        s { moveTo(6f, 6f); lineTo(18f, 18f) }
        s { moveTo(18f, 6f); lineTo(6f, 18f) }
    } }

    val Plus: ImageVector by lazy { icon("Plus") {
        s { moveTo(12f, 5f); lineTo(12f, 19f) }
        s { moveTo(5f, 12f); lineTo(19f, 12f) }
    } }

    val Check: ImageVector by lazy { icon("Check") {
        s { moveTo(4.5f, 12.5f); lineTo(9.8f, 17.8f); lineTo(19.5f, 6.8f) }
    } }

    val Volume: ImageVector by lazy { icon("Volume") {
        // 圆润重绘（2026-09-25 v2）：喇叭六处角全部二次曲线圆角过渡（左缘 r=1.5、
        // 号角 junction r≈1.4、锥形尖端 r≈1.35），无任何硬折角；声波圆头笔触微加宽
        f {
            moveTo(5.2f, 9.2f); lineTo(6.3f, 9.2f)
            quadTo(7.75f, 9.2f, 8.75f, 8.35f)
            lineTo(11.85f, 5.7f)
            quadTo(12.9f, 4.85f, 12.9f, 6.2f)
            lineTo(12.9f, 17.8f)
            quadTo(12.9f, 19.15f, 11.85f, 18.3f)
            lineTo(8.75f, 15.65f)
            quadTo(7.75f, 14.8f, 6.3f, 14.8f)
            lineTo(5.2f, 14.8f)
            quadTo(3.7f, 14.8f, 3.7f, 13.3f)
            lineTo(3.7f, 10.7f)
            quadTo(3.7f, 9.2f, 5.2f, 9.2f)
            close()
        }
        s(2.1f) { moveTo(16.1f, 9.3f); curveTo(17.25f, 10.3f, 17.25f, 13.7f, 16.1f, 14.7f) }
        s(2.1f) { moveTo(18.7f, 7.1f); curveTo(21.05f, 9.1f, 21.05f, 14.9f, 18.7f, 16.9f) }
    } }

    val MoreDots: ImageVector by lazy { icon("MoreDots") {
        f { moveTo(5f, 12f); moveTo(5.01f, 12f) }
        s(3.2f) { moveTo(5f, 12f); lineTo(5f, 12.01f) }
        s(3.2f) { moveTo(12f, 12f); lineTo(12f, 12.01f) }
        s(3.2f) { moveTo(19f, 12f); lineTo(19f, 12.01f) }
    } }

    /** 垃圾桶（本地音乐删除） */
    val Trash: ImageVector by lazy { icon("Trash") {
        s { moveTo(4.5f, 6.5f); lineTo(19.5f, 6.5f) }
        s { moveTo(9.5f, 6.5f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 6.5f) }
        s { moveTo(6.5f, 6.5f); lineTo(7.5f, 19.5f); lineTo(16.5f, 19.5f); lineTo(17.5f, 6.5f) }
        s { moveTo(10.2f, 10f); lineTo(10.2f, 16f) }
        s { moveTo(13.8f, 10f); lineTo(13.8f, 16f) }
    } }

    val Download: ImageVector by lazy { icon("Download") {
        s { moveTo(7.6f, 10.2f); lineTo(12f, 14.8f); lineTo(16.4f, 10.2f) }
        s { moveTo(5f, 19.2f); lineTo(19f, 19.2f) }
    } }

    val Repeat: ImageVector by lazy { icon("Repeat") {
        // 圆润重绘（2026-09-25）：箭头改外凸弧形弯头（quad 尖端无折角），弯位曲线圆角
        s { moveTo(16.8f, 3.6f); quadTo(20.7f, 6.6f, 16.8f, 9.6f) }
        s { moveTo(19.4f, 6.6f); lineTo(9f, 6.6f); curveTo(6.5f, 6.6f, 5f, 8.1f, 5f, 11.2f) }
        s { moveTo(7.2f, 20.4f); quadTo(3.3f, 17.4f, 7.2f, 14.4f) }
        s { moveTo(4.6f, 17.4f); lineTo(15f, 17.4f); curveTo(17.5f, 17.4f, 19f, 15.9f, 19f, 12.8f) }
    } }

    val Shuffle: ImageVector by lazy { icon("Shuffle") {
        // 圆润重绘（2026-09-25）：三处箭头全部弧形弯头，与 Repeat 同语汇
        s { moveTo(3.5f, 6.5f); lineTo(7.5f, 6.5f); curveTo(12.5f, 6.5f, 11.5f, 17.5f, 16.5f, 17.5f); lineTo(19.3f, 17.5f) }
        s { moveTo(16.8f, 15f); quadTo(20.6f, 17.5f, 16.8f, 20f) }
        s { moveTo(3.5f, 17.5f); lineTo(7.5f, 17.5f); curveTo(9.4f, 17.5f, 10.5f, 15.9f, 11.5f, 14.4f) }
        s { moveTo(12.5f, 9.6f); curveTo(13.5f, 8.1f, 14.6f, 6.5f, 16.5f, 6.5f); lineTo(19.3f, 6.5f) }
        s { moveTo(16.8f, 4f); quadTo(20.6f, 6.5f, 16.8f, 9f) }
    } }

    val SingleLoop: ImageVector by lazy { icon("SingleLoop") {
        // 圆润重绘（2026-09-25）：箭头肘部 r=1 圆角，「1」的斜旗改软弧
        s { moveTo(18.9f, 9.5f); curveTo(17.6f, 6.9f, 15f, 5f, 12f, 5f); curveTo(8.1f, 5f, 5f, 8.1f, 5f, 12f) }
        s { moveTo(5.1f, 14.5f); curveTo(6.4f, 17.1f, 9f, 19f, 12f, 19f); curveTo(15.9f, 19f, 19f, 15.9f, 19f, 12f) }
        s { moveTo(19.3f, 5.4f); lineTo(19.3f, 8.5f); quadTo(19.3f, 9.5f, 18.3f, 9.5f); lineTo(15f, 9.5f) }
        s { moveTo(11.2f, 15.2f); lineTo(11.2f, 9.8f) }
        s { moveTo(9.4f, 11.4f); quadTo(10.6f, 10.9f, 11.2f, 9.7f) }
    } }

    val HeartMode: ImageVector by lazy { icon("HeartMode") {
        // 圆润重绘（2026-09-25）：心电折线改连续软脉冲（对称曲线峰，无尖角）
        s { moveTo(12f, 19.2f); curveTo(7.9f, 15.8f, 4.9f, 13.2f, 4.9f, 9.8f); curveTo(4.9f, 7.2f, 6.8f, 5.4f, 9f, 5.4f); curveTo(10.3f, 5.4f, 11.4f, 6f, 12f, 7f); curveTo(12.6f, 6f, 13.7f, 5.4f, 15f, 5.4f); curveTo(17.2f, 5.4f, 19.1f, 7.2f, 19.1f, 9.8f); curveTo(19.1f, 13.2f, 16.1f, 15.8f, 12f, 19.2f); close() }
        s(1.9f) { moveTo(6.9f, 12.2f); lineTo(9.6f, 12.2f); curveTo(11.2f, 12.2f, 10.9f, 8.8f, 12f, 8.8f); curveTo(13.1f, 8.8f, 12.8f, 12.2f, 14.4f, 12.2f); lineTo(17.3f, 12.2f) }
    } }

    val QueueList: ImageVector by lazy { icon("QueueList") {
        s { moveTo(4f, 6.8f); lineTo(13f, 6.8f) }
        s { moveTo(4f, 11.8f); lineTo(13f, 11.8f) }
        s { moveTo(4f, 16.8f); lineTo(9f, 16.8f) }
        f { moveTo(15f, 13.6f); lineTo(20.4f, 16.8f); lineTo(15f, 20f); close() }
    } }

    val Bookmark: ImageVector by lazy { icon("Bookmark") {
        s { moveTo(7f, 4.6f); lineTo(17f, 4.6f); lineTo(17f, 19.8f); lineTo(12f, 15.8f); lineTo(7f, 19.8f); close() }
    } }

    val Send: ImageVector by lazy { icon("Send") {
        f { moveTo(3.6f, 11.4f); lineTo(20.4f, 4.2f); lineTo(14.2f, 20.2f); lineTo(11f, 13.6f); close() }
    } }

    val ImageIc: ImageVector by lazy { icon("ImageIc") {
        s { moveTo(4f, 7f); curveTo(4f, 5.9f, 4.9f, 5f, 6f, 5f); lineTo(18f, 5f); curveTo(19.1f, 5f, 20f, 5.9f, 20f, 7f); lineTo(20f, 17f); curveTo(20f, 18.1f, 19.1f, 19f, 18f, 19f); lineTo(6f, 19f); curveTo(4.9f, 19f, 4f, 18.1f, 4f, 17f); close() }
        s(2.8f) { moveTo(9f, 9.4f); lineTo(9f, 9.41f) }
        s { moveTo(6.4f, 16.2f); lineTo(10.8f, 11.6f); lineTo(13.8f, 14.6f); lineTo(15.9f, 12.6f); lineTo(19.2f, 15.9f) }
    } }

    val Together: ImageVector by lazy { icon("Together") {
        s { moveTo(8.6f, 5.4f); curveTo(10.1f, 5.4f, 11.3f, 6.6f, 11.3f, 8.1f); curveTo(11.3f, 9.6f, 10.1f, 10.8f, 8.6f, 10.8f); curveTo(7.1f, 10.8f, 5.9f, 9.6f, 5.9f, 8.1f); curveTo(5.9f, 6.6f, 7.1f, 5.4f, 8.6f, 5.4f); close() }
        s { moveTo(15.4f, 5.4f); curveTo(16.9f, 5.4f, 18.1f, 6.6f, 18.1f, 8.1f); curveTo(18.1f, 9.6f, 16.9f, 10.8f, 15.4f, 10.8f); curveTo(13.9f, 10.8f, 12.7f, 9.6f, 12.7f, 8.1f); curveTo(12.7f, 6.6f, 13.9f, 5.4f, 15.4f, 5.4f); close() }
        s { moveTo(3.6f, 18.6f); curveTo(3.6f, 15.8f, 5.8f, 14f, 8.6f, 14f); curveTo(11.4f, 14f, 13.6f, 15.8f, 13.6f, 18.6f) }
        s { moveTo(15.4f, 14.2f); curveTo(17.6f, 14.2f, 20.4f, 15.6f, 20.4f, 18.6f) }
    } }

    val Refresh: ImageVector by lazy { icon("Refresh") {
        s { moveTo(19f, 12f); curveTo(19f, 15.9f, 15.9f, 19f, 12f, 19f); curveTo(8.1f, 19f, 5f, 15.9f, 5f, 12f); curveTo(5f, 8.1f, 8.1f, 5f, 12f, 5f); curveTo(14.6f, 5f, 16.8f, 6.6f, 18f, 8.6f) }
        s { moveTo(18.2f, 4.8f); lineTo(18.2f, 8.8f); lineTo(14.2f, 8.8f) }
    } }

    // 2026-09-13 胖三角：填充+同路径圆角描边（round join），三个角自然带弧度
    val Play: ImageVector by lazy { icon("Play") {
        f { moveTo(7.8f, 5.6f); lineTo(18.6f, 12f); lineTo(7.8f, 18.4f); close() }
        s(2.4f) { moveTo(7.8f, 5.6f); lineTo(18.6f, 12f); lineTo(7.8f, 18.4f); close() }
    } }

    val Pause: ImageVector by lazy { icon("Pause") {
        s(3.8f) { moveTo(9f, 8f); lineTo(9f, 16f) }
        s(3.8f) { moveTo(15f, 8f); lineTo(15f, 16f) }
    } }

    // 2026-09-13 对齐参考样式：三角镂空描边（原实心），几何放大铺满视口
    val Next: ImageVector by lazy { icon("Next") {
        s(2.6f) { moveTo(5.2f, 4.8f); lineTo(16.4f, 12f); lineTo(5.2f, 19.2f); close() }
        s(2.9f) { moveTo(19.6f, 4.8f); lineTo(19.6f, 19.2f) }
    } }

    val Prev: ImageVector by lazy { icon("Prev") {
        s(2.6f) { moveTo(18.8f, 4.8f); lineTo(7.6f, 12f); lineTo(18.8f, 19.2f); close() }
        s(2.9f) { moveTo(4.4f, 4.8f); lineTo(4.4f, 19.2f) }
    } }

    val Note: ImageVector by lazy { icon("Note") {
        f { moveTo(9.6f, 17.4f); curveTo(9.6f, 18.8f, 8.5f, 19.9f, 7.1f, 19.9f); curveTo(5.7f, 19.9f, 4.6f, 18.8f, 4.6f, 17.4f); curveTo(4.6f, 16f, 5.7f, 14.9f, 7.1f, 14.9f); curveTo(8.5f, 14.9f, 9.6f, 16f, 9.6f, 17.4f); close() }
        f { moveTo(19.4f, 15.4f); curveTo(19.4f, 16.8f, 18.3f, 17.9f, 16.9f, 17.9f); curveTo(15.5f, 17.9f, 14.4f, 16.8f, 14.4f, 15.4f); curveTo(14.4f, 14f, 15.5f, 12.9f, 16.9f, 12.9f); curveTo(18.3f, 12.9f, 19.4f, 14f, 19.4f, 15.4f); close() }
        s { moveTo(9.6f, 17.4f); lineTo(9.6f, 4.8f); lineTo(19.4f, 7f); lineTo(19.4f, 15.4f) }
    } }

    val Qr: ImageVector by lazy { icon("Qr") {
        s { moveTo(4f, 8.5f); lineTo(4f, 6f); curveTo(4f, 4.9f, 4.9f, 4f, 6f, 4f); lineTo(8.5f, 4f) }
        s { moveTo(15.5f, 4f); lineTo(18f, 4f); curveTo(19.1f, 4f, 20f, 4.9f, 20f, 6f); lineTo(20f, 8.5f) }
        s { moveTo(20f, 15.5f); lineTo(20f, 18f); curveTo(20f, 19.1f, 19.1f, 20f, 18f, 20f); lineTo(15.5f, 20f) }
        s { moveTo(8.5f, 20f); lineTo(6f, 20f); curveTo(4.9f, 20f, 4f, 19.1f, 4f, 18f); lineTo(4f, 15.5f) }
        s { moveTo(12f, 10.5f); lineTo(12f, 13.5f) }
    } }

    /** 睡眠定时（月牙 + 圆头 z） */
    val Moon: ImageVector by lazy { icon("Moon") {
        // 圆润重绘（2026-09-25）：月牙左移让位，右上角补一颗圆头 z，睡眠语义更直白
        s { moveTo(17.4f, 12.9f); curveTo(16.9f, 13.1f, 16.4f, 13.2f, 15.9f, 13.2f); curveTo(12.4f, 13.2f, 9.6f, 10.4f, 9.6f, 6.9f); curveTo(9.6f, 6.4f, 9.7f, 5.9f, 9.8f, 5.4f); curveTo(6.5f, 6.2f, 4.1f, 9.2f, 4.1f, 12.7f); curveTo(4.1f, 16.7f, 7.4f, 20f, 11.4f, 20f); curveTo(14.5f, 20f, 17f, 18f, 17.4f, 12.9f); close() }
        s(1.9f) { moveTo(16.8f, 4.4f); lineTo(19.2f, 4.4f) }
        s(1.9f) { moveTo(19.2f, 4.4f); lineTo(16.8f, 7.2f) }
        s(1.9f) { moveTo(16.8f, 7.2f); lineTo(19.2f, 7.2f) }
    } }

    /** 听歌排行（柱状图） */
    val Chart: ImageVector by lazy { icon("Chart") {
        s(2.2f) { moveTo(5.5f, 19.5f); lineTo(5.5f, 14f) }
        s(2.2f) { moveTo(12f, 19.5f); lineTo(12f, 9f) }
        s(2.2f) { moveTo(18.5f, 19.5f); lineTo(18.5f, 4.5f) }
    } }

    /** 评论（对话气泡） */
    val CommentIc: ImageVector by lazy { icon("CommentIc") {
        s(1.8f) { moveTo(12f, 4.5f); curveTo(7.6f, 4.5f, 4.2f, 7.4f, 4.2f, 11f); curveTo(4.2f, 13f, 5.3f, 14.8f, 7f, 16f); lineTo(6.4f, 19.5f); lineTo(10f, 17.4f); curveTo(10.6f, 17.5f, 11.3f, 17.6f, 12f, 17.6f); curveTo(16.4f, 17.6f, 19.8f, 14.6f, 19.8f, 11f); curveTo(19.8f, 7.4f, 16.4f, 4.5f, 12f, 4.5f); close() }
    } }
    /** 复制（双层圆角矩形） */
    val Copy: ImageVector by lazy { icon("Copy") {
        s { moveTo(9f, 9f); lineTo(18f, 9f); arcTo(1.2f, 1.2f, 0f, false, true, 19.2f, 10.2f); lineTo(19.2f, 18f); arcTo(1.2f, 1.2f, 0f, false, true, 18f, 19.2f); lineTo(10.2f, 19.2f); arcTo(1.2f, 1.2f, 0f, false, true, 9f, 18f); close() }
        s { moveTo(6f, 15f); curveTo(4.9f, 15f, 4f, 14.1f, 4f, 13f); lineTo(4f, 6f); curveTo(4f, 4.9f, 4.9f, 4f, 6f, 4f); lineTo(13f, 4f); curveTo(14.1f, 4f, 15f, 4.9f, 15f, 6f) }
    } }

    val History: ImageVector by lazy { icon("History") {
        s {
            moveTo(3f, 12f); arcTo(9f, 9f, 0f, false, true, 21f, 12f)
            arcTo(9f, 9f, 0f, false, true, 3f, 12f)
            moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15.5f, 14f)
        }
    } }

    /** 主题（明暗对比圆：右半实心 + 左半描边，中轴由填充直边自然形成） */
    val Theme: ImageVector by lazy { icon("Theme") {
        f { moveTo(12f, 3f); arcTo(9f, 9f, 0f, false, true, 12f, 21f); close() }
        s(1.9f) { moveTo(12f, 3f); arcTo(9f, 9f, 0f, false, false, 12f, 21f) }
    } }

}
