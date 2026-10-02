package com.ncm.watch.data

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 系统媒体界面接入点（2026-10-01 对齐 OPPO 手表音乐包 `com.allsaints.wearmusic`）。
 *
 * ## 为什么需要它
 * WMusic 早前已注册**平台** `android.media.session.MediaSession`（供 AVRCP 蓝牙按键与系统
 * 媒体面板），但 ColorOS Watch 的表盘音乐卡片 / 「正在播放」页**不认**那条通道 ——
 * 系统是按 action `androidx.media3.session.MediaSessionService` 去扫服务的
 * （实测 `pm query-services -a androidx.media3.session.MediaSessionService`
 * 只列出 OPPO 示例包、没有本 App）。本类就是补上这个「锚点」。
 *
 * ## 所有权边界（关键）
 * ExoPlayer 归 [PlayerEngine] 所有，本服务只**借用引用**：
 *  - 绝不 `player.release()`（只在 [onDestroy] 里 release 自己的会话）
 *  - 不建第二条播放链路，系统 UI 的操作全部翻译回 [PlayerEngine] 的业务方法
 *
 * ## 与既有平台会话共存
 * 两条会话读的是同一个 Player、写的是同一套业务方法（[PlayerEngine.playAction] /
 * [pauseAction] / [next] / [prev] / [seekTo]），因此谁被系统选中行为都一致，
 * 不存在「抢按键」问题。
 */
class MusicSessionService : MediaSessionService() {

    private var session: MediaSession? = null

    /**
     * 系统/外部控制器连接时由此拿到会话。
     * ★ Media3 要求本方法在主线程返回；`PlayerEngine` 的 ExoPlayer 也是主线程 Looper 构建的，
     *   两边一致才能建会话。
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        obtainSession()

    /**
     * 幂等取会话。系统可能在本 App 尚未预热播放器时就 bind 过来
     * （例如用户先点系统媒体卡片），此时 [PlayerEngine.sessionPlayerOrNull] 会用
     * 传入的 Context 补一次初始化并同步建播放器，语义等价于冷启动后直接点歌。
     */
    private fun obtainSession(): MediaSession? {
        session?.let { return it }
        val player: Player = PlayerEngine.sessionPlayerOrNull(this) ?: return null
        return MediaSession.Builder(this, EnginePlayer(player))
            .setSessionActivity(openAppIntent())
            .build()
            .also { session = it }
    }

    /** 点击系统媒体卡片/通知回到 App（OPPO 示例包漏了这步，点了没反应） */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, com.ncm.watch.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * ★ 故意**只覆盖单参重载、且不调 super** —— 这是让 Media3 完全不发通知的开关。
     *
     * Media3 1.5.1 的字节码实现是：
     * ```
     * public void onUpdateNotification(MediaSession s) { defaultMethodCalled = true; }   // 默认体
     * public void onUpdateNotification(MediaSession s, boolean startInForegroundRequired) {
     *     onUpdateNotification(s);                       // 覆盖后这里进的是空实现
     *     if (defaultMethodCalled) mediaNotificationManager.updateNotification(s, ...);
     * }
     * ```
     * 覆盖成空实现 → `defaultMethodCalled` 永远为 false → MediaNotificationManager 一次都不会被调，
     * 既不发通知也不拉前台。保活与媒体通知统一由 [PlaybackService] 的 MediaStyle 通知负责，
     * 否则手表上会出现两条一模一样的媒体通知。
     */
    override fun onUpdateNotification(session: MediaSession) = Unit

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
        // 注意：ExoPlayer 不在这里 release —— 它归 PlayerEngine，服务只借了引用
    }

    /**
     * 把系统 UI 下发的 Player 命令翻译回 [PlayerEngine] 的业务动作。
     *
     * 为什么不能直接让 Media3 操作 ExoPlayer：WMusic 的队列 / 播放模式 / 一起听协调
     * 全在 PlayerEngine 里，而 ExoPlayer 每次只 `setMediaItem` 载入**一首**
     * （见 `PlayerEngine.load`），直接 `seekToNextMediaItem()` 只会原地不动。
     */
    private class EnginePlayer(delegate: Player) : ForwardingPlayer(delegate) {

        override fun play() = PlayerEngine.playAction()

        override fun pause() = PlayerEngine.pauseAction()

        /** 系统可能直接写 playWhenReady（媒体键/局域网控制器），同样走业务路径 */
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) play() else pause()
        }

        override fun seekTo(positionMs: Long) = PlayerEngine.seekTo(positionMs)

        // 上/下一首：新老控制器的命令 ID 不同，四个入口都要接
        override fun seekToNext() = PlayerEngine.next()
        override fun seekToPrevious() = PlayerEngine.prev()
        override fun seekToNextMediaItem() = PlayerEngine.next()
        override fun seekToPreviousMediaItem() = PlayerEngine.prev()

        /** 系统媒体面板的关闭键：与平台会话的 onStop 保持一致，只暂停不销毁会话 */
        override fun stop() = PlayerEngine.pauseAction()

        /**
         * 追加「上/下一首」命令位：ExoPlayer 里始终只有一首歌（队列在 PlayerEngine），
         * 默认命令集不含这几个位，系统 UI 就不会画出切歌按钮。
         * 与 OPPO 示例包 `getAvailableCommands().addAll(5,6,7,8,9)` 是同一手法，
         * 只是这里用常量名而不是数字，避免 media3 版本间编号漂移。
         */
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()
    }
}
