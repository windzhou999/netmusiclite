@file:OptIn(ExperimentalFoundationApi::class)

package com.ncm.watch.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ncm.watch.data.AlbumItem
import com.ncm.watch.data.ArtistItem
import com.ncm.watch.data.Song
import com.ncm.watch.ui.theme.Accent
import com.ncm.watch.ui.theme.SurfaceGlass
import com.ncm.watch.ui.theme.SurfaceStrong
import com.ncm.watch.ui.theme.TextPrimary
import com.ncm.watch.ui.theme.TextSecondary
import com.ncm.watch.ui.theme.TextTertiary

/** 歌曲行（普通列表样式，非卡片）：封面38 + 标题/艺人 + 时长 + 选中态 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    isCurrent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    selectMode: Boolean = false,
    /**
     * 跨语言搜索提示：这首歌**因为别名/译名**命中当前搜索词时，把那个译名缀在艺人后面
     * （「夜に駆ける」搜中文「向夜晚奔去」时显示「YOASOBI · 向夜晚奔去」），
     * 让用户一眼看懂为什么搜索结果里会出现一首外文标题的歌。歌名本身命中时传 null。
     */
    aliasHint: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            SongCover(song, 38.dp, shape = RoundedCornerShape(8.dp))
            if (isCurrent) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(NcmIcons.Play, null, tint = TextPrimary, modifier = Modifier.size(15.dp)) }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, fontSize = 12.sp, color = if (isCurrent) Accent else TextPrimary,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(songSubtitle(song, aliasHint), fontSize = 9.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selectMode) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(19.dp).clip(CircleShape)
                    .border(1.5.dp, if (selected) Accent else TextTertiary, CircleShape)
                    .background(if (selected) Accent else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(NcmIcons.Check, null, tint = com.ncm.watch.ui.theme.OnAccent, modifier = Modifier.size(12.dp))
            }
        } else {
            Spacer(Modifier.width(6.dp))
            Text(formatMs(song.durationMs.toLong()), fontSize = 9.sp, color = TextTertiary)
        }
        if (trailing != null) trailing()
    }
}

/**
 * 歌曲副标题：艺人名；跨语言搜索命中别名时用强调色追加译名。
 * 汇成一条 AnnotatedString 而不是两个 Text 并排，是为了让省略号统一作用在整行上
 * （并排时两个 Text 各自截断，窄屏上会变成「YOASO… · 向夜…」这种双截断）。
 */
internal fun songSubtitle(song: Song, aliasHint: String?): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(song.artist)
        if (!aliasHint.isNullOrBlank()) {
            withStyle(androidx.compose.ui.text.SpanStyle(color = Accent)) {
                append(" · ")
                append(aliasHint)
            }
        }
    }

/** 搜索结果里的艺人胶囊卡：最左圆形头像 + 艺人名 */
@Composable
fun ArtistCapsule(artist: ArtistItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(50))
            .frostedGlass(RoundedCornerShape(50))
            .glassEdgeHighlight(RoundedCornerShape(50))
            .combinedClickable(onClick = onClick)
            .padding(start = 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(artist.avatarUrl, 38.dp, shape = CircleShape)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(artist.name, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("艺人", fontSize = 9.sp, color = TextSecondary)
        }
        Spacer(Modifier.weight(1f))
        Icon(NcmIcons.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(14.dp))
    }
}

/** 专辑卡片行：左封面右名称（按发布时间排列） */
@Composable
fun AlbumCardRow(album: AlbumItem, onClick: () -> Unit) {
    // 2026-10-01（艺人页专辑区）：竖向缝隙缩小 50%（5dp→2.5dp，卡间由 10dp→5dp），
    // 卡片整体尺寸缩小 15%（圆角 16→13.6 / 内距 9→7.65 / 封面 52→44.2 / 字号 12→10.2、9→7.65）。
    Row(
        modifier = Modifier
            .padding(horizontal = 15.3.dp, vertical = 2.5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.6.dp))
            .background(SurfaceGlass)
            .combinedClickable(onClick = onClick)
            .padding(7.65.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(album.coverUrl, 44.2.dp, shape = RoundedCornerShape(8.5.dp))
        Spacer(Modifier.width(9.35.dp))
        Column {
            Text(album.name, fontSize = 10.2.sp, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val y = if (album.publishTime > 0) formatDate(album.publishTime) else ""
            Text(
                listOf(album.artist, y).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 7.65.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 多选下载悬浮胶囊 */
@Composable
fun DownloadSelectionBar(count: Int, onDownload: () -> Unit, onClear: () -> Unit) {
    if (count <= 0) return
    Row(
        modifier = Modifier
            .padding(horizontal = 40.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(Accent)
            .combinedClickable(onClick = onDownload)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(NcmIcons.Download, null, tint = com.ncm.watch.ui.theme.OnAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text("下载 $count 首", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = com.ncm.watch.ui.theme.OnAccent)
        Spacer(Modifier.width(12.dp))
        Icon(
            NcmIcons.Close, "取消选择",
            tint = com.ncm.watch.ui.theme.OnAccent.copy(alpha = 0.8f),
            modifier = Modifier.size(14.dp).combinedClickable(onClick = onClear),
        )
    }
}

/**
 * 多选删除控制组（本地音乐页）：全选（可选）+ 取消 + 删除（右上角数量角标）。
 */
@Composable
fun SelectionDeleteCircles(
    count: Int,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    selectAll: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.padding(top = 50.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 全选（可选）
        if (selectAll != null) {
            Box(
                Modifier
                    .size(31.dp)
                    .clip(CircleShape)
                    .background(SurfaceStrong)
                    .combinedClickable(onClick = selectAll),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NcmIcons.Check, "全选", tint = Accent, modifier = Modifier.size(14.dp))
            }
        }
        // 取消选择
        Box(
            Modifier
                .size(31.dp)
                .clip(CircleShape)
                .background(SurfaceStrong)
                .combinedClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NcmIcons.Close, "取消选择", tint = TextPrimary, modifier = Modifier.size(13.dp))
        }
        // 删除（右上角数量角标）
        Box(Modifier.size(37.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Accent)
                    .combinedClickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NcmIcons.Trash, "删除所选", tint = com.ncm.watch.ui.theme.OnAccent, modifier = Modifier.size(15.dp))
            }
            if (count > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$count", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Accent)
                }
            }
        }
    }
}

/**
 * 多选模式顶部控制组：屏幕上方的小圆形按钮对（取消 ✕ + 下载 ↓ 带数量角标）。
 * 用于卡片堆叠歌曲列表（我喜欢/每日推荐/歌单/专辑）的长按多选下载。
 */
@Composable
fun SelectionDownloadCircles(
    count: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    selectAll: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.padding(top = 50.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 全选（可选，仅我喜欢页启用）
        if (selectAll != null) {
            Box(
                Modifier
                    .size(31.dp)
                    .clip(CircleShape)
                    .background(SurfaceStrong)
                    .combinedClickable(onClick = selectAll),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NcmIcons.Check, "全选", tint = Accent, modifier = Modifier.size(14.dp))
            }
        }
        // 取消选择
        Box(
            Modifier
                .size(31.dp)
                .clip(CircleShape)
                .background(SurfaceStrong)
                .combinedClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NcmIcons.Close, "取消选择", tint = TextPrimary, modifier = Modifier.size(13.dp))
        }
        // 下载（右上角数量角标）
        Box(Modifier.size(37.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Accent)
                    .combinedClickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NcmIcons.Download, "下载所选", tint = com.ncm.watch.ui.theme.OnAccent, modifier = Modifier.size(15.dp))
            }
            if (count > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$count", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Accent)
                }
            }
        }
    }
}
