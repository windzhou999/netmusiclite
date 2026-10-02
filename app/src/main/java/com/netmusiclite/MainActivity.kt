package com.netmusiclite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.ui.components.NcmHaptics
import com.netmusiclite.ui.nav.AppRoot
import com.netmusiclite.ui.theme.NcmTheme

/**
 * 入口：初始化会话/震动/播放器/下载，然后交给 Compose 导航。
 * 未登录首启直接进扫码登录页。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 流畅度 R2：Compose 首帧即满屏自绘，系统窗口背景层是多余的根层填充
        window.setBackgroundDrawable(null)
        // 播放保活（2026-09-26）：前台媒体服务的通知可见性走运行时权限；
        // 不授予时服务照常保活，仅媒体通知不显示
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        SessionStore.init(this)
        NcmHaptics.init(this)
        // 冷启动优化：这里只做轻量登记（context/偏好读取），ExoPlayer/MediaSession 构建
        // 延迟到 Compose 首帧后由 AppRoot 调 PlayerEngine.warmUp()，落在启动浮层窗口内
        PlayerEngine.init(this)
        DownloadStore.init(this)
        com.netmusiclite.data.BackgroundStore.init(this)
        com.netmusiclite.data.AppearancePrefs.init(this)
        com.netmusiclite.data.QualityPrefs.init(this)
        com.netmusiclite.data.HistoryStore.init(this)
        com.netmusiclite.data.ListenSession.init(this)
        com.netmusiclite.data.OfflineCache.init(this)
        com.netmusiclite.data.ConsentStore.init(this)
        // 全局 Coil：封面/背景无透明通道，RGB565 解码省一半显存带宽，滚动更稳；
        // 流畅度 R6：crossfade(false) 省掉淡入动画帧，allowHardware(true) 硬件位图走 GPU 合成
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(this)
                .allowRgb565(true)
                .allowHardware(true)
                .crossfade(false)
                .respectCacheHeaders(false)
                .build()
        )
        setContent {
            NcmTheme {
                AppRoot()
            }
        }
        // 评论点赞诊断（临时版）：冷启动 3 秒后自动跑一次请求形态矩阵，结果进 logcat 的 LTDiag
        if (com.netmusiclite.data.NcmApi.DIAG_ON) {
            Thread {
                runCatching {
                    Thread.sleep(3000)
                    kotlinx.coroutines.runBlocking { com.netmusiclite.data.NcmApi.diagCommentLike(3355480212L) }
                }
            }.start()
        }
    }
}
