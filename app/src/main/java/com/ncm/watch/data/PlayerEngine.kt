package com.ncm.watch.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PlayMode(val label: String) {
    LOOP("循环播放"), SHUFFLE("随机播放"), SINGLE("单曲循环"), HEART("心动模式");
    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}

/**
 * 全局播放器：Media3 ExoPlayer 封装 + 应用自有队列 + 红心镜像。
 * 播放地址实时拉取不缓存；已下载歌曲直接播本地文件。
 */
object PlayerEngine {
    private lateinit var appCtx: Context
    private var am: AudioManager? = null
    private var focusReq: AudioFocusRequest? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * ★ queue/index 必须是 Compose 状态（2026-09-12 修「一起听歌单浮层加载不出来」）：
     * 旧版是普通 var，一起听房间的队列由远端同步/轮询在后台整体替换，
     * 界面读到的永远是打开浮层那一刻的快照 —— 歌单看着像「加载不出来」，
     * 必须关掉重开才能看到新歌。改成 mutableStateOf 后队列一变就重组。
     */
    var queue by mutableStateOf<List<Song>>(emptyList())
    var index by mutableStateOf(-1)
    var current by mutableStateOf<Song?>(null)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var loading by mutableStateOf(false)
    var errorMsg by mutableStateOf<String?>(null)
    var playMode by mutableStateOf(PlayMode.LOOP)
    private var pendingSeekMs = 0L
    var likedIds by mutableStateOf<Set<Long>>(emptySet())

    private var player: ExoPlayer? = null
    private val loudnessAudioProcessor = LoudnessAudioProcessor()
    private var sleepGain = 1f
    private var activeMediaId: String? = null
    private var activeReadySeq = -1
    private var activeCompletionSeq = -1
    private var activeErrorSeq = -1
    private var initialized = false
    /** 上次播放状态是否已恢复（[preloadState] 幂等标记，与 initialized 分开：恢复要早于建播放器） */
    private var stateLoaded = false
    private var pauseAfterPrepare = false
    /**
     * 播放会话意图（前台服务保活依据）：起播置位，暂停/加载失败清除。
     * 切歌的取址+prepare 间隙保持置位 —— 后台自动切歌时前台服务全程不撤
     * （playWhenReady 在不同 media3 版本的 stop() 后语义不一，不能作保活依据）。
     */
    private var fgWanted = false

    private val exoPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!isActiveMedia()) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    loading = true
                    isPlaying = false
                    updateMediaSession()
                }
                Player.STATE_READY -> onPlayerReady(loadSeq)
                Player.STATE_ENDED -> handleCompletion(loadSeq)
            }
        }

        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            if (!isActiveMedia()) return
            isPlaying = isPlayingNow
            updateMediaSession()
        }

        override fun onPlayerError(error: PlaybackException) {
            val seq = loadSeq
            if (!isActiveMedia() || activeErrorSeq == seq) return
            failLoad(seq, "播放失败(${error.errorCode})")
        }
    }
    private var ticker: Job? = null
    private var loadSeq = 0
    private var mediaSession: MediaSession? = null
    // 收听时长统计（对齐网易云）：ticker 按真实挂钟差累计"正在播放"的时长，
    // 结算点 = 切歌 / 播完（含单曲循环每遍）/ 长暂停兜底，整段会话汇总上报一条。
    // ★ 网易按 play 日志条数计听歌次数：一条日志 = 一次完整收听会话，绝不分片上报
    //   （旧版每 30s 分片上报导致听一次涨十几次）。同理不能"暂停即结算"——
    //   那会让同一首分两段听会计 2 次；也不能置永久标记只报一次——续听的时长会被丢弃。
    // ★ 暂停不结算：未上报秒数随 player_state.json 落盘，进程被杀后重启恢复同首续接，
    //   切歌时一次报出 —— 数据不丢、日志条数不增。
    private var listenAccSec = 0f
    private var lastTickRt = 0L   // 上次 ticker 的 elapsedRealtime：算真实增量用
    private var lastActiveRt = 0L // 上次处于播放态的 elapsedRealtime：长暂停判定用
    /** 本会话是否还需补发 startplay（load 置位、首次结算消费）：startplay 是「最近播放」的
     *  数据源，一次会话只发一条。旧版每次结算都补发 → 最近播放时间被结算时刻反复刷新 */
    private var startPending = false

    /** 暂停超过此时长视为本次收听会话结束，由 ticker 兜底结算（挂机/遗忘场景）。
     *  30min：日常接电话/临时离开不会把一次收听拆成两次计次（旧版 10min 偏激进） */
    private const val PAUSE_FLUSH_MS = 30 * 60_000L

    /** 连续播放失败计数：自动跳灰歌用，成功起播清零，连败 3 次停（防整条流都是灰歌时空转） */
    private var autoSkipStreak = 0

    /** 把当前歌累计的真实收听秒数一次性上报给网易（一段会话只报一条）。
     *  ★ 上报失败且未切歌时把秒数并回当前会话，下个结算点随下次一并报出（时长不丢） */
    private fun flushListen() {
        val song = current ?: return
        if (song.id <= 0) { listenAccSec = 0f; return } // 本地歌曲无云端统计
        val secs = listenAccSec.toInt()
        if (secs < 1) return
        listenAccSec = 0f
        val withStart = startPending
        startPending = false
        scope.launch {
            val ok = runCatching { NcmApi.scrobble(song.id, secs, withStart) }.getOrDefault(false)
            if (ok) {
                PageCache.invalidateRecord() // 刚听完的进排行缓存失效：进页即见最新次数
            } else if (current?.id == song.id) {
                listenAccSec += secs // 还账（切了歌就不还，避免把 A 的时长记到 B 头上）
                startPending = true
            }
        }
    }
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        // 耳机拔出 / 蓝牙音频断开 → 系统发 BECOMING_NOISY，对齐系统行为自动暂停
        override fun onReceive(ctx: Context?, i: android.content.Intent?) {
            pauseAction()
        }
    }

    /**
     * 轻量初始化（onCreate 调用）：只记 context / 读偏好开关，不建 ExoPlayer。
     * 冷启动关键路径：ExoPlayer 构建要扫描解码器列表（骁龙 W5 实测几十~一百多 ms），
     * MediaSession 创建同样偏重 —— 都挪到 [warmUp]，由首帧后（启动浮层 300ms 窗口内）触发。
     */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        LoudnessPrefs.init(appCtx)
        loudnessAudioProcessor.setEnabled(LoudnessPrefs.enabled)
        am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        stateFile = java.io.File(appCtx.filesDir, "player_state.json")
    }

    /**
     * 只恢复「上次播放状态」（2026-09-30 冷启动专项拆出来）：
     * IO 读一个小 json → 回主线程落地队列/当前歌/进度。轻量且与首帧并行，
     * 播放页揭开时内容已经就位；建播放器/媒体会话那套重活留给 [warmUp] 错峰做。幂等。
     */
    fun preloadState() {
        if (!::appCtx.isInitialized || stateLoaded) return
        stateLoaded = true
        // 启动窗口内不做任何磁盘 I/O：恢复状态移到后台线程解析，再回主线程应用
        scope.launch(Dispatchers.IO) { restoreState() }
    }

    /** 幂等预热：建播放器/媒体会话、注册耳机拔出接收器、恢复上次播放状态、启动心跳。首帧后调用 */
    fun warmUp() {
        if (initialized) return
        initialized = true
        appCtx.registerReceiver(
            noisyReceiver,
            android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
        preloadState() // 兜底：极端时序下 ensurePlayer 先于 AppNav 调到这里
        // ExoPlayer 构建（内部扫解码器列表，W5 上 50-150ms）移出主线程：预热发生在启动后 300ms
        // —— 正是用户开始滑动的窗口，主线程构建会直接堵死输入。显式 setLooper(Main) 保证
        // 构建在 Default 线程也合规（listener 回调全部投递主线程）；构建完成后回主线程
        // 赋值并建 MediaSession（MediaSession 必须在主线程构建）。
        scope.launch(Dispatchers.Default) {
            val p = buildExoPlayer()
            withContext(Dispatchers.Main) {
                if (player == null) {
                    configPlayer(p)
                    player = p
                    initMediaSession()
                } else {
                    p.release() // 极端时序：ensurePlayer 已同步补建
                }
            }
        }
        ticker = scope.launch {
            var n = 0
            while (isActive) {
                delay(500)
                val p = player
                val now = android.os.SystemClock.elapsedRealtime()
                if (p == null) { lastTickRt = now; continue }
                runCatching {
                    if (p.isPlaying) {
                        positionMs = p.currentPosition
                        val d = p.duration
                        if (d > 0L && d != C.TIME_UNSET) durationMs = d
                        if (++n % 10 == 0) persistState() // 每 5 秒存一次进度
                        // 真实收听秒数：按挂钟差累计（delay(500) 有调度/休眠漂移，固定 +0.5 会少计）；
                        // 单次增量夹 30s 上限防系统时钟跳变虚增
                        listenAccSec += (now - lastTickRt).coerceIn(0L, 30_000L) / 1000f
                    } else if (listenAccSec >= 1f && now - lastActiveRt >= PAUSE_FLUSH_MS) {
                        flushListen() // 长暂停兜底结算：挂机/遗忘后不结算就只剩落盘数据
                    }
                }
                if (p.isPlaying) lastActiveRt = now
                lastTickRt = now
            }
        }
        // 红心列表同步不在预热期拉：回包重组会撞上冷启动后的滑动窗口，由 AppRoot 启动任务错峰调用
    }

    /** 确保播放器就绪：warmUp 的后台构建尚未完成（预热 300ms 内极端时序点歌）时在主线程同步补建，
     *  语义与旧版同步构建等价；稍后后台构建完成会发现 player 非空并自行 release */
    private fun ensurePlayer(): ExoPlayer {
        warmUp()
        return player ?: buildExoPlayer().also { exo ->
            configPlayer(exo)
            player = exo
        }
    }

    /** 仅构建（重活：渲染器实例化+解码器扫描，50-150ms），可在后台线程调用——已显式 setLooper(Main)，
     *  但注意：build() 之后的一切 player 方法（配置/播放控制）仍必须在主线程调用 */
    private fun buildExoPlayer(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(appCtx) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(loudnessAudioProcessor))
                .build()
        }
        return ExoPlayer.Builder(appCtx, renderersFactory)
            .setLooper(android.os.Looper.getMainLooper())
            .build()
    }

    /** 播放器配置（轻调用，必须主线程） */
    private fun configPlayer(exo: ExoPlayer) {
        val attributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Keep the existing requestFocus() path; ExoPlayer must not request focus a second time.
        exo.setAudioAttributes(attributes, false)
        // 播放期持有 CPU/WiFi 锁（配 WAKE_LOCK 权限）：手表熄屏流媒体不断流
        exo.setWakeMode(C.WAKE_MODE_NETWORK)
        exo.volume = sleepGain
        exo.addListener(exoPlayerListener)
    }

    // ---------- 播放状态持久化：重进 App 显示上次退出时正在播的歌 ----------
    private lateinit var stateFile: java.io.File

    private fun songJson(s: Song) = org.json.JSONObject()
        .put("id", s.id).put("t", s.title).put("ar", s.artist).put("arid", s.artistId)
        .put("al", s.album).put("alid", s.albumId).put("dt", s.durationMs)
        .put("cv", s.coverUrl ?: "")

    private fun songFrom(o: org.json.JSONObject) = Song(
        o.optLong("id"), o.optString("t"), o.optString("ar"), o.optLong("arid"),
        o.optString("al"), o.optLong("alid"), o.optInt("dt"),
        o.optString("cv").ifEmpty { null },
    )

    private val stateExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 序列化+写盘全部在后台单线程执行，主线程只做不可变数据的手递（ Song 不可变，安全） */
    private fun persistState() {
        val cur = current ?: return
        val q = queue
        val idx = index
        val pos = positionMs
        val mode = playMode
        val stream = streamSource
        stateExecutor.execute {
            runCatching {
                val arr = org.json.JSONArray()
                q.forEach { arr.put(songJson(it)) }
                stateFile.writeText(
                    org.json.JSONObject()
                        .put("current", songJson(cur))
                        .put("queue", arr)
                        .put("index", idx)
                        .put("position", pos)
                        .put("mode", mode.name)
                        .put("stream", stream.name)
                        // 未上报的收听秒数随存档落盘：进程被杀后重启可续接，切歌时一次报出
                        .put("listenSong", if (cur.id > 0) cur.id else 0L)
                        .put("listenSec", listenAccSec.toDouble())
                        .toString()
                )
            }
        }
    }

    /** 只恢复界面状态（队列/当前歌/进度/模式），不自动出声；按播放键时从上次进度续播 */
    private fun restoreState() {
        // 在 Dispatchers.IO 上调用：解析完成后回主线程应用 Compose 状态
        runCatching {
            if (!stateFile.exists()) return
            val j = org.json.JSONObject(stateFile.readText())
            val cur = songFrom(j.getJSONObject("current"))
            val q = ArrayList<Song>()
            j.optJSONArray("queue")?.let { arr ->
                for (i in 0 until arr.length()) runCatching { q.add(songFrom(arr.getJSONObject(i))) }
            }
            val idx = j.optInt("index", 0)
            val pos = j.optLong("position", 0L)
            val mode = runCatching { PlayMode.valueOf(j.optString("mode", "LOOP")) }
                .getOrDefault(PlayMode.LOOP)
            // 队列来源一并恢复：重启后 FM/心动流继续能自动续取，不会退化成小队列原地循环
            val stream = runCatching { StreamSource.valueOf(j.optString("stream", "NONE")) }
                .getOrDefault(StreamSource.NONE)
                // 旧版本存档没有 stream 字段：HEART 模式的队列就是心动流，补上否则退化成普通循环
                .let { s -> if (s == StreamSource.NONE && mode == PlayMode.HEART) StreamSource.HEART else s }
            // 被杀进程前的未上报收听时长：同一首歌才续接（换歌/本地歌不续）
            val lsSong = j.optLong("listenSong", 0L)
            val lsSec = j.optDouble("listenSec", 0.0).toFloat()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                queue = q
                index = idx
                current = cur
                positionMs = pos
                durationMs = cur.durationMs.toLong()
                // ★ 这里【不要】写 pendingSeekMs（2026-09-27）：它属于「本次载歌的起播进度」，
                //   只由 load(startAtMs) 赋值。恢复存档时写进去，会让上次进度一直存活到下一首
                //   歌载入 —— 重启后点「下一首」或从搜索点新歌都会继承上一首的进度，越界时被
                //   结尾 15s 缓冲夹到歌尾就是「没播完自动跳下一首」。重启后的续播由 playInternal
                //   显式把 positionMs 传给 load 完成，不依赖这里。
                playMode = mode
                if (lsSong > 0 && cur.id == lsSong) listenAccSec = lsSec
                resetStream(stream, q)
            }
        }
    }

    fun refreshLikes() {
        val uid = SessionStore.uid
        if (uid <= 0) return
        scope.launch {
            likedIds = NcmApi.likeList(uid).toSet()
        }
    }

    fun toggleLike(song: Song) {
        val target = song.id !in likedIds
        likedIds = if (target) likedIds + song.id else likedIds - song.id
        PageCache.invalidateLiked() // 我喜欢页缓存失效（下次进入重新拉取）
        scope.launch {
            val ok = NcmApi.likeSong(song.id, target)
            if (!ok) {
                likedIds = if (target) likedIds - song.id else likedIds + song.id
                PageCache.invalidateLiked()
            }
        }
    }

    fun isLiked(id: Long) = id in likedIds

    // ---------- 队列 ----------
    /**
     * 队列来源。FM / 心动模式是流式推荐：接口每次只推一小批（FM 实测约 3 首，服务端按调用次数推进），
     * 必须边播边续取。旧版把它当完整队列，播完只能在原地循环 —— 「只有三首重复」的根因。
     */
    enum class StreamSource { NONE, HEART, FM }

    var streamSource = StreamSource.NONE
        private set
    private var streamBusy = false
    private var streamCooldownUntil = 0L

    /** 已进过队列的歌曲 id（环形，上限 200）：推荐流去重用，服务端推回重复批次时不再入队 */
    private val recentIds = ArrayDeque<Long>()
    private val recentSet = HashSet<Long>()

    private fun remember(id: Long) {
        if (!recentSet.add(id)) return
        recentIds.addLast(id)
        if (recentIds.size > 200) recentSet.remove(recentIds.removeFirst())
    }

    private fun resetStream(src: StreamSource, songs: List<Song>) {
        streamSource = src
        streamCooldownUntil = 0
        recentIds.clear()
        recentSet.clear()
        songs.forEach { remember(it.id) }
    }

    fun playQueue(songs: List<Song>, startIndex: Int, stream: StreamSource = StreamSource.NONE) {
        // 只剔除 id=0 的解析残缺项；负 id 是本地音乐，必须保留
        val clean = songs.filter { it.id != 0L }
        if (clean.isEmpty()) return
        queue = clean
        resetStream(stream, clean)
        playAt(startIndex.coerceIn(0, clean.lastIndex))
    }

    fun playOne(song: Song) = playQueue(listOf(song), 0)

    // ---------- 队列增删（2026-10-01：搜索长按「添加到列表 / 下一首播放」、房间歌单长按删除） ----------

    /**
     * 追加一首到队尾（搜索结果「添加到列表」）。
     * 队列为空时直接起播 —— 否则点了「添加到列表」屏幕上什么都没发生，像坏了一样。
     * 已存在的同 id 不重复追加；本地音乐 id < 0 允许重复（那是不同的文件）。
     */
    fun appendToQueue(song: Song) {
        if (song.id == 0L) return
        if (queue.isEmpty() || index < 0) { playOne(song); return }
        if (song.id > 0L && queue.any { it.id == song.id }) return
        queue = queue + song
        remember(song.id)
    }

    /**
     * 插到当前歌之后，成为「下一首」（搜索结果「下一首播放」）。
     *
     * ★ 2026-10-01 崩溃修复：旧版**无条件** `add(at, song)`，而搜索结果页点歌时
     *   `playQueue(songs, i)` 已经把**整页搜索结果**放进了队列 —— 再从同一页长按
     *   「下一首播放」，插进去的就是队列里已有的同一首（同一 id 出现两次）。
     *   随后打开「列表播放」（QueueScreen）用的是 `key = { _, s -> s.id }`，
     *   LazyColumn 遇到重复 key 直接抛 IllegalArgumentException 崩溃。
     *   现在：已在队列里 → **移动到当前歌之后**（而不是复制一份），
     *   本地音乐（id < 0，不同文件可同 id）保持原「直接插入」语义。
     */
    fun playNextInQueue(song: Song) {
        if (song.id == 0L) return
        if (queue.isEmpty() || index < 0) { playOne(song); return }
        // 远端歌：队列里已有同 id → 搬移，绝不产生重复项（重复 key 会让列表崩）
        val existing = if (song.id > 0L) queue.indexOfFirst { it.id == song.id } else -1
        if (existing == index + 1) return // 已经是下一首，什么都不用做
        val list = queue.toMutableList()
        if (existing >= 0) {
            list.removeAt(existing)
            // 移除点若在当前歌之前，当前歌下标整体前移一位
            if (existing < index) index -= 1
        }
        val at = (index + 1).coerceIn(0, list.size)
        list.add(at, song)
        queue = list
        remember(song.id)
        // 随机模式的预排计划按**下标**存，插队后必须重抽，否则预加载目标与实播对不上
        if (playMode == PlayMode.SHUFFLE) drawShufflePlan(2)
    }

    /**
     * 从队列移除第 [i] 首，返回是否真的移除了。
     * - 删的是**正在播**那首 → 落到同一位置（即原下一首）并续播；队列被删空则停播。
     * - 删的是前面的 → index 前移一位，当前歌不受影响、不打断播放。
     */
    fun removeFromQueue(i: Int): Boolean {
        if (i !in queue.indices) return false
        val wasCurrent = i == index
        queue = queue.toMutableList().also { it.removeAt(i) }
        when {
            queue.isEmpty() -> {
                index = -1
                current = null
                pauseAction()
            }
            wasCurrent -> {
                index = i.coerceAtMost(queue.lastIndex)
                playAt(index)
            }
            i < index -> index -= 1
        }
        if (shufflePlan.isNotEmpty()) drawShufflePlan(2)
        return true
    }

    fun playAt(i: Int) {
        if (queue.isEmpty()) return
        index = i
        // 随机计划以新 index 重抽维护（切歌与预加载同源）；非随机清空
        if (playMode == PlayMode.SHUFFLE && queue.size > 1) drawShufflePlan(2)
        else if (shufflePlan.isNotEmpty()) shufflePlan.clear()
        load(queue[i])
        maybePrefetch() // 提前补流：让「下一首」几乎总是即时的，不必等网络
    }

    fun toggle() {
        val p = player
        if (p?.isPlaying == true || p?.playWhenReady == true) pauseAction() else playAction()
    }

    /** 播放（蓝牙耳机播放键同路径）：恢复态 = 重新加载上次的歌并 seek 到上次进度 */
    fun playAction() = playInternal(userIntent = true)

    private fun playInternal(userIntent: Boolean) {
        val p = player
        if (p == null) {
            current?.let { load(it, startAtMs = positionMs) }
            return
        }
        if (p.currentMediaItem == null || activeMediaId == null) {
            current?.let { load(it, startAtMs = positionMs) }
            return
        }
        runCatching {
            val wasEnded = p.playbackState == Player.STATE_ENDED
            if (wasEnded) {
                activeCompletionSeq = -1
                p.seekTo(0L)
            }
            if (!p.playWhenReady || wasEnded) {
                requestFocus()
                fgWanted = true
                p.play()
                isPlaying = p.isPlaying
                // 乐迷团房：用户手动恢复 = 解除本地暂停屏蔽，并让 FLT 轮询立刻追平房间进度
                if (userIntent && ListenSession.fltMode) ListenSession.onFltUserResume()
                reportCmd("PLAY"); updateMediaSession()
            }
        }
    }

    /** 暂停（蓝牙耳机暂停键同路径） */
    fun pauseAction() = pauseInternal(userIntent = true)

    private fun pauseInternal(userIntent: Boolean) {
        val p = player ?: return
        runCatching {
        if (p.isPlaying || p.playWhenReady) {
            p.pause(); isPlaying = p.isPlaying; fgWanted = false
                // 乐迷团房：用户手动暂停 = 记住本地意图。服务端 playingSong.playing 恒为 true
                // （成员无暂停上报接口，房间由艺人/房主控制），不加屏蔽的话 5s 轮询会把
                // 刚按下的暂停顶回播放——「暂停点第一下会自动继续播放」就是它。
                if (userIntent && ListenSession.fltMode) ListenSession.fltLocalPause = true
                reportCmd("PAUSE"); persistState(); updateMediaSession()
                // 暂停不结算：未报秒数随 persistState 落盘，续播并入本次会话继续累计，
                // 长暂停（≥10min）由 ticker 兜底结算，切歌/播完时统一报出
            }
        }
    }

    fun seekTo(ms: Long) {
        // Clamp before the end: seeking to/past duration can leave a completed item stuck.
        val d = durationMs
        val safe = if (d > 0L) ms.coerceIn(0L, (d - 1000L).coerceAtLeast(0L)) else ms
        runCatching { player?.seekTo(safe) }
        positionMs = safe
        if (ListenSession.active) ListenSession.reportSeek(positionMs)
        updateMediaSession() // 系统媒体面板进度条同步（2026-09-25）
    }

    fun next() {
        if (queue.isEmpty()) return
        // 流式推荐队列：走到队尾先补下一批（用户等一次网络），补不到才回到本地循环
        if (streamSource != StreamSource.NONE && index >= queue.size - 1) {
            scope.launch {
                val ok = extendStream()
                // 等待网络期间队列可能被替换（一起听同步），越界就退回本地推进
                if (ok && index + 1 < queue.size) playAt(index + 1) else advanceLocal()
            }
            return
        }
        advanceLocal()
    }

    /** 本地队列推进（普通歌单/专辑/本地音乐的正常切歌路径） */
    private fun advanceLocal() {
        val n = when {
            // 随机排除当前首：走预抽计划（与预加载共用同一随机序，随机也能命中直链缓存）
            playMode == PlayMode.SHUFFLE && queue.size > 1 -> {
                if (shufflePlan.isEmpty() || shufflePlan.any { it >= queue.size }) drawShufflePlan(2)
                val p = shufflePlan.removeFirstOrNull()
                if (p == null || p == index) (queue.indices - index).random() else p
            }
            else -> (index + 1) % queue.size
        }
        playAt(n)
    }

    // ---------- 下两首预加载（2026-09-25）----------
    // 随机模式预抽计划：提前抽定后续随机序，advanceLocal 切歌与预加载判next共用，
    // 随机播放的「下一首」因此可预知、可预解析
    private val shufflePlan = mutableListOf<Int>()

    private fun drawShufflePlan(count: Int) {
        shufflePlan.clear()
        if (playMode != PlayMode.SHUFFLE || queue.size <= 1) return
        val pool = (queue.indices - index).toMutableList()
        repeat(count) {
            if (pool.isEmpty()) return@repeat
            val k = pool.random()
            shufflePlan += k
            pool.remove(k)
        }
    }

    /** 与 next()/advanceLocal() 同源的「接下来 n 首」id；流式续批不可预知时返回空 */
    private fun nextSongIds(n: Int): List<Long> {
        if (queue.isEmpty()) return emptyList()
        if (streamSource != StreamSource.NONE && index >= queue.size - 1) return emptyList()
        val out = ArrayList<Long>(n)
        if (playMode == PlayMode.SHUFFLE && queue.size > 1) {
            shufflePlan.take(n).forEach { if (it in queue.indices) out += queue[it].id }
        } else {
            var i = index
            repeat(n) { i = (i + 1) % queue.size; out += queue[i].id }
        }
        return out.distinct()
    }

    // 直链预解析缓存：id -> (https 直链, 失效时刻)。load() 命中免一次 songUrl 网络往返
    private val urlCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, Long>>()
    private var preloadSeq = 0

    private fun cachedUrl(id: Long): String? {
        val e = urlCache[id] ?: return null
        return if (android.os.SystemClock.elapsedRealtime() < e.second) e.first
        else { urlCache.remove(id); null }
    }

    /** 歌曲就绪后预解析接下来最多 2 首直链（顺序/随机同源），切歌几乎零等待 */
    private fun schedulePreload() {
        val mySeq = ++preloadSeq
        scope.launch {
            for (id in nextSongIds(2)) {
                if (mySeq != preloadSeq) return@launch // 已切歌/换队列，目标过期
                if (cachedUrl(id) != null) continue
                if (DownloadStore.localFile(appCtx, id) != null) continue // 本地已有文件，无需预解析
                runCatching {
                    val u = withContext(Dispatchers.IO) { NcmApi.songUrl(id, QualityPrefs.level) }
                    if (u != null && mySeq == preloadSeq) {
                        val https = if (u.startsWith("http://")) u.replace("http://", "https://") else u
                        urlCache[id] = https to (android.os.SystemClock.elapsedRealtime() + 10 * 60_000L)
                    }
                }
            }
        }
    }

    /** 接近队尾时静默预取下一批；处于失败冷却期则跳过（避免弱网反复空跑） */
    private fun maybePrefetch() {
        if (streamSource == StreamSource.NONE) return
        if (index < queue.size - 3) return
        if (android.os.SystemClock.elapsedRealtime() < streamCooldownUntil) return
        scope.launch { extendStream() }
    }

    /**
     * 拉下一批推荐流、按 id 去重后追加到队尾，返回是否真的加到了新歌。
     * 按来源链依次尝试：同一首种子拉回重复批次时自动换种子、换来源；全部落空才进 10 秒冷却。
     */
    private suspend fun extendStream(): Boolean {
        val src = streamSource
        if (src == StreamSource.NONE || streamBusy) return false
        streamBusy = true
        try {
            val seed = current?.id ?: 0L
            val tail = queue.lastOrNull()?.id ?: seed
            val fm: suspend () -> List<Song> = { NcmApi.personalFM() }
            val daily: suspend () -> List<Song> = { NcmApi.dailyRecommend() }
            val chain: List<suspend () -> List<Song>> = when (src) {
                // FM 逐批推进；批被推重时用心动接口换种子
                StreamSource.FM -> listOf(fm, { NcmApi.heartModeList(tail, count = 30) }, daily)
                StreamSource.HEART -> listOf({ NcmApi.heartModeList(seed, count = 30) }, fm, daily)
                StreamSource.NONE -> emptyList()
            }
            for (fetch in chain) {
                val fresh = runCatching { fetch() }.getOrDefault(emptyList())
                    .filter { !recentSet.contains(it.id) }
                if (fresh.isNotEmpty()) {
                    appendToQueue(fresh)
                    android.util.Log.i("RoamDiag", "extend $src +${fresh.size} queue=${queue.size} idx=$index")
                    return true
                }
            }
            android.util.Log.i("RoamDiag", "extend $src 无新歌 queue=${queue.size}")
            streamCooldownUntil = android.os.SystemClock.elapsedRealtime() + 10_000
            return false
        } catch (e: Exception) {
            // 兜底：续流失败绝不能把异常抛进 scope，否则会崩掉应用
            android.util.Log.i("RoamDiag", "extend $src 异常 ${e.message}")
            return false
        } finally {
            streamBusy = false
        }
    }

    /** 追加到队尾；已播历史只保留最近 60 首（够 prev/回看），避免队列与存档无限膨胀 */
    private fun appendToQueue(add: List<Song>) {
        queue = queue + add
        add.forEach { remember(it.id) }
        val cut = index - 60
        if (cut > 0 && queue.size > 140) {
            queue = queue.subList(cut, queue.size).toList()
            index -= cut
        }
    }

    fun prev() {
        if (queue.isEmpty()) return
        playAt((index - 1 + queue.size) % queue.size)
    }

    fun setMode(mode: PlayMode) {
        playMode = mode
        drawShufflePlan(2) // 模式切换重抽随机计划，保证预加载目标与实际下一首一致
        // 心动模式真实现：切入即按当前歌重建推荐流并开始播放（不是空壳档位）
        if (mode == PlayMode.HEART) enterHeartMode()
    }

    private var heartBusy = false

    /** 心动模式：以当前歌为种子重建队列；拿不到相似流就退 FM（同样是流式，自动续取） */
    fun enterHeartMode() {
        if (heartBusy) return // 连点模式按钮防重入
        heartBusy = true
        scope.launch {
            try {
                val seed = current?.id ?: 0L
                val heart = if (seed > 0) NcmApi.heartModeList(seed, count = 30) else emptyList()
                val songs = heart.ifEmpty { NcmApi.fmBatch(2) }
                // 等待期间用户可能又切回别的模式 → 只有仍处于心动模式才落地，否则不抢
                if (songs.isNotEmpty() && playMode == PlayMode.HEART) {
                    playQueue(songs, 0, if (heart.isNotEmpty()) StreamSource.HEART else StreamSource.FM)
                }
            } finally {
                heartBusy = false
            }
        }
    }

    /** 私人电台 / 私人漫游入口：拉一批 FM 流起播并挂续流（每批仅几首，靠续流接上），回调在主线程 */
    fun startRoam(onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            // FM 被风控/为空时退每日推荐，保证起播一定有歌
            val songs = NcmApi.fmBatch(3).ifEmpty { NcmApi.dailyRecommend() }
            if (songs.isNotEmpty()) playQueue(songs, 0, StreamSource.FM)
            onDone(songs.isNotEmpty())
        }
    }

    fun volume() = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    fun maxVolume() = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
    fun setVolume(v: Int) {
        am?.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, maxVolume()), 0)
    }

    /** Toggle normalization on the active PCM pipeline without changing queue or position. */
    fun setLoudnessEnabled(enabled: Boolean) {
        loudnessAudioProcessor.setEnabled(enabled)
    }

    // ---------- 睡眠定时专用：播放器私有音量（不动系统音量） ----------
    /** Sleep timer gain only; normalization remains independent in the PCM processor. */
    fun setPlayerVolume(f: Float) {
        sleepGain = f.coerceIn(0f, 1f)
        runCatching { player?.volume = sleepGain }
    }

    /** Restore sleep attenuation only; do not alter the processor's normalization gain. */
    fun restorePlayerVolume() {
        sleepGain = 1f
        runCatching { player?.volume = sleepGain }
    }

    // ---------- 蓝牙耳机/AVRCP 媒体键（上一首/下一首/播放/暂停）----------
    // 注册系统 MediaSession：耳机线控、表盘播放控件走 AVRCP 标准按键，
    // 框架把 KEYCODE_MEDIA_NEXT/PREVIOUS/PLAY/PAUSE 翻译成对应回调；
    // 播放状态与歌曲元数据回写给会话，蓝牙设备 UI 与系统媒体面板同步显示。

    private fun initMediaSession() {
        val ms = MediaSession(appCtx, "WMusic")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = playAction()
            override fun onPause() = pauseAction()
            override fun onSkipToNext() { next(); updateMediaSession() }
            override fun onSkipToPrevious() { prev(); updateMediaSession() }
            // 对齐系统接口（2026-09-25）：表盘播放控件/系统媒体面板的进度条拖动与停止键
            override fun onSeekTo(pos: Long) { seekTo(pos); updateMediaSession() }
            override fun onStop() = pauseAction()
        })
        ms.isActive = true
        mediaSession = ms
        updateMediaSession()
    }

    /** 把当前播放状态+歌曲信息回写给 MediaSession（蓝牙 UI / 系统媒体面板读取） */
    fun updateMediaSession() {
        syncForeground()
        val ms = mediaSession ?: return
        val playing = isPlaying && !loading
        val state = when {
            current == null -> PlaybackState.STATE_NONE
            loading -> PlaybackState.STATE_BUFFERING
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        ms.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                )
                .setState(state, positionMs, if (playing) 1f else 0f)
                .build()
        )
        current?.let { s ->
            ms.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                    .build()
            )
        }
    }

    private fun isActiveMedia(): Boolean {
        val mediaId = activeMediaId ?: return false
        return player?.currentMediaItem?.mediaId == mediaId
    }

    // ---------- 播放保活前台服务（2026-09-26 修「播放中被杀后台」） ----------

    /** 前台服务判定：有播放意图（播放中 / 缓冲中 / 切歌加载间隙）即需要保活 */
    val playbackActive: Boolean
        get() = current != null && (isPlaying || fgWanted)

    /** 通知 MediaStyle 关联的会话 token（与表盘媒体控件/耳机线控同一会话） */
    val mediaSessionToken: android.media.session.MediaSession.Token?
        get() = mediaSession?.sessionToken

    // ---------- Media3 会话服务（系统媒体界面接入点，2026-10-01 对齐 OPPO 手表音乐包） ----------

    /**
     * 把内部 ExoPlayer 交给 [MusicSessionService] 建 Media3 会话。
     *
     * ★ 只交出**引用**，不交出所有权：生命周期、监听器、解码配置全归本对象，
     *   服务侧绝不可 release（否则等于把正在播的播放器拆了）。
     * ★ 与既有平台 MediaSession 共存：两条会话读同一个 Player、写同一套业务方法，
     *   系统选哪条行为都一致，不存在抢按键。
     * ★ 系统可能在 App 尚未预热时直接 bind 服务（例如冷启动后先点系统媒体卡片），
     *   此处用传入的 ctx 补一次 init + 同步建播放器，语义等价于「冷启动后直接点歌」。
     * ★ 必须在主线程调用：ExoPlayer 用主 Looper 构建，MediaSession 也要求同线程建。
     */
    fun sessionPlayerOrNull(ctx: Context): Player? {
        if (!::appCtx.isInitialized) init(ctx)
        return runCatching { ensurePlayer() }.getOrNull()
    }

    /**
     * 系统媒体界面用的封面地址。复用界面层的统一封面解析（本地 → 已下载落盘封面 → 网络 url），
     * 保证系统卡片上的封面与 App 内显示的是同一张。
     * ★ 已下载落盘封面是**应用私有目录**里的 File，系统 UI 进程读不到，
     *   所以这里优先还原成它当初的下载来源（song.coverUrl），取不到才退回 file://。
     */
    private fun coverUriFor(song: Song): Uri? {
        val model = runCatching { com.ncm.watch.ui.components.songCoverModel(song) }.getOrNull() ?: return null
        return when (model) {
            is Uri -> model
            is java.io.File -> song.coverUrl?.takeIf { it.startsWith("http") }?.let(Uri::parse)
                ?: Uri.fromFile(model)
            is String -> model.takeIf { it.isNotEmpty() && !it.startsWith("local://") }?.let(Uri::parse)
            else -> null
        }
    }

    /**
     * 前台服务同步：挂在 updateMediaSession 的最前面（它早于 mediaSession 判空返回，
     * 冷启动 300ms 窗口内点歌的路径也能及时拉起保护）。
     * 有播放意图 → 确保 mediaPlayback 前台服务在跑（已在跑则顺带刷新通知）；
     * 暂停/空闲 → 撤掉。播放/切歌都发生在用户前台操作时，无后台启动限制；
     * 蓝牙耳机后台唤起被系统拒绝的极端时序仅损失一次保护，不影响出声。
     */
    private fun syncForeground() {
        if (!::appCtx.isInitialized) return
        if (playbackActive) PlaybackService.ensureStarted(appCtx)
        else PlaybackService.stop(appCtx)
    }

    private fun onPlayerReady(seq: Int) {
        val exo = player ?: return
        if (seq != loadSeq || !isActiveMedia()) return
        val song = current ?: return
        loading = false
        if (activeReadySeq == seq) {
            isPlaying = exo.isPlaying
            updateMediaSession()
            return
        }
        activeReadySeq = seq
        autoSkipStreak = 0
        schedulePreload() // 本首就绪即预解析下两首直链（顺序/随机同源）
        val duration = exo.duration
        durationMs = if (duration > 0L && duration != C.TIME_UNSET) duration else song.durationMs.toLong()
        if (pendingSeekMs > 0L) {
            val target = pendingSeekMs
            pendingSeekMs = 0L
            // 自动恢复/远端跟播的 seek 夹紧留 15s 结尾缓冲：旧 dur-1000 会把越界的
            // 恢复进度变成「载完播 1 秒 → ENDED → 立刻切下一首」的连环跳歌
            val safe = if (durationMs > 0L) target.coerceAtMost((durationMs - 15_000L).coerceAtLeast(0L)) else target
            runCatching { exo.seekTo(safe) }
            positionMs = safe
        }
        if (pauseAfterPrepare) {
            // Remote together-listen state may intentionally arrive paused. The player was held
            // while preparing, so do not briefly start and then pause it here.
            pauseAfterPrepare = false
            exo.pause()
            isPlaying = exo.isPlaying
        } else {
            requestFocus()
            fgWanted = true
            exo.volume = sleepGain
            exo.play()
            isPlaying = exo.isPlaying
            HistoryStore.record(song) // Count only after the item is actually ready to play.
        }
        updateMediaSession()
        if (ListenSession.active && !isRemoteLoad(seq)) {
            ListenSession.reportGoto(song.id, positionMs)
            ListenSession.syncPlaylistIfHost(queue)
        }
    }

    private var remoteLoadSeq = -1

    private fun isRemoteLoad(seq: Int) = remoteLoadSeq == seq

    private fun handleCompletion(seq: Int) {
        if (seq != loadSeq || !isActiveMedia() || activeCompletionSeq == seq) return
        activeCompletionSeq = seq
        loading = false
        isPlaying = false
        val exo = player
        when {
            // Sleep timer completion takes precedence over all queue modes.
            SleepTimer.consumeEndOfSong() -> {
                pauseAction()
                isPlaying = false
                updateMediaSession()
            }
            PlayMode.SINGLE == playMode -> {
                flushListen()
                current?.let { HistoryStore.record(it) }
                startPending = true
                activeCompletionSeq = -1
                fgWanted = true // 单曲循环继续出声，保活不撤
                exo?.seekTo(0L)
                exo?.play()
                isPlaying = exo?.isPlaying == true
                updateMediaSession()
            }
            ListenSession.active -> {
                if (ListenSession.isHost) {
                    next()
                    // 播完一首补一首到歌单末尾（按用户收听喜好；仅房主补，见 refillRoomQueueAsync）
                    ListenSession.refillRoomQueueAsync()
                } else ListenSession.deferLocalAdvance()
                updateMediaSession()
            }
            else -> {
                next()
                updateMediaSession()
            }
        }
    }

    private fun failLoad(seq: Int, message: String) {
        if (seq != loadSeq || activeErrorSeq == seq) return
        activeErrorSeq = seq
        loading = false
        errorMsg = message
        fgWanted = false // 播放失败：撤前台保护（随后自动跳歌/停下）
        runCatching { player?.pause() }
        isPlaying = false
        updateMediaSession()
        skipOrStop()
    }

    // ---------- 加载 ----------
    /**
     * fromRemote：本次 load 由一起听远端同步（applyListenSync）触发。
     * ★ 远端载歌完成后【不上报】GOTO/队列——旧版在 prepared 里无条件 reportGoto(song,0)，
     *   成员按远端状态载完歌又把「进度 0」报回房间，房内其他人全被拽回歌首，
     *   即「反复卡住开始」。只有本地操作（切歌/点歌/恢复播放）产生的载歌才上报，
     *   且上报的是真实起播进度（旧版硬编码 0，恢复播放场景会把房间拉回歌首）。
     *
     * ★ startAtMs：本次载歌的【唯一】起播进度来源。
     *   换歌（playAt → load(song)）恒为 0 = 一律从头播；同一首歌续播（playInternal）传
     *   positionMs；一起听跟播（applyListenSync）传远端进度。绝不要再从外部写 pendingSeekMs。
     */
    private fun load(song: Song, fromRemote: Boolean = false, startAtMs: Long = 0L) {
        // 本地选歌 = 明确的播放意图：解除乐迷团房的本地暂停屏蔽（远端触发的 load 不算）
        if (!fromRemote && ListenSession.fltMode) ListenSession.fltLocalPause = false
        if (!fromRemote) pauseAfterPrepare = false
        flushListen()          // 上一首的收听时长先结算（整段会话汇总一条）
        listenAccSec = 0f
        startPending = true    // 新会话：首次结算时补一条 startplay（进「最近播放」）
        val seq = ++loadSeq
        remoteLoadSeq = if (fromRemote) seq else -1
        activeReadySeq = -1
        activeCompletionSeq = -1
        activeErrorSeq = -1
        // ★ 起播进度只有 startAtMs 这一个来源，每次载歌无条件覆盖（2026-09-27 修「重启后切歌
        //   继承上一首进度」与「搜索点歌没播完就跳下一首」）：旧版把它放在共享字段 pendingSeekMs
        //   里、且只由 onPlayerReady 消费清零，于是 restoreState 写入的上次进度会一直活到
        //   【下一首】歌 READY —— 新歌从老歌进度起播；被结尾 15s 缓冲夹到歌尾时更直接变成
        //   「播十几秒 → ENDED → 跳下一首」。
        pendingSeekMs = startAtMs.coerceAtLeast(0L)
        loading = true
        errorMsg = null
        current = song
        // ★ 本地播放次数不在这里记：载歌 ≠ 听过。播放失败自动跳灰歌、一起听本地暂停期间
        //   房间远端连切 N 首（pauseAfterPrepare 载入但不起播）都会走到这里——旧版在这种
        //   场景下每首都 +1，本地统计与云端严重背离。改到 onPrepared 真正起播成功才记。
        updateMediaSession()
        positionMs = 0
        durationMs = song.durationMs.toLong()
        releasePlayer()
        persistState()
        scope.launch {
            val localUri = LocalMusic.localUri(song)
            var url = localUri?.toString() ?: DownloadStore.localFile(appCtx, song.id)?.absolutePath
            if (url == null) {
                url = withContext(Dispatchers.IO) { cachedUrl(song.id) ?: NcmApi.songUrl(song.id, QualityPrefs.level) }
                if (seq != loadSeq) return@launch
                if (url == null) {
                    val message = if (NcmApi.error()?.contains("404") == true || NcmApi.error()?.contains("460") == true)
                        "该歌曲暂无版权或需要 VIP" else "获取播放地址失败"
                    failLoad(seq, message)
                    return@launch
                }
            }
            val fetchedUrl = url ?: return@launch
            val sourceUrl = if (fetchedUrl.startsWith("http://")) fetchedUrl.replace("http://", "https://") else fetchedUrl
            withContext(Dispatchers.Main) {
                if (seq != loadSeq) return@withContext
                try {
                    val exo = ensurePlayer()
                    val mediaId = "$seq:${song.id}"
                    val uri = resolveMediaUri(sourceUrl)
                    activeMediaId = mediaId
                    loudnessAudioProcessor.resetForNewTrack()
                    exo.pause()
                    exo.volume = sleepGain
                    // ★ 媒体元数据是系统媒体界面（ColorOS Watch 表盘音乐卡片 / 「正在播放」页）
                    //   的**唯一**数据来源 —— Media3 会话上报的标题/歌手/封面全部取自
                    //   player.currentMediaItem.mediaMetadata（2026-10-01 对齐 OPPO 接入点时补）。
                    //   旧版只 setMediaId/setUri，系统界面即空标题空封面。
                    exo.setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(mediaId)
                            .setUri(uri)
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(song.title)
                                    .setArtist(song.artist)
                                    .setAlbumTitle(song.album)
                                    .apply {
                                        if (durationMs > 0L) setDurationMs(durationMs)
                                        coverUriFor(song)?.let { setArtworkUri(it) }
                                    }
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .build(),
                            )
                            .build(),
                    )
                    exo.prepare()
                } catch (e: Exception) {
                    failLoad(seq, "无法播放: ${e.message}")
                }
            }
        }
    }

    private fun resolveMediaUri(value: String): Uri {
        val uri = Uri.parse(value)
        return when (uri.scheme?.lowercase()) {
            "content", "file", "http", "https" -> uri
            else -> Uri.fromFile(java.io.File(value))
        }
    }

    /**
     * 播放失败兜底：自动跳下一首。VIP 也可能遇到「数字专辑未购买 / 版权下架 / 无音源」的歌，
     * 不跳就会永远停在那。连败 3 次停手，防止整条推荐流都是灰歌时反复空转刷请求。
     * 一起听成员不自行跳灰歌：等房主跳歌的 GOTO 跟随（与播完切歌同一套协调），超时由
     * checkPendingAdvance 兜底推进——否则两台设备各自跳歌、GOTO 互相踩，全房横跳。
     */
    private fun skipOrStop() {
        if (autoSkipStreak >= 3) { autoSkipStreak = 0; return }
        autoSkipStreak++
        if (ListenSession.active && !ListenSession.isHost) ListenSession.deferLocalAdvance()
        else scope.launch { next() }
    }

    private fun releasePlayer() {
        activeMediaId = null
        runCatching { player?.stop() }
        runCatching { player?.clearMediaItems() }
        isPlaying = false
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        val mgr = am ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) {
                focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .build()
            }
            runCatching { mgr.requestAudioFocus(focusReq!!) }
        } else {
            runCatching { mgr.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) }
        }
    }

    private fun reportCmd(type: String) {
        if (ListenSession.active) {
            ListenSession.reportCommand(type, current?.id ?: 0, positionMs)
        }
    }

    // ---------- 一起听：应用远端播放状态（轮询同步用） ----------

    /**
     * 把服务器侧的房间播放状态落到本地播放器。
     * ★ 队列更新与「切歌」解耦：别人往房间加一首歌只更新队列，**不再打断你正在听的歌**
     *   （旧版整队列逐项比对，任何人加歌都会让全房间的人重新 load 当前歌）。
     * ★ seek 带 ±1.5s 容差：毫秒级漂移不值得跳进度。
     *
     * @param enforcePlay false = 用户本地已暂停（乐迷团房本地暂停屏蔽）：队列/歌曲照常跟随，
     *   但**不**把播放器顶回播放态、也不追进度（进度条冻结，恢复时由 onFltUserResume 追平）。
     */
    fun applyListenSync(songs: List<Song>, targetId: Long, progressMs: Long, play: Boolean, shouldSeek: Boolean,
                        enforcePlay: Boolean = true) {
        if (songs.isEmpty()) return
        val idx = songs.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: 0
        val songChanged = current?.id != targetId
        android.util.Log.i("LTDiag",
            "apply songs=${songs.size} target=$targetId songChanged=$songChanged " +
                "cur=${current?.id} pos=$progressMs play=$play seek=$shouldSeek shielded=${!enforcePlay}")
        queue = songs
        index = idx
        resetStream(StreamSource.NONE, songs) // 一起听队列是房间快照，不接推荐续流
        if (loading && isRemoteLoad(loadSeq)) {
            // Polls can update the desired room state while the current media item is still
            // resolving its URL or buffering. Let the latest remote state win at READY.
            pauseAfterPrepare = !play || !enforcePlay
        }
        if (songChanged) {
            pauseAfterPrepare = !play || !enforcePlay
            // 远端进度经入参传入（不再写共享字段：写进去会一直活到下一首歌载入才被消费）。
            // fromRemote = 远端触发：prepared 后不上报命令/队列（防进度回拽）
            val resumeAt = if (shouldSeek && progressMs > 0) progressMs else 0L
            load(songs[idx], fromRemote = true, startAtMs = resumeAt)
        } else {
            val drift = kotlin.math.abs(positionMs - progressMs)
            // 自动追进度双保险（防「最后几十秒直接跳歌」）：
            // ① durationMs=0（远端载歌元数据未到）不追——seekTo 失去夹紧依据，越界目标会直落歌尾；
            // ② 目标必须离歌尾 ≥15s——任何逼近结尾的 target 都会被 seekTo 的 dur-1000
            //    夹紧变成「拽到最后一秒 → 播完即 ENDED → 切歌」。倒退跟随（房间回拖）不受限。
            val saneTarget = durationMs > 0L && progressMs <= durationMs - 15_000L
            if (enforcePlay && shouldSeek && progressMs > 0 && drift > 1500 && saneTarget) seekTo(progressMs)
            if (enforcePlay && play && !isPlaying) {
                // 播放器已在歌尾（本机播完等房间切歌）：远端 PLAY 若是歌曲重开（进度回到头部）
                // 才回零重播；否则不对已完成的播放器 start()（会在歌尾空转）
                if (durationMs > 0L && positionMs >= durationMs - 1000L && progressMs > 3000L) {
                    seekTo(0L)
                }
                playInternal(userIntent = false)
            }
            if (!play && (isPlaying || player?.playWhenReady == true)) pauseInternal(userIntent = false)
        }
    }
}
