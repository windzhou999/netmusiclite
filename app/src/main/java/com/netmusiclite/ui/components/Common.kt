package com.netmusiclite.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.BgElevated
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.SurfaceGlassBorder
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.TransitionCoordinator
import com.netmusiclite.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 玻璃高光渐变：静态缓存（流畅度 R7）——滚动期每帧 draw 不再新建 Brush 对象 */
private val GLASS_HIGHLIGHT_BRUSH = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.16f),
    0.35f to Color.Transparent,
)

/** 边缘高光描边：仅在玻璃模式（有背景图 **且** 用户没切到黑色卡片）且卡片模糊强度 < 50% 时绘制 ——
 *  轻模糊（透底清晰）用高光/描边辅助轮廓；重模糊、无背景、或黑色半透明卡片都不加。 */
@Composable
fun Modifier.glassEdgeHighlight(shape: Shape): Modifier =
    if (com.netmusiclite.data.BackgroundStore.ready &&
        com.netmusiclite.data.BackgroundStore.cardGlass &&
        com.netmusiclite.data.BackgroundStore.cardBlur < 0.5f
    )
        this.border(BorderStroke(1.dp, SurfaceGlassBorder), shape)
    else this

/**
 * 磨砂玻璃卡片材质：背景图取样模糊 + 半透明填充；
 * 边缘高光跟随模糊强度：< 50% 绘制（辅助轮廓），≥ 50% 不画（干净玻璃）。
 * 无背景图 → 玻璃整体关闭（2026-09-13 全局生效）：卡片底色改半透明黑（融入环境色背景）。
 */
@Composable
fun Modifier.frostedGlass(shape: Shape, fill: Color = SurfaceGlass): Modifier {
    // ★ 2026-10-01：走「黑色半透明」分支的条件从「没有背景图」扩展为
    //   「没有背景图 **或** 用户主动选了黑色卡片」—— 设置页「卡片样式」开关切到黑色时，
    //   全站玻璃件（含播放器/歌词/歌房里的）立刻统一变成纯黑 45%，不取样、不做糊。
    return if (!com.netmusiclite.data.BackgroundStore.ready ||
        !com.netmusiclite.data.BackgroundStore.cardGlass
    ) {
        this.drawBehind {
            // 默认玻璃底换 45% 半透明黑；显式传入的填充（强调色圆钮等）保留。
            // ★ 2026-10-01 浅色主题：底色本身就亮，再叠 45% 黑会得到一张脏灰卡，
            //   必须换成高不透明度白（92%）—— 叠在 #EFEFF3 底上约等于纯白，
            //   与底色拉开约 15/255 的差值，卡片边界才立得住（手表上没有阴影可用）。
            //   （drawBehind 里读组合状态：主题切换只触发重绘，不触发重组，零额外开销）
            val fallback = if (com.netmusiclite.ui.theme.isLightTheme)
                Color.White.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.45f)
            drawRect(if (fill == SurfaceGlass) fallback else fill)
        }
    } else {
        val bmp = com.netmusiclite.data.BackgroundStore.blurredImage
        var pos by remember { mutableStateOf(IntOffset.Zero) }
        this
            .clip(shape)
            .onGloballyPositioned { ic ->
                // 2px 量化：底图本身是重模糊图，2px 取样偏移不可见；
                // 滚动吸附尾巴等小位移不再触发玻璃层重绘（滚动掉帧的主要来源）
                val p = ic.positionInRoot()
                val q = IntOffset(p.x.roundToInt() / 2 * 2, p.y.roundToInt() / 2 * 2)
                if (q != pos) pos = q
            }
            .drawBehind {
                if (bmp != null) {
                    val x = pos.x.coerceIn(0, (bmp.width - size.width.toInt()).coerceAtLeast(0))
                    val y = pos.y.coerceIn(0, (bmp.height - size.height.toInt()).coerceAtLeast(0))
                    drawImage(
                        image = bmp,
                        srcOffset = IntOffset(x, y),
                        srcSize = IntSize(size.width.toInt(), size.height.toInt()),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        alpha = com.netmusiclite.data.BackgroundStore.cardBlur, // 强度滑杆：1=最大
                    )
                }
                // 亮度随背景压暗同步变暗，减少卡片与背景的突兀感
                // ★ 2026-10-01 浅色主题反向：叠加白色，让背景图向浅色主题淡化，而不是发脏
                val shade = if (com.netmusiclite.ui.theme.isLightTheme)
                    Color.White.copy(alpha = com.netmusiclite.data.BackgroundStore.dim * 0.7f)
                else
                    Color.Black.copy(alpha = com.netmusiclite.data.BackgroundStore.dim * 0.6f)
                drawRect(shade)
                drawRect(fill)
                if (com.netmusiclite.data.BackgroundStore.cardBlur < 0.5f) {
                    drawRect(GLASS_HIGHLIGHT_BRUSH)
                }
            }
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDesc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 46.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    container: Color = SurfaceGlass,
    tint: Color = TextPrimary,
    dim: Boolean = false,
    navMotionKind: NavMotionKind? = null,
) {
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    Pressable(
        onClick = {
            if (navMotionKind == null) onClick()
            else TransitionCoordinator.withSource(navMotionKind, coordinates.value, onClick)
        },
        modifier = modifier.onGloballyPositioned { coordinates.value = it },
    ) {
        // 透明容器 = 纯图标悬浮（不画玻璃底），供弧形底栏等已有整体背景的场景使用
        Box(
            if (container == Color.Transparent) Modifier.size(size)
            else Modifier.size(size).clip(CircleShape).frostedGlass(CircleShape, fill = container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDesc,
                tint = tint.copy(alpha = if (dim) 0.38f else 1f), modifier = Modifier.size(iconSize))
        }
    }
}

/** 圆屏内文本输入对话框（新建歌单 / 房间号） */
@Composable
fun TextInputDialog(
    title: String,
    placeholder: String = "",
    confirmText: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // TextFieldValue 状态重载：保留光标/选区，避免按删除键时光标跳到上一个字
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    DialogScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(BgElevated)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceGlass)
                    .padding(vertical = 10.dp, horizontal = 12.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (text.text.isEmpty()) Text(placeholder, fontSize = 13.sp, color = TextSecondary)
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircleIconButton(NcmIcons.Close, "取消", onDismiss, size = 40.dp, iconSize = 17.dp)
                CircleIconButton(
                    NcmIcons.Check, confirmText,
                    { if (text.text.isNotBlank()) onConfirm(text.text.trim()) }, size = 40.dp, iconSize = 17.dp,
                    container = Accent,
                )
            }
        }
    }
}

/** 全屏半透明遮罩容器（浅色主题下遮罩减淡到 32%，600/1000 会糊成一片死黑） */
@Composable
fun DialogScrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(
                alpha = if (com.netmusiclite.ui.theme.isLightTheme) 0.32f else 0.6f
            ))
            .clickable(onClick = onDismiss)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // ── 可滚动内容框（2026-10-01 圆屏弹窗可见性修复）─────────────────────
        // 旧版这里是 `Box { content() }`，内容高度上限 = 屏高 − 24dp×2（本机 185dp）。
        // Column 在高度不够时会**逐个把剩余高度分给孩子**：前面的兄弟吃满后，
        // 最后一个按钮拿到 0dp —— 于是「长按歌曲」那个面板底部按钮的文字整段消失
        // （用户反馈「看不到按钮上的字，被挡住了」）。
        //
        // 加 verticalScroll 后，内容以**无界高度**测量：不再被压扁，超长就纵向滚动，
        // 保证每一条都真实存在、都能滚到。高度上限仍由外层 padding 控制（不额外收紧，
        // 否则原本刚好放得下的弹窗反而要滚动）。
        Box(
            Modifier
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

/**
 * 当前设备是否为**圆形屏**（Wear OS 圆表）。
 *
 * ★ 2026-10-02 方形设备适配（用户口径「检测到安装设备是方形的时候，填充空缺区域」）：
 *   本 App 的整套布局是按「466×466 方屏面板 + 圆形表圈」设计的 —— 窗口铺满方屏、
 *   四角在物理上被表圈切掉，所以根布局一直 `clip(CircleShape)`。
 *   装到**方形/矩形屏**设备上时这个裁切就变成了 bug：四角被裁掉后没有内容可画，
 *   而 MainActivity 里 `window.setBackgroundDrawable(null)`（首帧优化）让窗口也没有底色，
 *   于是屏幕四角留出四块纯黑空白 —— 也就是用户看到的「空缺区域」。
 *
 * 判据用系统配置里的 SCREENROUND 位（[android.content.res.Configuration.isScreenRound]，
 * API 23+，本项目 minSdk 26），比任何「按宽高比/分辨率猜形状」都可靠：
 *   · 圆表                → true  → 保留圆形裁切 + 弧边内缩（原有行为，一个像素都不变）
 *   · 方表 / 手机 / 模拟器 → false → 不裁切、不内缩，内容直接铺满整屏
 *
 * ⚠ 用 [androidx.compose.ui.platform.LocalConfiguration] 而不是在启动时读一次存全局：
 *   跟随配置变化，且在 Compose 里读到的就是本 Activity 的真实配置。
 */
@Composable
fun isRoundScreen(): Boolean =
    androidx.compose.ui.platform.LocalConfiguration.current.isScreenRound

/**
 * 圆屏「水平安全内缩」（px）—— 控件纵向中心距屏幕顶部 [centerYFromTopPx] 像素时，
 * 返回让它左右两端完整落在圆形可视区内的最小内缩量（已含 [marginPx] 余量）。
 *
 * 为什么需要：手表是 466×466 方屏面板 + 圆形表圈，窗口铺满整个方屏，**四角在物理上被切掉**。
 * 通宽控件放在屏幕最上/最下时，两端会被弧边吃掉（真机表现就是「输入框两头被裁切」）。
 *
 * 推导：圆心在方屏正中、半径 R = 屏宽/2。距圆心 dy 处的可用半宽 = √(R² − dy²)，
 * 故所需内缩 = R − √(R² − dy²)。越靠上/下，圆越窄，内缩越大；屏幕正中为 0。
 *
 * 用法：拿控件自己的纵向中心（`positionInRoot().y + size.height/2`）算一次即可；
 * 内缩只影响横向宽度、不影响纵向位置，因此不会产生测量回环。
 */
@Composable
fun circleSafeInsetPx(centerYFromTopPx: Float, marginPx: Float = 6f): Float {
    // ★ 2026-10-02 方形设备适配：方屏没有弧边可避，内缩恒为 0 ——
    //   否则通宽控件（房间号输入框等）会无谓地缩窄，白白浪费本来就有限的宽度。
    if (!isRoundScreen()) return 0f
    val screenWpx = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val r = screenWpx / 2f
    if (r <= 0f) return 0f
    val dy = kotlin.math.abs(centerYFromTopPx - r).coerceAtMost(r)
    val half = kotlin.math.sqrt((r * r - dy * dy).coerceAtLeast(0f))
    return (r - half) + marginPx
}

/**
 * 右滑返回淡出引擎（SwipeBackBox / 播放页 / 歌词页 / 歌房页共用）：
 * - 跟手「右移 + 非线性淡出」：页面随手指向右移动（35% 位移）并以 smoothstep 非线性曲线淡出；
 * - 淡出速度随滑动速度变化：瞬时速度折算成进度加成，快滑时淡出领先手指，慢滑跟手缓淡；
 * - 松手未达阈值 150ms 淡回原位；达阈值由调用方触发返回 —— 页面保持当前位移/透明度
 *   继续向右滑出场，下层页面立即接上（配合 NavHost popEnter 60ms 接场，无空白帧）；
 * - 2026-09-13：滑动返回全程不再触发震动。
 * fade/slide 用 snapshot float 直写：拖动路径零协程/零 mutex（旧版每个拖动事件
 * scope.launch 两次 + Animatable.snapTo 的 mutex 抢占，W5 高频触摸下有可感抖动）。
 */
/**
 * 右滑跟手缩放的终值（拖满阈值行程时缩到位，松手后继续收缩到 [QZ_EXIT_SCALE]）。
 *
 * 取 0.80 而不是更小：跟手阶段缩太狠（曾用 0.55）会让松手后只剩 0.05 的行程，
 * 却仍要跑满一整个 [flingOut] 时长 —— 页面几乎不动却白占时间，用户看到的就是
 * 「退出很犹豫、一顿一顿」。跟手温和一点，把「果断收掉」全部交给松手后那一段。
 */
private const val QZ_DRAG_SCALE = 0.80f

/** 右滑松手返回时缩到的终值，与页面转场常量 QZ_FROM 取同一个数（退页收向 50%）。 */
private const val QZ_EXIT_SCALE = 0.50f

/**
 * 松手缩放的缓动：**起步极快、收尾干净**。
 * 旧的 (0.32, 0.72, 0.35, 1) 起手斜率只有 2.25、尾巴又拖得长，收束过程拖泥带水，
 * 用户反馈「退出曲线显得很犹豫」。现在起始斜率 ≈ 5.9 —— 一松手就迅速收掉大半行程，
 * 最后一段干脆落位，观感是「果断关掉」。
 */
private val QZ_EXIT_EASE = androidx.compose.animation.core.CubicBezierEasing(0.16f, 0.94f, 0.26f, 1f)

class SwipeBackFader(
    private val thresholdPx: Float,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /**
     * 跟手与收场用哪种视觉。
     *
     * · `true`（默认）= **缩放**：页面级右滑返回（用户要求「退出也要是缩放」）。
     * · `false` = **位移**：播放器组内部的横向切换，例如歌词页 → 播放页
     *   （用户明确要求「歌词界面切换到播放界面保留滑动切换动画，不要用缩放」）。
     *
     * 2026-10-01 补：此前把整条 SwipeBackFader 无差别改成了缩放，连带把播放器组内部的
     * 横向切换也一起改掉 —— 那是用户明确要保留的滑动，所以这里必须按用途分开。
     */
    private val scaleMode: Boolean = true,
) {
    private val fadeState = androidx.compose.runtime.mutableFloatStateOf(1f) // 1=不透明
    private val slideState = androidx.compose.runtime.mutableFloatStateOf(0f) // 跟手位移 px（已停用，恒 0）
    private val scaleState = androidx.compose.runtime.mutableFloatStateOf(1f) // 缩放（右滑返回主视觉）
    private val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
    private var settleJob: kotlinx.coroutines.Job? = null
    private var dragX = 0f

    /** 内容不透明度（供 graphicsLayer 读） */
    val alpha: Float get() = fadeState.floatValue

    /** 内容跟手位移 px（供 graphicsLayer 读；右滑返回改缩放后恒为 0，保留属性兼容调用点） */
    val slidePx: Float get() = slideState.floatValue

    /**
     * 内容缩放（供 graphicsLayer 读）。
     *
     * ★ 2026-10-01：右滑返回改为**缩放主导**。
     *   此前是「跟手右移 35% + 松手滑出屏幕」，用户两次反馈「右滑（退出）不是缩放」
     *   （第一次原话「你只改了进入动画没改退出动画」）—— 右移把缩放完全盖住了，
     *   就算 NavHost 侧加了 scale 也看不出来。
     *   轻舟的返回实测是**纯缩放**：退页恒 1.000 → 回弹 1.038 → 收向 0.50 并淡出，
     *   被揭示的下层页不缩放、只在后半程淡入。这里照此把跟手位移归零，改用跟手缩小。
     */
    val scale: Float get() = scaleState.floatValue

    /** 右滑累计是否已达返回阈值 */
    val passedThreshold: Boolean get() = dragX >= thresholdPx

    fun onDragStart() {
        settleJob?.cancel(); settleJob = null
        tracker.resetTracking()
    }

    /** 每个横向拖动事件调用；delta<0（左移）即清零右滑进度并淡回 */
    fun onDrag(change: androidx.compose.ui.input.pointer.PointerInputChange, delta: Float) {
        settleJob?.cancel()
        tracker.addPosition(change.uptimeMillis, change.position)
        if (delta < 0) {
            dragX = 0f
            if (fadeState.floatValue < 1f) settleBack(130)
            return
        }
        dragX += delta
        val v = runCatching { tracker.calculateVelocity().x }.getOrDefault(0f)
        val p = (dragX / thresholdPx).coerceIn(0f, 1f)
        val t = (p * 0.72f + (v / 2400f).coerceIn(0f, 1f) * 0.28f).coerceIn(0f, 1f)
        // 爆音空白帧缓解：松手返回时新页首帧组合在 W5 上耗时几十 ms（巨帧期间屏幕定格在拖拽末帧），
        // 淡出越接近 0 定格越像"闪白"。给透明度设 0.45 下限，定格帧仍是可辨识的页面内容。
        val a = (1f - t * t * (3f - 2f * t)).coerceAtLeast(0.45f) // smoothstep 非线性：前缓后急
        fadeState.floatValue = a
        // 缩放模式：跟手缩小（右滑返回的主视觉）。
        // 位移模式：跟手右移 35%（播放器组内横向切换，用户要求保留滑动）。
        if (scaleMode) {
            scaleState.floatValue = 1f - (1f - QZ_DRAG_SCALE) * p
            slideState.floatValue = 0f
        } else {
            scaleState.floatValue = 1f
            slideState.floatValue = dragX * 0.35f
        }
    }

    /** 松手：triggered=true 表示即将返回（页面已入场接场，无需回弹） */
    fun onDragEnd(triggered: Boolean) {
        if (!triggered) settleBack(150)
    }

    fun onDragCancel() = settleBack(150)

    /**
     * 松手返回的收束时长。
     *
     * 2026-10-01 改：右滑返回已由「滑出屏幕」改为「缩放收束」，时长不再取决于屏幕距离。
     * 固定 190ms 配合 [QZ_EXIT_EASE]（起始斜率 ≈5.9），松手后前 1/4 时长就收掉大半行程。
     * 旧实现按屏宽折算成固定 212ms，但缩放行程是随跟手深度变的 —— 行程小的时候
     * 照样跑满 212ms，正是「退出犹豫」的来源之一。screenPx 保留在签名里兼容调用点。
     */
    fun flingOutDuration(screenPx: Float): Int =
        if (scaleMode) 190
        else {
            // 位移模式仍按剩余距离折算（平均速率 ~2200px/s，夹取 140–220ms）
            val dist = (screenPx - slideState.floatValue).coerceAtLeast(0f)
            (dist / 2200f * 1000f).toInt().coerceIn(140, 220)
        }

    /**
     * 松手返回：把页面从当前跟手缩放收束到 [QZ_EXIT_SCALE]（0.50），淡出交给 NavHost。
     *
     * 2026-10-01 由「单条 Hermite 滑出屏幕」改为缩放收束 —— 位移整段移除，只留缩放。
     * 缓动用 [QZ_EXIT_EASE]（起始斜率 ≈5.9）：一松手就迅速收掉大半行程，收尾干脆，
     * 不再有「先顿一下再缩」的犹豫感。
     */
    fun flingOut(screenPx: Float, durationMs: Int) {
        settleJob?.cancel()
        settleJob = scope.launch {
            if (scaleMode) {
                // 缩放模式：收束到 0.50，不再滑出屏幕（页面级右滑返回）
                androidx.compose.animation.core.animate(
                    initialValue = scaleState.floatValue, targetValue = QZ_EXIT_SCALE,
                    animationSpec = tween(durationMs, easing = QZ_EXIT_EASE),
                ) { v, _ -> scaleState.floatValue = v }
            } else {
                // 位移模式：单条 Hermite（k=1.8）单调减速滑到 screenPx ——
                // f'(0)=1.8×（先快）、f'(1)=0（后慢）、f''≤0 全程严格单调减速，中段无速度波动。
                val ease = androidx.compose.animation.core.Easing { x ->
                    val k = 1.8f
                    (k - 2f) * x * x * x + (3f - 2f * k) * x * x + k * x
                }
                androidx.compose.animation.core.animate(
                    initialValue = slideState.floatValue, targetValue = screenPx,
                    animationSpec = tween(durationMs, easing = ease),
                ) { v, _ -> slideState.floatValue = v }
            }
        }
    }

    private fun settleBack(ms: Int) {
        settleJob = scope.launch {
            launch {
                androidx.compose.animation.core.animate(
                    initialValue = fadeState.floatValue, targetValue = 1f,
                    animationSpec = tween(ms),
                ) { v, _ -> fadeState.floatValue = v }
            }
            launch {
                androidx.compose.animation.core.animate(
                    initialValue = slideState.floatValue, targetValue = 0f,
                    animationSpec = tween(ms),
                ) { v, _ -> slideState.floatValue = v }
            }
            androidx.compose.animation.core.animate(
                initialValue = scaleState.floatValue, targetValue = 1f,
                animationSpec = tween(ms),
            ) { v, _ -> scaleState.floatValue = v }
        }
    }
}

/**
 * 全局滑动返回：屏幕任意位置向右滑动（全屏右滑，非边缘限定），内容跟手「右移+非线性淡出」
 * （淡出速度随滑动速度变化，快滑淡得更快），超过阈值松手即返回。
 * 右滑期间本页置于转场最上层（lifted）：松手返回时下层页面立即接上，无空白帧。
 * 竖向列表滚动/子组件横滑手势优先级更高，互不干扰。
 * 圆表上左上角按钮难点按，统一用这个替代返回钮。
 *
 * 2026-09-13 重写：原「跟手位移」改为跟手右移+非线性速度感应淡出（SwipeBackFader），
 * 并取消滑动返回/左划切换时的震动。
 * 2026-09-04 扩展：新增 onSwipeLeft（左划回调，主页/搜索左划进播放页）——
 * ⚠ 子级不要再挂横向拖拽检测，会先消费事件把本组件的右滑返回饿死（真机踩坑）。
 * 方向互斥：左划累计与右划累计各自独立，换向即清零。
 */
@Composable
fun SwipeBackBox(
    onBack: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 返回行程 37dp（2026-09-13 二次下调 40%：76dp → 61dp → 37dp）
    val popPx = with(androidx.compose.ui.platform.LocalDensity.current) { 37.dp.toPx() }
    val fader = remember(popPx) { SwipeBackFader(popPx, scope) }
    // 滑出目标 = 屏宽（flingOut 清屏用）
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    // 回调经 rememberUpdatedState 读取：pointerInput(Unit) 不随 lambda 变化重启，拖动中重组不打断手势
    val currentBack by androidx.compose.runtime.rememberUpdatedState(onBack)
    val currentSwipeLeft by androidx.compose.runtime.rememberUpdatedState(onSwipeLeft)
    // 右滑期间把本页抬到转场最上层：松手返回时退场页盖在入场页上方继续滑出（下层接场无空白帧）
    var lifted by remember { mutableStateOf(false) }

    Box(
        Modifier
            .zIndex(if (lifted) 2f else 0f)
            .fillMaxSize()
            .pointerInput(Unit) {
                var dragL = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragL = 0f
                        fader.onDragStart()
                        lifted = currentBack != null
                    },
                    onDragEnd = {
                        val back = currentBack != null && fader.passedThreshold
                        val left = !back && currentSwipeLeft != null && dragL >= popPx
                        fader.onDragEnd(back)
                        if (back) {
                            // 松手速度接续：fader 从当前位移线性滑出全屏（时长按拖拽末速度折算），
                            // NavHost 转场只做淡出+下层入场，不再叠加位移（消除两段动画接缝）
                            val ms = fader.flingOutDuration(screenPx)
                            TransitionCoordinator.withGesturePop(ms) { currentBack?.invoke() }
                            fader.flingOut(screenPx, ms)
                        }
                        else {
                            if (left) currentSwipeLeft?.invoke()
                            lifted = false
                        }
                    },
                    onDragCancel = {
                        fader.onDragCancel()
                        lifted = false
                    },
                ) { change, amt ->
                    change.consume()
                    if (amt < 0) dragL += -amt else dragL = 0f
                    // onBack 为空（如主页）右滑无动作，淡出/位移不介入
                    if (currentBack != null) fader.onDrag(change, amt)
                }
            },
    ) {
        Box(
            Modifier.graphicsLayer {
                alpha = fader.alpha
                translationX = fader.slidePx
                // ★ 2026-10-01：右滑返回的主视觉 —— 跟手缩放（替代原来的跟手右移）
                scaleX = fader.scale
                scaleY = fader.scale
            },
        ) { content() }
    }
}

/** 居中提示（加载/空/错误） */
@Composable
fun CenterHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 48.dp))
    }
}

/** 简易 toast 提示条（圆屏友好，2.2s 自动消失由调用方控制显示状态）
 *  浅色主题下反转为「浅底深字」，否则会在浅色页面里留一块突兀的深色方块 */
@Composable
fun NcmToast(message: String?, modifier: Modifier = Modifier) {
    if (message != null) {
        val light = com.netmusiclite.ui.theme.isLightTheme
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message,
                fontSize = 11.sp, color = if (light) Color(0xFF1A1A1E) else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (light) Color(0xF2FFFFFF) else Color(0xCC2B2B33))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun rememberAutoClearMessage(): Pair<String?, (String) -> Unit> {
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) {
        if (msg != null) { delay(2200); msg = null }
    }
    return msg to { msg = it }
}

// ---------- 时间/数字格式 ----------
fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatDate(ts: Long): String {
    if (ts <= 0) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    return "%d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
}

fun formatCount(n: Long): String = when {
    n >= 100_000_000 -> "%.1f亿".format(n / 100_000_000f)
    n >= 10_000 -> "%.1f万".format(n / 10_000f)
    else -> n.toString()
}
