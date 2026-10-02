package com.netmusiclite.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.netmusiclite.data.Song
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.screenBg
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.SurfaceStrong
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.TextTertiary
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.TransitionCoordinator

/**
 * ColorOS 手表设置页风格的「卡片堆叠」列表：
 * - 胶囊圆角卡片（30dp 圆角 / 62dp 高 / 8dp 间距）通宽铺满（左右出血到圆屏边缘）
 * - contentPadding 上下留 (视口高 - 卡高)/2，首尾卡片也能滚动到正中
 * - rememberSnapFlingBehavior：滚动停止时中央卡片吸附居中，
 *   上/下相邻卡片在圆屏边缘被自然裁切只露出局部（peek 效果）
 * - 表冠滚动（rotaryList）：转冠平滑滚动（2026-09-04 真机要求取消表冠自动吸附，

 */

val STACKED_VIEWPORT = 233.dp

/**
 * 边缘回弹动画：**固定 320ms 的过冲收束**。
 *
 * ⚠ 不要换成弹簧。最初用的是 `spring(dampingRatio = 0.40f, stiffness = 320f)`：
 *   ω = √320 ≈ 18 rad/s，衰减包络 e^(−ζωt) 要 0.6~0.7 秒才收敛 ——
 *   用户真机反馈「滑动完成不是立刻回弹而是延时一秒左右才回弹」，
 *   **那不是触发延迟，是低阻尼弹簧本身在慢慢晃**。
 *   tween 的时长硬性封顶在 320ms；BackOut 缓动同样给到「冲过原位一点点再收回」的回弹观感。
 */
private val EDGE_REBOUND = tween<Float>(320, easing = CubicBezierEasing(0.30f, 1.42f, 0.52f, 1f))

/**
 * 「边缘下沉」效果：以屏幕横向中线为准，卡片中心离中线越远越下沉——
 * 缩小（最多 -12%）+ 变暗（alpha 最低 0.64）+ 层级压低（translationZ 最低 0），
 * 中央卡片浮起突出。smoothstep 缓动；graphicsLayer 延迟读取位置，滚动中无重组开销。
 */
@Composable
fun Modifier.sinkFromCenter(): Modifier {
    val density = LocalDensity.current
    val half = with(density) { (STACKED_VIEWPORT / 2).toPx() }
    var centerY by remember { mutableFloatStateOf(half) }
    return this
        .onGloballyPositioned { ic ->
            centerY = ic.positionInRoot().y + ic.size.height / 2f
        }
        .graphicsLayer {
            val t = kotlin.math.abs((centerY - half) / half).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            val sc = 1f - 0.12f * eased
            scaleX = sc
            scaleY = sc
            alpha = 1f - 0.36f * eased
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StackedCardList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    title: String? = null,
    titleSubtitle: String? = null,
    cardHeight: Dp = 62.dp,
    cardSpacing: Dp = 8.dp,
    viewportHeight: Dp = STACKED_VIEWPORT,
    progressTotal: Int = -1,
    progressOverride: Float? = null,
    /** 卡片左右各收窄的像素（圆屏边缘出血微调；按用户口径直译 px，内部转 dp） */
    horizontalInsetPx: Int = 0,
    content: LazyListScope.() -> Unit,
) {
    // 上下各留 (视口 - 卡高)/2：首尾卡片也能滚动到正中
    val centerPad = (viewportHeight - cardHeight) / 2
    val insetDp = with(LocalDensity.current) { horizontalInsetPx.toFloat().toDp() }
    // 2026-10-01：标题胶囊整体缩小 10% + 上移 10 像素（用户口径为物理像素，按 density 折算）
    val titleLiftDp = with(LocalDensity.current) { 10f.toDp() }
    // 卡片横向宽度：收窄 10% 后按用户要求加宽 6% → 净宽 95.4% 屏宽（每边收窄 2.3%）
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val widthShrink = (screenWidthDp * 0.023f).dp

    // 零分配进度估算：只用 snapshot int（index+offset），滚动中不产生 layoutInfo 分配，避免 GC 抖动
    val density2 = LocalDensity.current
    val pitchPx = with(density2) { (cardHeight + cardSpacing).toPx() }
    val progress by remember(state, progressTotal, pitchPx) {
        derivedStateOf {
            if (progressTotal <= 1) 0.15f
            else {
                val idx = state.firstVisibleItemIndex +
                    (state.firstVisibleItemScrollOffset / pitchPx).coerceAtMost(1f)
                ((idx - 0.5f) / (progressTotal - 1)).coerceIn(0.15f, 1f)
            }
        }
    }

    // ★ 2026-10-01：列表**顶端/底端双向**非线性回弹（用户要求「列表滑到最下方加非线性回弹」，
    //   试机后补充「顶端也要」）。滚到头后继续同向拖拽，内容按橡胶带阻尼继续位移
    //   （越拉越沉，非线性渐近上限），松手用带过冲的弹簧弹回。
    //   真机录屏里轻舟的列表只有单调减速停止、**没有**触底回弹，所以这是按用户要求新增的效果。
    //
    //   符号约定：sinkPx > 0 = 内容被向下拉（顶端越界）；< 0 = 内容被向上推（底端越界）。
    //   【2026-10-01 二次修正】松手回弹原来只挂在 onPostFling 上，而 onPostFling 是嵌套滚动的
    //   **post** 阶段、必须等 LazyColumn 自己的 fling 动画彻底跑完才回调。
    //   【2026-10-01 三次修正】真机仍报「用力滑到最顶/最底，回弹迟一秒」——根因不在触发时机，
    //   而在 onPostScroll 撞边时把整段越界量 `return Offset(0f, available.y)` 标成已消费：
    //   LazyColumn 内建 fling 的默认实现只要发现「本帧 delta 没被消费完」就立刻取消动画，
    //   我们把账全认了，它就以为还没到边，惯性继续按 spline 衰减空跑约 1 秒 —— 下沉早已
    //   卡在极限不动，onPostFling / isScrollInProgress / 去抖计时器全在等它跑完。
    //   现在位移照常写进 sinkPx、但返回 Offset.Zero（不再「认账」）：fling 撞边当帧即被取消，
    //   onPostFling 同帧回调，回弹当帧起跳；原先 70ms 去抖计时器随之失去存在意义，一并移除。
    val bounceMaxPx = with(LocalDensity.current) { 26.dp.toPx() }
    val sinkPx = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var reboundJob by remember { mutableStateOf<Job?>(null) }
    /** 手指是否仍按在屏幕上（由下面 pointerInput 维护） */
    val fingerDown = remember { mutableStateOf(false) }

    /**
     * 立即回弹（所有触发路径统一走这里，收敛到唯一一个 animator）：
     * 取消上一段回弹后从当前值重新起跳，重复调用收敛到 0，无害。
     */
    val rebound: () -> Unit = {
        reboundJob?.cancel()
        if (sinkPx.floatValue != 0f) {
            reboundJob = scope.launch {
                animate(
                    initialValue = sinkPx.floatValue,
                    targetValue = 0f,
                    animationSpec = EDGE_REBOUND,
                ) { v, _ -> sinkPx.floatValue = v }
            }
        }
    }

    val edgeBounce = remember(state, bounceMaxPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val cur = sinkPx.floatValue
                // 阻尼：跟手系数随已越界量线性衰减到 0 ⇒ 位移呈 1-exp 型渐近饱和
                val grip = 0.62f * (1f - abs(cur) / bounceMaxPx)
                // 底端：手指继续上推（available.y < 0）且已无内容可滚
                if (available.y < 0f && !state.canScrollForward) {
                    sinkPx.floatValue = (cur + available.y * grip).coerceIn(-bounceMaxPx, 0f)
                    // 惯性撞边（手指已离开）→ 当帧即弹（fling 也已在返回 Zero 后被取消）
                    if (!fingerDown.value) rebound()
                    // ★ 关键：不认账（返回 Zero）。认了整段账，LazyColumn 的 fling 就以为
                    //   还没到边、继续空跑约 1 秒 —— 这正是「回弹迟一秒」的根因。
                    return Offset.Zero
                }
                // 顶端：手指继续下拉（available.y > 0）且已在列表最前
                if (available.y > 0f && !state.canScrollBackward) {
                    sinkPx.floatValue = (cur + available.y * grip).coerceIn(0f, bounceMaxPx)
                    if (!fingerDown.value) rebound()
                    return Offset.Zero
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // fling 撞边被取消后同帧回调到这里，回弹立即起跳
                // （available 是被取消时剩余的惯性速度，一律吞掉不再上抛）
                rebound()
                return Velocity.Zero
            }
        }
    }
    // 回弹的四条触发路径（全部收敛到 rebound()，重复调用无害）：
    //   ① 惯性撞边 → onPostScroll 当帧直接 rebound()
    //   ② fling 撞边被取消 → onPostFling 同帧 rebound()
    //   ③ 手指抬起 → pointerInput 直接调 rebound()（按住时下沉的 case）
    //   ④ isScrollInProgress 翻 false → 兜底（表冠滚动、程序滚动等非手势来源）
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) rebound()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(screenBg())
            .nestedScroll(edgeBounce)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Final)
                        val down = e.changes.any { it.pressed }
                        fingerDown.value = down
                        // 手指一离屏就回弹（Final 阶段只读不消费，不干扰列表手势）
                        if (!down) rebound()
                    }
                }
            }
            .rotaryList(state),
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = sinkPx.floatValue },
            contentPadding = PaddingValues(horizontal = insetDp + widthShrink, vertical = centerPad),
            verticalArrangement = Arrangement.spacedBy(cardSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }

        // 顶部悬浮标题（不属于列表项，不参与吸附）
        if (title != null) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp - titleLiftDp)
                    .clip(RoundedCornerShape(50))
                    // ★ 2026-10-01：顶部悬浮标题胶囊。浅色主题下黑胶囊配 TextPrimary（深字）
                    //   会彻底糊死，必须反向量标定为 72% 白底。
                    .background(
                        if (com.netmusiclite.ui.theme.isLightTheme)
                            Color.White.copy(alpha = 0.72f)
                        else Color.Black.copy(alpha = 0.34f)
                    )
                    .padding(horizontal = 11.7.dp, vertical = 3.6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, fontSize = 9.9.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
                if (titleSubtitle != null) {
                    Text(titleSubtitle, fontSize = 8.1.sp, color = TextSecondary, maxLines = 1)
                }
            }
        }

        if (progressOverride != null) {
            CircularSideProgress(progress = { progressOverride }, modifier = Modifier.fillMaxSize())
        } else if (progressTotal > 1) {
            CircularSideProgress(progress = { progress }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 列表筛选胶囊：点击就地展开输入框并拉起输入法（无二级弹窗），输入即过滤；× 清空并收起 */
@Composable
fun ListFilterCard(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    if (!editing) {
        StackedCard(modifier = modifier, onClick = { editing = true }, height = 46.dp) {
            Icon(NcmIcons.Search, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (query.isEmpty()) "搜索歌曲 / 艺人" else query,
                fontSize = 11.sp, color = if (query.isEmpty()) TextSecondary else TextPrimary,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                Icon(
                    NcmIcons.Close, "清除", tint = TextSecondary,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onQuery("") },
                )
            }
        }
    } else {
        StackedCard(modifier = modifier, height = 46.dp) {
            Icon(NcmIcons.Search, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); editing = false }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            Icon(
                NcmIcons.Close, "收起", tint = TextSecondary,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        keyboard?.hide()
                        editing = false
                    },
            )
        }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}

/**
 * 堆叠卡片通用样式：30dp 圆角 + 玻璃材质 + 通宽 + 62dp 高（可参数化）+ 按压缩放。
 * content 为横向排布的行内容（RowScope）。onLongClick 用于进入多选模式。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StackedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    navMotionKind: NavMotionKind? = null,
    height: Dp = 62.dp,
    cornerRadius: Dp = 30.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val src = remember { MutableInteractionSource() }
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = PressMotion.targetScale(pressed),
        animationSpec = PressMotion.spec(pressed),
        label = "stackedCard",
    )
    Row(
        modifier = modifier
            .onGloballyPositioned { coordinates.value = it }
            .fillMaxWidth()
            .height(height)
            .sinkFromCenter()
            .graphicsLayer {
                // 按压缩放走图层属性延迟读取：动画期间零重组（W5 流畅度）
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .frostedGlass(shape)
            .glassEdgeHighlight(shape)
            .then(
                when {
                    onClick != null && onLongClick != null ->
                        Modifier.combinedClickable(
                            interactionSource = src, indication = null,
                            onClick = {
                                if (navMotionKind == null) onClick()
                                else TransitionCoordinator.withSource(navMotionKind, coordinates.value, onClick)
                            },
                            onLongClick = onLongClick,
                        )
                    onClick != null ->
                        Modifier.clickable(interactionSource = src, indication = null) {
                            if (navMotionKind == null) onClick()
                            else TransitionCoordinator.withSource(navMotionKind, coordinates.value, onClick)
                        }
                    else -> Modifier
                }
            )
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** 堆叠图标卡：左圆底图标 + 标题（可选副标题），对应原 CapsuleCard 视觉 */
@Composable
fun StackedIconCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = TextPrimary,
    iconBg: Color = SurfaceStrong,
    height: Dp = 62.dp,
    navMotionKind: NavMotionKind? = null,
    onClick: () -> Unit = {},
) {
    StackedCard(modifier = modifier, onClick = onClick, navMotionKind = navMotionKind, height = height) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 堆叠歌曲卡：封面 + 标题/艺人 + 时长，行高默认 56dp（比入口卡略矮）；支持多选模式（选中圆圈替换时长） */
@Composable
fun StackedSongCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    height: Dp = 56.dp,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** 跨语言搜索命中别名时展示的译名（见 [SongRow] 的同名参数） */
    aliasHint: String? = null,
) {
    StackedCard(
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        height = height,
        contentPadding = PaddingValues(horizontal = 13.dp),
    ) {
        Box {
            SongCover(song, 38.dp, shape = RoundedCornerShape(8.dp))
            if (isCurrent) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    // 固定白：这层是压在封面图（黑 45%）上的播放标识，
                    // 与主题文字色无关 —— 浅色主题下 TextPrimary 是深色，会看不见
                    Icon(NcmIcons.Play, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, fontSize = 12.sp,
                color = if (isCurrent) com.netmusiclite.ui.theme.Accent else TextPrimary,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(songSubtitle(song, aliasHint), fontSize = 9.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selectMode) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(19.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, if (selected) com.netmusiclite.ui.theme.Accent else TextTertiary, CircleShape)
                    .background(if (selected) com.netmusiclite.ui.theme.Accent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(NcmIcons.Check, null, tint = com.netmusiclite.ui.theme.OnAccent, modifier = Modifier.size(12.dp))
            }
        } else {
            Spacer(Modifier.width(6.dp))
            Text(formatMs(song.durationMs.toLong()), fontSize = 9.sp, color = TextTertiary)
        }
    }
}
