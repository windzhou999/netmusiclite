package com.ncm.watch.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.ncm.watch.MainActivity
import com.ncm.watch.R

/**
 * 播放保活前台服务（foregroundServiceType=mediaPlayback）。
 *
 * ★「播放中被杀后台」根因（2026-09-26）：全应用此前没有任何前台服务 —— ExoPlayer 跑在
 *   Activity 进程里，退到桌面/熄屏后进程被降为缓存进程，系统内存紧张时直接回收，播放中断。
 *   修法：PlayerEngine 在有播放意图（播放中/缓冲中）时经 [ensureStarted] 拉起本服务
 *   startForeground，把整个进程提升到前台服务优先级（ExoPlayer 与本服务同进程，一并受保护）；
 *   暂停/空闲即撤 —— 播放进度/队列随 player_state.json 持续落盘，进程被杀重进可续播。
 *   MediaSession 仍由 PlayerEngine 持有（手表表盘媒体控件/耳机线控不变），
 *   通知用 MediaStyle 关联同一会话，通知栏按键经 onStartCommand 直接驱动 PlayerEngine。
 */
class PlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlayerEngine.playAction()
            ACTION_PAUSE -> PlayerEngine.pauseAction()
            ACTION_NEXT -> PlayerEngine.next()
            ACTION_PREV -> PlayerEngine.prev()
        }
        if (PlayerEngine.playbackActive) {
            startForeground(NOTIF_ID, buildNotification())
        } else {
            // ★ 2026-09-30 修「点播放后马上暂停 → 进程被杀」：
            //   ensureStarted() 判定播放意图成立时才调 startForegroundService()，但意图可能在
            //   本回调执行前就翻转（用户立刻暂停 / 起播失败撤销 fgWanted / 队列被替换）——
            //   旧版这时直接 stopForeground+stopSelf，从未调用 startForeground()，系统在 5s 后
            //   抛 RemoteServiceException: "Context.startForegroundService() did not then call
            //   Service.startForeground()" 杀掉整个进程（真机实测必崩，crash buffer 可查）。
            //   契约只对 startForegroundService 拉起的这次启动（本类走 ACTION_START）生效：
            //   先补一次 startForeground 满足契约，再立刻撤回（通知一闪即消，无残留）。
            if (intent?.action == ACTION_START) {
                startForeground(NOTIF_ID, buildNotification())
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        getSystemService(NotificationManager::class.java).let { nm ->
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "正在播放", NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
                )
            }
        }
        val song = PlayerEngine.current
        val playing = PlayerEngine.isPlaying
        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun cmd(action: String): PendingIntent = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_w)
            .setContentTitle(song?.title ?: "WMusic")
            .setContentText(song?.artist ?: "")
            .setContentIntent(contentPi)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif_prev), "上一首", cmd(ACTION_PREV)).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(
                    this, if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play),
                if (playing) "暂停" else "播放",
                cmd(if (playing) ACTION_PAUSE else ACTION_PLAY),
            ).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif_next), "下一首", cmd(ACTION_NEXT)).build())
        PlayerEngine.mediaSessionToken?.let { token ->
            builder.setStyle(
                Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2),
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 42
        const val ACTION_START = "com.ncm.watch.playback.START"
        const val ACTION_PLAY = "com.ncm.watch.playback.PLAY"
        const val ACTION_PAUSE = "com.ncm.watch.playback.PAUSE"
        const val ACTION_NEXT = "com.ncm.watch.playback.NEXT"
        const val ACTION_PREV = "com.ncm.watch.playback.PREV"

        /** 运行中服务实例（同进程直引用：通知原地刷新用） */
        @Volatile
        private var instance: PlaybackService? = null

        /**
         * PlayerEngine 在播放意图出现/歌曲切换时调用（主线程）。
         * 服务已运行：同进程直调 startForeground 原地刷新通知 —— 不经 start 系调用，
         * 后台自动切歌的路径也放行（31+ 的后台 FGS 启动限制只拦「启动」，不拦「已运行」）。
         * 未运行：startForegroundService 拉起（正常都在用户前台操作时发生）；极端时序
         * （蓝牙耳机从后台唤起播放）可能被系统拒绝，捕获后仅本次失去保护，不影响出声。
         */
        fun ensureStarted(ctx: Context) {
            instance?.let { s ->
                if (PlayerEngine.playbackActive) s.startForeground(NOTIF_ID, s.buildNotification())
                return
            }
            runCatching {
                ctx.startForegroundService(Intent(ctx, PlaybackService::class.java).setAction(ACTION_START))
            }
        }

        /** 暂停/空闲即撤：stopService 对运行中前台服务无后台限制；未运行时为无害 no-op */
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) }
        }
    }
}
