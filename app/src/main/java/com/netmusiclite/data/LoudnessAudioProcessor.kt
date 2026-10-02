package com.netmusiclite.data

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 响度均衡：**曲目间**对齐（每首歌一个固定增益），不做曲目内的动态压缩。
 *
 * ── 2026-10-01 语义修正（用户口径）────────────────────────────────────────────
 * 旧实现是「每 100ms 重算一次 RMS 增益 + 0.8s/1.8s 平滑」—— 那实质是一个慢速 AGC：
 * 它把**一首歌内部**的段落（前奏轻、副歌响）也一路拉平，等于把歌的动态压掉了。
 * 用户要的是「平衡每一首歌的音量到同一水平」：**曲目之间**齐平，**曲目之内**保留原动态。
 *
 * 现方案：
 *  1. **探针期**（每首开头 [PROBE_MIN_SECONDS]~[PROBE_MAX_SECONDS] 秒，输出保持透明）：
 *     累积该曲的整体均方值（能量门限以下视为静音，避免把气声/底噪当素材），
 *     同时记录峰值。
 *  2. **锁定**：探针结束即算出**本曲唯一**的增益
 *     `gain = min(TARGET_RMS / rms, CEILING / peak)`，此后整首歌不再随段落变化。
 *  3. **峰值保险**（只降不升）：曲内若出现比探针更高的瞬态，按 `CEILING / runningPeak`
 *     单向压低增益 —— runningPeak 在单曲内单调不减，所以增益也只降不升，
 *     不会产生逐句起伏；常见情况下探针已覆盖峰值，这一步根本不介入。
 *  4. 切歌时**保留上一曲的增益**作为起点（探针期不再回落到单位增益），
 *     所以连续播放时不会有「每首开头几秒音量不对」的空档。
 *
 * 支持的编码仍只有 PCM 16bit / float；其它编码在 configure 返回 NOT_SET 直接不进链。
 */
@UnstableApi
class LoudnessAudioProcessor : BaseAudioProcessor() {
    private val enabled = AtomicBoolean(false)
    private val resetRequested = AtomicBoolean(false)

    private var sampleRate = 0
    private var channelCount = 0
    private var frameSamples = FloatArray(0)

    // ---- 探针（本曲整体响度分析）----
    private var probeFrames = 0
    private var probeSquareSum = 0.0
    private var probePeak = 0f
    private var probeDone = false
    private var probeMinFrames = 1
    private var probeMaxFrames = 1

    // ---- 增益 ----
    /** 本曲锁定增益（探针结论；切歌后先沿用上一曲的值，探针结束再改写） */
    private var lockedGain = 1f
    /** 曲内峰值上限（只降不升） */
    private var peakCap = 1f
    private var smoothedGain = 1f
    private var attackBlend = 0f
    private var releaseBlend = 0f

    private var framePeak = 0f

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    /** Called from the player thread; reset is applied at the next PCM buffer boundary. */
    fun resetForNewTrack() {
        resetRequested.set(true)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supported || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        frameSamples = FloatArray(channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            replaceOutputBuffer(0).flip()
            return
        }

        if (resetRequested.getAndSet(false)) applyTrackReset()

        val format = inputAudioFormat
        val bytesPerFrame = format.bytesPerFrame
        val byteCount = inputBuffer.remaining()
        val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        val rawInput = inputBuffer.duplicate().order(ByteOrder.nativeOrder())

        // PCM buffers should be frame-aligned. Preserve unusual trailing bytes unchanged.
        if (bytesPerFrame <= 0 || byteCount % bytesPerFrame != 0) {
            output.put(rawInput)
            output.flip()
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val sampleInput = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val frameCount = byteCount / bytesPerFrame
        // 爆音修复：增益绝不允许突变。关闭时只要 smoothedGain 还偏离单位增益就继续平滑
        // drain 到 1f 再切纯直通；切歌/开关切换也不硬置增益——波形连续即无 pop。
        val nowEnabled = enabled.get()
        if (!nowEnabled) lockedGain = 1f
        val shouldApplyGain = nowEnabled || abs(smoothedGain - 1f) > DRAIN_EPS

        if (shouldApplyGain) {
            repeat(frameCount) {
                val frameSquareSum = readFrame(sampleInput)
                probe(frameSquareSum)
                val gain = smoothGainOneFrame(nowEnabled)
                for (channel in 0 until channelCount) {
                    val limited = (frameSamples[channel] * gain).coerceIn(-CEILING, CEILING)
                    writeSample(output, limited, format.encoding)
                }
            }
            output.flip()
        } else {
            // Bit-for-bit transparent while still probing the current track's level,
            // so enabling normalization does not need to scan/download the track.
            repeat(frameCount) {
                val frameSquareSum = readFrame(sampleInput)
                probe(frameSquareSum)
            }
            output.put(rawInput)
            output.flip()
        }
        inputBuffer.position(inputBuffer.limit())
    }

    private fun readFrame(input: ByteBuffer): Double {
        var squareSum = 0.0
        for (channel in 0 until channelCount) {
            val sample = when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> input.short / 32768f
                C.ENCODING_PCM_FLOAT -> input.float
                else -> 0f
            }
            val finite = if (sample.isFinite()) sample else 0f
            frameSamples[channel] = finite
            val a = if (finite < 0f) -finite else finite
            if (a > framePeak) framePeak = a
            squareSum += finite.toDouble() * finite.toDouble()
        }
        return squareSum
    }

    /**
     * 探针：累积本曲整体能量与峰值，攒够时长就锁定本曲唯一增益。
     * 采样时长不足 / 整体近乎静音（前奏留白）时继续往后看，最多 [probeMaxFrames]。
     */
    private fun probe(frameSquareSum: Double) {
        if (framePeak > probePeak) probePeak = framePeak
        framePeak = 0f
        if (probeDone) return

        probeSquareSum += frameSquareSum
        probeFrames++
        if (probeFrames < probeMinFrames) return

        val rms = sqrt(probeSquareSum / (probeFrames.toDouble() * channelCount)).toFloat()
        // 静音（或只有底噪）时不下结论：等真正有素材再锁
        if (rms < SILENCE_FLOOR && probeFrames < probeMaxFrames) return

        lockedGain = if (rms < SILENCE_FLOOR) 1f else {
            val rmsGain = (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
            val peakGain = if (probePeak > 0.0005f) CEILING / probePeak else MAX_GAIN
            min(rmsGain, peakGain)
        }
        probeDone = true
    }

    /**
     * 本曲当前应有的增益 = min(锁定增益, 峰值上限)。
     * 峰值上限只降不升（探针峰值在单曲内单调不减），因此不会出现逐句起伏。
     */
    private fun smoothGainOneFrame(nowEnabled: Boolean): Float {
        if (!nowEnabled) {
            val blend = if (1f < smoothedGain) attackBlend else releaseBlend
            smoothedGain += (1f - smoothedGain) * blend
            return smoothedGain
        }
        if (probePeak > 0.0005f) {
            val cap = CEILING / probePeak
            if (cap < peakCap) peakCap = cap
        }
        val target = min(lockedGain, peakCap).coerceIn(MIN_GAIN, MAX_GAIN)
        val blend = if (target < smoothedGain) attackBlend else releaseBlend
        smoothedGain += (target - smoothedGain) * blend
        return smoothedGain
    }

    private fun writeSample(output: ByteBuffer, sample: Float, encoding: Int) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val pcm = (sample * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(pcm.toShort())
            }
            C.ENCODING_PCM_FLOAT -> output.putFloat(sample)
        }
    }

    /** 切歌：只重置**探针**（重新分析这首），增益沿用上一曲作为起点，避免开头音量空档 */
    private fun applyTrackReset() {
        probeFrames = 0
        probeSquareSum = 0.0
        probePeak = 0f
        probeDone = false
        framePeak = 0f
        peakCap = MAX_GAIN
    }


    override fun onFlush() {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        probeMinFrames = max(1, (sampleRate * PROBE_MIN_SECONDS).toInt())
        probeMaxFrames = max(probeMinFrames, (sampleRate * PROBE_MAX_SECONDS).toInt())
        attackBlend = (1.0 - exp(-1.0 / (ATTACK_SECONDS * sampleRate))).toFloat()
        releaseBlend = (1.0 - exp(-1.0 / (RELEASE_SECONDS * sampleRate))).toFloat()
        frameSamples = FloatArray(channelCount.coerceAtLeast(0))
        resetRequested.set(false)
        applyTrackReset()
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        probeMinFrames = 1
        probeMaxFrames = 1
        attackBlend = 0f
        releaseBlend = 0f
        frameSamples = FloatArray(0)
        applyTrackReset()
    }

    private companion object {
        /** 探针最短时长：够判一首歌的整体响度，又不至于让开头太久不齐 */
        const val PROBE_MIN_SECONDS = 3.0
        /** 探针上限：前奏长时间留白时最多看到这里就下结论 */
        const val PROBE_MAX_SECONDS = 12.0
        const val TARGET_RMS = 0.12589254f // -18 dBFS
        const val SILENCE_FLOOR = 0.001f // -60 dBFS
        const val MIN_GAIN = 0.25f
        const val MAX_GAIN = 1.5f // 原为 2f：+6dB 放大直接把无损瞬态顶进削波（失真来源），收紧到 +3.5dB
        const val CEILING = 0.89125094f // -1 dBFS
        const val DRAIN_EPS = 0.01f // 增益平滑收敛阈值：偏离 1f 小于此值即切纯直通（波形无感）
        /** 增益靠拢速度：向下 0.6s、向上 0.9s —— 锁定时干净利落，又不会台阶感 */
        const val ATTACK_SECONDS = 0.6
        const val RELEASE_SECONDS = 0.9
    }
}
