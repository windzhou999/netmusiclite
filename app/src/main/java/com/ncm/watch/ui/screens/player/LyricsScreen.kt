package com.ncm.watch.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.ncm.watch.data.AppearancePrefs
import com.ncm.watch.data.BackgroundStore
import com.ncm.watch.data.DownloadStore
import com.ncm.watch.data.LyricLine
import com.ncm.watch.data.NcmApi
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.ui.components.CircularSideProgress
import com.ncm.watch.ui.components.SongAmbient
import com.ncm.watch.ui.components.SwipeBackFader
import com.ncm.watch.ui.components.ambientSurfaceColor
import com.ncm.watch.ui.components.rotaryList
import com.ncm.watch.ui.nav.TransitionCoordinator
import com.ncm.watch.ui.theme.Accent
import com.ncm.watch.ui.theme.Bg
import com.ncm.watch.ui.theme.OnAccent
import com.ncm.watch.ui.theme.TextPrimary
import com.ncm.watch.ui.theme.TextSecondary
import com.ncm.watch.ui.theme.lyricInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val LRC_REGEX = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")

/**
 * 歌词切换曲线（2026-09-30，从参考录屏逐帧反解，非拍脑袋值）
 *
 * 反解方法：对参考录屏逐帧在歌词区做 1D 竖直互相关求位移，得到「位移—时间」曲线；
 * 取 4 次切换的均值曲线拟合三次贝塞尔 —— RMSE 0.015，优于最好的内置缓动
 * （FastOutSlowIn 0.062 / easeOutQuad 0.072）约 4 倍，且 4 次样本标准差极小
 * （中段最大 ±0.054），说明行为高度一致、不是噪声拟合。
 *
 * 曲线特征（归一化进度 vs 归一化时长）：
 *     15% 时长 →  8.6%   软起步（前 150ms 几乎不动，先蓄势）
 *     20% 时长 → 22.0%
 *     30% 时长 → 51.2%   爆发段：15% 的时长走完 43% 的位移
 *     40% 时长 → 70.6%
 *     60% 时长 → 88.0%
 *     80% 时长 → 95.9%   长尾滑行：最后 20% 时长只走 4%
 *     100%     → 100%    全程严格单调、零过冲（y'(t) 系数恒正，已解析验证）
 *
 * 时长 630ms 取自 4 次切换实测中位数（584/647/615/644）；位移恒为 1 个行距。
 */
private val LyricGlide = CubicBezierEasing(0.31f, 0.02f, 0.15f, 0.98f)
private const val LYRIC_GLIDE_MS = 630

/**
 * 明亮度（2026-10-01 六轮，用户口径「歌词聚焦切换时明亮度要加渐变效果」）：
 *
 *     d = 0     → 1.00   焦点行（纯白最大）
 *     d = 1     → 0.48   相邻行（延续「非聚焦 -27%」的口径，即 0.66 × 0.73）
 *     d = 1 → 5   →  0.48 → 0.36 随距离平滑递减（smoothstep）
 *     d ≥ 5     → 0.36   渐变下限（仍可读；之前一轮曾到 α≈0.13，圆表上读不清，不能再去）
 *
 * 为什么改：2026-09-30 那次用「FLOOR 一刀切」把非聚焦行统一成同一档（当时是为了修
 * 远处行太暗的可读性 bug），结果**整屏只剩焦点行和它相邻那一行在变明暗**，
 * 参考界面「整屏像一块竖直的透明度渐变板、随歌词一起平移」的核心观感就没了。
 * 现在改成随距离连续衰减：既是用户要的渐变，也让**每次切换时整屏每一行的亮度都在动**
 * （每行的 d 都平移了 1），渐变感是整屏的，不是只有两行在对调。
 */
private const val LYRIC_DIM_NEAR = 0.48f   // d=1（相邻行）
private const val LYRIC_DIM_FAR = 0.36f    // 渐变下限
private const val LYRIC_DIM_SPAN = 4f      // 从 d=1 衰减到 d=5

private fun lyricBrightness(distance: Float): Float {
    val d = abs(distance)
    if (d <= 0.001f) return 1f
    if (d <= 1f) return 1f + (LYRIC_DIM_NEAR - 1f) * d
    val t = ((d - 1f) / LYRIC_DIM_SPAN).coerceIn(0f, 1f)
    val s = t * t * (3f - 2f * t)
    return LYRIC_DIM_NEAR + (LYRIC_DIM_FAR - LYRIC_DIM_NEAR) * s
}

/**
 * 缩放**只在可读带之外**生效（2026-10-01 四轮）。
 *
 * 为什么必须这样切：**给文字图层加一个非整数的 scale，会带来亚像素位移**。
 * 原理：图层带 scale 时，硬件渲染要把内容画进一块离屏贴图再按变换贴回来，而贴图的
 * 边界是按变换后的（小数）范围取的 —— 采样栅格与屏幕像素栅格错开约半个像素，字就"偏"了。
 * 一行歌词的 scale 从 0.94 连续长到 1.0 的过程中，这个错位一直在变；等到它成为当前行、
 * scale 恰好落到 1.0（恒等变换）时，硬件改走直接绘制 —— **这一帧会看到一次离散的亚像素
 * 归位**，就是用户反复报的「切换歌词有轻微像素偏移」。
 *
 * 所以：**用户正在读的那一带（当前行 ±2 行）永远保持 scale = 1.0 的恒等变换，像素级精确**；
 * 缩放层级只在再往外的行上体现 —— 那一带本来就被 12.5dp 高斯模糊糊掉、透明度也只有 0.48，
 * 尺寸层级在那里才有意义（有景深、又不会被看清像素）。
 * 用 smoothstep 衔接，两端一阶连续，不会在阈值处出现折角。
 *
 * 副作用（正向）：自动推进时，即将成为当前行的那一行从 |d|=1 一路到 0 都恒为 1.0，
 * 中途**完全不缩放**；上一行也恒为 1.0 —— 可读带里一次尺寸变化都不会发生。
 */
private const val LYRIC_SCALE_INNER = 2f    // |d| ≤ 2：恒等变换，不缩放
private const val LYRIC_SCALE_OUTER = 4.5f  // |d| ≥ 4.5：收到缩放下限
private const val LYRIC_SCALE_DEPTH = 0.125f // 缩放下限 = 1 - 0.125 = 0.875

/** 0 = 不缩放（恒等），1 = 缩到下限。可读带内恒为 0，避免任何亚像素位移 */
private fun lyricScaleAmount(distance: Float): Float {
    val d = abs(distance)
    if (d <= LYRIC_SCALE_INNER) return 0f
    if (d >= LYRIC_SCALE_OUTER) return 1f
    val t = (d - LYRIC_SCALE_INNER) / (LYRIC_SCALE_OUTER - LYRIC_SCALE_INNER)
    return t * t * (3f - 2f * t) // smoothstep
}

/**
 * 行间距拉伸回弹（2026-09-30，同源反解）
 *
 * 参考界面在每次切换时，整列歌词行距会先被拉开、再弹回原位。用「自相关求主导空间周期」
 * 量化（该估计量与行的身份无关，不受行滚动/进出屏造成的误匹配干扰）：
 *     基线行距            117.03 px
 *     帧 77-83（+150ms）  120.4 px  +2.9%   ← 预拉
 *     帧 86（+190ms）     129.42 px +10.59% ← 峰值
 *     帧 89               124.8 px  +6.7%
 *     帧 94               119.7 px  +2.3%
 *     帧 114（+740ms）    117.0 px   0      ← 回弹完成
 * 即：约 190ms 拉到峰值 +10.5%，再用约 470ms 长尾回弹到 0，全程与滚动共用同一缓动。
 *
 * 实现要点：拉伸必须只拉「间距」，不能拉字形。因此不用 scaleY，而是给每行加一个
 * translationY = 距锚定行的距离 × 行高 × 拉伸量 —— 各行以当前行为中心向外推开／收拢，
 * 字形尺寸完全不变，且仍是纯渲染层操作（零重排版）。
 */
private const val LYRIC_STRETCH_PEAK = 0.105f
private const val LYRIC_STRETCH_ATTACK_MS = 190
private const val LYRIC_STRETCH_RELEASE_MS = 470

/**
 * 锚点扫掠的行数上限（2026-10-01 三轮）。
 *
 * 正常切换（自动推进、点相邻行、点击屏幕上任意一行歌词）跨越 1~6 行，锚点用 630ms 扫过这
 * 几行正好是参考界面「明暗整片滑过去」的观感，必须保留。
 *
 * 但**瞬移**（换歌后归位、快进到远处、手动滚完 3.5s 后的自动跟随）可能一次跨几十行：
 * 让锚点扫过整段，等于把沿途几十行的缩放 + 拉伸各演一遍，同时几十行的渲染图层逐帧改变换，
 * 与「双份歌词列表 + 全屏高斯模糊」叠加起来就是掉帧。
 * 超过这个距离改为**直接落位**：高亮立刻到目标行、拉伸归零，位移完全交给列表滚动 ——
 * 滚动期间每行的缩放/明暗都是常量，零逐帧变换。
 */
private const val LYRIC_ANCHOR_SNAP_LINES = 8f

/**
 * 歌词区上下边缘的高斯模糊遮挡带（2026-10-01 新增）：
 *  - 距屏幕边框 16 像素内 = 「完全遮挡核心」（用户口径）
 *  - 核心之外再延伸一段，做「模糊 → 清晰」的渐变过渡
 *  - 实现：底层铺一份整屏模糊的歌词，上层铺清晰歌词并按垂直渐变把上下边缘擦掉
 *    （DstIn），被擦掉的区域露出底层 ⇒ 视觉即「边缘高斯模糊 + 渐变遮挡」。
 *    两层共享同一滚动位置（独立 LazyListState + snapshotFlow 同步），
 *    绝不复用主列表的 layoutInfo（自动跟随定位强依赖它）。
 *
 * ★ 2026-10-01 二轮（用户口径「非焦点歌词模糊加大 25%，滑动浏览时模糊渐变消失」）：
 *   - 模糊强度 10 → 12.5dp、过渡带 44 → 55dp（均 +25%）
 *   - 手动浏览（表冠/拖动，userScrolling）时两层一起淡出：边缘恢复全清晰，
 *     用户能看清下方还没唱到的歌词；3.5s 无操作后自动复位
 */
private val LYRIC_EDGE_BLUR = 12.5.dp
private const val LYRIC_EDGE_CORE_PX = 16f
private val LYRIC_EDGE_SPREAD = 55.dp

/**
 * ★ 2026-10-02 用户口径（三条）：
 *   ① 「歌词界面聚焦的歌词提高一些亮度，增加可读性」
 *   ② 「增加白色光线扫过聚焦歌词的效果（尽量同步该句速度）」
 *   ③ 「扫光显示区域仅在歌词字体上面」（同日追加）——
 *      首版除字形层外还有一层铺在「材质」上的背景光晕（参考图底栏那团白光），
 *      实机看过后用户不要它：光必须只出现在字形像素上，字与字之间、行的底色上
 *      不能留光。所以背景光晕层整体删除，最终只剩字形遮罩层。
 *
 * ── 为什么必须做成「绘制阶段的遮罩」而不是改文字颜色 ────────────────────────
 * 本页有三条已经被反复踩出来的硬约束（见 LyricRow 的注释），一条都不能破：
 *   · 不能新增 Text 节点          → 否则文本要测量/排版第二遍；
 *   · 不能改 Text 的颜色          → 否则 Paragraph 缓存整体失效，等价每帧重排版；
 *   · 不能在组合期读逐帧动画值    → 否则整页每帧重组。
 * 所以扫光整个发生在**绘制阶段**（LyricRow 的 drawWithContent 里），单层：
 *   **字形提亮 + 扫光**（画在文字之上）：把本行内容画进离屏层 → `SrcIn` 压成光色剪影
 *   → `DstIn` 用「底噪 + 移动径向光斑」遮罩 → `restore()` 叠回原文之上。
 *   `SrcIn` 只染**非透明像素** ⇒ 光天然只落在字形上 —— 这正是用户口径③的实现方式；
 *   遮罩的**底噪**（[LYRIC_SWEEP_BASE]）= 聚焦行整体提亮（用户口径①）；
 *   遮罩的**移动峰值**（[LYRIC_SWEEP_PEAK]）= 白色光线扫过（用户口径②）。
 * 位置与强度每帧从 lambda 延迟读取 ⇒ 零重组合、零重排版、零新增布局节点。
 * 非聚焦行（|d| 超阈值）在绘制入口就 return，一次多余绘制都不会发生。
 */
/** 扫光只作用于焦点行附近（|d| ≤ 该值），并按距离线性淡出 —— 与 anchorPos 共用同一根时间轴 */
private const val LYRIC_SWEEP_BAND = 0.9f
/** 遮罩底噪 = 聚焦行整体提亮量（用户口径①） */
private const val LYRIC_SWEEP_BASE = 0.30f
/** 遮罩峰值 = 光斑扫过处的提亮量（用户口径②） */
private const val LYRIC_SWEEP_PEAK = 0.82f
/** 光斑半径 = 行宽 × 该系数（≈ 行宽 42%，与参考图底栏那团光晕的尺度接近） */
private const val LYRIC_SWEEP_RADIUS = 0.42f
/** 某句没有下一句时（最后一句）的扫光时长兜底 */
private const val LYRIC_SWEEP_FALLBACK_MS = 4000f
/** 扫光时长夹取：太短的句子（<0.6s）光斑会闪成一条线，太长（>12s）慢到看不出在动 */
private const val LYRIC_SWEEP_MIN_MS = 600f
private const val LYRIC_SWEEP_MAX_MS = 12000f

/**
 * 把「本行内容」重绘一遍并染成光色、再用移动光斑遮罩叠回原文之上（上面说明里的字形遮罩层，
 * 也是唯一一层 —— 用户口径③「扫光仅在歌词字体上面显示」，见文件头注释）。
 *
 * 抽成普通函数是为了让 [LyricRow] 的 drawWithContent 保持可读 —— 它必须是
 * `DrawScope` 的扩展（要拿 size / drawRect / drawContext），且**不持有任何 Compose 状态**：
 * 所有逐帧量都由调用方以参数传入，保证这里是纯粹的绘制代码。
 *
 * @param drawOriginal 原文的绘制入口（即 drawContent 的引用）
 * @param base 底噪强度（0..1，= 聚焦行整体提亮量）
 * @param peak 光斑峰值强度（0..1）；等于 base 时只提亮、不扫光
 * @param cx 光斑中心横坐标（px，由调用方按扫光进度算好）
 * @param radius 光斑半径（px）
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLyricSweep(
    drawOriginal: () -> Unit,
    base: Float,
    peak: Float,
    cx: Float,
    radius: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    // 光色恒为纯白（2026-10-02 用户口径「浅色主题的歌词呈现黑色无法体现扫光，需要与深色主题同步」）：
    // 深色主题下字是亮色/彩色，白光扫过 = 提亮；浅色主题下字是深色，白光扫过 = 字形被洗亮，
    // 同样是「一道白光扫过去」的观感，而且比叠黑看得清楚得多（叠黑等于黑字上再叠黑，什么也看不见）。
    // ⚠ 浅色主题下这一层的**底噪**由调用方置 0（见 LyricRow 的 drawWithContent）——
    //   光带之外不去动深色字，避免把对比度抹平。
    val ink = Color.White

    drawContext.canvas.saveLayer(Rect(0f, 0f, w, h), Paint())
    drawOriginal()
    // ① 非透明像素全部染成光色（alpha 保留 ⇒ 字形边缘的抗锯齿不丢）
    drawRect(ink, blendMode = BlendMode.SrcIn)
    // ② 移动光斑遮罩：TileMode.Clamp ⇒ 光斑半径之外自动落在「底噪」那一档上，
    //    这正是参考图里「光晕之外还有一层淡底光」的观感，不需要额外再画一层。
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = peak),
                Color.White.copy(alpha = base + (peak - base) * 0.72f),
                Color.White.copy(alpha = base + (peak - base) * 0.26f),
                Color.White.copy(alpha = base),
            ),
            center = Offset(cx, h / 2f),
            radius = radius,
        ),
        blendMode = BlendMode.DstIn,
    )
    drawContext.canvas.restore()
}

fun parseLrc(raw: String): List<LyricLine> {
    val out = mutableListOf<LyricLine>()
    raw.lines().forEach { line ->
        val matches = LRC_REGEX.findAll(line).toList()
        if (matches.isEmpty()) return@forEach
        val text = line.substringAfterLast(']').trim()
        if (text.isEmpty()) return@forEach
        matches.forEach { match ->
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fraction = match.groupValues[3].ifEmpty { "0" }
            val fractionMs = when (fraction.length) {
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.take(3).toLong()
            }
            out.add(LyricLine(minutes * 60_000 + seconds * 1000 + fractionMs, text))
        }
    }
    return out.sortedBy { it.timeMs }
}

fun parseLrcTrans(raw: String): Map<Long, String> {
    val out = mutableMapOf<Long, String>()
    raw.lines().forEach { line ->
        val match = LRC_REGEX.find(line) ?: return@forEach
        val text = line.substringAfterLast(']').trim()
        if (text.isEmpty()) return@forEach
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3].ifEmpty { "0" }
        val fractionMs = when (fraction.length) {
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        out[minutes * 60_000 + seconds * 1000 + fractionMs] = text
    }
    return out
}

private fun Map<Long, String>.transFor(timeMs: Long): String? =
    this[timeMs] ?: entries.filter { abs(it.key - timeMs) <= 500 }
        .minByOrNull { abs(it.key - timeMs) }?.value

private fun Map<Long, String>.nearFor(timeMs: Long): String? =
    this[timeMs] ?: entries.filter { abs(it.key - timeMs) <= 500 }
        .minByOrNull { abs(it.key - timeMs) }?.value

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsScreen(nav: NavHostController) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(ambientSurfaceColor()),
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scope = rememberCoroutineScope()
        val song = PlayerEngine.current
        val viewportHeight = maxHeight
        val circleDiameter = minOf(maxWidth, maxHeight)
        // A centered text band stays inside the round screen's useful area and leaves room for
        // the right-edge progress arc. This is in Dp and follows the measured window constraints.
        val lyricSafeWidth = circleDiameter * 0.68f
        // ★ 2026-10-01 歌词取色（用户口径「自动取色范围延伸到歌词上面，同时保证歌词可读性」）：
        //   歌词正文不再是恒定白，改用「自动取色」的强调色（见 theme/Theme.kt 的 lyricInk）。
        //   底色必须传**本页真实底色**，不是 Bg —— 环境色模式下本页铺的是歌曲环境色
        //   （封面主色向近黑压暗 75%，自带彩度、亮度随歌变化），对比度得按它算；
        //   只有「有自定义背景图且关了保留取色」时本页才透明透出背景图，那种情况按 Bg 标定
        //   （与全站文字同一个假设）。
        //   ⚠ 只在页面层算一次并逐行下传：LyricRow 的颜色必须是**重组周期内的常量**，
        //     行内逐帧改颜色会让 Paragraph 缓存整体失效（等价每帧重排版，见 LyricRow 注释）。
        val lyricInkColor = lyricInk(
            if (!BackgroundStore.ready || BackgroundStore.keepAmbient) SongAmbient.color else Bg
        )
        val backFader = remember(density) {
            // ★ scaleMode = false：歌词页 → 播放页保持**位移滑动**，不用缩放
            //   （用户明确要求「歌词界面切换到播放界面保留滑动切换动画」）
            SwipeBackFader(with(density) { 34.dp.toPx() }, scope, scaleMode = false)
        }
        // 滑出目标 = 屏宽（松手速度接续滑出用）
        val screenPx = with(density) {
            androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
        }
        var swipeLifted by remember { mutableStateOf(false) }
        var lines by remember(song?.id) { mutableStateOf<List<LyricLine>>(emptyList()) }
        var trans by remember(song?.id) { mutableStateOf<Map<Long, String>>(emptyMap()) }
        var romaMap by remember(song?.id) { mutableStateOf<Map<Long, String>>(emptyMap()) }
        var loading by remember(song?.id) { mutableStateOf(true) }
        var userScrolling by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()

        LaunchedEffect(song?.id) {
            lines = emptyList()
            trans = emptyMap()
            romaMap = emptyMap()
            loading = true
            if (song != null) {
                // 冷启动切歌词页卡帧根因（2026-09-25）：本地歌词文件读 + 全量 LRC 正则解析
                // 此前在主线程协程同步执行（转场动画的前几帧正好被堵）——全部挪到 Default 线程，
                // 网络请求本身是 suspend 不变；解析完回主线程一次性赋值
                val sid = song.id
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val local = DownloadStore.localLyric(sid)
                    if (local != null) {
                        val l = local.first; val t = local.second
                        val localRoma = DownloadStore.localRomaText(sid)
                        val rm = if (localRoma != null) {
                            parseLrc(localRoma).associate { it.timeMs to it.text }
                        } else {
                            runCatching {
                                val roma = NcmApi.lyricV1(sid).roma
                                if (roma.isNullOrBlank()) null else {
                                    DownloadStore.saveRoma(sid, roma)
                                    parseLrc(roma).associate { it.timeMs to it.text }
                                }
                            }.getOrNull() ?: emptyMap()
                        }
                        lines = l; trans = t; romaMap = rm
                    } else {
                        val bundle = NcmApi.lyricV1(sid)
                        lines = parseLrc(bundle.lrc ?: "")
                        trans = parseLrcTrans(bundle.trans ?: "")
                        romaMap = parseLrc(bundle.roma ?: "").associate { it.timeMs to it.text }
                    }
                    loading = false
                }
            } else {
                loading = false
            }
        }

        val activeIdx by remember(lines) {
            derivedStateOf {
                val position = PlayerEngine.positionMs
                var active = -1
                lines.forEachIndexed { index, line -> if (line.timeMs <= position) active = index }
                active
            }
        }
        var showRoma by rememberSaveable { mutableStateOf(false) }

        // 亚像素补偿量（2026-10-01 五轮）：整数滚动追不到的那半个像素以内，交给每行的
        // graphicsLayer{translationY}。只被 graphicsLayer 延迟读取 → 每帧写它不会引起任何重组合。
        var glideTy by remember(lines) { mutableStateOf(0f) }

        // Scroll to the measured center of the active row. If it was offscreen, first bring it
        // into the lazy list's viewport, then use its actual measured offset and height.
        // 首次同步（进页/切歌）硬定位当前行，不从顶部做长距离滚动动画（2026-09-25）
        var positionedForSong by remember(lines) { mutableStateOf(false) }
        // 高亮锚点的「连续行坐标」（2026-09-30）：整数 activeIdx 的平滑版本。
        // 每行的高亮强度由 |index - anchorPos| 推出 —— 因为 anchorPos 用与滚动完全相同的
        // 曲线/时长、在同一帧启动，整屏的明暗渐变就与滚动锁相，即参考界面的核心观感。
        // 注意：这是唯一的动画驱动源，LyricRow 内部只在 graphicsLayer 里读它（延迟读 →
        // 不触发任何行的重组合，也不触发任何文本重新排版）。
        // 初值 -1 = 「尚无当前行」（前奏期间），此时第一行处于半亮，唱到时滑到满亮。
        val anchorPos = remember(lines) { Animatable(-1f) }
        // 行距拉伸量（0=常态，1=峰值）。与 anchorPos 同帧启动、同缓动，各自衰减。
        // 同样只在 LyricRow 的 graphicsLayer 里被读（延迟读 → 零重组合）
        val stretchAnim = remember(lines) { Animatable(0f) }

        // ★ 2026-10-02 白色扫光进度（用户口径②）：0 = 光斑还在行左端外侧，
        //   1 = 已完全扫出行右端外侧；< 0 = 不扫光，只保留底噪提亮（前奏/暂停/已扫完）。
        //   ⚠ 只在 LyricRow 的 drawWithContent 里被读（延迟读）→ 每帧写它不引起任何重组。
        val sweepFrac = remember(lines) { mutableFloatStateOf(-1f) }
        val lyricPlaying = PlayerEngine.isPlaying
        // ★ 2026-10-02 设置页「歌词扫光」开关（AppearancePrefs.lyricSweep）：
        //   关 = sweepFrac 恒 -1，即「不扫光，只保留底噪提亮」—— 聚焦行提亮（用户口径①）
        //   不随扫光开关连带消失。开关本身是 Compose 状态，读在这里会让本组合自动订阅，
        //   切换开关时本 LaunchedEffect 重建、立即生效，无需重进歌词页。
        val sweepOn = AppearancePrefs.lyricSweep
        LaunchedEffect(lines, activeIdx, lyricPlaying, sweepOn) {
            val i = activeIdx
            if (!sweepOn || !lyricPlaying || i < 0 || i >= lines.size) {
                // 扫光开关关闭 / 暂停 / 前奏（还没有当前行）/ 歌词为空：不扫光（底噪提亮仍然保留）
                sweepFrac.floatValue = -1f
                return@LaunchedEffect
            }
            // 「同步该句速度」：扫光时长 = 这一句的持续时长（= 下一句的时间戳 − 本句时间戳）。
            val startMs = lines[i].timeMs
            val nextMs = lines.getOrNull(i + 1)?.timeMs ?: (startMs + LYRIC_SWEEP_FALLBACK_MS.toLong())
            val span = (nextMs - startMs).toFloat().coerceIn(LYRIC_SWEEP_MIN_MS, LYRIC_SWEEP_MAX_MS)
            // 连续播放进度 =「最近一次 positionMs 采样」+「按帧时间外推」。
            // PlayerEngine 的 ticker 是 500ms 一跳（见 PlayerEngine.ticker），直接拿 positionMs
            // 当光斑位置会得到 2fps 的台阶；外推后每帧连续，而每来一个新采样就重新锚定 ⇒
            // 与真实进度长期不漂移，seek 也能自动跟上。
            var sampleMs = PlayerEngine.positionMs
            var sampleRt = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val latest = PlayerEngine.positionMs
                if (latest != sampleMs) {
                    sampleMs = latest
                    sampleRt = now
                }
                val pos = sampleMs + (now - sampleRt) / 1_000_000f
                val f = (pos - startMs) / span
                if (f >= 1f) {
                    // 光斑已扫出行右端：停在「只提亮」状态，不再逐帧空转
                    sweepFrac.floatValue = -1f
                    break
                }
                sweepFrac.floatValue = f.coerceAtLeast(0f)
            }
        }

        LaunchedEffect(lines, activeIdx, userScrolling) {
            if (userScrolling) return@LaunchedEffect
            val active = activeIdx
            val itemCount = lines.size
            if (itemCount == 0) return@LaunchedEffect

            // ① 本曲首次就位（进页 / 切歌 / 歌词刚解析出来）：只做一次硬定位。
            //    ⚠ 这里必须先判「是否已就位」，再判 activeIdx —— 早期版本把 `active < 0`
            //    提前返回写在前面，导致**前奏期间 positionedForSong 永远不置位**：
            //    用户在前奏点歌词跳转时，第一次激活恰好命中这个硬定位分支，
            //    滚动的曲线动画被 scrollToItem 吃掉，表现为「硬切」（2026-09-30 实测 bug）。
            if (!positionedForSong) {
                positionedForSong = true
                if (active < 0) {
                    // 前奏中：没有「当前行」可对齐，列表停在顶部即默认位置，
                    // 锚点置 -1（无高亮行）；第一句唱到时正常走曲线滑入
                    anchorPos.snapTo(-1f)
                    stretchAnim.snapTo(0f)
                    return@LaunchedEffect
                }
                val target = active + 2 // title and top spacer
                listState.scrollToItem(target)
                withFrameNanos { }
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                if (info != null) {
                    val viewport = listState.layoutInfo
                    val viewportCenter = (viewport.viewportStartOffset + viewport.viewportEndOffset) / 2f
                    val correction = ((info.offset + info.size / 2f) - viewportCenter).roundToInt().toFloat()
                    if (correction != 0f) listState.scrollBy(correction)
                }
                // 硬定位 → 高亮锚点与拉伸量必须一起瞬移，否则会看到一次从上行扫过来的假动画
                anchorPos.snapTo(active.toFloat())
                stretchAnim.snapTo(0f)
                glideTy = 0f   // 换歌是硬切，亚像素补偿必须清干净，不能留残值
                return@LaunchedEffect
            }

            // ② 之后每一次切换都走曲线；前奏期间没有切换可言
            if (active < 0) return@LaunchedEffect
            val target = active + 2 // title and top spacer

            /**
             * 把目标行滑到视口中心 —— **自带亚像素补偿**（2026-10-01 五轮根治）。
             *
             * 为什么不能用 `animateScrollBy`：`LazyListItemInfo.offset` / `viewportStartOffset` /
             * `viewportEndOffset` 全是 `Int`，**LazyColumn 的列表项只能落在整数像素上**。
             * 于是缓动的慢速尾段必然退化成「每 2~3 帧才跳 1px」的台阶 —— 录屏逐帧实测：
             * 焦点行上边缘 `211→210→210→209→209→209→208→208`，而当时真实速度只有 ~0.5px/帧。
             * 这就是用户报的「自动切换歌词时有轻微像素偏移」：不是落位偏了（实测每次都精确落在
             * 同一行），而是**慢速段一格一格地跳**。
             *
             * 做法：整数滚动只追「连续目标位置的最近整数」，把不超过 ±0.5px 的余量交给每行的
             * `graphicsLayer { translationY }`（纯渲染层变换，不受整数约束）。
             * 于是绘制位置 = -applied - (want - applied) = -want **完全连续**，一帧台阶都没有；
             * 而整数滚动与连续位置的偏差恒 ≤ 0.5px ⇒ 顶/底最多露出半像素的空带，肉眼不可见
             * （而且那一条带本来就被边缘模糊遮挡层盖住）。
             * 终点 want 正好是整数 ⇒ applied = 目标、余量 = 0 ⇒ 收尾干净，落位仍旧是整数像素。
             */
            suspend fun glideRowToCenter(
                durationMs: Int = LYRIC_GLIDE_MS,
                easing: androidx.compose.animation.core.Easing = LyricGlide,
            ) {
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                if (info == null) {
                    glideTy = 0f
                    return
                }
                val viewport = listState.layoutInfo
                val viewportCenter = (viewport.viewportStartOffset + viewport.viewportEndOffset) / 2f
                val total = ((info.offset + info.size / 2f) - viewportCenter).roundToInt()
                if (total == 0) {
                    glideTy = 0f
                    return
                }
                var applied = 0
                val t0 = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val u = ((now - t0) / 1_000_000f / durationMs).coerceIn(0f, 1f)
                    val want = total * easing.transform(u)
                    val whole = want.roundToInt()
                    if (whole != applied) {
                        listState.scrollBy((whole - applied).toFloat())
                        applied = whole
                    }
                    // 余量 ≤ 0.5px，交给渲染层；终端帧 want == total ⇒ 余量归零
                    glideTy = applied - want
                    if (u >= 1f) break
                }
                if (applied != total) {
                    listState.scrollBy((total - applied).toFloat())
                }
                glideTy = 0f
            }

            // 目标行在视口外（大幅跳转/快进）：先把它卷进视口 —— 这一段不做曲线，
            // 距离可能几十行，套 630ms 曲线会变成慢速长滚。锚点照常动画（高亮不能僵在旧行）
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                listState.animateScrollToItem(target)
                withFrameNanos { }
            }
            // 高亮锚点与滚动同一帧启动、同一曲线同时长 —— 两者锁相。
            // 但瞬移（跨度 > LYRIC_ANCHOR_SNAP_LINES 行）改为直接落位：见常量处的说明。
            val sweep = abs(active - anchorPos.value)
            if (sweep > LYRIC_ANCHOR_SNAP_LINES) {
                anchorPos.snapTo(active.toFloat())
                stretchAnim.snapTo(0f)
            } else {
                launch {
                    anchorPos.animateTo(active.toFloat(), tween(LYRIC_GLIDE_MS, easing = LyricGlide))
                }
                // 行距拉伸回弹：起跳 190ms 到峰值，再 470ms 长尾弹回。
                // 不做 snapTo(0) —— 连打时（一句还没弹完下一句就到了）从当前值继续拉起，避免跳变；
                // 新的 animateTo 会通过 Animatable 的 MutatorMutex 自动取消上一次动画
                launch {
                    stretchAnim.animateTo(1f, tween(LYRIC_STRETCH_ATTACK_MS, easing = LyricGlide))
                    stretchAnim.animateTo(0f, tween(LYRIC_STRETCH_RELEASE_MS, easing = LyricGlide))
                }
            }
            glideRowToCenter()
            // ★ 2026-10-01 说明：四轮删掉的「残余校正」不是元凶（删完用户仍报偏移）。
            //   真正的原因是上面 glideRowToCenter 注释里写的那条 —— LazyColumn 只能整数滚动，
            //   慢速尾段退化成 1px 台阶。现在由亚像素补偿根治，不需要残余校正，也不要再往回加。
        }
        LaunchedEffect(userScrolling) {
            if (userScrolling) {
                delay(3500)
                userScrolling = false
            }
        }

        val lyricEdgeCorePx = LYRIC_EDGE_CORE_PX
        val lyricEdgeSpreadPx = with(density) { LYRIC_EDGE_SPREAD.toPx() }
        // 边缘模糊强度（2026-10-01 二轮）：手动浏览（表冠/拖动）时整体淡出，边缘恢复全清晰
        // —— 用户口径「滑动浏览下方未播放歌词时，模糊渐变消失」。userScrolling 由
        // rotaryList / 拖动手势置位，3.5s 无操作后自动复位（见上方 LaunchedEffect）。
        val edgeBlurK by animateFloatAsState(
            targetValue = if (userScrolling) 0f else 1f,
            animationSpec = tween(220),
            label = "lyricEdgeBlur",
        )
        // ★ 2026-10-01 三轮（用户口径「点击切换歌词…伴有卡顿」）：
        //   模糊层（整屏 RenderEffect 高斯模糊 + **第二份完整歌词列表**）是歌词页最贵的开销；
        //   它还要每帧跟主列表做 scrollToItem 同步。edgeBlurK 归零时它已彻底看不见，却仍在合成。
        //   这里只在「确实可见」时才把它放进组合，并让同步循环一起歇掉。
        //   ⚠ 必须用 derivedStateOf 包成布尔：直接读 edgeBlurK 会让 220ms 淡入淡出期间
        //   **逐帧重组整个歌词页**，反而更卡；包成布尔后只在「可见/不可见」翻转的那一帧重组。
        val blurLayerVisible by remember { derivedStateOf { edgeBlurK > 0.001f } }
        val edgeMaskActive = lines.isNotEmpty() && blurLayerVisible

        // 边缘模糊层用独立滚动状态（2026-10-01）：绝不能与主列表共用 LazyListState ——
        // layoutInfo 是单实例，两份列表会互相覆盖，直接破坏上方的「自动跟随定位」。
        val edgeListState = rememberLazyListState()
        LaunchedEffect(edgeMaskActive) {
            // 模糊层不在组合里 → 镜像列表没挂载，没必要跑这个每帧 scrollToItem 的循环
            if (!edgeMaskActive) return@LaunchedEffect
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (i, o) ->
                    if (edgeListState.firstVisibleItemIndex != i ||
                        edgeListState.firstVisibleItemScrollOffset != o
                    ) {
                        edgeListState.scrollToItem(i, o)
                    }
                }
        }

        // 歌词正文（渲染两份：底层模糊 / 上层清晰带边缘擦除）；modifier 由调用处决定
        val lyricBody: @Composable (Modifier, Boolean) -> Unit = { m, isEdgeLayer ->
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                LazyColumn(
                    state = if (isEdgeLayer) edgeListState else listState,
                    modifier = m,
                ) {
                item(key = "title") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            song?.title ?: "歌词",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            modifier = Modifier
                                .widthIn(max = lyricSafeWidth)
                                .fillMaxWidth()
                                .padding(top = 52.dp, bottom = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (loading) {
                    item(key = "loading") {
                        Text(
                            "歌词加载中…",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(top = viewportHeight * 0.30f),
                            textAlign = TextAlign.Center,
                        )
                    }
                } else if (lines.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "暂无歌词",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(top = viewportHeight * 0.30f),
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    item(key = "pad_top") { Spacer(Modifier.height(24.dp)) }
                    itemsIndexed(
                        lines,
                        key = { index, line -> "l:${line.timeMs}:$index:${line.text}" },
                        contentType = { _, _ -> "lyric" },
                    ) { index, line ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            LyricRow(
                                modifier = Modifier.widthIn(max = lyricSafeWidth).fillMaxWidth(),
                                seekTimeMs = line.timeMs,
                                // 延迟读：距离只在 graphicsLayer 阶段求值，anchorPos 每帧变化
                                // 不会引起本行重组合，也不会让文本重新测量/排版
                                // 有符号：正 = 在当前行下方（行距拉伸要靠符号决定推开方向）
                                signedDistance = { index - anchorPos.value },
                                stretchAmount = { stretchAnim.value },
                                glideOffset = { glideTy },
                                // 白色扫光进度（延迟读：只在绘制期求值，见 drawLyricSweep）。
                                // 边缘模糊镜像层传 NaN = 整层不做扫光：那层只在屏幕最上下缘露出，
                                // 而焦点行恒在屏幕中央，画了也看不见，白搭一次离屏合成 + 一次文本重绘。
                                sweepFrac = { if (isEdgeLayer) Float.NaN else sweepFrac.floatValue },
                                // 歌词取色：页面层算好的常量（已按本页底色做对比度限幅）
                                tint = lyricInkColor,
                                mainText = line.text,
                                translation = trans.transFor(line.timeMs),
                                roma = romaMap.nearFor(line.timeMs),
                                showRoma = showRoma,
                            )
                        }
                    }
                    item(key = "pad_bottom") { Spacer(Modifier.height(viewportHeight / 2f)) }
                }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .zIndex(if (swipeLifted) 2f else 0f)
                .graphicsLayer {
                    alpha = backFader.alpha
                    translationX = backFader.slidePx
                    scaleX = backFader.scale
                    scaleY = backFader.scale
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(onDragStart = { userScrolling = true }) { change, amount ->
                        change.consume()
                        listState.dispatchRawDelta(amount)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { backFader.onDragStart(); swipeLifted = true },
                        onDragEnd = {
                            val goBack = backFader.passedThreshold
                            backFader.onDragEnd(goBack)
                            if (goBack) {
                                // ★ 2026-10-01：歌词页 → 播放页保持**滑动**转场（用户要求）。
                                //   所以这里**不武装手势旗标**：一旦武装，NavHost 就走「淡出 + 淡入」
                                //   的手势分支；不武装则按 LYRICS entry 自己记的 Slide 来源走
                                //   popExit/popEnter 的滑动分支（旧页左移滑出、播放页从左侧滑入），
                                //   与 fader 的位移滑出接成一条连贯的横向滑动。
                                val ms = backFader.flingOutDuration(screenPx)
                                nav.popBackStack()
                                backFader.flingOut(screenPx, ms)
                            } else {
                                swipeLifted = false
                            }
                        },
                        onDragCancel = { backFader.onDragCancel(); swipeLifted = false },
                    ) { change, amount ->
                        change.consume()
                        backFader.onDrag(change, amount)
                    }
                }
                .rotaryList(listState, sensitivity = 0.4f, onUserInput = { userScrolling = true }),
        ) {
            // 禁用列表边缘 stretch 拉伸（Android 12+ 果冻回弹）：歌词页高频触边（自动跟随+表冠滚），
            // 拉伸回弹在圆表上被放大观感且费 GPU —— 关闭后到边即停，回弹彻底消失
            //
            // 2026-10-01 边缘高斯模糊遮挡：
            //   ① 底层 = 整屏模糊歌词（只有上下边缘会被看见）
            //   ② 上层 = 清晰歌词，用垂直渐变把上下边缘擦掉（DstIn）→ 擦掉处露出底层模糊
            //   渐变关键点：距边框 16px 内保持完全遮挡（擦净），之后 55dp 内渐变过渡到清晰。
            //   「发音」胶囊在下面最后绘制，始终位于两层之上，不会被模糊遮住。
            //   edgeMaskActive 在上方定义（模糊淡尽后整层退出组合）。
            if (edgeMaskActive) {
                Box(
                    Modifier
                        .fillMaxSize()
                        // 浏览中整层淡出（alpha 走渲染层，不触发布局、不重排文本）
                        .graphicsLayer { alpha = edgeBlurK }
                        .blur(LYRIC_EDGE_BLUR),
                ) {
                    lyricBody(Modifier.fillMaxSize(), true)
                }
            }
            lyricBody(
                Modifier
                    .fillMaxSize()
                    .then(
                        // 没有模糊层时（lines 为空，或模糊已淡尽）不必再做 Offscreen 离屏合成 +
                        // 边缘擦除 —— 擦除量此时为 0，做与不做画面完全一致，但离屏合成是实打实的开销。
                        if (!edgeMaskActive) Modifier
                        else Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                val end = ((lyricEdgeCorePx + lyricEdgeSpreadPx) / size.height)
                                    .coerceIn(0.02f, 0.49f)
                                // DstIn：src.alpha 直接乘进目标 alpha —— 边缘 alpha 越接近 0 擦得越干净
                                // （露出底层模糊）。edgeBlurK→0（浏览中）时边缘 alpha→1，等于完全不擦，
                                // 歌词恢复全清晰，与模糊层的淡出同步。
                                val erase = 1f - edgeBlurK
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = erase),
                                        end to Color.Black,
                                        1f - end to Color.Black,
                                        1f to Color.Black.copy(alpha = erase),
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                    ),
                false,
            )

            CircularSideProgress(
                progress = {
                    val count = lines.size
                    val active = activeIdx
                    if (count == 0) 0.15f else ((active + 1).toFloat() / (count + 1)).coerceIn(0.15f, 1f)
                },
                modifier = Modifier.fillMaxSize(),
            )
            // ★ 2026-10-01 用户口径「没有发音文件的歌词不显示发音按钮」：
            //   之前是按钮恒在、没有罗马音时置灰 —— 置灰仍然占着顶部那条位置。
            //   现在这首歌没有 roma 数据就整个不画，顶部干净。
            if (romaMap.isNotEmpty()) {
                ArcPill(
                    label = "发音",
                    active = showRoma,
                    onToggle = { showRoma = !showRoma },
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = 10.5.dp),
                )
            }
        }
    }
}

@Composable
private fun LyricRow(
    modifier: Modifier,
    seekTimeMs: Long,
    signedDistance: () -> Float,
    stretchAmount: () -> Float,
    /** 亚像素滚动补偿（2026-10-01 五轮）：整列一起平移，只补 |·| ≤ 0.5px 的零头 */
    glideOffset: () -> Float,
    /**
     * 白色扫光进度（2026-10-02）：0 = 光斑在行左端外侧，1 = 已扫出行右端外侧，
     * < 0 = 不扫光（前奏/暂停/已扫完），只保留底噪提亮；**NaN = 本层完全不做扫光**。
     * 同样是延迟读。
     */
    sweepFrac: () -> Float,
    /**
     * 歌词取色（2026-10-01）：由 LyricsScreen 算好的常量（见 theme/Theme.kt 的 lyricInk）。
     * 必须是常量 —— 逐帧变化的颜色会让 Paragraph 缓存整体失效，等价于每帧重排版。
     */
    tint: Color,
    mainText: String,
    translation: String? = null,
    roma: String? = null,
    showRoma: Boolean,
) {
    // 流畅度专项（骁龙 W5）：
    // ① 字号/字重/内边距逐帧动画会强制每帧文本重新测量+排版+布局 —— 歌词页掉帧元凶，
    //    改为固定排版 + graphicsLayer 缩放（纯渲染层，零重排版）。
    // ② 2026-09-30 二代：连「颜色动画」也移出 Text。animateColorAsState 每帧产出新
    //    TextStyle，会让 Paragraph 缓存整体失效，等价于每帧重排版。现在文字颜色全部为常量，
    //    明暗完全由图层 alpha 承担，并且随「与当前行的连续距离」连续变化。
    // ③ 同二代：行距拉伸回弹也走 translationY —— 只推间距、不动字形、不触发布局。
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { PlayerEngine.seekTo(seekTimeMs) }
            .graphicsLayer {
                // 唯一每帧变化的状态在这里求值（延迟读）：只失效本行图层，
                // 不触发重组合，也不触发任何文本测量/排版
                val d = signedDistance()
                // |d| ≤ 0.001 即「这一行就是当前行」—— alpha 必须严格取 1f
                val focused = abs(d) <= 0.001f
                // 字号缩放：
                //  二轮：上限锁 1.0（当前行不放大，避免放大带来的重采样）。
                //  三轮：删掉 1/16 档位量化（量化会在阈值处硬跳 6.25%，就是「缩放抽动」）。
                //  四轮（用户口径「切换歌词还是有轻微的像素偏移」）：见 lyricScaleAmount 的注释 ——
                //    **非整数 scale 本身就会带来亚像素位移**，所以可读带（当前行 ±2 行）内一律
                //    保持 scale = 1.0 的恒等变换，缩放层级只在被模糊掉的远端体现。
                val scale = 1f - LYRIC_SCALE_DEPTH * lyricScaleAmount(d)
                scaleX = scale
                scaleY = scale
                // 当前行必须**严格** alpha = 1f。只要差一个 ULP（0.99999994）——
                // 硬件就会把它当成半透明图层，为此多走一次离屏合成；
                // 半透明 + 变换正是亚像素错位的另一条来路。
                // ★ 2026-10-01 六轮：亮度改用随距离连续衰减的渐变曲线（见 lyricBrightness 注释）。
                alpha = if (focused) 1f else lyricBrightness(d)
                // 行距拉伸：以当前行为中心把各行向外推开（d=0 的当前行不动，即保持锚定）。
                // 位移量 = 行距离 × 行高 × 拉伸比例 —— 比例相同 ⇒ 所有间距等比拉伸，
                // 这正是参考界面「整列拉开再弹回」的观感；字形因不参与缩放而完全不变形。
                // ＋ 亚像素滚动补偿（glideOffset，|·| ≤ 0.5px）：让整数滚动看不见的零头也连续。
                translationY = d * size.height * (LYRIC_STRETCH_PEAK * stretchAmount()) + glideOffset()
            }
            // ★ 2026-10-02 聚焦行提亮 + 白色光线扫过（用户口径①②，实现与理由见 drawLyricSweep）。
            //   放在 graphicsLayer 之后、padding 之前：绘制范围 = 整行（含内边距），
            //   光斑可以完整地从行左端扫到右端，不会被内边距提前截断。
            .drawWithContent {
                val d = signedDistance()
                val f = sweepFrac()
                // NaN = 本层不做扫光（边缘模糊镜像层用，见调用处：那层只在屏幕最上下缘露出，
                // 而焦点行恒在屏幕中央，画了也看不见，白费一次离屏合成 + 一次文本重绘）
                if (f.isNaN() || abs(d) > LYRIC_SWEEP_BAND) {
                    drawContent()
                    return@drawWithContent
                }
                // 按与焦点行的距离线性淡出 —— 与 anchorPos 的 630ms 滑动共用同一根时间轴，
                // 所以切换歌词时光斑是「跟着明暗整片滑过去」的，不是硬切
                val focusK = 1f - abs(d) / LYRIC_SWEEP_BAND
                val bandOn = f >= 0f
                // ★ 浅色主题：聚焦行是**深色字**，再整体叠白等于把「深字压浅底」的对比度抹平 ——
                //   所以浅色主题下底噪置 0，只保留**移动光带**那一下（瞬时白光扫过字形）。
                //   深色主题底噪照旧（彩色/亮字整体提亮 = 用户口径①的「提高亮度」）。
                //   两个主题共用同一条光带、同一套位置/时长，观感完全同步。
                val base = if (com.ncm.watch.ui.theme.isLightTheme) 0f else LYRIC_SWEEP_BASE * focusK
                val peak = if (bandOn) LYRIC_SWEEP_PEAK * focusK else base
                if (peak <= 0.01f) {
                    drawContent()
                    return@drawWithContent
                }
                val w = size.width
                val h = size.height
                // 光斑半径与中心：frac = 0 时整个光斑在行左端外侧，= 1 时已扫出右端外侧
                val radius = (w * LYRIC_SWEEP_RADIUS).coerceAtLeast(1f)
                val cx = -radius + f.coerceIn(0f, 1f) * (w + radius * 2f)
                // ★ 2026-10-02 用户口径「扫光仅在歌词字体上面显示」：原「第①层」铺在材质上的
                //   背景光晕（BlendMode.Plus 的径向白光）已整体删除 —— 光只经下面的
                //   drawLyricSweep 以 SrcIn 剪影落在字形不透明像素上，行的底色不再发光。
                drawContent()
                // ── 字形提亮 + 白色光线扫过（画在文字之上，见 drawLyricSweep）──
                drawLyricSweep(
                    drawOriginal = { drawContent() },
                    base = base,
                    peak = peak,
                    cx = cx,
                    radius = radius,
                )
            }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            mainText,
            fontSize = 15.sp,
            lineHeight = 16.sp,
            // Apple Music 材质（2026-09-25）：所有行同等超重 W900，仅靠图层 alpha 分层
            fontWeight = FontWeight.Black,
            // 2026-10-01 主题化：浅色主题下歌词页是浅底，白字会彻底消失
            // ★ 2026-10-01 歌词取色：改为「自动取色」的强调色（已按本页底色限幅保证对比度）。
            //   明暗分层仍由外层 graphicsLayer 的 alpha 承担，这里只定色相。
            color = tint,
            textAlign = TextAlign.Center,
        )
        if (showRoma && !roma.isNullOrEmpty()) {
            Text(
                roma,
                fontSize = 9.5.sp,
                lineHeight = 10.sp,
                color = tint.copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )
        }
        if (!translation.isNullOrEmpty()) {
            Text(
                translation,
                fontSize = 9.5.sp,
                lineHeight = 10.sp,
                color = tint.copy(alpha = 0.70f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ArcPill(
    label: String,
    active: Boolean,
    // 2026-10-01：没有发音数据的歌连按钮都不画了，这个开关就没了用武之地，改成默认可用
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit = {},
) {
    val foreground = when {
        active -> OnAccent
        !enabled -> TextSecondary.copy(alpha = 0.4f)
        else -> TextSecondary
    }
    Text(
        label,
        fontSize = 10.sp,
        color = foreground,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Accent else Color(0xB3202020))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(horizontal = 11.dp, vertical = 4.dp),
    )
}
