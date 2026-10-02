package com.ncm.watch.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ncm.watch.data.HotSearch
import com.ncm.watch.data.NcmApi
import com.ncm.watch.data.PageCache
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.data.SearchHistoryStore
import com.ncm.watch.data.Song
import com.ncm.watch.ui.components.CenterHint
import com.ncm.watch.ui.components.CircularSideProgress
import com.ncm.watch.ui.components.DialogScrim
import com.ncm.watch.ui.components.NcmToast
import com.ncm.watch.ui.components.NcmIcons
import com.ncm.watch.ui.components.SongRow
import com.ncm.watch.ui.components.estimateProgress
import com.ncm.watch.ui.components.ArtistCapsule
import com.ncm.watch.ui.components.rotaryList
import com.ncm.watch.ui.nav.Routes
import com.ncm.watch.ui.nav.NavMotionKind
import com.ncm.watch.ui.nav.NavMotionSpec
import com.ncm.watch.ui.nav.navigateWithMotion
import com.ncm.watch.ui.theme.Accent
import com.ncm.watch.ui.theme.Bg
import com.ncm.watch.ui.theme.BgElevated
import com.ncm.watch.ui.theme.screenBg
import com.ncm.watch.ui.theme.SurfaceGlass
import com.ncm.watch.ui.theme.TextPrimary
import com.ncm.watch.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * 搜索：350ms 防抖真实搜索。
 * 结果最上方为艺人胶囊卡（圆头像+艺人名），下方相关歌曲普通列表。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(nav: NavHostController, initialQuery: String? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var kw by remember { mutableStateOf(initialQuery ?: "") }
    var result by remember { mutableStateOf<NcmApi.SearchResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(SearchHistoryStore.load(ctx)) }
    var hotList by remember { mutableStateOf<List<HotSearch>>(emptyList()) }
    val listState = rememberLazyListState()
    // 长按歌曲弹出的操作目标（null = 不显示操作浮层）
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toast) { if (toast != null) { delay(1800); toast = null } }

    LaunchedEffect(Unit) { hotList = PageCache.searchHot() }

    LaunchedEffect(kw) {
        if (kw.isBlank()) { result = null; return@LaunchedEffect }
        delay(350)
        loading = true
        result = NcmApi.search(kw.trim())
        loading = false
        // 搜索成功（有结果）才写历史：最新在前、去重、上限 10 条
        if (result?.songs?.isNotEmpty() == true) {
            SearchHistoryStore.add(ctx, kw.trim())
            history = SearchHistoryStore.load(ctx)
        }
    }

    val songs = result?.songs ?: emptyList()
    val artists = result?.artists ?: emptyList()

    // 空查询 → 「历史 + 热搜」组合页；输入关键词/有结果 → 结果列表
    val emptyState = kw.isBlank() && result == null

    // 侧缘进度环：按当前列表实际条目数动态估算，不写死
    val listItemCount = when {
        emptyState -> (if (history.isNotEmpty()) 3 else 0) + 1 + hotList.size
        result != null -> songs.size + (if (artists.isNotEmpty()) 1 else 0)
        else -> 1
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(screenBg())
            .rotaryList(listState),
    ) {
        // 单图层纵向流：搜索框固定头部 → 历史 → 热搜/结果，互不重叠
        Column(Modifier.fillMaxSize()) {
            // 搜索框固定头部（不悬浮、不遮内容）
            Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                SearchField(kw, onQuery = { kw = it })
            }

            LazyColumn(state = listState, contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp)) {
                if (loading && result == null) {
                    item { Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                        Text("搜索中…", fontSize = 12.sp, color = TextSecondary)
                    } }
                } else if (emptyState) {
                if (history.isNotEmpty()) {
                    item(key = "history_label") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("搜索历史", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "清空",
                                fontSize = 9.sp, color = TextSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        SearchHistoryStore.clear(ctx)
                                        history = emptyList()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    item(key = "history_chips") {
                        FlowRow(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            history.forEach { h ->
                                Text(
                                    h,
                                    fontSize = 10.sp, color = TextPrimary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(SurfaceGlass)
                                        .clickable(
                                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                            indication = null,
                                        ) { kw = h }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                    item(key = "hot_gap") { Spacer(Modifier.height(14.dp)) }
                }
                item(key = "hot_label") {
                    Text(
                        "网易云热搜榜",
                        fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                    )
                }
                if (hotList.isEmpty()) {
                    item(key = "hot_empty") {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                            Text("热搜加载中…", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                } else {
                    itemsIndexed(hotList, key = { i, _ -> "hot_$i" }, contentType = { _, _ -> "hot" }) { i, h ->
                        HotRow(i, h, maxScore = hotList.maxOf { it.score }) { kw = h.word }
                    }
                }
            } else if (result == null) {
                item {}
            } else {
                if (artists.isNotEmpty()) {
                    item(key = "artist_card") {
                        ArtistCapsule(artists.first()) {
                            nav.navigateWithMotion(
                                Routes.artistDetail(artists.first().id),
                                NavMotionSpec(NavMotionKind.Pill),
                            )
                        }
                    }
                    item(key = "artist_gap") { Spacer(Modifier.height(4.dp)) }
                }
                if (songs.isEmpty()) {
                    item { Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("没有找到相关歌曲", fontSize = 12.sp, color = TextSecondary)
                    } }
                } else {
                    itemsIndexed(songs, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                        SongRow(
                            song = song,
                            isCurrent = PlayerEngine.current?.id == song.id,
                            // 跨语言搜索：中文词命中日文歌时，把命中的译名标出来
                            aliasHint = com.ncm.watch.data.SongQuery.matchedAlias(song, kw.trim()),
                            onClick = {
                                PlayerEngine.playQueue(songs, i)
                                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
                            },
                            // 长按出操作浮层：添加到列表 / 下一首播放（2026-10-01）
                            onLongClick = { actionSong = song },
                        )
                    }
                }
            }
        }
        }

        val scrollProgress by remember(listItemCount) {
            derivedStateOf { estimateProgress(listState, listItemCount.coerceAtLeast(2)) }
        }
        CircularSideProgress(
            progress = { scrollProgress },
            modifier = Modifier.fillMaxSize(),
        )

        // 长按歌曲的操作浮层：下一首播放（2026-10-01）
        actionSong?.let { s ->
            DialogScrim(onDismiss = { actionSong = null }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(BgElevated)
                        // ★ 2026-10-01：整块压扁（内边距 18→13dp、字号各收一档）。
                        //   旧版纵向排两行时整块比圆屏可用高度还高，Column 把最后一个按钮的
                        //   高度分到 0dp —— 按钮文字整段消失（用户反馈「看不到按钮上的字」）。
                        //   现按用户要求只留「下一首播放」一项，整块 ≈80dp，稳稳落在圆内。
                        .padding(horizontal = 13.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        s.title, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        s.artist, fontSize = 8.5.sp, color = TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    // 只保留「下一首播放」——「添加到列表」按用户要求移除
                    SongActionRow("下一首播放", Accent) {
                        PlayerEngine.playNextInQueue(s)
                        actionSong = null
                        toast = "已设为下一首 · ${s.title}"
                    }
                }
            }
        }

        NcmToast(toast, Modifier.align(Alignment.Center))
    }
}

/**
 * 长按操作浮层里的单个动作按钮（胶囊）。
 * ★ 2026-10-01：底从「动作色 14% 透明」改为玻璃材质 + 动作色描边 ——
 *   旧版浅色主题色（含白色）下会出现「浅字压浅底」而整条看不见的情况；
 *   玻璃底 + 描边让按钮边界与文字在深/浅两套主题下都稳定可读。
 *   两个动作改为并排后按钮变窄，字号收到 10sp 且不换行。
 */
@Composable
private fun SongActionRow(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(SurfaceGlass)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun SearchField(kw: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    // TextFieldValue 状态重载：光标/选区由调用方持有，
    // 避免按删除键时光标状态在重组中丢失（表现为跳到上一个字而非删字）
    var textState by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(kw))
    }
    // 外部改动（点 × 清空）时同步回输入框
    LaunchedEffect(kw) {
        if (kw != textState.text) {
            textState = TextFieldValue(kw, TextRange(kw.length))
        }
    }
    Row(
        modifier = modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .background(SurfaceGlass)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(NcmIcons.Search, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = textState,
            onValueChange = {
                textState = it
                onQuery(it.text)
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box { if (kw.isEmpty()) Text("搜索音乐", fontSize = 12.sp, color = TextSecondary); inner() }
            },
        )
        if (kw.isNotEmpty()) {
            val clearSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Icon(
                NcmIcons.Close, "清空", tint = TextSecondary,
                modifier = Modifier
                    .size(14.dp)
                    .androidxClickable(clearSrc) { onQuery("") },
            )
        }
    }
}

private fun Modifier.androidxClickable(
    src: androidx.compose.foundation.interaction.MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.then(
    Modifier.clickable(interactionSource = src, indication = null, onClick = onClick)
)

/** 热搜榜单行：序号（前三名强调色）+ 词条 + 右侧热度细条（按 score 归一化） */
@Composable
private fun HotRow(index: Int, h: HotSearch, maxScore: Int, onClick: () -> Unit) {
    val top3 = index < 3
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${index + 1}",
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (top3) Accent else TextSecondary,
            modifier = Modifier.width(22.dp),
        )
        Text(
            h.word,
            fontSize = 12.sp, color = TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (maxScore > 0) {
            val frac = (h.score.toFloat() / maxScore).coerceIn(0.06f, 1f)
            Box(
                Modifier
                    .width((12 + 46 * frac).dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Accent.copy(alpha = if (top3) 0.85f else 0.35f)),
            )
        }
    }
}
