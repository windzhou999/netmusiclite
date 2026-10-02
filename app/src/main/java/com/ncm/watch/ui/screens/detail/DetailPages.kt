package com.ncm.watch.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ncm.watch.data.DownloadStore
import com.ncm.watch.data.NcmApi
import com.ncm.watch.data.PageCache
import com.ncm.watch.data.PlaylistItem
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.data.Song
import com.ncm.watch.ui.components.CircleIconButton
import com.ncm.watch.ui.components.CircularSideProgress
import com.ncm.watch.ui.components.NcmIcons
import com.ncm.watch.ui.components.SelectionDownloadCircles
import com.ncm.watch.ui.components.SongRow
import com.ncm.watch.ui.components.rotaryList
import com.ncm.watch.ui.components.estimateProgress
import com.ncm.watch.ui.components.formatCount
import com.ncm.watch.ui.nav.Routes
import com.ncm.watch.ui.nav.NavMotionKind
import com.ncm.watch.ui.nav.NavMotionSpec
import com.ncm.watch.ui.nav.navigateWithMotion
import com.ncm.watch.ui.theme.Bg
import com.ncm.watch.ui.theme.screenBg
import com.ncm.watch.ui.theme.TextPrimary
import com.ncm.watch.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * 尖锐封面头：专辑/歌单详情 —— 最上方为封面原图，
 * 封面下缘渐变到主题纯色，封面处显示名称，下面是歌曲普通列表。
 */
@Composable
fun SharpCoverHeader(
    coverUrl: String?,
    title: String,
    subtitle: String? = null,
) {
    val ctx = LocalContext.current
    val fadeOut = com.ncm.watch.data.BackgroundStore.ready
    Box(Modifier.fillMaxWidth().height(176.dp)) {
        // 图像层：启用背景图时用 DstIn 沿高度擦除 alpha = 真渐隐
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (fadeOut) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black,
                                0.45f to Color.Black.copy(alpha = 0.8f),
                                
                                1f to Color.Transparent,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
                }
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(coverUrl).size(520).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF1C1C2A)))
            }
        }
        if (!fadeOut) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.42f to Color.Black.copy(alpha = 0.30f),
                        0.78f to Bg,
                        1f to Bg,
                    )
                )
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 22.dp).padding(bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                textAlign = TextAlign.Center, maxLines = 2)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 9.sp, color = TextSecondary, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

/** 专辑详情：歌曲长按多选下载 */
@Composable
fun AlbumScreen(nav: NavHostController, albumId: Long) {
    var name by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()
    val listState = rememberLazyListState()

    LaunchedEffect(albumId) {
        val r = PageCache.albumDetail(albumId)
        if (r == null) failed = true
        else { name = r.first; cover = r.second; songs = r.third }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    fun downloadSelected() {
        val list = selected.values.filter { it.id > 0 && DownloadStore.states[it.id] != 2 }
        if (list.isEmpty()) { toast = "所选歌曲均已下载"; selected.clear(); return }
        toast = "开始下载 ${list.size} 首…"
        DownloadStore.enqueue(list) { okN, failN, reasons ->
            toast = when {
                failN == 0 -> "已下载 $okN 首，到本地音乐查看"
                okN == 0 -> "下载失败：$reasons"
                else -> "成功 $okN 首；失败 $failN 首（$reasons）"
            }
            selected.clear()
        }
    }

    Box(Modifier.fillMaxSize().background(screenBg()).rotaryList(listState)) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 90.dp)) {
            item(key = "header") { SharpCoverHeader(cover, name.ifEmpty { "专辑" }, "${songs.size} 首") }
            itemsIndexed(songs, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                SongRow(
                    song = song,
                    isCurrent = PlayerEngine.current?.id == song.id,
                    selected = selected.containsKey(song.id),
                    selectMode = selectMode,
                    onClick = {
                        if (selectMode) {
                            if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                        } else {
                        PlayerEngine.playQueue(songs, i)
                        nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill)) // 点歌即播并进入播放页
                    }
                    },
                    onLongClick = {
                        if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                    },
                )
            }
        }
        if (selectMode) {
            SelectionDownloadCircles(
                count = selected.size,
                onDownload = { downloadSelected() },
                onCancel = { selected.clear() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        // 返回 = 全局左缘滑动
        val pageProgress by remember(songs.size) {
            derivedStateOf { estimateProgress(listState, (songs.size + 1).coerceAtLeast(2)).coerceIn(0.15f, 1f) }
        }
        CircularSideProgress(progress = { pageProgress }, modifier = Modifier.fillMaxSize())
        if (failed) {
            Box(Modifier.align(Alignment.Center)) {
                Text("专辑加载失败", fontSize = 12.sp, color = TextSecondary)
            }
        }
        if (toast != null) {
            Box(Modifier.align(Alignment.Center)) {
                Text(toast!!, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

/** 歌单详情：歌曲长按多选下载 */
@Composable
fun PlaylistDetailScreen(nav: NavHostController, playlistId: Long) {
    var info by remember { mutableStateOf<PlaylistItem?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()
    // 列表进度恢复（免闪烁，2026-09-25）：进播放页前存的视口进度直接作为列表初始位置，
    // 歌曲未加载完前不渲染列表，首次布局即落在原进度，无顶部→跳转的闪现
    val savedHandle = nav.currentBackStackEntry?.savedStateHandle
    val savedIdx = remember { savedHandle?.get<Int>("list_restore_index") }
    val restoring = savedIdx != null
    // ★ 必须 remember：LazyListState(...) 是类构造器（优先于同名 Composable 工厂），
    //   裸调用会每次重组新建实例——多选歌曲/toast/切歌等任何重组都会让列表弹回顶部
    val listState = remember {
        androidx.compose.foundation.lazy.LazyListState(
            savedIdx ?: 0,
            savedHandle?.get<Int>("list_restore_offset") ?: 0,
        )
    }

    LaunchedEffect(playlistId) {
        val r = PageCache.playlistDetail(playlistId)
        if (r == null) failed = true
        else { info = r.first; songs = r.second }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    fun downloadSelected() {
        val list = selected.values.filter { it.id > 0 && DownloadStore.states[it.id] != 2 }
        if (list.isEmpty()) { toast = "所选歌曲均已下载"; selected.clear(); return }
        toast = "开始下载 ${list.size} 首…"
        DownloadStore.enqueue(list) { okN, failN, reasons ->
            toast = when {
                failN == 0 -> "已下载 $okN 首，到本地音乐查看"
                okN == 0 -> "下载失败：$reasons"
                else -> "成功 $okN 首；失败 $failN 首（$reasons）"
            }
            selected.clear()
        }
    }

    Box(Modifier.fillMaxSize().background(screenBg()).rotaryList(listState)) {
        // 恢复进度等待期不渲染列表：避免先按顶部布局再跳转的闪现
        if (!restoring || songs.isNotEmpty()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 90.dp)) {
            item(key = "header") {
                SharpCoverHeader(
                    info?.coverUrl, info?.name ?: "歌单",
                    "${songs.size} 首 · 播放 ${formatCount(info?.playCount ?: 0)}",
                )
            }
            itemsIndexed(songs, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                SongRow(
                    song = song,
                    isCurrent = PlayerEngine.current?.id == song.id,
                    selected = selected.containsKey(song.id),
                    selectMode = selectMode,
                    onClick = {
                        if (selectMode) {
                            if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                        } else {
                        PlayerEngine.playQueue(songs, i)
                        // 进播放页前保存列表视口进度，返回时复用（savedStateHandle）
                        nav.currentBackStackEntry?.savedStateHandle?.apply {
                            set("list_restore_index", listState.firstVisibleItemIndex)
                            set("list_restore_offset", listState.firstVisibleItemScrollOffset)
                        }
                        nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill)) // 点歌即播并进入播放页
                    }
                    },
                    onLongClick = {
                        if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                    },
                )
            }
        }
        }
        if (selectMode) {
            SelectionDownloadCircles(
                count = selected.size,
                onDownload = { downloadSelected() },
                onCancel = { selected.clear() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        // 返回 = 全局左缘滑动
        val pageProgress by remember(songs.size) {
            derivedStateOf { estimateProgress(listState, (songs.size + 1).coerceAtLeast(2)).coerceIn(0.15f, 1f) }
        }
        CircularSideProgress(progress = { pageProgress }, modifier = Modifier.fillMaxSize())
        if (failed) {
            Box(Modifier.align(Alignment.Center)) {
                Text("歌单加载失败", fontSize = 12.sp, color = TextSecondary)
            }
        }
        if (toast != null) {
            Box(Modifier.align(Alignment.Center)) {
                Text(toast!!, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

/** 左上返回钮已废弃：统一用全局左缘滑动返回 */
