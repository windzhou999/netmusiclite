package com.netmusiclite.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PageCache
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.SleepMode
import com.netmusiclite.data.SleepTimer
import com.netmusiclite.ui.components.CircleIconButton
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedSongCard
import com.netmusiclite.ui.components.TextInputDialog
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.screenBg
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ================= 音量：播放页音量按钮直接唤出系统音量面板（2026-09-06），应用内音量页已移除 =================

// ================= 睡眠定时（模式选择 + 实时倒计时） =================

@Composable
fun SleepTimerScreen(nav: NavHostController) {
    val current = SleepTimer.mode
    StackedCardList(title = "睡眠定时", progressTotal = SleepMode.entries.size) {
        itemsIndexed(SleepMode.entries, key = { _, m -> m.name }, contentType = { _, _ -> "sleep" }) { _, m ->
            com.netmusiclite.ui.components.StackedCard(onClick = {
                SleepTimer.start(m)
            }) {
                Text(
                    m.label,
                    fontSize = 12.sp,
                    color = if (current == m) Accent else TextPrimary,
                    fontWeight = if (current == m) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                if (current == m) {
                    SleepCountdownLabel(fontSize = 9.sp)
                    Icon(
                        NcmIcons.Check, null,
                        tint = Accent,
                        modifier = Modifier.padding(horizontal = 16.dp).size(14.dp),
                    )
                }
            }
        }
    }
}

/** 定时剩余时间（More 里与播放页共用）：播完本歌显示模式文案，分钟模式显示 mm:ss */
@Composable
fun SleepCountdownLabel(
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val mode = SleepTimer.mode
    val text = when {
        mode == SleepMode.OFF -> ""
        mode == SleepMode.END_OF_SONG -> "播完暂停"
        else -> {
            val m = SleepTimer.remainingSec / 60
            val s = SleepTimer.remainingSec % 60
            "%d:%02d".format(m, s)
        }
    }
    if (text.isNotEmpty()) {
        Text(text, fontSize = fontSize, color = TextSecondary, modifier = modifier)
    }
}

// ================= 列表播放（当前队列，歌曲行卡片堆叠） =================

@Composable
fun QueueScreen(nav: NavHostController) {
    val queue = PlayerEngine.queue
    val currentId = PlayerEngine.current?.id
    StackedCardList(title = "列表播放", progressTotal = queue.size, cardHeight = 56.dp) {
        if (queue.isEmpty()) {
            item(key = "empty") {
                Text("队列为空", fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 140.dp), textAlign = TextAlign.Center)
            }
        } else {
            // ★ 2026-10-01：key 用「下标+id」复合键。此前用 `s.id` 单键 —— 队列里一旦出现
            //   重复 id（旧版「下一首播放」会复制一份、房间同步也可能回重复项），
            //   LazyColumn 直接抛 IllegalArgumentException 让整页崩溃。复合键恒唯一。
            itemsIndexed(queue, key = { i, s -> "q$i-${s.id}" }, contentType = { _, _ -> "song" }) { i, s ->
                StackedSongCard(song = s, isCurrent = s.id == currentId, onClick = {
                    PlayerEngine.playAt(i)
                    nav.popBackStack() // 回到播放页
                })
            }
        }
    }
}

// ================= 收藏到歌单（新建 + 列表 + ×） =================

@Composable
fun CollectScreen(nav: NavHostController) {
    var playlists by remember { mutableStateOf<List<com.netmusiclite.data.PlaylistItem>>(emptyList()) }
    var showCreate by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        playlists = PageCache.userPlaylists(SessionStore.uid).filter { it.isMine }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(1800); toast = null } }

    Box(Modifier.fillMaxSize().background(screenBg()).rotaryList(listState)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Text("收藏到歌单", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 34.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceGlass)
                    .clickable { showCreate = true }
                    .padding(horizontal = 13.dp),
            ) {
                Icon(NcmIcons.Plus, null, tint = Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("新建歌单", fontSize = 11.sp, color = TextPrimary)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), state = listState, horizontalAlignment = Alignment.CenterHorizontally) {
                itemsIndexed(playlists, key = { _, p -> p.id }, contentType = { _, _ -> "playlist" }) { _, pl ->
                    Text(
                        pl.name,
                        fontSize = 11.sp, color = TextPrimary,
                        modifier = Modifier
                            .padding(horizontal = 34.dp, vertical = 4.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(SurfaceGlass)
                            .clickable {
                                val sid = PlayerEngine.current?.id
                                if (sid == null) {
                                    toast = "没有正在播放的歌曲"
                                } else scope.launch {
                                    val ok = NcmApi.addToPlaylist(pl.id, sid)
                                    if (ok) { PageCache.invalidatePlaylistDetail(pl.id); PageCache.invalidatePlaylists() }
                                    toast = if (ok) "已收藏到「${pl.name}」" else "收藏失败"
                                }
                            }
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            CircleIconButton(NcmIcons.Close, "退出", { nav.popBackStack() }, size = 38.dp, iconSize = 15.dp)
            Spacer(Modifier.height(16.dp))
        }

        if (showCreate) {
            TextInputDialog(
                title = "新建歌单",
                placeholder = "歌单名称",
                onConfirm = { name ->
                    showCreate = false
                    scope.launch {
                        val id = NcmApi.createPlaylist(name)
                        if (id != null) {
                            toast = "已创建「$name」"
                            PageCache.invalidatePlaylists()
                            playlists = PageCache.userPlaylists(SessionStore.uid).filter { it.isMine }
                        } else toast = "创建失败"
                    }
                },
                onDismiss = { showCreate = false },
            )
        }
        toast?.let { msg ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(msg, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}
