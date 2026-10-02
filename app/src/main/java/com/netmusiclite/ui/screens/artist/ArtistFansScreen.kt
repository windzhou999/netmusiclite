package com.netmusiclite.ui.screens.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.ArtistPost
import com.netmusiclite.data.NcmApi
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.Pressable
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.BgElevated
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private const val PAGE = 30

/** 首屏目标条数：`/api/event/get` 每页只回 1~3 条，得多拉几页才够一屏可滚 */
private const val FIRST_TARGET = 18

/** 顶部标题投影（静态复用）：浅色字落在浅色内容上也不糊；1dp 下偏移 + 6px 模糊 + 55% 黑 */
private val FAN_HEADER_SHADOW = TextStyle(
    shadow = Shadow(
        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
        offset = Offset(0f, 1f),
        blurRadius = 6f,
    )
)

/**
 * 艺人乐迷团（帖子流：可点赞、可进帖子看评论/发评论）。
 *
 * 数据源：`NcmApi.artistDynamic`（实测命中 `/api/event/get`，靠 lasttime 游标翻页）。
 * 加载策略是「先来先显示」：第一页回来立刻渲染，剩下的在后台接着拉
 * （每页仅 1~3 条，若等凑满再显示，真机主观感受就是「半天不出来 / 只拉到几条」）。
 */
@Composable
fun ArtistFansScreen(nav: NavHostController, artistId: Long) {
    var artist by remember { mutableStateOf<NcmApi.ArtistPage?>(null) }
    var posts by remember { mutableStateOf<List<ArtistPost>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(artistId) {
        artist = runCatching { NcmApi.artistHot(artistId) }.getOrNull()
    }

    LaunchedEffect(artistId, reloadTick) {
        loading = true
        failed = false
        endReached = false
        posts = emptyList()
        val first = NcmApi.artistDynamic(artistId, PAGE, restart = true)
        if (first == null) {
            failed = true
            loading = false
            return@LaunchedEffect
        }
        posts = first.posts
        endReached = !first.hasMore
        loading = false
        // 后台续拉：补到一屏够滚为止（每次 1 页），不阻塞首帧。
        // 与触底续拉共用 loadingMore 旗标串行化 —— 两条路都在推进同一个游标，
        // 并发跑会各拿一次同 lasttime，白拉一页（列表靠 id 去重，但体验上白等）。
        var guard = 0
        while (!endReached && posts.size < FIRST_TARGET && guard < 6) {
            if (loadingMore) {
                kotlinx.coroutines.delay(200)
                continue
            }
            loadingMore = true
            try {
                val r = NcmApi.artistDynamic(artistId, PAGE) ?: break
                val fresh = r.posts.filter { p -> posts.none { it.id == p.id } }
                if (fresh.isNotEmpty()) posts = posts + fresh
                endReached = !r.hasMore
                if (fresh.isEmpty()) break
            } finally {
                loadingMore = false
            }
            guard++
        }
    }

    /** 触底续拉：一批 2 页（每页 1~3 条），拉不到新内容或到底就收手 */
    fun loadMore() {
        if (loadingMore || endReached || failed || loading) return
        loadingMore = true
        scope.launch {
            try {
                var guard = 0
                while (!endReached && guard < 2) {
                    guard++
                    val r = NcmApi.artistDynamic(artistId, PAGE) ?: break
                    val fresh = r.posts.filter { p -> posts.none { it.id == p.id } }
                    if (fresh.isNotEmpty()) posts = posts + fresh
                    endReached = !r.hasMore
                    if (fresh.isEmpty()) break
                }
            } finally {
                loadingMore = false
            }
        }
    }

    /** 点赞 / 取消赞：先本地乐观更新（点赞手感要即时），失败回滚 */
    fun toggleLike(p: ArtistPost) {
        // threadId 缺失时老实现是静默 return —— 用户视角就是「点了没反应」，先留痕
        if (p.threadId.isEmpty()) {
            android.util.Log.i(
                "LTDiag",
                "like tap ignored: threadId empty id=${p.id} rid=${p.resourceId}",
            )
            return
        }
        val target = !p.liked
        fun apply(on: Boolean) {
            posts = posts.map {
                if (it.id != p.id) it
                else it.copy(
                    liked = on,
                    likeCount = (it.likeCount + if (on) 1 else -1).coerceAtLeast(0L),
                )
            }
        }
        apply(target)
        scope.launch {
            val good = runCatching { NcmApi.likeEvent(p.threadId, target) }.getOrDefault(false)
            android.util.Log.i("LTDiag", "like tap id=${p.id} target=$target ok=$good")
            if (!good) apply(!target)
        }
    }

    val endVisible by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(endVisible, posts.size) {
        if (endVisible && !loading && !loadingMore && !endReached && posts.isNotEmpty()) loadMore()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .rotaryList(listState),
    ) {
        // 视口半高：卡片离中线越远越「立起来」，滚到中线时回正平铺
        val halfPx = with(LocalDensity.current) { (maxHeight / 2).toPx() }
        // 顶部标题淡出进度（2026-10-01）：列表顶部留白固定 46dp，滚过这段即完全消失
        val headerFadePx = with(LocalDensity.current) { 46.dp.toPx() }
        val headerFade by remember {
            derivedStateOf {
                val off = if (listState.firstVisibleItemIndex == 0)
                    listState.firstVisibleItemScrollOffset.toFloat() else headerFadePx
                (1f - off / headerFadePx).coerceIn(0f, 1f)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            // 顶部留出悬浮标题的高度，卡片从标题下方滚入。
            // 2026-10-01：卡片整体缩小 14% —— 左右内距 11 → 25.7dp，
            // 卡片实际宽度 ≈ 屏宽 86%，与卡片内部元素（字号/头像/图片）同步缩放。
            contentPadding = PaddingValues(start = 25.7.dp, end = 25.7.dp, top = 46.dp, bottom = 26.dp),
        ) {
            when {
                loading -> item(key = "loading") {
                    CenterHint("正在读取乐迷团动态…", Modifier.fillMaxWidth().height(140.dp))
                }
                failed -> item(key = "failed") {
                    CenterHint(
                        "乐迷团动态获取失败\n点此重试",
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { reloadTick++ },
                    )
                }
                posts.isEmpty() -> item(key = "empty") {
                    // 空列表但服务端还有下一页：可能是这一批全是无正文的转发空壳，
                    // 给一个「继续加载」入口，别让用户以为真的没有内容
                    val more = !endReached
                    CenterHint(
                        if (more) "这一批没有可显示的动态\n点此继续加载" else "这位艺人暂时还没有乐迷团动态",
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { if (more) loadMore() },
                    )
                }
                else -> {
                    items(posts, key = { it.id }) { p ->
                        PostCard(
                            p,
                            modifier = Modifier.postCardLift(halfPx),
                            onLike = { toggleLike(p) },
                            onOpen = {
                                FanPostBus.post = p
                                nav.navigateWithMotion(Routes.FAN_POST, NavMotionSpec(NavMotionKind.Pill))
                            },
                        )
                    }
                    item(key = "footer") {
                        Text(
                            when {
                                loadingMore -> "加载中…"
                                endReached -> "没有更多了"
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

        // 顶部标题（2026-10-01 改）：去掉整条黑色渐变悬浮底，固定在列表顶端；
        // 整体随列表滑动进度淡出（滚过顶部留白即消失），文字自带轻投影，落在任何内容上都清晰。
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .graphicsLayer { alpha = headerFade }
                .padding(top = 5.dp, bottom = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "乐迷团",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                textAlign = TextAlign.Center, style = FAN_HEADER_SHADOW,
            )
            Text(
                artist?.artist?.name ?: "艺人主页",
                fontSize = 9.sp, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                style = FAN_HEADER_SHADOW,
                modifier = Modifier.padding(horizontal = 54.dp),
            )
        }
    }
}

/**
 * 卡片「边缘轻收 → 中心平铺」。
 *
 * 与项目既有 `sinkFromCenter`（StackedCardList）同源：以屏幕横向中线为基准算距离比 t，
 * smoothstep 缓动，在缩放/变暗之外叠加**极轻微**的绕 X 轴倾斜。
 *
 * 注意：这里是「连续阅读的信息流」，不是轮播卡组。倾角一旦给大（早期为 48°），
 * 中线上下两侧会朝相反方向翻倒，相邻两张在接缝处张成 ∧/∨ 形，视觉上直接把
 * 连续动态切成扇形卡组 —— 主观感受就是「卡片间距过大、阅读不连续」。
 * 因此倾角压到 8°，alpha 衰减也从 0.26 收到 0.10，只保留一点纵深暗示。
 */
@Composable
private fun Modifier.postCardLift(halfPx: Float): Modifier {
    var centerY by remember { mutableFloatStateOf(halfPx) }
    return this
        .onGloballyPositioned { ic ->
            centerY = ic.positionInRoot().y + ic.size.height / 2f
        }
        .graphicsLayer {
            val ratio = ((centerY - halfPx) / halfPx).coerceIn(-1f, 1f)
            val t = kotlin.math.abs(ratio)
            val eased = t * t * (3f - 2f * t)   // smoothstep，按距离（非带符号值）缓动
            // 中线上下自动反向；只留 8° 纵深暗示，不破坏信息流的连续性
            rotationX = ratio * 8f
            val sc = 1f - 0.04f * eased
            scaleX = sc
            scaleY = sc
            alpha = 1f - 0.10f * t
        }
}

/**
 * 单条帖子卡片。
 *
 * 点卡片主体进帖子详情（看评论/发评论）；底部两个操作互不干扰：
 * 心形 = 点赞（乐观更新），气泡 = 评论（等同点卡片）。
 *
 * ★ 2026-10-01：按用户口径整体缩小 14%（所有尺寸 ×0.86，与艺人页专辑卡同一套做法）。
 *   横向由列表 contentPadding 同步收窄（11dp → 25.7dp/边），卡片实际宽度 = 屏宽 86%。
 */
@Composable
private fun PostCard(
    p: ArtistPost,
    modifier: Modifier = Modifier,
    onLike: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.3.dp))
            .background(SurfaceGlass)
            .padding(horizontal = 8.6.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverImage(p.avatarUrl, 18.9.dp, shape = CircleShape)
            Spacer(Modifier.width(6.dp))
            Text(
                p.author, fontSize = 8.6.sp, color = TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(agoText(p.timeMs), fontSize = 6.9.sp, color = TextSecondary)
        }

        // 正文与图片整块可点（进详情），避免误触底部操作区
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpen() },
        ) {
            if (p.text.isNotEmpty()) {
                Text(
                    p.text,
                    fontSize = 8.6.sp, color = TextPrimary,
                    maxLines = 5, overflow = TextOverflow.Ellipsis,
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(top = 5.2.dp),
                )
            }
            if (p.images.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 5.2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.3.dp),
                ) {
                    p.images.take(2).forEach { url ->
                        CoverImage(url, 53.3.dp, shape = RoundedCornerShape(6.9.dp))
                    }
                }
            }
        }

        Row(
            Modifier.padding(top = 3.4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pressable(onClick = onLike, modifier = Modifier.clip(RoundedCornerShape(50))) {
                Row(
                    // 放大热区：图标只有 11dp，手表上必须给足可点面积，否则「点了没反应」
                    Modifier.padding(horizontal = 6.9.dp, vertical = 5.2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (p.liked) NcmIcons.HeartFill else NcmIcons.Heart,
                        contentDescription = if (p.liked) "取消赞" else "点赞",
                        tint = if (p.liked) Accent else TextSecondary,
                        modifier = Modifier.size(11.2.dp),
                    )
                    if (p.likeCount > 0L) {
                        Spacer(Modifier.width(3.4.dp))
                        Text(
                            "${p.likeCount}",
                            fontSize = 6.9.sp,
                            color = if (p.liked) Accent else TextSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.width(3.4.dp))
            Pressable(onClick = onOpen, modifier = Modifier.clip(RoundedCornerShape(50))) {
                Row(
                    Modifier.padding(horizontal = 6.9.dp, vertical = 5.2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        NcmIcons.CommentIc, contentDescription = "评论",
                        tint = TextSecondary, modifier = Modifier.size(11.2.dp),
                    )
                    Spacer(Modifier.width(3.4.dp))
                    Text(
                        if (p.commentCount > 0L) "${p.commentCount}" else "评论",
                        fontSize = 6.9.sp, color = TextSecondary,
                    )
                }
            }
        }
    }
}

/** 相对时间：兼容秒级/毫秒级时间戳 */
private fun agoText(raw: Long): String {
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
