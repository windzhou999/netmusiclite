package com.ncm.watch.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 睡眠定时模式 */
enum class SleepMode(val label: String) {
    OFF("关闭定时"),
    MIN15("15 分钟后暂停"),
    MIN30("30 分钟后暂停"),
    MIN60("60 分钟后暂停"),
    END_OF_SONG("播完本歌暂停"),
}

/**
 * 睡眠定时器：
 * - 分钟模式：每秒倒数，最后 10 秒播放器音量渐弱（ExoPlayer 私有音量，不动系统音量），到点 pauseAction()
 * - 播完本歌模式：armEndOfSong() 挂标志，PlayerEngine 在 onCompletion 回调询问 consumeEndOfSong()
 * 到点统一走 pauseAction()：自动存进度 + 上报收听时长 + 回写 MediaSession，与手动暂停完全同路径
 */
object SleepTimer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var endOfSongArmed = false

    var mode by mutableStateOf(SleepMode.OFF)
        private set
    var remainingSec by mutableIntStateOf(0)
        private set

    fun start(m: SleepMode) {
        job?.cancel()
        job = null
        mode = m
        when (m) {
            SleepMode.OFF -> {
                endOfSongArmed = false
                remainingSec = 0
                PlayerEngine.restorePlayerVolume()
            }
            SleepMode.END_OF_SONG -> {
                endOfSongArmed = true
                remainingSec = 0
            }
            else -> {
                remainingSec = when (m) {
                    SleepMode.MIN15 -> 900
                    SleepMode.MIN30 -> 1800
                    else -> 3600
                }
                job = scope.launch {
                    while (remainingSec > 0) {
                        delay(1000)
                        remainingSec--
                        // 最后 10 秒渐弱（播放器私有音量）
                        PlayerEngine.setPlayerVolume((remainingSec / 10f).coerceIn(0.05f, 1f))
                    }
                    finish()
                }
            }
        }
    }

    /** 分钟模式到点：渐弱结束 → 暂停（与手动暂停同路径） */
    fun finish() {
        PlayerEngine.restorePlayerVolume()
        PlayerEngine.pauseAction()
        mode = SleepMode.OFF
        remainingSec = 0
    }

    /** 播完本歌模式：completion 回调询问后消费标志 */
    fun consumeEndOfSong(): Boolean {
        if (!endOfSongArmed) return false
        endOfSongArmed = false
        mode = SleepMode.OFF
        return true
    }
}
