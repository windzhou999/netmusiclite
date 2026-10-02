package com.ncm.watch.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.ncm.watch.data.ArtistItem
import com.ncm.watch.data.NcmApi
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.ui.components.CircleIconButton
import com.ncm.watch.ui.components.EdgeProgressRing
import com.ncm.watch.ui.components.NcmIcons
import com.ncm.watch.ui.components.Pressable
import com.ncm.watch.ui.components.RotatingCover
import com.ncm.watch.ui.components.SwipeBackBox
import com.ncm.watch.ui.components.SwipeBackFader
import com.ncm.watch.ui.components.ambientSurfaceColor
import com.ncm.watch.ui.components.frostedGlass
import com.ncm.watch.ui.components.rotaryCustom
import com.ncm.watch.ui.nav.NavMotionKind
import com.ncm.watch.ui.nav.NavMotionSpec
import com.ncm.watch.ui.nav.Routes
import com.ncm.watch.ui.nav.TransitionCoordinator
import com.ncm.watch.ui.nav.navigateWithMotion
import com.ncm.watch.ui.theme.Accent
import com.ncm.watch.ui.theme.Bg
import com.ncm.watch.ui.theme.SurfaceGlass
import com.ncm.watch.ui.theme.screenBg
import com.ncm.watch.ui.theme.TextPrimary
import com.ncm.watch.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 播放页（核心）：
 * - 顶部：歌名跑马灯（过长滚动），下方艺人
 * - 中部：上一首 / 暂停(大、镂空、内嵌旋转封面) / 下一首
 * - 底部沿弧：喜欢 / 声音(唤出系统音量面板) / 更多(三点)
 * - 右缘进度弧（官方样式 53° 弧，弧带纵向拖拽 seek，防误触）
 * - 左划 → 歌词页；右划 → 栈底时切卡片主页（应用入口为播放页），否则返回上一页
 */
@Composable
fun PlayerScreen(nav: NavHostController) {
    val song = PlayerEngine.current
    val playing = PlayerEngine.isPlaying
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // 右滑返回淡出引擎（跟手右移+非线性淡出+速度加成，无震动）
    // 返回行程 34dp（2026-09-13 二次下调 40%：70dp → 56dp → 34dp）
    val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }
    val backFader = remember(swipePx) { SwipeBackFader(swipePx, scope) }
    // 滑出目标 = 屏宽（松手速度接续滑出用）
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    var swipeLifted by remember { mutableStateOf(false) } // 右滑期间置顶，返回时下层接场无空白帧
    var showArtistPicker by remember { mutableStateOf(false) }
    var artistOptions by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }

    // 表冠唤出音量面板（2026-09-25）：误触宽量——400ms 窗口内累计增量 ≥96px（约 1.5 格
    // 刻度）才触发，单格蹭动/口袋误转不弹面板；触发后 1s 冷却防连发。面板弹出后继续转
    // 冠由系统音量面板接管调节
    var volAccum by remember { mutableStateOf(0f) }
    var volAccumAt by remember { mutableStateOf(0L) }
    var volFiredAt by remember { mutableStateOf(0L) }
    fun onCrownForVolume(delta: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - volAccumAt > 400L) volAccum = 0f // 停转超时重新累计
        volAccumAt = now
        if (now - volFiredAt < 1000L) return // 冷却期内吞掉
        volAccum += delta
        if (abs(volAccum) >= 96f) {
            volAccum = 0f
            volFiredAt = now
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_SAME,
                android.media.AudioManager.FLAG_SHOW_UI,
            )
        }
    }

    // 点艺人名：实时拉歌曲的 ar 数组 → 单艺人直达主页，多艺人弹胶囊选择列表
    fun openArtistPicker() {
        val s = song ?: return
        if (s.id <= 0) return  // 本地歌曲无艺人主页
        scope.launch {
            val list = runCatching { NcmApi.songArtists(s.id) }.getOrNull().orEmpty()
                .ifEmpty { if (s.artistId > 0) listOf(ArtistItem(s.artistId, s.artist, null)) else emptyList() }
            when {
                list.isEmpty() -> Unit
                list.size == 1 -> nav.navigateWithMotion(
                    Routes.artistDetail(list[0].id), NavMotionSpec(NavMotionKind.Pill),
                )
                else -> {
                    artistOptions = list
                    showArtistPicker = true
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ambientSurfaceColor())
            .zIndex(if (swipeLifted) 2f else 0f)
            // 右滑返回跟手缩放+非线性淡出（速度感应、无震动；2026-10-01 由跟手右移改为缩放）
            .graphicsLayer {
                alpha = backFader.alpha
                translationX = backFader.slidePx
                scaleX = backFader.scale
                scaleY = backFader.scale
            }
            .pointerInput(Unit) {
                val px = 70.dp.toPx()
                var leftX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { leftX = 0f; backFader.onDragStart(); swipeLifted = true },
                    onDragEnd = {
                        val back = backFader.passedThreshold
                        backFader.onDragEnd(back)
                        if (back) {
                            // 右划：入口播放页（栈底）→ 切卡片主页；非入口 → 返回上一页
                            if (nav.previousBackStackEntry == null) {
                                // ★ 2026-10-01：栈底没有上一页可 pop，只能前进导航到主页。
                                //   但前进导航默认走 enter/exit 转场 —— 播放页会被 NavHost
                                //   按「前进」方向往左滑出，而手指刚把它拖到右边，观感就是
                                //   「往左飘」（只在冷启动后第一次发生，因为只有这时它在栈底）。
                                //   现在武装手势旗标：NavHost 对它只做淡出、主页从左侧滑入，
                                //   页面继续跟着 fader 向右滑出场 —— 和真正的手势返回一致。
                                val ms = backFader.flingOutDuration(screenPx)
                                TransitionCoordinator.withGesturePop(ms) {
                                    nav.navigateWithMotion(Routes.HOME, NavMotionSpec(NavMotionKind.Slide))
                                }
                                backFader.flingOut(screenPx, ms)
                            } else {
                                // 松手速度接续：fader 线性滑出全屏，转场时长同步武装给 NavHost
                                val ms = backFader.flingOutDuration(screenPx)
                                TransitionCoordinator.withGesturePop(ms) { nav.popBackStack() }
                                backFader.flingOut(screenPx, ms)
                            }
                        } else {
                            if (leftX > px) {
                                // 左划进歌词：滑动来源 → 保留滑动转场
                                nav.navigateWithMotion(Routes.LYRICS, NavMotionSpec(NavMotionKind.Slide))
                            }
                            swipeLifted = false
                        }
                    },
                    onDragCancel = { backFader.onDragCancel(); swipeLifted = false },
                ) { change, amount ->
                    change.consume()
                    backFader.onDrag(change, amount) // 左移增量引擎内部清零并淡回
                    if (amount < 0) leftX += -amount else leftX = 0f
                }
            }
            .pointerInput(Unit) {
                var totalY = 0f
                // 下滑进评论页；右缘弧带内的纵向拖拽被弧 seek 消费，不会误触
                detectVerticalDragGestures(
                    onDragStart = { totalY = 0f },
                    onDragEnd = {
                        val cur = PlayerEngine.current
                        if (totalY > 70.dp.toPx() && cur != null) {
                            // 下滑进评论：滑动来源 → 保留滑动转场
                            nav.navigateWithMotion(
                                Routes.comments(cur.id), NavMotionSpec(NavMotionKind.Slide),
                            )
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    totalY += amount
                }
            }
            // 表冠：累计超过误触宽量唤出系统音量面板（含通用滚轮通道）
            .rotaryCustom(onDelta = { onCrownForVolume(it) }),
    ) {
        // 右缘进度弧（绘制期读进度：500ms tick 只重绘弧线，本页不重组）
        EdgeProgressRing(
            progress = {
                val d = PlayerEngine.durationMs.coerceAtLeast(1L)
                (PlayerEngine.positionMs.toFloat() / d).coerceIn(0f, 1f)
            },
            onSeek = if (song == null) null else { p ->
                PlayerEngine.seekTo((p * PlayerEngine.durationMs).toLong())
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 睡眠定时倒计时（右上角小字，定时关闭后自动消失）
        if (com.ncm.watch.data.SleepTimer.mode != com.ncm.watch.data.SleepMode.OFF) {
            com.ncm.watch.ui.screens.player.SleepCountdownLabel(
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 16.dp),
            )
        }

        // 标题区（顶部固定；2026-09-06 上移 20px）
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(100.dp), contentAlignment = Alignment.Center) {
                Text(
                    song?.title ?: "未在播放",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .basicMarquee()
                        .then(
                            if (song != null) Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                nav.navigateWithMotion(
                                    Routes.songBaikePage(song.id), NavMotionSpec(NavMotionKind.Pill),
                                )
                            }
                            else Modifier
                        ),
                )
            }
            Spacer(Modifier.height(2.dp))
            // 艺人名可点：单艺人直达艺人主页，多艺人弹胶囊列表（见 ArtistPickerOverlay）
            Text(
                song?.artist ?: "点任意歌曲开始",
                fontSize = 9.sp, color = TextSecondary, maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 60.dp)
                    .then(
                        if (song != null) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { openArtistPicker() }
                        else Modifier
                    ),
            )
            val err = PlayerEngine.errorMsg
            if (err != null) {
                Spacer(Modifier.height(4.dp))
                Text(err, fontSize = 9.sp, color = Accent, textAlign = TextAlign.Center)
            }
            // 「正在获取播放地址…」提示已移除（2026-09-25）：切歌静默加载，不打扰
        }

        // 三键区（固定偏移，位于中上部，给弧形底栏让位）
        // 2026-09-13 对齐参考样式：上一首/下一首为裸图标（无玻璃圆底），中央暂停键细圈+暗化封面；
        // 位置按用户口径：整体下移 20px（=10dp），左右两键各向外 25px（=12.5dp）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 88.dp),
        ) {
            Pressable(onClick = { PlayerEngine.prev() }) {
                Icon(NcmIcons.Prev, contentDescription = "上一首", tint = TextPrimary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(22.5.dp))
            PauseButton(playing = playing, song = song) { PlayerEngine.toggle() }
            Spacer(Modifier.width(22.5.dp))
            Pressable(onClick = { PlayerEngine.next() }) {
                Icon(NcmIcons.Next, contentDescription = "下一首", tint = TextPrimary, modifier = Modifier.size(30.dp))
            }
        }

        // 底部弧形三按钮
        ArcBottomBar(
            liked = song != null && PlayerEngine.isLiked(song.id),
            onLike = { song?.let { PlayerEngine.toggleLike(it) } },
            onVolume = {
                // 唤出系统音量调节面板（STREAM_MUSIC，行为/表冠/滑块全部跟系统走）
                val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                am.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.ADJUST_SAME,
                    android.media.AudioManager.FLAG_SHOW_UI,
                )
            },
            onMore = { nav.navigateWithMotion(Routes.MORE) },
        )

        // 多艺人选择浮层（压暗背景；右滑或点背景退回播放页）
        if (showArtistPicker) {
            ArtistPickerOverlay(
                artists = artistOptions,
                onPick = { id ->
                    showArtistPicker = false
                    nav.navigateWithMotion(
                        Routes.artistDetail(id), NavMotionSpec(NavMotionKind.Pill),
                    )
                },
                onDismiss = { showArtistPicker = false },
            )
        }
    }
}

/**
 * 多艺人选择浮层：压暗背景 + 玻璃胶囊纵向排列，点胶囊进对应艺人主页；
 * 右滑（SwipeBackBox 阈值）或点背景任意处退回播放页。
 * 纵向拖拽在此层被吞掉，不会穿透触发播放页的下滑进评论页手势。
 */
@Composable
private fun ArtistPickerOverlay(
    artists: List<ArtistItem>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(
                alpha = if (com.ncm.watch.ui.theme.isLightTheme) 0.32f else 0.55f
            ))
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ -> change.consume() }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        SwipeBackBox(onBack = onDismiss) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
            ) {
                Text("查看艺人主页", fontSize = 9.sp, color = TextSecondary)
                artists.forEach { a ->
                    Text(
                        a.name,
                        fontSize = 12.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            // 跟随卡片材质（玻璃 / 黑色半透明）
                            .frostedGlass(RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onPick(a.id) }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** 大暂停键：镂空圆环 + 内嵌旋转封面（暂停时覆盖播放键） */
@Composable
private fun PauseButton(playing: Boolean, song: com.ncm.watch.data.Song?, onClick: () -> Unit) {
    Pressable(onClick = onClick) {
        // 外圈收紧贴合封面：68dp 圈(3dp 环) 内缘 62dp = 封面直径，无缝隙
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            RotatingCover(song, 62.dp, playing)
            // 暂停遮罩只在「真暂停」时出现：切歌加载间隙（loading=true）不闪暂停图标
            if (!playing && !PlayerEngine.loading) {
                // 对齐参考样式：暂停时封面暗化，白播放键浮于其上
                Box(
                    Modifier.size(62.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(NcmIcons.Play, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(27.dp))
                }
            }
        }
    }
}

/**
 * 底部弧形操作区：一条贴合屏幕弧度的弯曲胶囊整体包裹 喜欢/音量/更多 三个按钮。
 * 按钮只留图标悬浮（无各自玻璃圆底，避免与小胶囊互相重叠），仅保留这一条外部大胶囊。
 * 面积约为上一版的 71%（缩小 29%≈30%）：带高 40dp，带中心半径 88dp，弧段 90°±30°，圆头。
 *
 * W5 流畅度（2026-09-30）：Canvas 条带化（同 EdgeProgressRing 方案）——旧版 fillMaxSize 的
 * 全屏 RenderNode 即使只画一条弧，合成/重录也按整屏算（实测播放页静止帧 GPU 中位 12ms）。
 * 收缩到弧带包围盒 200×112dp（466px/2x 屏 ≈ 全屏 41%，实际绘制像素更少），扫渐变光栅化面积随之缩小。
 * 弧带 y 范围（相对圆心向下）：端帽顶 88·cos60°−20 = 24dp → 底点 88+20 = 108dp。
 */
@Composable
private fun ArcBottomBar(
    liked: Boolean,
    onLike: () -> Unit,
    onVolume: () -> Unit,
    onMore: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cx = maxWidth / 2
        val cyc = maxHeight / 2
        val rBand = 88.dp

        // ★ 2026-10-01：弧形胶囊**跟随设置页的「玻璃卡片 / 黑色半透明卡片」**。
        //   玻璃 = 白 10%（原来固定的那档，透出模糊底的观感）；
        //   黑色半透明 = 黑 45%（与 frostedGlass() 黑分支取同一档，全站观感一致）。
        //   注意判定条件与 frostedGlass 完全对齐：没背景图时玻璃本来就不成立，一律走黑。
        val glassCard = com.ncm.watch.data.BackgroundStore.ready &&
            com.ncm.watch.data.BackgroundStore.cardGlass
        val light = com.ncm.watch.ui.theme.isLightTheme
        val arcBandColor: Color = when {
            // ★ 2026-10-01 浅色主题：弧带底在浅色页面上必须是浅色，
            //   取白 55% 与 frostedGlass() 的浅色分支同一档
            light -> Color.White.copy(alpha = 0.55f)
            glassCard -> Color.White.copy(alpha = 0.10f)
            else -> Color.Black.copy(alpha = 0.45f)
        }

        // 弧带局部 Canvas：宽 200dp 高 112dp，Canvas 顶 = 圆心（局部圆心 (100dp, 0)）。
        // 弧段 60°~120° 全在圆心下方：端帽顶圆心下 24dp、底点 108dp（+cap/余量 110dp），
        // 实际绘制像素 x ∈ [4, 196]、y ∈ [24, 110] 全在界内 —— Canvas 外的圆部分不产生任何像素。
        // （旧版 fillMaxSize 的全屏 RenderNode 即使只画一条弧，合成/重录也按整屏算）
        val bandW = 200.dp
        val bandH = 112.dp
        val bandCx = 100.dp
        val bandCy = 0.dp
        Canvas(
            Modifier
                .offset {
                    // Canvas 左上 = (圆心x − bandW/2, 圆心y + bandCy)：圆心 = 屏幕中心（与旧全屏版完全同位）
                    IntOffset(
                        ((maxWidth / 2) - bandW / 2).roundToPx(),
                        ((maxHeight / 2) + bandCy).roundToPx(),
                    )
                }
                .width(bandW)
                .height(bandH),
        ) {
            val stroke = 40.dp.toPx()
            val r = rBand.toPx()
            val cX = bandCx.toPx()
            val cY = bandCy.toPx()
            val tl = Offset(cX - r, cY - r)
            fun taperedArc(radiusPx: Float, widthPx: Float, startDeg: Float, sweepDeg: Float, fadeDeg: Float, color: Color) {
                val arcTopLeft = Offset(cX - radiusPx, cY - radiusPx)
                val center = Offset(cX, cY)
                val tip0 = startDeg / 360f
                val full0 = (startDeg + fadeDeg) / 360f
                val full1 = (startDeg + sweepDeg - fadeDeg) / 360f
                val tip1 = (startDeg + sweepDeg) / 360f
                val brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    tip0 to Color.Transparent,
                    full0 to color,
                    full1 to color,
                    tip1 to Color.Transparent,
                    1f to Color.Transparent,
                    center = center,
                )
                drawArc(
                    brush = brush, startAngle = startDeg, sweepAngle = sweepDeg, useCenter = false,
                    topLeft = arcTopLeft, size = Size(radiusPx * 2, radiusPx * 2),
                    style = Stroke(widthPx, cap = StrokeCap.Butt),
                )
            }
            // 主体弧带：圆头胶囊（90°±30°）—— 颜色跟随卡片材质（见上方 arcBandColor）
            drawArc(
                color = arcBandColor,
                startAngle = 60f, sweepAngle = 60f, useCenter = false,
                topLeft = tl, size = Size(r * 2, r * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            // 胶囊上缘（内缘）高光：平头+两端渐隐，端头不再出现小台阶。
            // 只在玻璃模式下画 —— 黑色卡片上再压一道白色高光会立刻露馅。
            if (glassCard) {
                val rInner = r - stroke / 2 - 0.75.dp.toPx()
                taperedArc(rInner, 1.5.dp.toPx(), 58f, 64f, 10f, Color.White.copy(alpha = 0.10f))
            }
        }

        val angles = listOf(-23f, 0f, 23f)
        val icons = listOf(
            if (liked) NcmIcons.HeartFill else NcmIcons.Heart,
            NcmIcons.Volume,
            NcmIcons.MoreDots,
        )
        val actions = listOf(onLike, onVolume, onMore)
        angles.forEachIndexed { i, deg ->
            val rad = Math.toRadians(deg.toDouble())
            val x = cx + rBand * sin(rad).toFloat()
            val y = cyc + rBand * cos(rad).toFloat()
            CircleIconButton(
                icons[i], "操作", actions[i],
                modifier = Modifier.offset {
                    IntOffset((x - 26.dp).roundToPx(), (y - 26.dp).roundToPx())
                },
                size = 52.dp, iconSize = 20.dp,
                container = Color.Transparent,
                tint = if (icons[i] == NcmIcons.HeartFill) Accent else TextPrimary,
                navMotionKind = if (i == 2) NavMotionKind.Circle else null,
            )
        }
    }
}
