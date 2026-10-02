package com.ncm.watch.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 震动（2026-10-01 重构，按用户要求）。
 *
 * ★ 一、按钮震动全删
 *   原 click / success / tick / fast 四个入口连同全工程 71 处调用点一并删除，
 *   应用内不再存在任何「点一下就震」的反馈（含设置项、列表项、播放页喜欢/更多、
 *   进度弧拖动刻度等）。
 *
 * ★ 二、唯一保留的震动通道 = 表冠刻度 [crownDetent]
 *   对齐 OPPO 手表音乐包的做法（见《OPPO手表音乐包_接入点分析.md》§3.4 / §4.2）：
 *   - 服务：`getSystemService("linearmotor")` → `LinearmotorVibrator`
 *   - 效果：`WaveformEffect.Builder().setEffectType(t).setEffectStrength(s)
 *           .setEffectLoop(false).build()` → `vibrate(effect)`
 *   - effectType 取 **302 = EFFECT_HIGHVOLTAGE_SYSTEM_FEEDBACK_SCROLL**
 *     （真值取自本机 ROM `framework.jar` 里 WaveformEffect 的静态常量表；
 *      正是 OPPO 自研音乐包播放页表冠反馈用的那一档）
 *
 * ★ 三、健壮性
 *   - `linearmotor` 是 OPPO ROM 私有服务，非 OPPO 机型返回 null；类
 *     `android.os.linearmotorvibrator.*` 也可能不存在 —— 全程走反射 + runCatching，
 *     任何一步失败都静默降级到系统 Vibrator 的 EFFECT_TICK，绝不抛异常。
 *   - 刻度当量取系统 `ViewConfiguration.scaledVerticalScrollFactor`（≈ 一个物理格的像素
 *     数），对增量做累积取整，所以「一格震一记」与事件被拆帧无关。
 */
object NcmHaptics {

    // ------------------------------------------------------------------ 手感参数

    /** 慢转单格强度：0=LIGHT / 1=MEDIUM / 2=STRONG（OPPO 自研音乐包用 2） */
    private const val CROWN_STRENGTH_SLOW = 2

    /** 快转连转强度：连转时降为 LIGHT，避免震成一团噪 */
    private const val CROWN_STRENGTH_FAST = 0

    /** 连转判定：相邻两次刻度间隔小于此值即视为快转（ms） */
    private const val CROWN_FAST_MS = 90L

    /** 刻度保险丝：同一格事件被 ROM 拆成多帧时，防止连震（ms） */
    private const val CROWN_FUSE_MS = 18L

    /** 表冠滚动效果类型 = WaveformEffect.EFFECT_HIGHVOLTAGE_SYSTEM_FEEDBACK_SCROLL */
    private const val CROWN_EFFECT_TYPE = 302

    // ------------------------------------------------------------------ 常量与反射句柄

    private const val SERVICE_LINEARMOTOR = "linearmotor"
    private const val CLS_LINEARMOTOR = "android.os.linearmotorvibrator.LinearmotorVibrator"
    private const val CLS_WAVEFORM_EFFECT = "android.os.linearmotorvibrator.WaveformEffect"
    private const val CLS_WAVEFORM_BUILDER = "android.os.linearmotorvibrator.WaveformEffect\$Builder"

    /** 系统 Vibrator（降级通道） */
    private var vibrator: Vibrator? = null
    private var cachedTickEffect: VibrationEffect? = null

    /** OPPO 线性马达（反射） */
    private var lmService: Any? = null
    private var lmVibrate: java.lang.reflect.Method? = null
    private var ebCtor: java.lang.reflect.Constructor<*>? = null
    private var ebSetType: java.lang.reflect.Method? = null
    private var ebSetStrength: java.lang.reflect.Method? = null
    private var ebSetLoop: java.lang.reflect.Method? = null
    private var ebBuild: java.lang.reflect.Method? = null

    /** 一格表冠 ≈ scaledVerticalScrollFactor 像素 */
    private var detentPx = 64f
    private var accum = 0f
    private var lastFireMs = 0L
    private var lastDetentMs = 0L

    private var inited = false

    // ------------------------------------------------------------------ 初始化

    fun init(ctx: Context) {
        if (inited) return
        inited = true

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        runCatching { ViewConfiguration.get(ctx).scaledVerticalScrollFactor.toFloat() }
            .getOrDefault(0f)
            .let { if (it > 1f) detentPx = it }

        initLinearMotor(ctx)
    }

    /**
     * 取 OPPO 私有线性马达并把 WaveformEffect.Builder 的几个句柄一次性缓存下来。
     * 任何一步失败都只是 available 保持 false，不影响其他功能。
     */
    private fun initLinearMotor(ctx: Context) {
        runCatching {
            val svc = ctx.getSystemService(SERVICE_LINEARMOTOR) ?: return@runCatching

            // 服务实例的真实类（优先按 ROM 声明名取，取不到就用运行时类）
            val cls = runCatching { Class.forName(CLS_LINEARMOTOR) }.getOrNull()
                ?.takeIf { it.isInstance(svc) } ?: svc.javaClass

            // 有马达才继续；方法缺失时按「有」处理
            val has = runCatching {
                cls.getMethod("hasLinearMotorVibrator").invoke(svc) as? Boolean
            }.getOrNull()
            if (has == false) return@runCatching

            val effectCls = Class.forName(CLS_WAVEFORM_EFFECT)
            val builderCls = Class.forName(CLS_WAVEFORM_BUILDER)

            val vib = cls.getMethod("vibrate", effectCls)
            val ctor = builderCls.getConstructor()
            val setType = builderCls.getMethod("setEffectType", Integer.TYPE)
            val build = builderCls.getMethod("build")

            ebSetStrength = runCatching {
                builderCls.getMethod("setEffectStrength", Integer.TYPE)
            }.getOrNull()
            ebSetLoop = runCatching {
                builderCls.getMethod("setEffectLoop", java.lang.Boolean.TYPE)
            }.getOrNull()

            lmService = svc
            lmVibrate = vib
            ebCtor = ctor
            ebSetType = setType
            ebBuild = build
        }
    }

    // ------------------------------------------------------------------ 表冠刻度

    /**
     * 表冠刻度震动。由 Rotary.kt 的两条输入通道（RotaryScrollEvent / Scroll 指针事件）
     * 在**乘灵敏度之前**调用，deltaPx 为原始像素增量。
     *
     * 内部按 [detentPx] 累积取整：走满一个物理格 → 震一记。
     * 慢转用 STRONG、连转用 LIGHT。
     */
    fun crownDetent(deltaPx: Float) {
        if (deltaPx == 0f || !deltaPx.isFinite()) return

        accum += deltaPx
        val steps = (accum / detentPx).toInt()   // 截断，未满一格不震
        if (steps == 0) return

        val now = System.currentTimeMillis()
        // 保险丝：命中时保留 accum，等下一次事件一起消化，避免被拆帧震成连响
        if (now - lastFireMs < CROWN_FUSE_MS) return

        accum -= steps * detentPx
        if (abs(accum) > detentPx * 4f) accum = 0f   // 异常抖动兜底

        val fast = now - lastDetentMs < CROWN_FAST_MS
        lastFireMs = now
        lastDetentMs = now
        fire(if (fast) CROWN_STRENGTH_FAST else CROWN_STRENGTH_SLOW)
    }

    // ------------------------------------------------------------------ 触发

    private fun fire(strength: Int) {
        if (fireLinearMotor(strength)) return
        fireSystem()
    }

    /** OPPO 线性马达：成功返回 true，失败（无服务/反射异常）返回 false 交给降级 */
    private fun fireLinearMotor(strength: Int): Boolean {
        val svc = lmService ?: return false
        val ctor = ebCtor ?: return false
        val setType = ebSetType ?: return false
        val build = ebBuild ?: return false
        val vib = lmVibrate ?: return false
        return runCatching {
            var b: Any = ctor.newInstance()
            b = setType.invoke(b, CROWN_EFFECT_TYPE) ?: b
            ebSetStrength?.invoke(b, strength)
            ebSetLoop?.invoke(b, java.lang.Boolean.FALSE)
            val effect = build.invoke(b) ?: return@runCatching false
            vib.invoke(svc, effect)
            true
        }.getOrDefault(false)
    }

    /** 降级：普通转子马达的清脆一记（非 OPPO 机型） */
    private fun fireSystem() {
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val effect = cachedTickEffect
                    ?: VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        .also { cachedTickEffect = it }
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(12L)
            }
        }
    }
}
