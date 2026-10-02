package com.ncm.watch.ui.screens.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ncm.watch.data.ArtistPost
import com.ncm.watch.data.CommentItem
import com.ncm.watch.data.NcmApi
import com.ncm.watch.ui.components.CenterHint
import com.ncm.watch.ui.components.CircleIconButton
import com.ncm.watch.ui.components.CoverImage
import com.ncm.watch.ui.components.NcmIcons
import com.ncm.watch.ui.components.NcmToast
import com.ncm.watch.ui.components.Pressable
import com.ncm.watch.ui.components.TextInputDialog
import com.ncm.watch.ui.components.rotaryList
import com.ncm.watch.ui.theme.Accent
import com.ncm.watch.ui.theme.Bg
import com.ncm.watch.ui.theme.Gold
import com.ncm.watch.ui.theme.SurfaceGlass
import com.ncm.watch.ui.theme.TextPrimary
import com.ncm.watch.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** 动态（event）类评论线程的资源类型：threadId 形如 `A_EV_2_{资源id}_{uid}` 里的那个 2 */
private const val EVENT_RESOURCE_TYPE = 2

/**
 * 详情页顶部标题栏渐变的起始色：比 `Bg` 抬升一档、带一点蓝，
 * 与 `BlurHeader` 的 `BgElevatedBrush`（`#23233A → Bg`）同族 —— 避免顶部是一条死黑边。
 */
private val HEAD_SCRIM_TOP = Color(0xFF23233A)

/**
 * 帖子详情的数据交接：列表页写入 → 详情页读取。
 *
 * 导航只带一个"当前帖子"的内存引用，不把正文/图片串进路由参数
 * （事件帖正文是长文本 + 图片数组，塞 URL 里既丑又容易超长）。
 */
object FanPostBus {
    var post: ArtistPost? = null
}

/**
 * 乐迷团帖子详情：帖子本体 + 点赞 + 评论列表（热评/最新）+ 发评论。
 *
 * 评论走通用资源评论接口（`v1/resource/comments/{threadId}`），threadId 由列表页解析
 * 动态时一并带出（`info.commentThread.threadId`），所以同一套代码也能喂歌曲评论。
 */
@Composable
fun PostDetailScreen(nav: NavHostController) {
    var post by remember { mutableStateOf(FanPostBus.post) }
    var hot by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var latest by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var inputting by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val threadId = post?.threadId.orEmpty()

    /** 拉评论：`replace` 重载（回到第一页），否则续页追加 */
    fun load(at: Int, replace: Boolean) {
        scope.launch {
            val r = NcmApi.commentsByThread(threadId, at)
            if (r == null) {
                if (replace) failed = true
                loadingMore = false
                loading = false
                return@launch
            }
            if (replace) hot = r.first
            latest = if (replace) r.second else latest + r.second
            offset = if (replace) r.second.size else offset + r.second.size
            if (r.second.size < 20) endReached = true
            loading = false
            loadingMore = false
        }
    }

    LaunchedEffect(threadId) {
        if (threadId.isEmpty()) {
            loading = false
            failed = true
            return@LaunchedEffect
        }
        load(0, replace = true)
    }

    val endVisible by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(endVisible, latest.size) {
        if (endVisible && !loading && !loadingMore && !endReached && latest.isNotEmpty() && !failed) {
            loadingMore = true
            load(offset, replace = false)
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(2200)
            toast = null
        }
    }

    /** 帖子点赞（乐观更新） */
    fun toggleLike() {
        val p = post ?: return
        if (p.threadId.isEmpty()) {
            android.util.Log.i("LTDiag", "detail like ignored: threadId empty id=${p.id}")
            return
        }
        val target = !p.liked
        // 注意每次从**当前**状态算，不能闭包捕获旧 post（连点会算错计数）
        fun apply(on: Boolean) {
            val cur = post ?: return
            post = cur.copy(
                liked = on,
                likeCount = (cur.likeCount + if (on) 1 else -1).coerceAtLeast(0L),
            )
            FanPostBus.post = post
        }
        apply(target)
        scope.launch {
            val good = runCatching { NcmApi.likeEvent(p.threadId, target) }.getOrDefault(false)
            android.util.Log.i("LTDiag", "detail like id=${p.id} target=$target ok=$good")
            if (good) toast = if (target) "已点赞" else "已取消赞" else {
                apply(!target)
                "点赞失败，请检查登录态"
            }
        }
    }

    /** 评论点赞（乐观更新） */
    fun toggleCommentLike(c: CommentItem, isHot: Boolean) {
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
            if (!good) apply(!target)
        }
    }

    fun send(text: String) {
        val p = post ?: return
        sending = true
        scope.launch {
            // 动态评论：resourceType=2（A_EV_2…），resourceId 取动态自身的资源 id
            val good = runCatching {
                NcmApi.addComment(p.threadId, p.resourceId, EVENT_RESOURCE_TYPE, text)
            }.getOrDefault(false)
            sending = false
            if (good) {
                toast = "评论已发布"
                post = p.copy(commentCount = p.commentCount + 1)
                FanPostBus.post = post
                loading = true
                endReached = false
                load(0, replace = true)
            } else {
                toast = "评论失败，请检查登录态"
            }
        }
    }

    val title = if (post?.author.isNullOrEmpty()) "乐迷团帖子" else "${post?.author}的动态"

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .rotaryList(listState),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 40.dp, bottom = 30.dp,
            ),
        ) {
            if (post == null) {
                item(key = "nolink") { CenterHint("帖子数据已过期\n请返回列表重新进入", Modifier.fillMaxWidth().height(150.dp)) }
            } else {
                item(key = "head") {
                    val p = post
                    if (p != null) {
                        PostHeader(p, onLike = { toggleLike() }, onWrite = { inputting = true })
                    }
                }

                if (loading) {
                    item(key = "cLoading") { CenterHint("正在读取评论…", Modifier.fillMaxWidth().height(110.dp)) }
                } else if (failed) {
                    item(key = "cFailed") { CenterHint("评论获取失败\n请检查登录态与网络", Modifier.fillMaxWidth().height(110.dp)) }
                } else {
                    if (hot.isNotEmpty()) {
                        item(key = "hotLabel") { SectionLabel("热门评论") }
                        itemsIndexed(hot, key = { i, _ -> "hot_$i" }) { _, c ->
                            CommentRow(c, true) { toggleCommentLike(c, true) }
                        }
                    }
                    item(key = "latestLabel") {
                        SectionLabel("最新评论 ${if (latest.isEmpty()) "" else "(${latest.size})"}")
                    }
                    itemsIndexed(latest, key = { i, _ -> "latest_$i" }) { _, c ->
                        CommentRow(c, false) { toggleCommentLike(c, false) }
                    }
                    if (latest.isEmpty()) {
                        item(key = "noComment") {
                            CenterHint("还没有人评论，来抢沙发", Modifier.fillMaxWidth().height(100.dp))
                        }
                    }
                    item(key = "footer") {
                        Text(
                            when {
                                sending -> "正在发布评论…"
                                loadingMore -> "加载中…"
                                endReached -> "到底啦"
                                else -> ""
                            },
                            fontSize = 9.sp, color = TextSecondary, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }

        // 悬浮标题：顶部用**渐变**兜底（不是纯黑实心条）。
        // 旧实现是 `.background(BgElevated)`，在纯黑根背景上就是一条硬邦邦的黑边，
        // 与列表页（ArtistFansScreen）顶部渐隐的观感也不一致。现在改成
        // 「偏蓝的抬升色 → 背景色 → 全透明」三段竖渐变：顶部有光感层次，底部自然融进列表。
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to HEAD_SCRIM_TOP,
                        0.62f to Bg.copy(alpha = 0.86f),
                        1f to Color.Transparent,
                    )
                )
                .padding(top = 7.dp, bottom = 13.dp, start = 46.dp, end = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }

        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
        ) {
            NcmToast(toast)
        }
    }

    if (inputting) {
        TextInputDialog(
            title = "写评论",
            placeholder = "说点什么…",
            confirmText = "发送",
            onConfirm = {
                inputting = false
                send(it)
            },
            onDismiss = { inputting = false },
        )
    }
}

/** 帖子本体卡片：作者 + 时间 + 正文 + 图片 + 点赞/评论/写评论 */
@Composable
private fun PostHeader(p: ArtistPost, onLike: () -> Unit, onWrite: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceGlass)
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverImage(p.avatarUrl, 24.dp, shape = CircleShape)
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    p.author, fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(relTime(p.timeMs), fontSize = 8.sp, color = TextSecondary)
            }
        }
        if (p.text.isNotEmpty()) {
            Text(
                p.text, fontSize = 10.sp, color = TextPrimary, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
        if (p.images.isNotEmpty()) {
            Column(Modifier.padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                p.images.take(4).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        row.forEach { url -> CoverImage(url, 86.dp, shape = RoundedCornerShape(10.dp)) }
                    }
                }
            }
        }
        Row(
            Modifier.padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pressable(onClick = onLike, modifier = Modifier.clip(RoundedCornerShape(50))) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (p.liked) NcmIcons.HeartFill else NcmIcons.Heart,
                        contentDescription = if (p.liked) "取消赞" else "点赞",
                        tint = if (p.liked) Accent else TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (p.likeCount > 0L) "${p.likeCount}" else "赞",
                        fontSize = 9.sp, color = if (p.liked) Accent else TextSecondary,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    NcmIcons.CommentIc, contentDescription = "评论",
                    tint = TextSecondary, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text("${p.commentCount}", fontSize = 9.sp, color = TextSecondary)
            }
            Spacer(Modifier.weight(1f))
            CircleIconButton(
                NcmIcons.Plus, "写评论", onWrite,
                size = 30.dp, iconSize = 14.dp, container = Accent,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}

/** 单条评论：头像 + 昵称 + 时间 + 内容 + 点赞数（点数字即赞/取消赞） */
@Composable
private fun CommentRow(c: CommentItem, isHot: Boolean, onLike: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(SurfaceGlass)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverImage(c.avatar, 19.dp, shape = CircleShape)
            Spacer(Modifier.width(6.dp))
            Text(
                c.user, fontSize = 9.sp, color = TextSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (isHot) {
                Text(
                    "热评", fontSize = 8.sp, color = Gold, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Gold.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(c.time, fontSize = 8.sp, color = TextSecondary)
        }
        Text(
            c.content, fontSize = 10.sp, color = TextPrimary, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Pressable(onClick = onLike, modifier = Modifier.clip(RoundedCornerShape(50))) {
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (c.mine) NcmIcons.HeartFill else NcmIcons.Heart,
                    contentDescription = if (c.mine) "取消赞" else "点赞",
                    tint = if (c.mine) Accent else TextSecondary,
                    modifier = Modifier.size(11.dp),
                )
                if (c.liked > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${c.liked}", fontSize = 8.sp,
                        color = if (c.mine) Accent else TextSecondary,
                    )
                }
            }
        }
    }
}

/** 相对时间：兼容秒级/毫秒级时间戳 */
private fun relTime(raw: Long): String {
    if (raw <= 0L) return ""
    val ms = if (raw in 1L..9_999_999_999L) raw * 1000L else raw
    val d = System.currentTimeMillis() - ms
    return when {
        d < 0L -> ""
        d < 60_000L -> "刚刚"
        d < 3_600_000L -> "${d / 60_000L} 分钟前"
        d < 86_400_000L -> "${d / 3_600_000L} 小时前"
        d < 30L * 86_400_000L -> "${d / 86_400_000L} 天前"
        else -> {
            val c = java.util.Calendar.getInstance()
            c.timeInMillis = ms
            "%d-%02d-%02d".format(
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }
    }
}
