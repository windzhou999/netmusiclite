package com.ncm.watch.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.ncm.watch.data.Song
import com.ncm.watch.data.LocalMusic
import com.ncm.watch.ui.theme.TextTertiary

/**
 * 歌曲封面数据源（SongCover / RotatingCover / 取色共用）：
 * 设备本地 Uri → 已下载落盘封面（断网/清缓存后仍可显示）→ 网络 URL（Coil 磁盘缓存兜底）。
 * 读 DownloadStore.states（可观察态）：下载完成的一瞬，在屏的封面即重组切到本地文件。
 * 普通函数（非 @Composable）：组合期调用会注册快照依赖；LaunchedEffect 里也能直接调。
 */
fun songCoverModel(song: Song?): Any? {
    song ?: return null
    LocalMusic.localUri(song)?.let { return it }
    if (com.ncm.watch.data.DownloadStore.states[song.id] == 2) {
        com.ncm.watch.data.DownloadStore.coverFile(song.id)?.let { return it }
    }
    return song.coverUrl
}

/** 通用封面图：网络封面 / 本地文件或 Uri / 兜底生成封面 */
@Composable
fun CoverImage(url: Any?, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    val str = url as? String
    val realUrl = str?.takeIf { it.isNotEmpty() && !it.startsWith("local://") }
    if (realUrl != null) {
        val ctx = LocalContext.current
        AsyncImage(
            // 快速滚动卡顿修复：请求级 crossfade(180) 会覆盖全局 crossfade(false)，
            // 每张新封面加载完都跑 180ms 淡入动画（每卡一个动画协程 + 每帧 alpha 重绘），
            // 快滚时几十个淡入并行。改为继承全局设置：直接显示，无逐帧动画。
            model = ImageRequest.Builder(ctx).data(realUrl).size(size.value.toInt() * 2).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else if (url is java.io.File || url is android.net.Uri) {
        // 本地文件/Uri：磁盘读取免网络，无需显式 size（按布局约束解码）
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        val seed = (str ?: "ncm").hashCode()
        SongCoverArt(seed, size, modifier, shape)
    }
}

@Composable
fun SongCover(song: Song, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    CoverImage(songCoverModel(song), size, modifier, shape)
}

/** 每歌固定配色的生成封面（无网络/无封面时兜底） */
@Composable
fun SongCoverArt(seed: Int, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    val hues = listOf(0xFF1C1C2E, 0xFF2E1C24, 0xFF14232E, 0xFF232E14, 0xFF2E2014)
    val accents = listOf(0xFF534AB7, 0xFFB74A5C, 0xFF3E7CB8, 0xFF7CB83E, 0xFFB8863E)
    val i = (seed % hues.size + hues.size) % hues.size
    val bg1 = Color(hues[i])
    val bg2 = Color(accents[i]).copy(alpha = 0.55f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(bg2, bg1))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(NcmIcons.Note, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(size / 3))
    }
}

/** 旋转封面：播放时转动，暂停即停。
 *  W5 流畅度（2026-09-30 二次降帧）：30fps → 15fps —— 每帧合成 = 整棵 RenderNode 树重新提交，
 *  实测播放页静止动画帧中位 13ms（GPU 12ms），全页合成就是每帧成本的大头；62dp 小圆 15fps
 *  旋转肉眼无感（每 4 帧步进 0.4°，角速度不变 12°/s），静止合成负载再砍半。
 *  切歌过渡（2026-09-25）：旧封面沿屏幕 X 轴向左滑出、新封面从右滑入（350ms 同步滑动）。
 *  旋转 graphicsLayer 放在滑动子项内部——滑动方向始终是屏幕坐标系，与当前旋转角无关；
 *  旋转角跨歌保持不重置；同专辑连播（封面相同）不触发动画 */
@Composable
fun RotatingCover(song: Song?, size: Dp, playing: Boolean, modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(song?.id, playing) {
        if (!playing) return@LaunchedEffect
        var frame = 0
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            frame++
            if (frame % 4 == 0) angle = (angle + 0.4f) % 360f
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // 封面源随歌解析（已下载用落盘文件，未下载走网络 URL）；同专辑/同源不触发滑动动画
        val coverModel = songCoverModel(song)
        AnimatedContent(
            targetState = coverModel,
            transitionSpec = {
                slideInHorizontally(tween(350)) { it } togetherWith
                    slideOutHorizontally(tween(350)) { -it }
            },
            label = "coverSlide",
        ) { model ->
            // 旋转只作用于封面内容本身：滑动过渡不受旋转角影响，始终沿屏幕 X 轴左出右进
            Box(Modifier.size(size).graphicsLayer { rotationZ = angle }) {
                CoverImage(model, size, Modifier, CircleShape)
            }
        }
    }
}
