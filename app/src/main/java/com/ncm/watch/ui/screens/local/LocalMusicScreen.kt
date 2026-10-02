package com.ncm.watch.ui.screens.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ncm.watch.data.DownloadStore
import com.ncm.watch.data.LocalMusic
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.data.Song
import com.ncm.watch.data.SongQuery
import com.ncm.watch.ui.components.CenterHint
import com.ncm.watch.ui.theme.Accent
import com.ncm.watch.ui.components.ListFilterCard
import com.ncm.watch.ui.components.rememberAliasAwareSongs
import com.ncm.watch.ui.components.NcmIcons
import com.ncm.watch.ui.components.StackedIconCard
import com.ncm.watch.ui.components.SelectionDeleteCircles
import com.ncm.watch.ui.components.StackedCardList
import com.ncm.watch.ui.components.StackedSongCard
import com.ncm.watch.ui.nav.Routes
import com.ncm.watch.ui.nav.NavMotionKind
import com.ncm.watch.ui.nav.NavMotionSpec
import com.ncm.watch.ui.nav.navigateWithMotion
import kotlinx.coroutines.delay

/**
 * 本地音乐：设备媒体库 + 应用内已下载合并（歌曲行卡片堆叠）；下载完成自动刷新。
 * 长按进入多选 → 顶部出现删除按钮：
 * 应用内下载的歌曲（音频+歌词）直接删除；系统媒体库文件受系统权限限制不可删，会提示。
 */
@Composable
fun LocalMusicScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()

    // 下载状态变化（0→1→2）即重扫，让刚下载完的歌立刻出现
    val dlTick by remember {
        derivedStateOf { DownloadStore.states.values.sumOf { it } + DownloadStore.states.size }
    }

    suspend fun rescan() {
        songs = LocalMusic.scan(ctx)
        loading = false
    }
    LaunchedEffect(Unit) { rescan() }
    LaunchedEffect(dlTick) { if (dlTick > 0) rescan() }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    fun deleteSelected() {
        val deletable = selected.values.filter { DownloadStore.songOf(it.id) != null }
        val skipped = selected.size - deletable.size
        deletable.forEach { DownloadStore.delete(it.id) }
        val removedIds = deletable.map { it.id }.toSet()
        songs = songs.filterNot { it.id in removedIds }
        selected.clear()
        toast = when {
            deletable.isEmpty() -> "仅支持删除应用内下载的歌曲"
            skipped > 0 -> "已删除 ${deletable.size} 首；$skipped 首为系统媒体文件不可删"
            else -> "已删除 ${deletable.size} 首"
        }
    }

    Box(Modifier.fillMaxSize()) {
        val n = songs.size
        var filter by remember { mutableStateOf("") }
        // 跨语言搜索：本地文件没有网易译名，按需"查询扩展 + 别名反查"（详 rememberAliasAwareSongs）
        val visible = rememberAliasAwareSongs(songs, filter)
        StackedCardList(title = "本地音乐", progressTotal = n + 2, cardHeight = 56.dp, horizontalInsetPx = 10) {
            item(key = "dl_mgr") {
                StackedIconCard(
                    title = "下载管理",
                    subtitle = dlSummaryLine(),
                    icon = NcmIcons.Download,
                    iconTint = Accent,
                    navMotionKind = NavMotionKind.Pill,
                    onClick = { nav.navigateWithMotion(Routes.DOWNLOAD_MGR) },
                )
            }
            item(key = "filter") { ListFilterCard(query = filter, onQuery = { filter = it }) }
            if (loading) {
                item { CenterHint("扫描中…", Modifier.fillMaxWidth().height(240.dp)) }
            } else if (songs.isEmpty()) {
                item { CenterHint("设备上没有本地音乐\n下载的歌曲会出现在这里", Modifier.fillMaxWidth().height(240.dp)) }
            } else {
                itemsIndexed(visible, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                    StackedSongCard(
                        song = song,
                        isCurrent = PlayerEngine.current?.id == song.id,
                        aliasHint = SongQuery.matchedAlias(song, filter),
                        selectMode = selectMode,
                        selected = selected.containsKey(song.id),
                        onClick = {
                            if (selectMode) {
                                if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                            } else {
                                PlayerEngine.playQueue(visible, i)
                                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
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
            SelectionDeleteCircles(
                count = selected.size,
                onDelete = { deleteSelected() },
                onCancel = { selected.clear() },
                selectAll = {
                    songs.forEach { selected[it.id] = it }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (toast != null) {
            Box(Modifier.align(Alignment.Center)) {
                Text(
                    toast!!, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 下载管理卡片副标题：下载中/排队/失败 计数（观察 DownloadStore 可组合态，自动刷新） */
@Composable
private fun dlSummaryLine(): String {
    val dl = DownloadStore.currentDownload != null
    val pd = DownloadStore.pendingQueue.size
    val fl = DownloadStore.failed.size
    if (!dl && pd == 0 && fl == 0) return "没有进行中的任务"
    return listOfNotNull(
        if (dl) "下载中 1" else null,
        if (pd > 0) "排队 $pd" else null,
        if (fl > 0) "失败 $fl" else null,
    ).joinToString(" · ")
}
