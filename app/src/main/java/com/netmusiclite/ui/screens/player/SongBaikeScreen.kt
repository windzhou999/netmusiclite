package com.netmusiclite.ui.screens.player

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.HistoryStore
import com.netmusiclite.data.ImageBlur
import com.netmusiclite.data.MediaSaver
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.Song
import com.netmusiclite.data.SongBaikeData
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.NcmListPage
import com.netmusiclite.ui.components.NcmToast
import com.netmusiclite.ui.components.SongCover
import com.netmusiclite.ui.components.StackedSongCard
import com.netmusiclite.ui.components.SwipeBackFader
import com.netmusiclite.ui.components.formatDate
import com.netmusiclite.ui.components.rememberAutoClearMessage
import com.netmusiclite.ui.components.songCoverModel
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.Separator
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌曲百科（播放页点歌名进入）：
 * ① 歌曲信息卡（2026-09-30 三合一）：歌曲标题/艺人 + 听歌数据 + 所属专辑合并为同一张卡，
 *    卡内附「专辑封面」入口（点击打开封面查看浮层）。
 * ② 听歌数据（2026-10-01 对齐官方百科页「回忆坐标」区块，见 [SongBaikeData] 的口径说明）：
 *    累计听过 / 第一次听优先取 `POST /api/song/play/about/block/page` 的服务端账号数据，
 *    缺失时依次回退听歌排行（/v1/play/record allData）与本机起播计数，并如实换标签名区分。
 * ③ 曲风 / 推荐标签 / 获奖成就（同接口「音乐百科」区块，creativeType = songTag/songBizTag/songAward）
 *    + 喜欢这首歌的人也爱听（simiSong，点击即播整列）。
 */
@Composable
fun SongBaikeScreen(nav: NavHostController, songId: Long) {
    var song by remember { mutableStateOf(Song(songId, "", "", 0, "", 0, 0, null)) }
    var baike by remember { mutableStateOf<SongBaikeData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showCover by remember { mutableStateOf(false) }

    LaunchedEffect(songId) {
        val cur = PlayerEngine.current?.takeIf { it.id == songId }
        if (cur != null) {
            song = cur
        } else {
            song = runCatching { NcmApi.songDetail(listOf(songId)) }.getOrDefault(emptyList())
                .firstOrNull() ?: song
        }
        baike = runCatching { NcmApi.songBaike(song, SessionStore.uid) }.getOrNull()
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        // 2026-10-01：删除「歌曲百科」标题字样，只保留卡片；
        // NcmListPage 内部已按 StackedCardList 同口径横向收窄，长列表滚到圆屏弧区也不会被裁。
        NcmListPage(horizontalInsetPx = 10) {
            // ① 三合一信息卡：标题/艺人 + 听歌数据 + 所属专辑（原三个独立卡片合并）
            item(key = "songinfo") {
                SongInfoCard(
                    song = song,
                    baike = baike,
                    onAlbum = {
                        nav.navigateWithMotion(
                            Routes.albumDetail(song.albumId),
                            NavMotionSpec(NavMotionKind.Pill),
                        )
                    },
                    onCover = {
                        showCover = true
                    },
                )
            }

            // 曲风 / 推荐标签 / 获奖成就（2026-10-01）
            // 均来自官方 `/api/song/play/about/block/page` 的「音乐百科」区块，
            // 对应 creativeType = songTag / songBizTag / songAward。
            // 旧实现读的 `/api/song/wiki/summary` 实测 **404 接口不存在**，
            // 所以「曲风」卡片在本次改动前从未真正显示过；「制作人员」在该接口里没有对应
            // creativeType（多样本实测只有 songTag/songBizTag/language/bpm/songAward/
            // entertainment/sheet/songComment），已随旧接口一并移除。
            val tags = baike?.tags.orEmpty()
            if (tags.isNotEmpty()) item(key = "tags") { TagCard("曲风", tags) }
            val bizTags = baike?.bizTags.orEmpty()
            if (bizTags.isNotEmpty()) item(key = "bizTags") { TagCard("推荐标签", bizTags) }
            val awards = baike?.awards.orEmpty()
            if (awards.isNotEmpty()) item(key = "awards") { TagCard("获奖成就", awards, perRow = 1) }

            item(key = "similar_head") {
                Text(
                    "喜欢这首歌的人也爱听",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            val similar = baike?.similar.orEmpty()
            if (similar.isEmpty() && !loading) {
                item(key = "similar_empty") { CenterHint("暂无相似推荐", Modifier.fillMaxWidth().height(120.dp)) }
            }
            itemsIndexed(similar, key = { _, it -> it.id }, contentType = { _, _ -> "song" }) { i, sim ->
                StackedSongCard(song = sim, onClick = {
                    PlayerEngine.playQueue(similar, i)
                    nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
                })
            }
        }

        // ② 封面查看浮层（点击卡内「专辑封面」/封面缩略图打开）
        AnimatedVisibility(
            visible = showCover,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160)),
        ) {
            CoverViewerOverlay(song = song, onDismiss = { showCover = false })
        }
    }
}

/**
 * 三合一歌曲信息卡（2026-09-30）：
 * 歌曲标题/艺人 → 听歌数据 → 所属专辑 → 专辑封面入口，同一张玻璃卡内以细分隔线分区。
 * 封面缩略图与「专辑封面」行都进封面查看浮层；专辑区整块可点进专辑详情。
 */
@Composable
private fun SongInfoCard(
    song: Song,
    baike: SongBaikeData?,
    onAlbum: () -> Unit,
    onCover: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(SurfaceGlass)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ① 歌曲标题 / 艺人（封面缩略图可点 → 封面查看）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCover,
                    ),
            ) {
                SongCover(song, 44.dp, shape = RoundedCornerShape(10.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title.ifEmpty { "加载中…" },
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(song.artist, fontSize = 9.sp, color = TextSecondary, maxLines = 1)
            }
        }

        // ② 听歌数据（2026-10-01 起对齐官方百科页「回忆坐标」区块）
        //
        // 官方口径（逆官方 9.5.90 + RN 包 rn-music-correlation 的 music-first-listen 组件实证）：
        //   累计听过 = resourceExt.musicTotalPlayDto.playCount（官方 UI 对 >999 截断显示「999+」）
        //   第一次听 = resourceExt.musicFirstListenDto 的 date/desc（服务端已排版好的字符串，直接显示）
        //   两块数据都来自 `POST /api/song/play/about/block/page`，
        //   **需登录且该账号对这首歌有收听记录**才会下发（未登录时服务端直接把 creatives 给空数组）。
        //
        // 优先级：官方回忆坐标 → 听歌排行 /v1/play/record allData（top1000 榜）→ 本机起播计数。
        // 三者口径彼此不同（账号累计 / 榜单累计 / 本机播放），因此用**不同标签名**如实区分，
        // 绝不互相冒充：官方数缺失时宁可不显示，也不拿本地计数顶上。
        DividerLine()
        Text(baike?.memoryTitle ?: "听歌数据", fontSize = 9.sp, color = TextSecondary)
        Spacer(Modifier.height(5.dp))
        val officialCount = baike?.listenCount
        val rankCount = baike?.playCount
        val local = HistoryStore.playCountOf(song.id)
        val officialFirst = baike?.firstListenDate
        val officialWhen = baike?.firstListenWhen
        val rankFirst = baike?.firstPlayMs
        when {
            officialCount != null -> InfoRow("累计听过", if (officialCount > 999) "999+ 次" else "$officialCount 次")
            rankCount != null -> InfoRow("累计听过", "$rankCount 次")
            local > 0 -> InfoRow("本机播放", "$local 次")
            else -> Unit
        }
        val firstLine = officialFirst ?: officialWhen
        val localFirst = HistoryStore.firstTsOf(song.id)
        when {
            firstLine != null -> InfoRow("第一次听", firstLine)
            rankFirst != null -> InfoRow("第一次听", formatDate(rankFirst))
            // 服务端完全没有首次记录时才退到本机，且换「本机首次」标签，绝不冒充官方「第一次听」
            localFirst != null -> InfoRow("本机首次", formatDate(localFirst))
            else -> Unit
        }
        // 官方原文照搬的补充文案（季节描述 / 累计听的说明），有则原样显示
        if (officialFirst != null && officialWhen != null) InfoNote(officialWhen)
        if (officialCount != null) baike?.listenHint?.let { InfoNote(it) }
        if (officialCount == null && rankCount == null && local <= 0 && firstLine == null &&
            rankFirst == null && localFirst == null
        ) {
            InfoRow("暂无收听记录", "多听听就有了")
        }

        // ③ 所属专辑（可点进专辑详情）
        if (song.albumId > 0) {
            DividerLine()
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAlbum,
                    ),
            ) {
                Text("所属专辑", fontSize = 9.sp, color = TextSecondary)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        song.album.ifEmpty { "-" },
                        fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(NcmIcons.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                }
                val pt = baike?.publishTime
                if (pt != null) InfoRow("发行时间", formatDate(pt))
            }
        }

        // ④ 专辑封面入口（打开封面查看浮层）
        DividerLine()
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCover,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NcmIcons.ImageIc, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text("专辑封面", fontSize = 11.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            Text("查看", fontSize = 10.sp, color = Accent)
            Icon(NcmIcons.ChevronRight, null, tint = Accent, modifier = Modifier.size(14.dp))
        }
    }
}

/** 卡片内分区细线 */
@Composable
private fun DividerLine() {
    Spacer(Modifier.height(11.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Separator))
    Spacer(Modifier.height(11.dp))
}

/**
 * 封面查看浮层（2026-09-30）：
 * - 中部正方形区域 = 清晰专辑封面（圆角 + 淡描边）
 * - 四周边缘 = 该封面的高斯模糊底（预模糊位图铺满）+ 压暗 + 中心径向柔光（Accent 微光，
 *   设备 API 30 无 RenderEffect，模糊走 ImageBlur 预计算）
 * - 底部 = 「保存到本地」按钮（Download 图标），经 MediaSaver 写入系统下载文件夹
 * - 右上关闭按钮 / 点背景空白处关闭；右上横向拖拽被本层消费，避免误触发页面右滑返回
 */
@Composable
private fun CoverViewerOverlay(song: Song, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 右滑退出（2026-09-30 二代）：阈值 40dp —— 与全局返回同一手感但略宽，查看封面时不易误退
    val backThreshold = with(LocalDensity.current) { 40.dp.toPx() }
    val backFader = remember(backThreshold) { SwipeBackFader(backThreshold, scope) }
    var srcBmp by remember { mutableStateOf<Bitmap?>(null) }
    var blurBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var glowColor by remember { mutableStateOf(Color.White) }
    var saving by remember { mutableStateOf(false) }
    val (toast, showToast) = rememberAutoClearMessage()

    LaunchedEffect(song.id, song.coverUrl) {
        val model = songCoverModel(song) ?: return@LaunchedEffect
        // 原图（保存用，1080 档足够；allowHardware(false) 才能取像素做模糊）
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val req = coil.request.ImageRequest.Builder(ctx)
                    .data(model)
                    .size(1080)
                    .allowHardware(false)
                    .build()
                (coil.Coil.imageLoader(ctx).execute(req).drawable as? BitmapDrawable)?.bitmap
            }.getOrNull()
        } ?: return@LaunchedEffect
        srcBmp = bmp
        // 边缘模糊：先缩到 220px 再盒式模糊（W5 上几十毫秒级），放大铺满由 GPU 缩放
        val (blur, glow) = withContext(Dispatchers.Default) {
            val bl = runCatching { ImageBlur.blurScaled(bmp, side = 220, radius = 7, passes = 2) }.getOrNull()
            bl to runCatching { ImageBlur.dominantColor(bmp) }.getOrDefault(Color.White)
        }
        blurBmp = blur?.asImageBitmap()
        glowColor = glow
    }

    fun save() {
        if (saving) return
        val bmp = srcBmp
        if (bmp == null) {
            showToast("封面还没加载好")
            return
        }
        scope.launch {
            saving = true
            val ok = withContext(Dispatchers.IO) {
                MediaSaver.saveCover(ctx, bmp, song.title, song.artist)
            }
            saving = false
            showToast(if (ok) "已保存到「下载」文件夹" else "保存失败，请重试")
        }
    }

    // 2026-10-01 修「打开封面查看时周围模糊区先黑一下」：
    //   旧版根背景是纯黑 Color.Black，而模糊底 blurBmp 是异步解码+盒式模糊（1080 解码在手表上
    //   要数百毫秒），这段时间整屏只有纯黑，模糊图到位后又硬切出来 —— 观感就是闪一下黑。
    //   现在：① 底色改用「歌曲环境色」（SongAmbient 由全局背景层按当前歌曲预先算好，
    //   进入本页时已就绪，立即可见且带该曲色调）② 模糊底图就绪后按 alpha 淡入，不再硬切。
    val blurBgAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (blurBmp != null) 1f else 0f,
        animationSpec = tween(260),
        label = "coverBgFade",
    )
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // 右滑跟手：与全局返回同一手感（2026-10-01 由右移 35% 改为跟手缩放）
                alpha = backFader.alpha
                translationX = backFader.slidePx
                scaleX = backFader.scale
                scaleY = backFader.scale
            }
            .background(com.netmusiclite.ui.components.SongAmbient.color)
            .pointerInput(Unit) {
                // 右滑退出浮层（回到歌曲百科）：跟手右移+淡出，过阈值松手即关。
                // 同时该手势消费横向拖拽 —— 浮层打开期间不会触到页面级右滑返回（子级先消费饿死父级）
                detectHorizontalDragGestures(
                    onDragStart = { backFader.onDragStart() },
                    onDragEnd = {
                        val out = backFader.passedThreshold
                        backFader.onDragEnd(out)
                        if (out) onDismiss()
                    },
                    onDragCancel = { backFader.onDragCancel() },
                ) { change, amount ->
                    change.consume()
                    backFader.onDrag(change, amount)
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        // 边缘区：封面高斯模糊铺满 + 压暗（保证中部封面与按钮可读）
        // 模糊底按 blurBgAlpha 淡入（见上方说明），避免「纯黑 → 模糊」硬切
        blurBmp?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = blurBgAlpha },
            )
        }
        // 压暗层（保证中部封面与按钮可读）—— ★ 2026-10-01 浅色主题反向：
        //   本层底色已随主题翻转为浅色环境色，再叠黑 34% 会让 TextPrimary（浅色主题下是深字）
        //   压在暗底上不可读，改叠白让模糊封面向浅色主题淡化
        Box(
            Modifier.fillMaxSize().background(
                if (com.netmusiclite.ui.theme.isLightTheme) Color.White.copy(alpha = 0.62f)
                else Color.Black.copy(alpha = 0.34f)
            )
        )

        // 发光（2026-09-30 二代：白主题下 Accent 光晕不可见 → 改封面自身色发光）：
        // ① 模糊封面以 Screen 混合叠在中心区 = 自然柔光晕 ② 封面主色径向光 ③ 贴封面白微光
        Canvas(Modifier.fillMaxSize()) {
            blurBmp?.let { bmp ->
                val d = 320.dp.toPx()
                drawImage(
                    image = bmp,
                    srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        ((size.width - d) / 2f).toInt(),
                        ((size.height - d) / 2f).toInt() - 20.dp.toPx().toInt(), // 与封面 offset(-20dp) 对齐
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(d.toInt(), d.toInt()),
                    alpha = 0.58f,
                    blendMode = BlendMode.Screen,
                )
            }
            val outerR = size.minDimension * 0.62f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor.copy(alpha = 0.42f), Color.Transparent),
                    center = center, radius = outerR,
                ),
                radius = outerR, center = center,
            )
            val innerR = size.minDimension * 0.30f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = center, radius = innerR,
                ),
                radius = innerR, center = center,
            )
        }

        // 中部：正方形清晰封面（其下垫一圈淡光提升「浮起」感）
        // 2026-09-30 二代：封面上移到 -20dp 并缩到 134dp —— 给底部保存按钮留出间距（两者不再重叠）
        Box(
            Modifier.align(Alignment.Center).offset(y = (-20).dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(170.dp)) {
                val r = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        center = center, radius = r,
                    ),
                    radius = r, center = center,
                )
            }
            val shape = RoundedCornerShape(14.dp)
            if (srcBmp != null) {
                Image(
                    bitmap = srcBmp!!.asImageBitmap(),
                    contentDescription = "专辑封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(134.dp)
                        .clip(shape)
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), shape),
                )
            } else {
                SongCover(song, 134.dp, shape = shape)
            }
        }

        // 保存到本地（底部主操作，SVG 下载图标 + 文案）
        // 2026-09-30 二代：整体缩小（图标 13dp / 字号 11sp / 内距 12×6dp）并下移贴近屏底，
        // 与 134dp 封面之间留出 ≥20dp 空隙
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(50))
                .background(Accent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { save() },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NcmIcons.Download, null, tint = OnAccent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (saving) "保存中…" else "保存到本地",
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OnAccent,
            )
        }

        // 右滑退出提示（首屏淡出，仅提示一次观感；无此提示用户不知道手势）
        NcmToast(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp))
    }
}

/** 百科信息卡：无固定高的玻璃卡（标题 + 内容行；整卡可点击，如专辑跳转） */
@Composable
private fun InfoCard(
    title: String,
    clickable: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceGlass)
            .clickable(
                enabled = clickable,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, fontSize = 9.sp, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 11.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 标签胶囊卡：曲风 / 推荐标签 / 获奖成就共用（数据来自百科区块的 songTag / songBizTag / songAward）。
 * [perRow] 控制每行胶囊数——获奖名称较长（如「第2届hito流行音乐奖」）时传 1 让它整行铺。
 */
@Composable
private fun TagCard(title: String, tags: List<String>, perRow: Int = 3) {
    InfoCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.chunked(perRow.coerceAtLeast(1)).forEach { rowTags ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTags.forEach { tag ->
                        Text(
                            tag, fontSize = 10.sp, color = Accent, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Accent.copy(alpha = 0.14f))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 卡片内整行小字附注：官方原文照搬的说明文案（无 label 列，直接铺一行） */
@Composable
private fun InfoNote(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = TextSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
    )
}
