package com.ncm.watch.ui.screens.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import com.ncm.watch.data.CommentItem
import com.ncm.watch.data.NcmApi
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.ui.components.CenterHint
import com.ncm.watch.ui.components.CoverImage
import com.ncm.watch.ui.components.NcmIcons
import com.ncm.watch.ui.components.rotaryList
import com.ncm.watch.ui.theme.Accent
import com.ncm.watch.ui.theme.Gold
import com.ncm.watch.ui.theme.TextPrimary
import com.ncm.watch.ui.theme.TextSecondary

/**
 * 云村评论（2026-10-01 升级）：
 * - 主楼显示「N 个赞 / N 条回复」；点赞走 SVG 心形，乐观更新、失败回滚
 * - 点「N 条回复」就地展开二级回复列表（读他人对这条评论的回复），再点收起；
 *   二级回复同样可点赞
 * - 滚到底自动翻页（offset+20）
 * 数据：NcmApi.commentsByThread / commentReplies / likeComment。
 */
@Composable
fun CommentsScreen(nav: NavHostController, songId: Long) {
    val song = PlayerEngine.current ?: com.ncm.watch.data.DownloadStore.songOf(songId)
    // 歌曲评论 threadId 固定形如 R_SO_4_{songId}（与 NcmApi.comments 同源）
    val threadId = remember(songId) { "R_SO_4_$songId" }
    var hot by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var latest by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ---- 二级回复（2026-10-01）----
    /** 当前展开回复的主楼评论 id（null = 全部收起） */
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val repliesMap = remember { mutableStateMapOf<Long, List<CommentItem>>() }
    var replyLoading by remember { mutableStateOf(false) }

    // ★ 2026-10-01 六轮（用户口径「评论区回复数量要主动显示」）：
    //   主评论列表接口不下发 replyCount（实测三种口径都没有），条数只能靠 floor/get 逐条问。
    //   之前是「展开一次才回填那一条」，其余入口只能一直显示笼统的「回复」。
    //   现在列表一到位就**主动**逐条补齐：
    //   - limit=1，只要 data.totalCount，载荷最小；
    //   - 串行 + 45ms 间隔，避免一次性几十个请求砸到接口；
    //   - 结果进 replyCounts 缓存（0 也缓存，避免重复问）；
    //   - 翻页加载出新评论后这个 Effect 会因 latest.size 变化自动续跑，只补没问过的。
    //   进页面 1~2 秒内各入口就会陆续从「回复」变成「N 条回复」。
    val replyCounts = remember { mutableStateMapOf<Long, Int>() }
    LaunchedEffect(songId, hot.size, latest.size) {
        if (failed) return@LaunchedEffect
        val seen = HashSet<Long>()
        for (c in hot + latest) {
            if (c.id <= 0L || !seen.add(c.id)) continue
            if (replyCounts.containsKey(c.id)) continue
            val t = runCatching {
                NcmApi.commentReplies(threadId, c.id, limit = 1).total
            }.getOrDefault(0)
            replyCounts[c.id] = t
            delay(45)
        }
    }

    LaunchedEffect(songId) {
        val r = NcmApi.comments(songId, 0)
        if (r == null) failed = true
        else {
            hot = r.first
            latest = r.second
            offset = r.second.size
            if (r.second.size < 20) endReached = true
        }
        loading = false
    }

    // 滚到末尾自动翻页：footer（最后一项）可见即触发。
    // 加载在 rememberCoroutineScope 里跑（不受本 Effect 重启取消影响），
    // loadingMore 用 try/finally 保证必复位——否则滚动中 Effect 被取消会永远卡「加载中」。
    fun loadMore() {
        if (loadingMore || endReached || failed || loading) return
        loadingMore = true
        scope.launch {
            try {
                val r = NcmApi.comments(songId, offset)
                if (r == null || r.second.isEmpty()) endReached = true
                else {
                    latest = latest + r.second
                    offset += r.second.size
                    if (r.second.size < 20) endReached = true
                }
            } finally {
                loadingMore = false
            }
        }
    }

    /** 主楼评论点赞 / 取消赞：乐观更新（热评与最新两条列表都改），失败回滚 */
    fun toggleLike(c: CommentItem, isHot: Boolean) {
        if (c.id <= 0L || threadId.isEmpty()) return
        val target = !c.mine
        fun apply(on: Boolean) {
            fun upd(list: List<CommentItem>) = list.map {
                if (it.id == c.id) it.copy(
                    mine = on,
                    liked = (it.liked + if (on) 1 else -1).coerceAtLeast(0),
                ) else it
            }
            if (isHot) hot = upd(hot) else latest = upd(latest)
        }
        apply(target)
        scope.launch {
            val good = runCatching { NcmApi.likeComment(threadId, c.id, target) }.getOrDefault(false)
            android.util.Log.i("LTDiag", "comment like id=${c.id} target=$target ok=$good")
            if (!good) apply(!target)
        }
    }

    /** 二级回复点赞（更新 repliesMap 中对应那一项） */
    fun toggleReplyLike(mainId: Long, r: CommentItem) {
        if (r.id <= 0L || threadId.isEmpty()) return
        val target = !r.mine
        fun apply(on: Boolean) {
            val cur = repliesMap[mainId] ?: return
            repliesMap[mainId] = cur.map {
                if (it.id == r.id) it.copy(
                    mine = on,
                    liked = (it.liked + if (on) 1 else -1).coerceAtLeast(0),
                ) else it
            }
        }
        apply(target)
        scope.launch {
            val good = runCatching { NcmApi.likeComment(threadId, r.id, target) }.getOrDefault(false)
            if (!good) apply(!target)
        }
    }

    /** 展开 / 收起某条评论的回复；首次展开才拉取 */
    fun toggleReplies(c: CommentItem) {
        if (c.id <= 0L) return
        if (expandedId == c.id) {
            expandedId = null
            return
        }
        expandedId = c.id
        if (repliesMap.containsKey(c.id)) return
        replyLoading = true
        scope.launch {
            try {
                val r = runCatching { NcmApi.commentReplies(threadId, c.id) }.getOrNull()
                repliesMap[c.id] = r?.items ?: emptyList()
                // 主评论列表不下发楼层数（实测），拿 floor/get 的真实 totalCount 回填主楼，
                // 之后入口就显示准确条数而不是笼统的「回复」
                val total = r?.total ?: 0
                if (total > 0) {
                    replyCounts[c.id] = total   // 同时进缓存，主动补齐的循环就不会再问这条
                    if (total != c.replyCount) {
                        fun patch(list: List<CommentItem>) = list.map {
                            if (it.id == c.id) it.copy(replyCount = total) else it
                        }
                        hot = patch(hot)
                        latest = patch(latest)
                    }
                }
            } finally {
                replyLoading = false
            }
        }
    }

    val endVisible by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(endVisible, latest.size) {
        if (endVisible && !loading && !loadingMore && !endReached && latest.isNotEmpty()) loadMore()
    }

    Box(
        Modifier
            .fillMaxSize()
            .rotaryList(listState),
    ) {
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        ) {
            // 悬浮胶囊标题：浮层覆盖列表顶部，胶囊以外全部是评论列表
            // 2026-10-01：先整体缩小 60%（16sp/22×6dp → 6.4sp/8.8×2.4dp），
            // 同日二轮按用户口径再放大 20%（→ 7.68sp / 10.56×2.88dp）
            Text(
                "评论",
                fontSize = 7.68.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    // 跟随主题的弱色块（深色主题→白 13%，浅色主题→黑 13%）
                    .background(TextPrimary.copy(alpha = 0.13f))
                    .padding(horizontal = 10.56.dp, vertical = 2.88.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 46.dp, bottom = 10.dp),
        ) {
            if (loading) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(160.dp)) }
            } else if (failed) {
                item { CenterHint("评论获取失败\n请检查登录态与网络", Modifier.fillMaxWidth().height(160.dp)) }
            } else {
                if (hot.isNotEmpty()) {
                    item(key = "hot_label") { SectionLabel("热门评论") }
                    itemsIndexed(
                        hot,
                        key = { i, _ -> "hot_$i" },
                        contentType = { _, _ -> "hot" },
                    ) { _, c ->
                        CommentRow(
                            c = c, isHot = true,
                            expanded = expandedId == c.id,
                            replies = repliesMap[c.id].orEmpty(),
                            replyLoading = replyLoading && expandedId == c.id,
                            replyCount = maxOf(replyCounts[c.id] ?: 0, c.replyCount),
                            onLike = { toggleLike(c, true) },
                            onToggleReplies = { toggleReplies(c) },
                            onReplyLike = { r -> toggleReplyLike(c.id, r) },
                        )
                    }
                    item(key = "latest_label") { SectionLabel("最新评论 (${latest.size})") }
                }
                itemsIndexed(
                    latest,
                    key = { i, _ -> "latest_$i" },
                    contentType = { _, _ -> "latest" },
                ) { _, c ->
                    CommentRow(
                        c = c, isHot = false,
                        expanded = expandedId == c.id,
                        replies = repliesMap[c.id].orEmpty(),
                        replyLoading = replyLoading && expandedId == c.id,
                        replyCount = maxOf(replyCounts[c.id] ?: 0, c.replyCount),
                        onLike = { toggleLike(c, false) },
                        onToggleReplies = { toggleReplies(c) },
                        onReplyLike = { r -> toggleReplyLike(c.id, r) },
                    )
                }
                if (endReached && latest.isEmpty()) {
                    item { CenterHint("还没有评论", Modifier.fillMaxWidth().height(120.dp)) }
                }
                item(key = "footer") {
                    Text(
                        when {
                            loadingMore -> "加载中…"
                            endReached -> "到底啦"
                            else -> ""
                        },
                        fontSize = 9.sp, color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
    )
}

/**
 * 主楼评论：头像 + 昵称 + 时间 + 内容 + 操作行（点赞 / 回复数）+ 可展开的二级回复。
 * 点赞与回复图标全部走 NcmIcons 矢量图（无 emoji）。
 */
@Composable
private fun CommentRow(
    c: CommentItem,
    isHot: Boolean,
    expanded: Boolean,
    replies: List<CommentItem>,
    replyLoading: Boolean,
    /** 主动补齐后的回复数（floor/get 逐条问回来的），接口没下发时由调用方兜底 */
    replyCount: Int,
    onLike: () -> Unit,
    onToggleReplies: () -> Unit,
    onReplyLike: (CommentItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverImage(c.avatar, 20.dp, shape = CircleShape)
            Spacer(Modifier.width(6.dp))
            Text(
                c.user, fontSize = 9.sp, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isHot) {
                Text(
                    "热评", fontSize = 9.sp, color = Gold, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Gold.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Text(c.time, fontSize = 8.sp, color = TextSecondary)
        }
        Spacer(Modifier.height(2.dp))
        Text(c.content, fontSize = 11.sp, color = TextPrimary, lineHeight = 15.sp)

        // 操作行：赞（SVG 心形）+ 回复（SVG 气泡，点开二级）
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLike,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (c.mine) NcmIcons.HeartFill else NcmIcons.Heart,
                    contentDescription = if (c.mine) "取消赞" else "点赞",
                    tint = if (c.mine) Accent else TextSecondary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("${c.liked}", fontSize = 9.sp, color = if (c.mine) Accent else TextSecondary)
            }
            // ★ 2026-10-01 二轮：入口**恒显**。服务端 `/v1/resource/comments/{threadId}`
            //   在实测的三种口径下都**不下发** replyCount / commentCount / showFloorComment
            //   （node 探针 probe_comment2/floorcount 实证），按 `replyCount > 0` 才给入口
            //   等于永远进不去二级 —— 这正是用户报的「评论的评论无法看到」的根因。
            //   现在一律给入口：条数已知显示「N 条回复」，未知显示「回复」；
            //   展开拉到 floor/get 的真实 totalCount 后会回填，下次即显示准确条数。
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleReplies,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    NcmIcons.CommentIc,
                    contentDescription = "回复",
                    tint = if (expanded) Accent else TextSecondary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        expanded -> "收起"
                        // ★ 2026-10-01 六轮：这里改用主动补齐的条数（floor/get 逐条问回来的），
                        //   不再依赖接口从不下发的 c.replyCount
                        replyCount > 0 -> "$replyCount 条回复"
                        else -> "回复"
                    },
                    fontSize = 9.sp, color = if (expanded) Accent else TextSecondary,
                )
            }
        }

        // 二级回复列表（就地展开，缩进 + 浅底表示从属关系）
        if (expanded) {
            when {
                replies.isEmpty() && replyLoading -> Text(
                    "加载回复…", fontSize = 9.sp, color = TextSecondary,
                    modifier = Modifier.padding(start = 26.dp, top = 4.dp),
                )
                replies.isEmpty() -> Text(
                    "暂无回复", fontSize = 9.sp, color = TextSecondary,
                    modifier = Modifier.padding(start = 26.dp, top = 4.dp),
                )
                else -> Column(Modifier.padding(start = 12.dp, top = 1.dp)) {
                    replies.forEach { r -> ReplyRow(r) { onReplyLike(r) } }
                }
            }
        }
    }
}

/** 二级回复行：无头像大图，缩进浅底；点赞可点 */
@Composable
private fun ReplyRow(r: CommentItem, onLike: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            // 回复行弱底：跟随主题（深色主题→白 5%，浅色主题→黑 5%）
            .background(TextPrimary.copy(alpha = 0.05f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CoverImage(r.avatar, 16.dp, shape = CircleShape)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.user, fontSize = 8.sp, color = TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onLike,
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (r.mine) NcmIcons.HeartFill else NcmIcons.Heart,
                        contentDescription = if (r.mine) "取消赞" else "点赞",
                        tint = if (r.mine) Accent else TextSecondary,
                        modifier = Modifier.size(10.dp),
                    )
                    if (r.liked > 0) {
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "${r.liked}", fontSize = 8.sp,
                            color = if (r.mine) Accent else TextSecondary,
                        )
                    }
                }
            }
            // 楼中楼里「回复某人」：接口 beReplied[0].user.nickname 带出被回复者，内联弱化显示
            val body = if (r.replyToUser.isEmpty()) {
                AnnotatedString(r.content)
            } else {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Accent.copy(alpha = 0.85f))) {
                        append("回复 @${r.replyToUser}")
                    }
                    append("：")
                    append(r.content)
                }
            }
            Text(
                body, fontSize = 10.sp, color = TextPrimary, lineHeight = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
