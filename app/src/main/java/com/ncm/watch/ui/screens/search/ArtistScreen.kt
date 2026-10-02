package com.ncm.watch.ui.screens.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.navigation.NavHostController
import com.ncm.watch.data.AlbumItem
import com.ncm.watch.data.DownloadStore
import com.ncm.watch.data.ListenSession
import com.ncm.watch.data.NcmApi
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.data.Song
import com.ncm.watch.ui.components.AlbumCardRow
import com.ncm.watch.ui.components.BlurHeader
import com.ncm.watch.ui.components.CenterHint
import com.ncm.watch.ui.components.CircleSvgButton
import com.ncm.watch.ui.components.CircleSvgButtonFilled
import com.ncm.watch.ui.components.CircularSideProgress
import com.ncm.watch.ui.components.DownloadSelectionBar
import com.ncm.watch.ui.components.NcmIcons
import com.ncm.watch.ui.components.SongRow
import com.ncm.watch.ui.components.sinkFromCenter
import com.ncm.watch.ui.components.rotaryList
import com.ncm.watch.ui.components.estimateProgress
import com.ncm.watch.ui.components.formatCount
import com.ncm.watch.ui.nav.NAV_MS
import com.ncm.watch.ui.nav.NavGlide
import com.ncm.watch.ui.nav.Routes
import com.ncm.watch.ui.nav.NavMotionKind
import com.ncm.watch.ui.nav.NavMotionSpec
import com.ncm.watch.ui.nav.navigateWithMotion
import com.ncm.watch.ui.theme.Accent
import com.ncm.watch.ui.theme.Bg
import com.ncm.watch.ui.theme.screenBg
import com.ncm.watch.ui.theme.TextPrimary
import com.ncm.watch.ui.theme.TextSecondary
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 艺人页：
 * - 头图裁满 + 高斯模糊 + 渐变到主题底色，中央圆形头像
 * - 检测到乐迷团一起听 → 「关注 + 加入一起听」对称 SVG 按钮（无文字）
 * - 歌曲（热度排序，普通列表，长按多选 → 顶部下载）/ 专辑（发布时间卡片）双 Tab
 * - ★ 2026-10-01：歌曲列表**左划**切到专辑 Tab（滑动转场），点 Tab 文字则是缩放转场
 */
@Composable
fun ArtistScreen(
    nav: NavHostController,
    artistId: Long,
) {
    var artist by remember { mutableStateOf<NcmApi.ArtistPage?>(null) }
    var albums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var tab by remember { mutableStateOf(0) }
    var following by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // ★ 2026-10-01 晚（用户要求）：**去掉**歌曲 ⇄ 专辑的切换过渡动画，并**移除**
    //   「歌曲列表左划切到专辑」的手势（AppNav 里挂的 onSwipeLeft 一并拿掉）。
    //   现在点「歌曲 / 专辑」文字就是原地直接换内容：没有位移、没有缩放、没有淡入淡出。
    fun switchTab(target: Int) {
        if (target != tab) tab = target
    }

    LaunchedEffect(artistId) {
        artist = NcmApi.artistHot(artistId)
        albums = NcmApi.artistAlbums(artistId)
        // 关注红心回读真实状态（此前固定 false，已关注的艺人也显示未关注）
        following = NcmApi.artistFollowed(artistId)
    }

    val songs = artist?.songs ?: emptyList()
    // 乐迷团入口：只要艺人加载成功就提供。
    // ★ 旧条件是「artist != null && songs.isNotEmpty()」——「有没有热度歌曲」与「有没有乐迷团」
    //   本无关系，导致热歌加载失败/为空时入口整块消失（2026-09-12 扫描报告 #2）。现改为仅看艺人。
    val hasFanGroup = artist != null
    val selectMode = selected.isNotEmpty()

    LaunchedEffect(toast) {
        if (toast != null) { kotlinx.coroutines.delay(2200); toast = null }
    }

    Box(Modifier.fillMaxSize().background(screenBg()).rotaryList(listState)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 90.dp),
            // 2026-10-01 晚：Tab 切换改为原地直接换内容，列表本体不再需要任何转场图层
        ) {
            item(key = "header") {
                BlurHeader(
                    imageUrl = artist?.artist?.avatarUrl,
                    title = artist?.artist?.name ?: "加载中…",
                    subtitle = "${songs.size} 首热度歌曲 · ${albums.size} 张专辑",
                    height = 178.dp,
                    avatarUrl = artist?.artist?.avatarUrl,
                    actions = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (following) {
                                CircleSvgButtonFilled(NcmIcons.HeartFill, size = 44.dp) {
                                    following = false
                                    scope.launch {
                                        // 取消关注结果校验：失败回滚红心并提示（旧版乐观置位，
                                        // 服务端静默失败时按钮看起来取消了、实际没取消）
                                        val okUn = runCatching { NcmApi.artistSub(artistId, false) }.getOrDefault(false)
                                        if (!okUn) { following = true; toast = "取消关注失败，请重试" }
                                    }
                                }
                            } else {
                                CircleSvgButton(NcmIcons.Heart, contentDesc = "关注", size = 44.dp) {
                                    following = true
                                    scope.launch {
                                        val okSub = runCatching { NcmApi.artistSub(artistId, true) }.getOrDefault(false)
                                        if (!okSub) { following = false; toast = "关注失败，请重试" }
                                    }
                                }
                            }
                            // 乐迷团入口：进入该艺人的乐迷团看帖子
                            // （原「加入一起听房」行为已下沉到一起听页，此处不再直接建房/进房）
                            if (hasFanGroup) {
                                CircleSvgButton(
                                    NcmIcons.Note, contentDesc = "乐迷团", size = 44.dp,
                                    container = Accent, tint = com.ncm.watch.ui.theme.OnAccent,
                                    navMotionKind = NavMotionKind.Circle,
                                ) {
                                    nav.navigateWithMotion("artist_fans/$artistId")
                                }
                            }
                        }
                    },
                )
            }
            item(key = "tabs") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    // 2026-10-01 晚：去掉过渡动画 —— 点击即为原地直接切换
                    TabLabel("歌曲", tab == 0) { switchTab(0) }
                    Spacer(Modifier.height(0.dp).padding(horizontal = 14.dp))
                    TabLabel("专辑", tab == 1) { switchTab(1) }
                }
            }
            if (tab == 0) {
                if (selectMode) {
                    item(key = "dl_hint") {
                        Text(
                            "已选 ${selected.size} 首 · 点底部按钮下载",
                            fontSize = 9.sp, color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                itemsIndexed(songs, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                    // 边缘下沉（2026-09-30 用户要求）：与全站卡片列表同一套 sinkFromCenter ——
                    // 离屏幕横向中线越远的歌曲，缩放越小、越暗（中央一首最大最亮）。
                    // 歌曲行是通宽平铺样式，在行外包一层做图层变换即可；不动 SongRow 本体，
                    // 搜索/历史/歌单等同样用 SongRow 的页面不受影响。
                    Box(Modifier.sinkFromCenter()) {
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
                                    nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
                                }
                            },
                            onLongClick = {
                                if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                            },
                        )
                    }
                }
            } else {
                if (albums.isEmpty()) {
                    item { CenterHint("暂无专辑", Modifier.fillMaxWidth().height(160.dp)) }
                } else {
                    itemsIndexed(albums.sortedByDescending { it.publishTime }, key = { _, a -> a.id }, contentType = { _, _ -> "album" }) { _, album ->
                        // 与「歌曲」Tab 同一套边缘下沉（2026-09-30 用户要求）：专辑卡离屏幕中线越远
                        // 越小越淡，中央一张最大最亮。AlbumCardRow 本体不动，只在外面套一层图层变换。
                        Box(Modifier.sinkFromCenter()) {
                            AlbumCardRow(album) {
                                nav.navigateWithMotion(
                                    Routes.albumDetail(album.id),
                                    NavMotionSpec(NavMotionKind.Pill),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectMode) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp)) {
                DownloadSelectionBar(
                    count = selected.size,
                    onDownload = {
                        val list = selected.values.filter { it.id > 0 && DownloadStore.states[it.id] != 2 }
                        if (list.isEmpty()) {
                            toast = "所选歌曲均已下载"
                            selected.clear()
                            return@DownloadSelectionBar
                        }
                        toast = "开始下载 ${list.size} 首…"
                        DownloadStore.enqueue(list) { okN, failN, reasons ->
                            toast = when {
                                failN == 0 -> "已下载 $okN 首，到本地音乐查看"
                                okN == 0 -> "下载失败：$reasons"
                                else -> "成功 $okN 首；失败 $failN 首（$reasons）"
                            }
                            selected.clear()
                        }
                    },
                    onClear = { selected.clear() },
                )
            }
        }

        val artistProgress by remember(tab, songs.size, albums.size) {
            derivedStateOf {
                val total = (if (tab == 0) songs.size else albums.size) + 1
                estimateProgress(listState, total.coerceAtLeast(2)).coerceIn(0.15f, 1f)
            }
        }
        CircularSideProgress(progress = { artistProgress }, modifier = Modifier.fillMaxSize())
        toast?.let {
            Box(Modifier.align(Alignment.Center)) {
                Text(it, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text, fontSize = if (active) 13.sp else 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) TextPrimary else TextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}
