package com.netmusiclite.ui.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 表冠 → LazyList 动画滚动 + 惯性滑行 + 可选吸附。
 *
 * ColorOS 手表的表冠走标准 Android 旋入式输入（SOURCE_ROTARY_ENCODER /
 * MotionEvent.AXIS_SCROLL，Compose onRotaryScrollEvent 的 verticalScrollPixels
 * 已按系统 verticalScrollFactor 缩放），无 OPPO 专用 API。
 *
 * ★ v2 适配要点（v1 因 dispatchRawDelta 逐事件跳变被反馈"过快"，后全局禁用）：
 * 1) 累积管道：事件像素先累积进 pending，由单个协程循环用 animateScrollBy
 *    （定长 80ms tween）消化——平滑连续、无逐格瞬跳，且单段延迟有上限；
 *    ⚠ 勿改回低刚度弹簧：弹簧收尾慢，高灵敏度下事件排队积压 → 滚动延迟大（真机实测）；
 * 2) 双通道输入：RotaryScrollEvent（表冠标准通道）+ PointerEventType.Scroll
 *    （通用滚轮通道，按系统 scaledVerticalScrollFactor 换算像素）统一进管道，
 *    Initial 消费阶段先于 LazyColumn 内建滚轮处理；
 * 3) 触感（2026-10-01 重接）：两条输入通道在**乘灵敏度之前**把原始像素增量交给
 *    NcmHaptics.crownDetent()，由它按「一个物理格 = scaledVerticalScrollFactor 像素」
 *    累积取整，每格震一记。震动走 OPPO 私有线性马达（effectType 302，
 *    见 NcmHaptics.kt 头注），非 OPPO 机型自动降级为系统 EFFECT_TICK。
 *    ⚠ 只在输入侧调用一次，勿在 pipeline/fling/snap 里补震——惯性滑行不震；
 * 4) 惯性：emit 时按事件间隔估计内容滚动速度（指数平滑），停转排空后速度
 *    达 300px/s 且距最后事件 <260ms → spline 衰减惯性滑行
 *    （2026-09-04 真机要求；阈值/窗口 2026-10-01 下调，见 FLING_* 常量注释）；
 *    滑行中再次转冠立即打断，滑到列表尽头自动停；
 * 5) 吸附：snap=true 时惯性结束后自动吸附最近项居中（机制保留，当前调用点全 false）。
 *
 * 灵敏度：0.4/0.22 真机过快，0.05 初版偏慢，按要求调至 0.5。
 * （动画管道不改变每格总位移，只改平滑度）
 * 一键禁用：ROTARY_ENABLED 改回 false，走 rotaryDisabled() 空挂载，各调用点无需改动。
 */
const val ROTARY_ENABLED = true
const val ROTARY_SENSITIVITY = 0.5f
private const val SNAP_DELAY_MS = 140L

/** 惯性速度阈值（px/s，低于此值停转即停）。
 *  ⚠ 别往上调：emit 收到的是**已乘灵敏度**的像素增量（每格≈55px），
 *  400 要每秒 7 格以上才够，中速转动永远等不到惯性（2026-10-01 实测结论）。
 *  300 ≈ 每秒 5 格，慢转单格仍不会误触发——首帧 dt 极大，速度估计≈0。*/
private const val FLING_MIN_VELOCITY = 300f

/** 最后事件宽限窗口。
 *  ⚠ 必须明显大于 ROTARY_SCROLL_SPEC.durationMillis(80ms)：停转后主循环还要把
 *  最后一截 pending 用 80ms tween 滚完，才轮到判惯性；窗口若只有 120ms，
 *  扣掉动画 + 帧抖动就会经常判「已凉」→ 惯性时有时无。取 260ms 留足余量。
 *  该窗口只决定「允不允许滑」，不引入延迟：滑行仍在排空结束的那一刻起跑。*/
private const val FLING_GRACE_MS = 260L

/** 滚动动画：定长短动画（LinearOutSlowIn，80ms）。
 *  ⚠ 不用低刚度弹簧——弹簧收尾耗时长，每段增量要等上一段 settle 才排到，
 *  高灵敏度下事件积压 = 明显滚动延迟（0.5 档真机实测）；定长 tween 把
 *  单段延迟上限锁死（110ms 仍有感，2026-09-04 真机要求再降 → 80ms），
 *  且循环每次排空全部 pending 积压，快转也追得上 */
private val ROTARY_SCROLL_SPEC = tween<Float>(
    durationMillis = 80,
    easing = LinearOutSlowInEasing,
)

/** 吸附动画：短促缓出，贴合 ColorOS 卡片吸附手感 */
private val ROTARY_SNAP_SPEC = tween<Float>(durationMillis = 160, easing = FastOutSlowInEasing)

/** 屏蔽表冠：只吞表冠事件，不做任何事；触摸手势（点击/拖动）不受影响。
 *  供需要禁用表冠的页面显式挂载（如音量页）；rotaryList/rotaryCustom 在
 *  ROTARY_ENABLED=false 时也回退到它 */
fun Modifier.rotaryDisabled(): Modifier = this
    .onRotaryScrollEvent { true }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Scroll) e.changes.forEach { it.consume() }
            }
        }
    }

/**
 * 表冠增量累积管道：emit() 把像素增量塞进 pending，单个协程循环消化；
 * 同时按事件间隔估计滚动速度，停转后触发惯性滑行（onFling）。
 * 主循环：排空积压 → 惯性滑行（转冠/到边即断）→ 回到排空 → 无事可做才结束。
 * （刻度震动在输入侧发出，管道本身不触碰 NcmHaptics；惯性滑行不震）
 */
private class RotaryPipeline(
    private val scope: CoroutineScope,
    private val state: LazyListState,
    private val flingDecay: DecayAnimationSpec<Float>,
    private val onSettled: (suspend () -> Unit)?,
) {
    private var pending = 0f
    private var job: Job? = null
    private var snapJob: Job? = null
    private var velocity = 0f      // 内容滚动速度估计 px/s（带符号）
    private var lastEmitMs = 0L

    fun emit(deltaPx: Float) {
        if (deltaPx == 0f) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            pending += deltaPx
            val dt = now - lastEmitMs
            velocity = if (dt in 1..200) {
                // 连续转动：指数平滑，稳定估计当前速度
                velocity * 0.6f + (deltaPx / dt * 1000f) * 0.4f
            } else {
                deltaPx / dt.coerceAtLeast(1L) * 1000f  // 新手势，直接取本次
            }
            lastEmitMs = now
        }
        snapJob?.cancel()
        snapJob = null
        if (job?.isActive != true) {
            job = scope.launch {
                while (true) {
                    // 1) 排空全部积压（滚动动画不震动）
                    while (true) {
                        val d = synchronized(this@RotaryPipeline) {
                            val t = pending; pending = 0f; t
                        }
                        if (abs(d) < 0.01f) break
                        state.animateScrollBy(d, ROTARY_SCROLL_SPEC)
                    }
                    // 2) 停转不久且速度够 → 惯性滑行（滑行不震动）
                    val v: Float
                    val since: Long
                    synchronized(this@RotaryPipeline) {
                        v = velocity; velocity = 0f
                        since = System.currentTimeMillis() - lastEmitMs
                    }
                    if (abs(v) >= FLING_MIN_VELOCITY && since < FLING_GRACE_MS) {
                        fling(v)
                        continue  // 滑行被打断（又转了）→ 回去排空新积压
                    }
                    break
                }
                onSettled?.let { settle ->
                    snapJob = scope.launch {
                        delay(SNAP_DELAY_MS)
                        settle()
                    }
                }
            }
        }
    }

    /** spline 衰减惯性：转冠再输入或滚到尽头即停 */
    private suspend fun fling(v: Float) {
        state.scroll {
            var last = 0f
            AnimationState(initialValue = 0f, initialVelocity = v)
                .animateDecay(flingDecay) {
                    val d = value - last
                    last = value
                    if (hasPending()) { cancelAnimation(); return@animateDecay }
                    val applied = scrollBy(d)
                    if (abs(d) > 0.5f && applied == 0f) cancelAnimation()
                }
        }
    }

    fun hasPending(): Boolean = synchronized(this) { abs(pending) > 0.01f }
}

/** 表冠滚动停稳后，把中心线最近的可见项吸附到视口中央 */
private suspend fun snapToNearestCenter(state: LazyListState) {
    val info = state.layoutInfo
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val target = info.visibleItemsInfo.minByOrNull {
        abs(it.offset + it.size / 2f - mid)
    } ?: return
    val delta = target.offset + target.size / 2f - mid
    if (abs(delta) > 1f) state.animateScrollBy(delta, ROTARY_SNAP_SPEC)
}

/** 系统滚轮刻度换算的像素系数（API 26+，本项目 minSdk 26 直取） */
private fun wheelScalePx(context: android.content.Context): Float =
    ViewConfiguration.get(context).scaledVerticalScrollFactor

/** onUserInput：任何非零表冠增量进管道前回调（歌词页用它暂停自动跟随），不参与 pipeline remember key */
fun Modifier.rotaryList(
    state: LazyListState,
    sensitivity: Float = ROTARY_SENSITIVITY,
    snap: Boolean = false,
    onUserInput: (() -> Unit)? = null,
): Modifier {
    if (!ROTARY_ENABLED) return rotaryDisabled()
    return composed {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val wheelPx = remember(context) { wheelScalePx(context) }
        val flingDecay = rememberSplineBasedDecay<Float>()
        val inputCallback = rememberUpdatedState(onUserInput)
        val pipeline = remember(state, sensitivity, snap) {
            RotaryPipeline(
                scope = scope,
                state = state,
                flingDecay = flingDecay,
                onSettled = if (snap) {
                    { snapToNearestCenter(state) }
                } else null,
            )
        }
        this
            .onRotaryScrollEvent { e ->
                if (e.verticalScrollPixels != 0f) {
                    inputCallback.value?.invoke()
                    NcmHaptics.crownDetent(e.verticalScrollPixels)
                }
                pipeline.emit(e.verticalScrollPixels * sensitivity)
                true
            }
            .pointerInput(pipeline) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Scroll) {
                            val dy = e.changes.fold(0f) { a, c -> a + c.scrollDelta.y }
                            if (dy != 0f) {
                                e.changes.forEach { it.consume() }
                                inputCallback.value?.invoke()
                                val raw = dy * wheelPx
                                NcmHaptics.crownDetent(raw)
                                pipeline.emit(raw * sensitivity)
                            }
                        }
                    }
                }
            }
    }
}

/**
 * 自由表冠回调（音量等非列表场景）。
 * onDelta 拿原始像素增量（双通道已统一为像素口径）；震动由调用方按速度分级。
 */
fun Modifier.rotaryCustom(onDelta: (Float) -> Unit): Modifier {
    if (!ROTARY_ENABLED) return rotaryDisabled()
    return composed {
        val context = LocalContext.current
        val wheelPx = remember(context) { wheelScalePx(context) }
        this
            .onRotaryScrollEvent { e ->
                if (e.verticalScrollPixels != 0f) NcmHaptics.crownDetent(e.verticalScrollPixels)
                onDelta(e.verticalScrollPixels)
                true
            }
            .pointerInput(onDelta) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Scroll) {
                            val dy = e.changes.fold(0f) { a, c -> a + c.scrollDelta.y }
                            if (dy != 0f) {
                                e.changes.forEach { it.consume() }
                                val raw = dy * wheelPx
                                NcmHaptics.crownDetent(raw)
                                onDelta(raw)
                            }
                        }
                    }
                }
            }
    }
}
