package com.netmusiclite.data

import android.os.SystemClock
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一起听会话（2026-09-11 按 MeloX 逆向规格重写为纯 HTTP 轮询方案）：
 * - 建房 room/create；加入走 play/invitation/accept（join 端点不存在，无需 ichat 长连接）
 * - 同步 = 轮询 sync/playlist/get（playCommand + 列表快照，签名去重防回环）：
 *   有变化 1s/轮，连续无变化平滑退避到 5s；status + 心跳约 10s 一次；连续 2 次失败转「重连中」
 * - 房内所有人都能控歌：本地操作上报 GOTO/PLAY/PAUSE/PROGRESS（clientSeq 递增），
 *   应用远端状态期间压掉本地上报（applyingRemote + suppressUntil 1s 防回环）；
 *   自己上报的命令不会被下一轮 sync apply 回来（防拖动进度/暂停被拉回旧值）
 * - 断线恢复：status/get 发现 inRoom 直接复活会话
 * - 人数/成员同步（2026-09-13 三轮）：房间快照深度吸收（curUserNums 等在 create/accept/
 *   status/heartbeat 各响应里嵌套位置不固定，整树遍历提取）+ 心跳响应不再丢弃；
 *   成员表一律合并去重（旧版整体覆盖 + topUsers 有 <2 条件 → 名单钉死在服务端单表
 *   ≈6 人上限）；version[].userId 来源的占位成员用 userDetail 补全昵称头像
 */
object ListenSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poll: Job? = null
    private var queueReport: Job? = null
    private var clientSeq = 0
    private var applyingRemote = false
    private var suppressUntilMs = 0L
    /** 用户意图保护窗：本地 播放/暂停/seek 上报时刷新，窗内远端播放态命令不落地（防顶回） */
    private var userIntentUntilMs = 0L
    private var lastPlaylistSig: String? = null
    private var lastCommandSig: String? = null
    private var lastDetailRetryRt = 0L   // 房间歌单详情重试节流（5s 一拍）
    private var syncFailures = 0

    var active by mutableStateOf(false)
    var isHost by mutableStateOf(false)
    var roomId by mutableStateOf<String?>(null)
    var chatRoomId by mutableStateOf<String?>(null)
    var creatorId by mutableStateOf<String?>(null)
    var members by mutableStateOf<List<FriendInfo>>(emptyList())
    /** 房间当前实时人数（RoomInfo.roomExt.curUserNums，官方当前在线口径；0=未下发，回退 totalUsers/members.size） */
    var curUsers by mutableStateOf(0L)
    /** 服务端口径的房间总人数（RoomInfo.totalUserNums，偏累计口径；0 = 未下发，回退 members.size） */
    var totalUsers by mutableStateOf(0L)
    /** 服务端统计的房间累计一起听时长（RoomInfo.effectiveDurationMs，ms；0=未下发。聊天/互动解锁用的就是它） */
    var roomListenMs by mutableStateOf(0L)

    /** UI 统一人数口径：实时 curUserNums > 偏累计 totalUserNums > 成员表大小（FLT 房同链路） */
    fun liveUserCount(): Long = when {
        fltMode -> when {
            // 乐迷团详情 roomUserNums = 验证界面同源的真实在线口径（2026-09-13 确诊：
            // totalUserNums=13万累计 / curUserNums=1.27万虚高，都不能用）
            fltRealUsers > 0L -> fltRealUsers
            fltCurUsers > 0L -> fltCurUsers
            fltUsers > 0L -> fltUsers
            else -> members.size.toLong()
        }
        curUsers > 0L -> curUsers
        totalUsers > 0L -> totalUsers
        else -> members.size.toLong()
    }
    var statusText by mutableStateOf("")
    var connected by mutableStateOf(true)     // false = 重连中
    /** FLT 艺人房模式（fla_ 前缀）：走 follow/listen 体系，轻量轮询维持，无普通房 sync/命令上报 */
    var fltMode by mutableStateOf(false)
    var fltTitle by mutableStateOf("")

    /**
     * FLT 本地暂停屏蔽（2026-09-13）：用户手动暂停后置 true，FLT 轮询
     * （`playingSong.playing` 恒 true，成员无暂停上报接口）就不再把播放器顶回播放态。
     * 用户手动播放时经 [onFltUserResume] 解除并立即追平房间进度。换房/退房复位。
     */
    var fltLocalPause by mutableStateOf(false)

    /** FLT 立即同步踢脚：本地恢复播放后不等下一拍 5s 轮询，马上追平房间进度 */
    @Volatile private var fltSyncKick = false

    /** 用户在乐迷团房手动恢复播放：解除本地暂停屏蔽 + 踢一脚立即同步 */
    fun onFltUserResume() {
        if (!fltMode) return
        fltLocalPause = false
        fltSyncKick = true
        android.util.Log.i("LTDiag", "flt user resume -> 解除暂停屏蔽，请求立即追平")
    }

    /**
     * 是否乐迷团跟听房（FLT）——**聊天通道选择用这个，不要直接用 fltMode**。
     * fltMode 只在 joinFlt() 里置位；若某个进入路径漏标（例如按房号 join 到 fla_xxx
     * 之外口径、或房间类型由 status 补下发），fltMode 会是 false，聊天就会退到
     * 「逐个成员发私信」的桥接通道 —— 乐迷团房成员来自全站 topUsers（陌生人），
     * 于是界面上就出现一堆私信错误。这里用房号前缀 + 房型三重兜底判定。
     */
    val isFltRoom: Boolean get() = fltMode ||
        roomId?.startsWith("fla_") == true || roomId?.startsWith("fl_") == true ||
        roomType.uppercase().contains("FOLLOW_LISTEN")

    /** 房间类型（roomInfo.roomType / roomExt.roomType；FLT 真机为 FOLLOW_LISTEN）：
     *  房内发消息时填进 clientExt 的 bizType / ltType */
    var roomType by mutableStateOf("")
    /** FLT 房在听总人数（roomInfo.totalUserNums，偏累计口径）：topUsers 只回头部若干人，总数以此为准 */
    var fltUsers by mutableStateOf(0L)
    /** FLT 房当前实时在听人数（roomExt.curUserNums；0=未下发时回退 fltUsers/members.size） */
    var fltCurUsers by mutableStateOf(0L)
    var lastCommandText by mutableStateOf<String?>(null)  // 最近一次远端命令（调试/展示）
    var syncedSongIds by mutableStateOf<List<Long>>(emptyList())
    /**
     * 房间推荐歌曲 id（官方字段：`sync/playlist/get` 响应里
     * `playlist.displayList.rcmdSongIds`，官方 DTO 即 `LTPlaylist$PlaylistBean$DisplayListBean`，
     * 字段名与层级已用官方 dex 逐字核对）。
     *
     * 官方没有独立的「取推荐」端点 —— 推荐随每次播放列表同步一起下发，想换一批就是
     * **重新拉一次 `sync/playlist/get`**（对应官方 `Lzc0/t0$m;->f` 的调用路径）。
     */
    var rcmdSongIds by mutableStateOf<List<Long>>(emptyList())
        private set
    /** 推荐最近一次刷新时刻（0 = 本会话还没拿到过） */
    var rcmdUpdatedAt by mutableStateOf(0L)
        private set
    /** 本次会话开始（进房）的墙钟时间：房间聊天按此过滤私信历史 */
    var sessionStartWallMs = 0L

    /**
     * 房内累计时长（uid → 秒）：官方同样无服务端字段（官方包内 LocalListenDuration{roomId,count}
     * 也是本地按房间记），但旧版存会话内存——重进房间/杀进程清零，即「只统计我进房间后的」。
     * 现按 roomId 持久化到 listen_room_secs.json，同一房间再进接着累计。
     * 他人 = 本端在房观察到的在场时长（对方早于我进房的部分无从得知）；自己 = 实际播放时长。
     */
    var memberRoomSecs by mutableStateOf<Map<Long, Long>>(emptyMap())
    /** 时长来自服务端下发（seedServerRoomTime 命中）的成员：UI 可标注「含进房前」 */
    var serverTimeUids by mutableStateOf<Set<Long>>(emptySet())
    /** FLT 诊断快照一次性开关（每个进程只 dump 一轮 roomInfo/心跳全量 JSON） */
    private var fltDumped = false
    private var hbDumped = false
    /** 乐迷团详情 listenTogether.roomUserNums（验证界面同源）= 房内真实在线数口径 */
    private var fltRealUsers by mutableStateOf(0L)
    private var fltProbeTick = 0
    /** 当前 FLT 房的艺人 id（乐迷团详情探测用），从房间 artistInfo 提取 */
    private var fltArtistId = 0L
    private val sinceRt = HashMap<Long, Long>()   // uid → 首次出现在成员表的 elapsedRealtime
    private val roomSecs = HashMap<Long, Long>()  // uid → 累计秒（establish 时从落盘回填）
    private var lastAccumRt = 0L
    private var lastSecsSaveRt = 0L

    private var secsFile: java.io.File? = null

    /** MainActivity 挂载私有目录（与 HistoryStore 同模式），房间累计时长落盘用 */
    fun init(ctx: android.content.Context) {
        secsFile = java.io.File(ctx.filesDir, "listen_room_secs.json")
    }

    /** 按 roomId 回填历史累计（同房间再进接着数；跨房间互不影响） */
    private fun loadRoomSecs(rid: String) {
        roomSecs.clear()
        val f = secsFile ?: return
        runCatching {
            if (!f.exists()) return
            val rooms = org.json.JSONObject(f.readText())
            val rec = rooms.optJSONObject(rid) ?: return
            rec.keys().forEach { k ->
                val v = rec.optLong(k, 0L)
                if (v > 0L) k.toLongOrNull()?.let { roomSecs[it] = v }
            }
        }
        memberRoomSecs = roomSecs.toMap()
    }

    /** 落盘（低频：60s 一拍 + 退房时），只保留最近 20 个房间防止无限膨胀 */
    private fun saveRoomSecs(forRid: String? = null) {
        val rid = forRid ?: roomId ?: return
        val f = secsFile ?: return
        val uid = SessionStore.uid
        if (uid > 0L) roomSecs[uid] = roomSecs.getOrDefault(uid, 0L) // 确保自己也有记录位
        runCatching {
            val rooms = if (f.exists()) runCatching { org.json.JSONObject(f.readText()) }.getOrDefault(org.json.JSONObject())
            else org.json.JSONObject()
            val rec = org.json.JSONObject()
            roomSecs.forEach { (u, s) -> if (u > 0L && s > 0L) rec.put(u.toString(), s) }
            rooms.put(rid, rec)
            while (rooms.length() > 20) {
                rooms.remove(rooms.keys().next())
            }
            f.writeText(rooms.toString())
        }
    }

    /** 进房前的播放模式：房间同步期间全局模式被房间接管，退出时还原 */
    private var modeBeforeJoin: PlayMode? = null

    // ---------- 房内切歌协调（2026-09-12 二轮） ----------
    // 旧版所有设备在本机 onCompletion 各自 next() 并上报 GOTO(下一首,0)：两台设备差几秒
    // 完成同一首歌，各自载歌上报，随机模式各选各的下一首 → 全房反复横跳重载。
    // 现「房主主导」：房主完成即推进上报；成员完成后 deferLocalAdvance 等远端 GOTO 跟随，
    // 超时才本地兜底推进（此时本端成为实际主导）。灰歌跳歌同规则。

    /** 房间当前已知目标歌（最近一次远端应用或本地上报）：GOTO 回声抑制基准 */
    private var roomTargetId = 0L
    private var pendingAdvanceRt = 0L
    private var pendingAdvanceSongId = 0L

    /** 成员本机歌播完/灰歌跳不过：不自行切，记下待跟随状态等远端 GOTO */
    fun deferLocalAdvance() {
        pendingAdvanceSongId = PlayerEngine.current?.id ?: 0L
        pendingAdvanceRt = SystemClock.elapsedRealtime()
        android.util.Log.i("LTDiag", "defer-advance song=$pendingAdvanceSongId")
    }

    /** 轮询每拍调用：远端已切歌（本地在跟随载歌）即撤销；超 6s 无远端动作则本地兜底推进 */
    private fun checkPendingAdvance() {
        if (pendingAdvanceRt == 0L) return
        when {
            PlayerEngine.current?.id != pendingAdvanceSongId -> pendingAdvanceRt = 0L
            SystemClock.elapsedRealtime() - pendingAdvanceRt > 6000L -> {
                pendingAdvanceRt = 0L
                android.util.Log.i("LTDiag", "defer-advance timeout -> local next")
                PlayerEngine.next()
            }
        }
    }

    private fun gate(): Boolean =
        active && !applyingRemote && SystemClock.elapsedRealtime() >= suppressUntilMs

    private fun nextSeq(): Int = ++clientSeq

    /** 从粘贴文本提取 (roomId, inviterId)：支持完整分享链接 / 房号+uid 空格分隔 / 裸房号(null) */
    fun parseInvite(raw: String): Pair<String, String?> {
        val s = raw.trim().replace("&amp;", "&")
        val rid = Regex("roomId=([^&\\s\"']+)", RegexOption.IGNORE_CASE).find(s)?.groupValues[1]
            ?: Regex("([0-9a-fA-F]{16,}_\\d+)").find(s)?.groupValues[1]
            ?: s
        val iid = Regex("inviter(?:Id|Uid)=([^&\\s\"']+)", RegexOption.IGNORE_CASE).find(s)?.groupValues[1]
        return rid to iid
    }

    /** 建房：需要正在播放的歌（进房即上报队列 + GOTO + 心跳） */
    suspend fun create(): Boolean {
        statusText = "正在创建房间…"
        val d = NcmApi.ltCreate() ?: run { statusText = "创建房间失败"; return false }
        val info = d.optJSONObject("roomInfo") ?: run { statusText = "房间信息异常"; return false }
        establish(info)
        isHost = true
        statusText = "房间已创建"
        startMonitor()
        // ★ 2026-10-01：房间初始歌单 = 「我喜欢的音乐」里随机 [ROOM_SEED_SIZE] 首。
        //   旧版是「把当前本地队列整份上报」—— 正在放「我喜欢的音乐」时开房，
        //   房间歌单就成了整个收藏列表（用户反馈「点开歌单直接是我的收藏歌曲」，
        //   而且这样别人推的歌也淹没在里面看不出来）。用户口径：随机五首收藏里的。
        //   ⚠ 播种必须在**建房成功之后**：它会切歌，建房失败就不该白打断用户正在听的歌。
        val seeded = seedRoomQueue()
        // 初始同步：队列快照 + 当前歌 GOTO（带房主真实进度，成员进房才对得上）+ 心跳
        PlayerEngine.current?.let {
            // ★ 只上报**房间自己的**歌单：播种命中就是那 5 首；播种失败（收藏为空、
            //   未登录且口味兜底也没返回）也最多带当前这一首 —— 绝不把整个本地队列
            //   （可能是我喜欢的音乐几百首）灌进房间。
            val snapshot = if (seeded) PlayerEngine.queue else listOf(it)
            syncPlaylistNow(snapshot)
            reportCommand("GOTO", it.id, PlayerEngine.positionMs)
        }
        heartbeatNow()
        // 房内「开始一起听」系统提示（官方 multi/start/msg）：房内聊天里会出现一条开场提示
        roomId?.let { runCatching { NcmApi.ltStartMsg(it) } }
        return true
    }

    /**
     * 房间初始歌单播种：取「我喜欢的音乐」随机 [ROOM_SEED_SIZE] 首作为房间歌单；
     * 收藏为空 / 未登录 / 接口失败时退回「按收听口味随机」的 [NcmApi.roomSeedSongs]。
     *
     * 为什么不能沿用「当前本地队列整份」：用户开房时往往正在放「我喜欢的音乐」，
     * 整份带过去 = 房间歌单变成他个人的收藏列表（别人推的歌也就看不出来了）。
     *
     * @return 是否真的播种成功（false = 队列未被替换，调用方应只上报当前这一首）
     */
    private suspend fun seedRoomQueue(): Boolean {
        val liked = runCatching { NcmApi.likedSongs(SessionStore.uid) }.getOrDefault(emptyList())
        val seed = if (liked.isNotEmpty()) liked.shuffled().take(ROOM_SEED_SIZE)
        else runCatching { NcmApi.roomSeedSongs(ROOM_SEED_SIZE) }.getOrDefault(emptyList())
        if (seed.isEmpty()) return false
        PlayerEngine.playQueue(seed, 0, PlayerEngine.StreamSource.FM)
        android.util.Log.i("LTDiag", "seedRoomQueue n=${seed.size} fromLiked=${liked.isNotEmpty()}")
        return true
    }

    /** 乐迷团一起听：① 同账号已在房（官方 App 开的乐迷团房间）→ status 直接恢复挂进房间；
     *  ② 否则发现好友在开的房间并加入（艺人页入口） */
    suspend fun joinDiscovered(): Boolean {
        statusText = "正在检查房间状态…"
        if (tryRestore()) {
            android.util.Log.i("LTDiag", "restore-from-status ok roomId=$roomId")
            return true
        }
        statusText = "正在寻找进行中的一起听…"
        val rid = NcmApi.ltDiscoverRoom() ?: run {
            // 诊断：失败时自动探测候选端点，logcat(tag=LTDiag) 里核对哪个端点能返回房间
            runCatching { NcmApi.ltProbeEndpoints(chatRoomId) }
            statusText = "未发现可加入的一起听（乐迷团房间需在官方 App 同账号已进入，或用邀请链接加入）"
            return false
        }
        android.util.Log.i("LTDiag", "discovered roomId=$rid")
        return join(rid)
    }

    /** 加入：分享链接（含 inviterId）最佳；裸房号退化为 check→discover creatorId→accept。
     *  FLT 艺人房（fla_/fl_ 前缀或链接 isFLT=true）走 follow/listen/join/room + 轻量轮询。 */
    suspend fun join(code: String): Boolean {
        val (rid, iid) = parseInvite(code)
        if (rid.isEmpty()) { statusText = "请输入房号或分享链接"; return false }
        if (rid.startsWith("fla_") || rid.startsWith("fl_") ||
            code.contains("isFLT=true", ignoreCase = true)) return joinFlt(rid)
        statusText = "正在检查房间…"
        val checkResp = NcmApi.ltCheckRaw(rid)
        val checkData = checkResp.optJSONObject("data")
        val joinable = checkData?.optBoolean("joinable", false) == true ||
            checkData?.optBoolean("isJoinable", false) == true
        if (!joinable) {
            statusText = "房间不可加入（check=${checkResp.optInt("code")}/${checkData?.optString("status") ?: "-"}）"
            return false
        }
        // inviterId：优先分享链接 → check 响应里的创建者 → status/get 查创建者
        val inviter = iid
            ?: checkData?.optString("creatorId")?.ifEmpty { null }
            ?: checkData?.optString("inviterId")?.ifEmpty { null }
            ?: creatorIdOfRoom(rid)
            ?: run { statusText = "房号缺少邀请人信息，请粘贴完整分享链接"; return false }
        statusText = "正在加入…"
        val d = NcmApi.ltAccept(rid, inviter)
        val info = d?.optJSONObject("roomInfo")
            ?: (NcmApi.ltStatus()?.optJSONObject("roomInfo")?.takeIf { it.optString("roomId") == rid })
            ?: run { statusText = "加入失败（accept 未返回房间）"; return false }
        establish(info)
        isHost = info.optString("creatorId") == SessionStore.uid.toString()
        statusText = "已加入房间"
        startMonitor()
        syncFromServer(initial = true)
        heartbeatNow()
        return true
    }

    /**
     * 加入 FLT 艺人房：follow/listen/join/room（★ 必须安卓口径 cookie，裸 MUSIC_U 被
     * 「当前设备存在异常」设备风控拒绝，2026-09-12 实测）。成功后轻量轮询维持。
     */
    private suspend fun joinFlt(rid: String): Boolean {
        statusText = "正在加入乐迷团房…"
        var r = NcmApi.fltJoin(rid) ?: run { statusText = "加入失败（服务端无响应）"; return false }
        if (r.optInt("code", 0) != 200) {
            statusText = "加入失败（code=${r.optInt("code", 0)}）"
            return false
        }
        // ★ 2026-09-13 真机确诊：join/room 返回 code=200 不一定加入成功 —— data.hintType 非成功时
        //   roomInfo 为 null（ROOM_NOT_EXIST=房间已解散 / BIZ_FILTER=请先关注艺人后加入）。
        //   旧版把 200 当成功进了"假房间"：chatRoomId=null → 房内聊天室通道整个不启动
        //   （"乐迷团房聊天不能用"的根因）。roomInfo 非空才算真房；
        //   BIZ_FILTER 时 failedOrpheus 里带 artistId，自动关注后重试一次。
        var data = r.optJSONObject("data")
        if (data?.optJSONObject("roomInfo") == null) {
            val bizMsg = data?.optString("bizFilterMessage").orEmpty()
            val failedOrpheus = data?.optString("failedOrpheus").orEmpty()
            val hintArtist = Regex("artistId=(\\d+)").find(failedOrpheus)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("fla_(\\d+)_").find(rid)?.groupValues?.get(1)?.toLongOrNull()
            if (bizMsg.contains("关注") && hintArtist != null && hintArtist > 0L) {
                statusText = "需关注艺人，关注后重试…"
                val subOk = runCatching { NcmApi.artistSub(hintArtist, true) }.getOrDefault(false)
                if (subOk) {
                    r = NcmApi.fltJoin(rid) ?: run { statusText = "加入失败（重试无响应）"; return false }
                    if (r.optInt("code", 0) != 200) {
                        statusText = "加入失败（重试 code=${r.optInt("code", 0)}）"
                        return false
                    }
                    data = r.optJSONObject("data")
                }
            }
            if (data?.optJSONObject("roomInfo") == null) {
                statusText = when {
                    bizMsg.isNotEmpty() -> "加入失败：$bizMsg"
                    data?.optString("hintType").orEmpty().isNotEmpty() -> "加入失败（房间已结束或不可加入）"
                    else -> "加入失败（服务端未返回房间信息）"
                }
                return false
            }
        }
        end(silent = true) // 防御：若残留普通房会话先清掉
        fltMode = true
        fltLocalPause = false // 新会话不带上一场的暂停屏蔽
        roomId = rid
        active = true
        connected = true
        isHost = false
        sessionStartWallMs = System.currentTimeMillis()
        resetRoomTime()
        // 房信息（逆向文档 4.2）：creator 与 topUsers 是 data 顶层字段，roomInfo 只有基本信息
        // （data 已在上方真房校验中取得，含自动关注重试后的最新响应）
        val info = data?.optJSONObject("roomInfo")
        // ★ 真机确诊（2026-09-12）：FLT 房的聊天室 id 不在 roomInfo 顶层，而在
        //   roomInfo.roomExt.chatRoomId（日志实录 roomExt={"roomType":"FOLLOW_LISTEN",
        //   "subType":"ARTIST","chatRoomId":16127873036,...}）。旧版只取顶层 →
        //   chatRoomId 恒为 null → 房内聊天室历史通道整个没启动（乐迷团房"收不到消息"的根因）。
        chatRoomId = info?.optString("chatRoomId")?.ifEmpty { null }
            ?: info?.optJSONObject("roomExt")?.optString("chatRoomId")?.ifEmpty { null }
        creatorId = data?.optJSONObject("creator")?.optLong("userId", 0L)?.takeIf { it > 0L }?.toString()
            ?: info?.optString("creatorId")?.ifEmpty { null }
        // 人数口径确诊日志：totalUserNums 的实际值与 roomInfo 全部键
        android.util.Log.i("LTDiag",
            "fltRoomInfo total=${info?.optLong("totalUserNums", -1L)} " +
                "cur=${info?.optJSONObject("roomExt")?.optLong("curUserNums", -1L)} " +
                "keys=${info?.keys()?.asSequence()?.joinToString(",").orEmpty().take(150)}")
        members = parseFltUsers(data?.optJSONArray("topUsers"))
            .ifEmpty { parseFltUsers(info?.optJSONArray("roomUsers")) }
        markMembersSeen()
        // 进房快照即试播种「对方进房前已听」（服务端下发该字段才有效，2026-09-13）
        seedRoomTimeFromUsers(data?.optJSONArray("topUsers"))
        seedRoomTimeFromUsers(info?.optJSONArray("roomUsers"))
        // 房间艺人 id（乐迷团详情轮询用）+ 真实人数口径重置（换房必须清零）
        info?.optJSONObject("artistInfo")?.optLong("artistId", 0L)
            ?.takeIf { it > 0L }?.let { fltArtistId = it }
        fltRealUsers = 0L; fltProbeTick = 0
        // 实时在听人数：roomExt.curUserNums 优先（totalUserNums 偏累计，含已退出者）
        info?.optJSONObject("roomExt")?.optLong("curUserNums", 0L)?.takeIf { it > 0L }?.let { fltCurUsers = it }
        fltUsers = info?.optLong("totalUserNums", 0L) ?: 0L
        roomType = roomTypeOf(info)
        fltTitle = info?.optString("roomTitle")?.ifEmpty { null } ?: "乐迷团房"
        statusText = "已加入乐迷团房"
        startFltMonitor()
        return true
    }

    /** FLT 成员数组解析（roomUsers / topUsers 通用；多形态防御已并入 parseRoomUser） */
    private fun parseFltUsers(arr: org.json.JSONArray?): List<FriendInfo> = parseRoomUsers(arr)

    /**
     * **创建乐迷团一起听房**（2026-10-01 新增，官方 `listen/together/multi/room/create`）。
     *
     * 与 [create] 的普通房区别：带 `artistId`（建的是艺人乐迷团房，房号 `fla_` 前缀）
     * 与 `autoJoinUids`（受邀好友**直接进房**，免对方再点接受 —— 官方「拉好友一起听」就是这个语义）。
     *
     * 服务端对艺人房有权限校验（通常仅该艺人乐迷团成员可开），被拒时按房号/房型回落判定：
     * 返回的房不是 FLT 房就当普通多人房跑 [startMonitor]，是 FLT 房就跑 [startFltMonitor]，
     * 两条监控链路各自对应各自的同步接口，不会互相打架。
     */
    suspend fun createFlt(artistId: Long, inviteUids: List<Long> = emptyList()): Boolean {
        statusText = "正在创建乐迷团房…"
        val songId = PlayerEngine.current?.id ?: 0L
        val d = NcmApi.ltCreateMulti(
            songId = songId,
            inviteUids = inviteUids,
            autoJoinUids = inviteUids,
            artistId = artistId,
        ) ?: run { statusText = "创建乐迷团房失败（服务端未返回房间）"; return false }
        val info = d.optJSONObject("roomInfo") ?: run { statusText = "房间信息异常"; return false }
        end(silent = true)
        establish(info)   // 内部会把 fltMode 重置为 false，下面按房号/房型重新定性
        val isFlt = roomId?.startsWith("fla_") == true || roomId?.startsWith("fl_") == true ||
            roomType.uppercase().contains("FOLLOW_LISTEN")
        if (isFlt) {
            fltMode = true
            fltLocalPause = false
            fltTitle = info.optString("roomTitle").ifEmpty { null } ?: "乐迷团房"
            // 乐迷团房的 chatRoomId 在 roomInfo.roomExt（与 joinFlt 同源，见那里的确诊注释）
            chatRoomId = info.optString("chatRoomId")?.ifEmpty { null }
                ?: info.optJSONObject("roomExt")?.optString("chatRoomId")?.ifEmpty { null }
            info.optJSONObject("artistInfo")?.optLong("artistId", 0L)?.takeIf { it > 0L }?.let { fltArtistId = it }
            fltUsers = info.optLong("totalUserNums", 0L)
            info.optJSONObject("roomExt")?.optLong("curUserNums", 0L)?.takeIf { it > 0L }?.let { fltCurUsers = it }
            fltRealUsers = 0L
            fltProbeTick = 0
        }
        isHost = true
        statusText = if (isFlt) "乐迷团房已创建" else "房间已创建"
        if (isFlt) startFltMonitor() else startMonitor()
        // 初始同步：队列快照 + 当前歌 GOTO（带房主真实进度，成员进房才对得上）+ 心跳
        PlayerEngine.current?.let {
            syncPlaylistNow(PlayerEngine.queue.ifEmpty { listOf(it) })
            reportCommand("GOTO", it.id, PlayerEngine.positionMs)
        }
        heartbeatNow()
        // 房内「开始一起听」系统提示（官方 multi/start/msg）
        roomId?.let { runCatching { NcmApi.ltStartMsg(it) } }
        return true
    }

    /** 房间类型：roomInfo.roomType 优先，回退 roomExt.roomType（FLT 真机实测为 FOLLOW_LISTEN） */
    private fun roomTypeOf(info: JSONObject?): String =
        info?.optString("roomType")?.ifEmpty { null }
            ?: info?.optJSONObject("roomExt")?.optString("roomType")?.ifEmpty { null }
            ?: ""

    /** org.json 对 JSON null 的 optString 返回字面 "null"：FLT 房字段统一清洗 */
    private fun clean(s: String): String = if (s.isEmpty() || s == "null") "" else s

    private fun artistsOf(o: JSONObject): String =
        o.optJSONArray("artistNames")?.let { a ->
            (0 until a.length()).mapNotNull { i -> clean(a.optString(i)).ifEmpty { null } }
                .joinToString("/")
        } ?: ""

    /** FLT 房 playingSong/songList 条目 → Song（真机字段不全，元数据靠 songDetail 补） */
    private fun fltSongOf(o: JSONObject): Song = Song(
        o.optLong("songId", 0L), clean(o.optString("songName")), artistsOf(o),
        0L, "", 0L, 0, clean(o.optString("picUrl")).ifEmpty { null })

    // ---------- 歌单主动拉取（浮层打开时不再只等轮询） ----------

    /** 歌单拉取状态："" 空闲 / "loading" / "ok" / "fail"（浮层显示用） */
    var queueSyncState by mutableStateOf("")
    var queueSyncNote by mutableStateOf("")

    /**
     * 主动拉一次房间歌单（歌单浮层打开时调用）。
     * ⚠ 只更新本地队列并把索引对齐到当前歌，**不切歌、不 seek** —— 与轮询同步
     *   「别人加歌不打断我正在听的歌」的语义保持一致；轮询处在退避/失败状态时，
     *   用户也能靠它把歌单拉出来（旧版浮层只被动等轮询，轮询一挂歌单就永远空着）。
     */
    suspend fun pullRoomQueueNow(): Boolean {
        if (!active) return false
        val rid = roomId ?: return false
        queueSyncState = "loading"
        queueSyncNote = ""
        val songs = try {
            if (fltMode) fetchFltQueue(rid) else fetchNormalQueue(rid)
        } catch (e: Exception) {
            queueSyncState = "fail"
            queueSyncNote = "歌单拉取失败：${e.message ?: "网络异常"}"
            return false
        }
        if (songs.isEmpty()) {
            queueSyncState = "fail"
            queueSyncNote = if (fltMode)
                "乐迷团歌单未返回内容（接口无响应时会自动重试）"
            else "房间歌单还没有歌 · 点下方「＋ 添加歌曲到房间」"
            android.util.Log.i("LTDiag", "pullQueue empty flt=$fltMode rid=$rid")
            return false
        }
        val cur = PlayerEngine.current?.id ?: 0L
        PlayerEngine.queue = songs
        val idx = songs.indexOfFirst { it.id == cur }
        if (idx >= 0) PlayerEngine.index = idx
        queueSyncState = "ok"
        queueSyncNote = ""
        android.util.Log.i("LTDiag", "pullQueue ok n=${songs.size} cur=$cur idx=$idx flt=$fltMode")
        return true
    }

    /** 普通房歌单：sync/playlist/get 的 displayList/randomList + 命令锚点 */
    private suspend fun fetchNormalQueue(rid: String): List<Song> {
        val d = NcmApi.ltSyncPayload(rid) ?: return emptyList()
        val playlist = d.optJSONObject("playlist")
        val ids = parseListEither(playlist, "displayList") ?: emptyList()
        val mode = playlist?.optString("playMode").orEmpty()
        val shuffled = mode.contains("RANDOM", true) || mode.contains("SHUFFLE", true)
        val list = (if (shuffled) parseListEither(playlist, "randomList") else null) ?: ids
        val anchor = d.optJSONObject("playCommand")?.optString("targetSongId")?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: playlist?.optString("anchorSongId")?.toLongOrNull()?.takeIf { it > 0L }
            ?: PlayerEngine.current?.id?.takeIf { it > 0L }
        val all = if (anchor != null && list.none { it == anchor }) list + anchor else list
        if (all.isEmpty()) return emptyList()
        return runCatching { NcmApi.songDetail(all) }.getOrDefault(emptyList())
    }

    /** FLT 房歌单：follow/listen/room/playlist/getAll 的 songList + playingSong */
    private suspend fun fetchFltQueue(rid: String): List<Song> {
        val j = NcmApi.fltPlaylistAll(rid) ?: return emptyList()
        if (j.optInt("code", 0) != 200) return emptyList()
        val data = j.optJSONObject("data") ?: return emptyList()
        val ps = data.optJSONObject("playingSong")
        val sid = ps?.optLong("songId", 0L) ?: 0L
        val q = ArrayList<Song>()
        data.optJSONArray("songList")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = fltSongOf(o)
                if (s.id > 0L) q.add(s)
            }
        }
        if (ps != null && sid > 0L && q.none { it.id == sid }) q.add(0, fltSongOf(ps))
        if (q.isEmpty()) return emptyList()
        // 真机 songList 字段不全（歌名/封面缺失）→ 批量补全，失败则用原条目
        val full = runCatching { NcmApi.songDetail(q.map { it.id }.distinct()) }.getOrDefault(emptyList())
        if (full.isEmpty()) return q
        val byId = full.associateBy { it.id }
        return q.map { byId[it.id] ?: it }
    }

    /**
     * FLT 房轻量维持：心跳 + playlist/getAll 轮询（官方 HTTP 兜底通道同思路）。
     * getAll 真机确诊结构（2026-09-12）：data.playingSong{songId,songName,artistNames[],
     * picUrl,playing,progress(秒),timestamp(服务端ms)} + data.songList[]。
     * 同步复用 PlayerEngine.applyListenSync：队列静默更新、歌变跟播、
     * 目标进度 = progress×1000 + (本机时间 − 服务端 timestamp)、±1.5s 容差防抖。
     */
    private fun startFltMonitor() {
        poll?.cancel()
        poll = scope.launch {
            var lastSong = 0L
            var tick = 0
            // ★ 歌单元数据缓存:getAll 的 songList 条目真机字段不全（真机歌单卡片全空白、
            //   时长 0:00）——按 id 批量 songDetail 拉全量存缓存，本拍与后续拍统一替换。
            //   新歌 id 出现才补拉，5s 轮询不重复请求
            val metaCache = HashMap<Long, Song>()
            var metaFetching = false
            while (isActive && active) {
                val rid = roomId
                if (rid == null) { end(notice = "房间已结束"); return@launch }
                // 本地恢复播放后的立即追平：踢一脚就跳过本轮末尾的 5s 等待
                val immediate = fltSyncKick
                fltSyncKick = false
                checkPendingAdvance()
                runCatching {
                    val hb = NcmApi.fltHeartbeat(rid)
                    // 一次性心跳响应全量快照（找真实在线数字段，见 fltNums 注释）
                    if (hb != null && !hbDumped) {
                        hbDumped = true
                        android.util.Log.i("LTBig", "hb=${hb.toString().take(1800)}")
                    }
                    hb?.let { absorbRoomSnapshot(it, flt = true) }
                }
                // 成员/人数定期刷新（follow/listen/status/get；房内成员动态进出）
                // 2026-09-13：10s 一拍（旧 30s 跟不上进出房节奏，是「实时人数不同步」一因）；
                // 解析走 absorbRoomSnapshot 整树吸收 + 合并去重（旧版 members 整体覆盖）
                if (++tick % 2 == 0) {
                    runCatching {
                        val st = NcmApi.fltStatusGet() ?: return@runCatching
                        if (st.optInt("code", 0) != 200) return@runCatching
                        // 人数口径确诊：roomExt 扩展 + 总数/头部数（实时在线数疑似在 roomExt）
                        val d = st.optJSONObject("data")
                        val dri = d?.optJSONObject("roomInfo")
                        android.util.Log.i("LTDiag",
                            "fltStatusExt total=${dri?.optLong("totalUserNums", -1L)} " +
                                "top=${d?.optJSONArray("topUsers")?.length()} " +
                                "roomExt=${dri?.optJSONObject("roomExt")?.toString()?.take(300)} " +
                                "merged=${members.size} live=${liveUserCount()}")
                        absorbRoomSnapshot(d ?: st, flt = true)
                        // 一次性 roomInfo 全量快照（分块进 LTBig，定位真实在线数字段）
                        if (!fltDumped) {
                            fltDumped = true
                            val full = dri?.toString() ?: "{}"
                            var i = 0
                            while (i * 900 < full.length) {
                                val end = minOf((i + 1) * 900, full.length)
                                android.util.Log.i("LTBig", "roomInfo[$i]=${full.substring(i * 900, end)}")
                                i++
                            }
                        }
                        // 数字字段全扫（每拍）：只保留 1~99999 的人数量级小数，
                        // id/时间戳类大数自动过滤 —— 2026-09-13 确诊 totalUserNums（13万，累计）
                        // 与 curUserNums（1.27万，虚高）都不是真实在线数（实际一百多），
                        // 真字段待本轮扫描定位后接管 liveUserCount 的 FLT 口径
                        val nums = StringBuilder()
                        fun scanNums(o: JSONObject?, depth: Int) {
                            if (o == null || depth > 2) return
                            for (k in o.keys()) {
                                when (val v = o.opt(k)) {
                                    is Number -> { val n = v.toLong(); if (n in 1..99999) nums.append("$k=$n ") }
                                    is JSONObject -> scanNums(v, depth + 1)
                                    else -> {}
                                }
                            }
                        }
                        scanNums(d, 0)
                        android.util.Log.i("LTDiag", "fltNums $nums")
                        // 房内真实在线数：乐迷团详情 listenTogether.roomUserNums
                        //（验证界面同源；2026-09-13 确诊 roomInfo 的 totalUserNums=13万累计、
                        // curUserNums=1.27万虚高、room/user/top=404 都不可用）。
                        // 首次拿到 artistId 立即探一次，之后 30s 一拍
                        dri?.optJSONObject("artistInfo")?.optLong("artistId", 0L)
                            ?.takeIf { it > 0L }?.let { fltArtistId = it }
                        if (fltArtistId > 0L && (fltRealUsers == 0L || ++fltProbeTick % 3 == 0)) {
                            runCatching {
                                val p = NcmApi.fanGroupListenProbe(fltArtistId)
                                if (p?.listening == true && p.roomUserNums > 0L) fltRealUsers = p.roomUserNums
                            }
                        }
                        // 聊天室 id 随 status 定期补齐（进房时可能还没下发，房间聊天靠它才能收到消息）
                        dri?.optJSONObject("roomExt")?.optString("chatRoomId")?.ifEmpty { null }
                            ?.let { chatRoomId = it }
                        roomTypeOf(dri).ifEmpty { null }?.let { roomType = it }
                    }
                }
                var fltAllFails = 0
                runCatching {
                    val j = NcmApi.fltPlaylistAll(rid)
                    if (j == null || j.optInt("code", 0) != 200) {
                        // 歌单拉取失败打点（风控/会话上下文问题时全房看不到歌曲列表，靠日志定位）
                        if (++fltAllFails % 3 == 1) {
                            android.util.Log.i("LTDiag", "fltPlaylistAll fail code=${j?.optInt("code") ?: -1}")
                        }
                        return@runCatching
                    } else fltAllFails = 0
                    val data = j.optJSONObject("data") ?: return@runCatching
                    val ps = data.optJSONObject("playingSong") ?: return@runCatching
                    val sid = ps.optLong("songId", 0L)
                    if (sid <= 0L) return@runCatching
                    val q = ArrayList<Song>()
                    data.optJSONArray("songList")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val s = fltSongOf(o)
                            if (s.id > 0L) q.add(s)
                        }
                    }
                    if (q.none { it.id == sid }) q.add(0, fltSongOf(ps))
                    // 元数据替换 + 缺失补拉（详情异步到缓存，下一拍生效）
                    if (metaCache.isNotEmpty()) {
                        for (i in q.indices) metaCache[q[i].id]?.let { q[i] = it }
                    }
                    val missing = q.map { it.id }.distinct().filter { metaCache[it] == null }
                    if (missing.isNotEmpty() && !metaFetching) {
                        metaFetching = true
                        launch {
                            val full = runCatching { NcmApi.songDetail(missing) }.getOrDefault(emptyList())
                            full.forEach { s -> if (s.id > 0L) metaCache[s.id] = s }
                            if (metaCache.size > 800) metaCache.clear() // 防膨胀
                            metaFetching = false
                        }
                    }
                    val playing = ps.optBoolean("playing", false)
                    val progMs = ps.optLong("progress", 0L) * 1000L
                    val ts = ps.optLong("timestamp", 0L)
                    var target = if (playing && ts > 0L)
                        (progMs + (System.currentTimeMillis() - ts)).coerceAtLeast(0L)
                    else progMs
                    // ★ 乐迷团艺人房实测：服务端 progress 会超出歌曲时长（房间会话级流逝时间，
                    //   且过期 timestamp 的补偿会把进度推到歌尾之外）。旧版把越界目标夹到
                    //   dur-1000（歌尾前1秒）→ 播放器被硬拽到最后一秒、播完即 ENDED 切歌，
                    //   「歌还剩几十秒直接跳下一首」的根因。现改为：结尾留 15s 缓冲；
                    //   progress 本身越过缓冲（被会话流逝时间污染）时进度整体不可信，
                    //   本轮放弃 seek 只信本地播放器，等服务端切歌再跟随。
                    var seek = true
                    val dur = (metaCache[sid]?.durationMs ?: 0).toLong()
                    if (dur > 0L) {
                        val tailGuard = (dur - 15_000L).coerceAtLeast(0L)
                        if (progMs > tailGuard) seek = false
                        target = target.coerceAtMost(tailGuard)
                    }
                    PlayerEngine.applyListenSync(q, sid, target, playing, shouldSeek = seek,
                        enforcePlay = !fltLocalPause)
                    // 当前歌元数据补全（房间页标题/封面/时长读 current）
                    PlayerEngine.current?.let { cur ->
                        val m = metaCache[cur.id]
                        if (m != null && (cur.title.isBlank() || cur.coverUrl == null)) {
                            PlayerEngine.current = m
                            if (m.durationMs > 0) PlayerEngine.durationMs = m.durationMs.toLong()
                        }
                    }
                }
                accumulateRoomTime()
                // 恢复播放踢脚：本轮已立即同步过 → 只歇 200ms 再来一拍（追上房间进度）；
                // 否则标准 5s 节拍
                delay(if (immediate) 200L else 5000L)
            }
        }
    }

    /** 断线恢复：启动/进页时发现服务器侧还在房内 → 直接复活会话 */
    suspend fun tryRestore(): Boolean {
        if (active) return true
        val st = NcmApi.ltStatus() ?: return false
        if (!st.optBoolean("inRoom", false)) return false
        val info = st.optJSONObject("roomInfo") ?: return false
        establish(info)
        isHost = info.optString("creatorId") == SessionStore.uid.toString()
        // ★ 恢复口径：establish 按普通房把 fltMode 重置为 false，但乐迷团房（fla_/fl_ 前缀、
        //   FOLLOW_LISTEN 房型）必须纠正回 FLT——否则恢复后的会话走普通房 sync 轮询
        //   （FLT 房拉不到歌单）、退房上报 ltEnd 而非 fltExit，整场口径全错
        if (isFltRoom) {
            fltMode = true
            fltLocalPause = false
            fltTitle = info.optString("roomTitle").ifEmpty { null } ?: "乐迷团房"
            info.optJSONObject("roomExt")?.optString("chatRoomId")?.ifEmpty { null }?.let { chatRoomId = it }
            statusText = "已恢复乐迷团房"
            startFltMonitor()
            return true
        }
        statusText = "已恢复房间"
        startMonitor()
        syncFromServer(initial = true)
        return true
    }

    private suspend fun creatorIdOfRoom(rid: String): String? {
        val st = runCatching { NcmApi.ltStatus() }.getOrNull() ?: return null
        val info = st.optJSONObject("roomInfo") ?: return null
        return info.optString("creatorId").ifEmpty { null }?.takeIf { info.optString("roomId") == rid }
    }

    private fun establish(info: JSONObject) {
        poll?.cancel()
        queueReport?.cancel()
        roomId = info.optString("roomId").ifEmpty { null }
        chatRoomId = info.optString("chatRoomId").ifEmpty { null }
        roomType = roomTypeOf(info)
        creatorId = info.optString("creatorId").ifEmpty { null }
        members = parseRoomUsers(info.optJSONArray("roomUsers"))
            .ifEmpty { parseRoomUsers(info.optJSONArray("topUsers")) }
        totalUsers = info.optLong("totalUserNums", 0L)
        // 实时人数：roomExt.curUserNums（官方当前在线口径）。totalUserNums 偏累计，
        // 把已退出/重复进出的人都算进去——「实时人数不正确」的主因
        info.optJSONObject("roomExt")?.optLong("curUserNums", 0L)?.takeIf { it > 0L }?.let { curUsers = it }
        info.optLong("effectiveDurationMs", 0L).takeIf { it > 0L }?.let { roomListenMs = it }
        clientSeq = 0
        modeBeforeJoin = PlayerEngine.playMode
        syncFailures = 0
        rcmdSongIds = emptyList()
        fltMode = false
        fltTitle = ""
        lastPlaylistSig = null
        lastCommandSig = null
        roomTargetId = 0L
        pendingAdvanceRt = 0L
        applyingRemote = false
        // ★ 不设抑制窗：establish 后紧跟着的初始 GOTO/队列快照/心跳是建房必须的第一条命令，
        //   旧版设 1s 抑制窗把这些请求全 gate 掉了——房间里根本没有当前歌命令，
        //   成员进房只能落到歌单第一首、进度 0（「无法同步播放进度」主因之一）
        suppressUntilMs = 0L
        connected = true
        active = true
        sessionStartWallMs = System.currentTimeMillis()
        roomId?.let { loadRoomSecs(it) }
        markMembersSeen()
        // 进房快照即试播种「对方进房前已听」（服务端下发该字段才有效，2026-09-13）
        seedRoomTimeFromUsers(info.optJSONArray("roomUsers"))
        seedRoomTimeFromUsers(info.optJSONArray("topUsers"))
        // 人数口径确诊（首轮进房打一条）：totalUserNums/curUserNums 实际值 + roomInfo/roomUser 键名
        android.util.Log.i("LTDiag",
            "establish totalUserNums=${info.optLong("totalUserNums", -1L)} " +
                "curUserNums=${info.optJSONObject("roomExt")?.optLong("curUserNums", -1L)} " +
                "roomUsers=${info.optJSONArray("roomUsers")?.length() ?: -1} " +
                "roomKeys=${info.keys()?.asSequence()?.joinToString(",").orEmpty().take(160)} " +
                "userKeys=${info.optJSONArray("roomUsers")?.optJSONObject(0)?.keys()?.asSequence()?.joinToString(",").orEmpty().take(160)}")
    }

    /** 单个房间成员解析（多形态防御：userId|uid|user.userId、nickname|profile.nickname|nickName、"null" 清洗） */
    private fun parseRoomUser(o: JSONObject): FriendInfo? {
        val uid = o.optLong("userId", 0L).takeIf { it > 0L }
            ?: o.optLong("uid", 0L).takeIf { it > 0L }
            ?: o.optJSONObject("user")?.optLong("userId", 0L)?.takeIf { it > 0L }
            ?: return null
        val prof = o.optJSONObject("profile")
        val nick = listOf(
            o.optString("nickname"), prof?.optString("nickname").orEmpty(),
            o.optString("nickName"), o.optString("userName"), o.optString("name"),
        ).firstOrNull { it.isNotEmpty() && it != "null" } ?: "用户 $uid"
        val avatar = listOfNotNull(
            o.optString("avatarUrl").ifEmpty { null }, prof?.optString("avatarUrl")?.ifEmpty { null },
            o.optString("avatar").ifEmpty { null },
        ).firstOrNull { it != "null" }
        return FriendInfo(uid, nick, avatar)
    }

    /** roomUsers/topUsers 数组 → 成员列表（普通房/FLT 房通用） */
    private fun parseRoomUsers(arr: JSONArray?): List<FriendInfo> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseRoomUser(it) } }
    }

    // ---------- 人数/成员快照吸收（2026-09-13「实时人数不同步 + 名单钉死 6 人」修复） ----------

    /**
     * 深度遍历房间快照，提取实时人数/总人数/房间累计时长，并合并成员表。
     * 背景：房间信息在 room/create、invitation/accept、status/get、heartbeat 各响应里的
     * 嵌套位置不固定（roomInfo 顶层 / roomInfo.roomExt / data.roomInfo…），旧版只在
     * 固定路径读 curUserNums —— status/get 不带该路径时，人数从进房起就不再变化。
     * 这里整树遍历：任一层出现 curUserNums/totalUserNums/effectiveDurationMs 即采纳；
     * roomUsers/topUsers 全部合并进成员表（去重，不覆盖已知的昵称头像）。
     */
    private fun absorbRoomSnapshot(root: JSONObject?, flt: Boolean) {
        root ?: return
        var cur = -1L; var total = -1L; var effMs = -1L
        val users = ArrayList<FriendInfo>()
        fun walk(o: JSONObject?) {
            o ?: return
            if (o.has("curUserNums")) cur = o.optLong("curUserNums", cur)
            if (o.has("totalUserNums")) total = o.optLong("totalUserNums", total)
            if (o.has("effectiveDurationMs")) effMs = o.optLong("effectiveDurationMs", effMs)
            for (key in listOf("roomUsers", "topUsers")) {
                o.optJSONArray(key)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val u = arr.optJSONObject(i) ?: continue
                        parseRoomUser(u)?.let {
                            users.add(it)
                            seedServerRoomTime(it.id, u)
                        }
                    }
                }
            }
            for (k in o.keys()) when (val v = o.opt(k)) {
                is JSONObject -> walk(v)
                is JSONArray -> for (i in 0 until v.length()) walk(v.optJSONObject(i))
                else -> {}
            }
        }
        walk(root)
        if (flt) {
            if (cur > 0L) fltCurUsers = cur
            if (total > 0L) fltUsers = total
        } else {
            if (cur > 0L) curUsers = cur
            if (total > 0L) totalUsers = total
            if (effMs > 0L) roomListenMs = effMs
        }
        mergeMembers(users)
        enrichUnknownMembers()
    }

    /**
     * 成员表合并去重：只新增、不覆盖（除非新数据更全：占位昵称→真昵称 / 补上头像）。
     * 旧版 refreshStatus 先 `members = fresh` 整体覆盖，再仅在 members.size < 2 时合并
     * topUsers —— roomUsers 一旦回了 6 人，topUsers 与 version[].userId 补进来的成员
     * 每 10s 被抹掉一次，名单被钉死在服务端单表上限（≈6 人）。
     */
    private fun mergeMembers(fresh: List<FriendInfo>) {
        if (fresh.isEmpty()) return
        val merged = members.toMutableList()
        var changed = false
        fresh.forEach { f ->
            if (f.id <= 0L) return@forEach
            val idx = merged.indexOfFirst { it.id == f.id }
            if (idx < 0) { merged.add(f); changed = true }
            else {
                val old = merged[idx]
                if ((old.name.startsWith("用户 ") && !f.name.startsWith("用户 ")) ||
                    (old.avatarUrl == null && f.avatarUrl != null)) {
                    merged[idx] = f; changed = true
                }
            }
        }
        if (changed) members = merged
        markMembersSeen()
    }

    /** 从成员数组播种「进房前已听」信息（服务端下发才有效，见 seedServerRoomTime） */
    private fun seedRoomTimeFromUsers(arr: JSONArray?) {
        arr ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            seedServerRoomTime(o.optLong("userId", o.optLong("uid", 0L)), o)
        }
    }

    /**
     * 防御性解析成员条目里的「进房前已在房」信息：
     * - joinTime/joinTs/enterTime（进房时刻，秒或毫秒时间戳）→ sinceRt 回拨到对方进房时刻、
     *   roomSecs 预填对应秒数 → 名单里能看到「我进房之前对方已听多久」；
     * - listenTime/listenDuration/inRoomTime（已在房时长，秒或毫秒）→ 直接预填。
     * ⚠ 官方 9.5.90 逆向实测服务端不下发这些字段（官方 App 的成员时长同样是本地计），
     *   此解析纯兜底：命中则 UI 标「含进房前」，不命中维持本端观测口径。
     */
    private fun seedServerRoomTime(uid: Long, o: JSONObject) {
        if (uid <= 0L || uid == SessionStore.uid) return
        val nowRt = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val joinRaw = listOf("joinTime", "joinTs", "enterTime", "inTime")
            .firstNotNullOfOrNull { k -> o.optLong(k, 0L).takeIf { it > 0L } }
        if (joinRaw != null && joinRaw > 1_000_000_000L) {   // 排除相对秒数等小值
            val joinMs = if (joinRaw > 1_000_000_000_000L) joinRaw else joinRaw * 1000L
            val aheadSecs = ((nowWall - joinMs) / 1000L).coerceIn(0L, 86_400L)
            if (aheadSecs > 0L) {
                sinceRt[uid] = nowRt - (aheadSecs * 1000L).coerceAtMost(nowRt)
                if (aheadSecs > (roomSecs[uid] ?: 0L)) roomSecs[uid] = aheadSecs
                serverTimeUids = serverTimeUids + uid
                memberRoomSecs = roomSecs.toMap()
            }
            return
        }
        val durRaw = listOf("listenTime", "listenDuration", "inRoomTime", "listenSecs", "heardTime")
            .firstNotNullOfOrNull { k -> o.optLong(k, 0L).takeIf { it > 0L } }
        if (durRaw != null && durRaw > 5L) {
            val secs = if (durRaw > 100_000L) durRaw / 1000L else durRaw   // >10 万视为毫秒
            if (secs > (roomSecs[uid] ?: 0L)) {
                roomSecs[uid] = secs
                serverTimeUids = serverTimeUids + uid
                memberRoomSecs = roomSecs.toMap()
            }
        }
    }

    // ---------- 占位成员资料补全 ----------

    /** 「用户 $uid」占位成员（version[].userId 来源，无昵称头像）低频补全，每拍最多 3 人 */
    private val enrichBusy = HashSet<Long>()
    private val enrichFailed = HashSet<Long>()
    private var lastEnrichRt = 0L

    private fun enrichUnknownMembers() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEnrichRt < 4000L) return
        lastEnrichRt = now
        members.filter { it.avatarUrl == null && it.name.startsWith("用户 ") }
            .filter { it.id !in enrichBusy && it.id !in enrichFailed }
            .take(3)
            .forEach { m ->
                enrichBusy.add(m.id)
                scope.launch {
                    val d = runCatching { NcmApi.userDetail(m.id) }.getOrNull()
                    enrichBusy.remove(m.id)
                    if (d == null) enrichFailed.add(m.id)
                    else members = members.map {
                        if (it.id == m.id) it.copy(name = d.nickname, avatarUrl = d.avatarUrl ?: it.avatarUrl) else it
                    }
                }
            }
    }

    // ---------- 房内时长跟踪（成员名单浮层的「在房/已听 X 分钟」） ----------

    /** 成员表刷新后调用：首次出现的成员记录起始时刻（自本次进房起累计） */
    private fun markMembersSeen() {
        val now = SystemClock.elapsedRealtime()
        members.forEach { m -> if (m.id > 0L) sinceRt.putIfAbsent(m.id, now) }
        SessionStore.uid.takeIf { it > 0L }?.let { sinceRt.putIfAbsent(it, now) }
    }

    /** 轮询每拍调用：他人按在场累计秒数，自己按实际播放累计（挂钟差、单次夹 30s 防时钟跳变） */
    private fun accumulateRoomTime() {
        val now = SystemClock.elapsedRealtime()
        if (lastAccumRt > 0L) {
            val delta = (now - lastAccumRt).coerceIn(0L, 30_000L) / 1000L
            if (delta > 0L) {
                members.forEach { m -> if (m.id > 0L) roomSecs.merge(m.id, delta, Long::plus) }
                val self = SessionStore.uid
                if (self > 0L && PlayerEngine.isPlaying) roomSecs.merge(self, delta, Long::plus)
            }
        }
        lastAccumRt = now
        memberRoomSecs = roomSecs.toMap()
        // 60s 一拍落盘：杀进程/掉线重进同房间时累计不丢（问题「只统计我进房间后的」）
        if (now - lastSecsSaveRt >= 60_000L) {
            lastSecsSaveRt = now
            saveRoomSecs()
        }
    }

    private fun resetRoomTime() {
        sinceRt.clear()
        roomSecs.clear()
        lastAccumRt = 0L
        memberRoomSecs = emptyMap()
        serverTimeUids = emptySet()
        fltRealUsers = 0L
        fltProbeTick = 0
        fltArtistId = 0L
    }

    /**
     * 主轮询循环。有变化时 1s/轮，连续无变化平滑退避到 5s（省电省流量，一有命令立刻回 1s）；
     * status + 心跳按真实时间 ≈10s 一次——心跳太疏会被服务端判离线，所以不随退避拉长。
     * 连续 2 次失败 → 「重连中」。
     */
    private fun startMonitor() {
        poll?.cancel()
        poll = scope.launch {
            var idle = 0
            var lastBeat = 0L
            while (isActive && active) {
                checkPendingAdvance()
                val changed = runCatching { syncFromServer(initial = false) }
                    .onFailure { syncFailures++ }
                    .onSuccess { syncFailures = 0 }
                    .getOrDefault(false)
                idle = if (changed) 0 else idle + 1
                accumulateRoomTime()
                val now = SystemClock.elapsedRealtime()
                if (now - lastBeat >= 10_000) {
                    lastBeat = now
                    runCatching {
                        refreshStatus()
                        if (active) heartbeatNow()
                    }
                }
                connected = syncFailures < 2
                delay(
                    when {
                        idle >= 20 -> 5000L
                        idle >= 8 -> 3000L
                        else -> 1000L
                    }
                )
            }
        }
    }

    private suspend fun heartbeatNow() {
        val rid = roomId ?: return
        val song = PlayerEngine.current ?: return
        // 载歌中（还没对齐到目标进度）不上报：把 position=0 发给服务端会污染房间播放状态，
        // 服务端若以心跳聚合「当前进度」，新成员 sync 拿到的就永远是 0
        if (PlayerEngine.loading) return
        runCatching {
            val j = NcmApi.ltHeartbeat(rid, song.id, PlayerEngine.isPlaying, PlayerEngine.positionMs)
            // 心跳响应也可能携带房间快照（人数/成员/时长）——顺手深度吸收，
            // 人数同步在 status/get 不带回快照时仍有一路来源（2026-09-13）
            j?.let { absorbRoomSnapshot(it, flt = fltMode) }
        }
    }

    /** 房间被服务端结束/被移出 → 清会话；否则刷新成员表与实时人数（≈10s 一次） */
    private suspend fun refreshStatus() {
        val rid = roomId ?: return
        val st = NcmApi.ltStatus() ?: return
        if (!st.optBoolean("inRoom", false)) { end(notice = "房间已结束") ; return }
        val info = st.optJSONObject("roomInfo") ?: return
        if (info.optString("roomId") != rid) { end(notice = "账号已在其他设备进房") ; return }
        // 人数/成员/时长：整树深度吸收 + 合并去重（2026-09-13 修复实时人数不同步、
        // 名单钉死 6 人——旧版先覆盖再 <2 条件合并，见 mergeMembers 注释）
        absorbRoomSnapshot(info, flt = false)
        statusLogTick++
        if (statusLogTick % 6 == 1) {
            android.util.Log.i("LTDiag",
                "roomStatus total=${info.optLong("totalUserNums", -1L)} " +
                    "cur=${info.optJSONObject("roomExt")?.optLong("curUserNums", -1L)} " +
                    "roomUsers=${info.optJSONArray("roomUsers")?.length() ?: -1} " +
                    "merged=${members.size} live=${liveUserCount()} " +
                    "roomListenMs=$roomListenMs " +
                    "roomKeys=${info.keys()?.asSequence()?.joinToString(",").orEmpty().take(160)}")
        }
    }

    private var statusLogTick = 0
    private var syncEmptyLog = 0

    /** 拉取远端播放状态并应用（签名去重：列表和命令各自变化才动作）。返回本轮是否有变化，作为轮询退避依据 */
    private suspend fun syncFromServer(initial: Boolean): Boolean {
        val rid = roomId ?: return false
        val fetchedAtRt = SystemClock.elapsedRealtime()
        val d = NcmApi.ltSyncPayload(rid) ?: return false
        val playlist = d.optJSONObject("playlist")
        val cmd = d.optJSONObject("playCommand")

        // displayList 两种形态都解析：{displayList:{result:[...]}}（服务端正规化/旧实测）
        // 与 displayList:[...]（官方 App PlaylistCommand 直传的裸数组）——形态不符时
        // id 列表为空 → 歌单/播放永远不同步（「歌单显示不出来」根因之一）
        val ids = parseListEither(playlist, "displayList") ?: emptyList()
        if (ids.isEmpty()) {
            syncEmptyLog++
            if (syncEmptyLog % 5 == 1) {
                android.util.Log.i("LTDiag",
                    "sync idsEmpty keys=${playlist?.keys()?.asSequence()?.joinToString(",")} body=${d.toString().take(280)}")
            }
        }
        // 房间智能推荐：官方把推荐歌曲挂在 displayList.rcmdSongIds（与 result 同级），
        // 不是独立端点，随每次 sync 一起返回，因此无需额外请求。
        // ★ 2026-10-01：解析改为多形态兜底（见 parseRcmdIds）—— 旧版只认
        //   `playlist.displayList` 是对象这一种形态，官方 PlaylistCommand 直传
        //   displayList 为**裸数组**时 optJSONObject 返回 null，推荐整块永远为空。
        rcmdSongIds = parseRcmdIds(d, playlist)
        rcmdUpdatedAt = System.currentTimeMillis()
        val playMode = playlist?.optString("playMode") ?: ""
        // ★ 成员自愈：version[].userId = 所有上报过歌单命令的房间参与者。status/get 的
        //   roomUsers 缺失/滞后时，聊天收件人、成员表靠这里补齐（否则私信桥接没有收件人，
        //   聊天「无法使用」）
        playlist?.optJSONArray("version")?.let { v ->
            val known = members.map { it.id }.toSet()
            val fresh = (0 until v.length()).mapNotNull { i ->
                val uid = v.optJSONObject(i)?.optLong("userId", 0L)?.takeIf { it > 0L } ?: return@mapNotNull null
                if (uid in known || uid == SessionStore.uid) null else FriendInfo(uid, "用户 $uid", null)
            }
            if (fresh.isNotEmpty()) { members = members + fresh; markMembersSeen() }
        }
        val playlistSig = "${playMode}|${ids.joinToString(",")}|${
            playlist?.optJSONArray("version")?.let { v ->
                (0 until v.length()).joinToString(",") { i -> "${v.optJSONObject(i)?.optString("userId")}:${v.optJSONObject(i)?.optInt("version")}" }
            } ?: ""
        }"
        val commandSig = cmd?.let {
            "${it.optLong("serverSeq")}|${it.optLong("clientSeq")}|${it.optString("userId")}|" +
                "${it.optString("commandType")}|${it.optString("targetSongId")}|${it.optLong("progress")}|${it.optString("playStatus")}"
        }
        val playlistChanged = playlistSig != lastPlaylistSig
        val commandChanged = commandSig != lastCommandSig
        if (!initial && !playlistChanged && !commandChanged) return false
        lastPlaylistSig = playlistSig
        lastCommandSig = commandSig
        syncedSongIds = ids
        lastCommandText = cmd?.let { c ->
            val who = if (c.optString("userId") == SessionStore.uid.toString()) "我" else "伙伴"
            "远端: ${who} ${c.optString("commandType")}"
        }

        // ★ 竞态修复：命令是我自己上报的（服务端只是回显）→ 本地已是最新状态，
        //   不能再 apply 回来，否则拖动进度/暂停会被 1 秒后的同步拉回旧值。
        //   只有刚加入/恢复（initial，需要对齐一次）或别人发的命令才落地。
        val fromMe = cmd?.optString("userId") == SessionStore.uid.toString()
        if (!initial && fromMe) return true

        // 随机模式优先取 randomList（同样双形态解析）
        val songIdList = (if (playMode.contains("RANDOM", true) || playMode.contains("SHUFFLE", true))
            parseListEither(playlist, "randomList") else null) ?: ids

        // 目标歌定位链：命令 → 歌单锚点（官方 App 房无 HTTP 命令时）→ 本机当前歌 → 歌单第一首
        val targetId = cmd?.optString("targetSongId")?.toLongOrNull()?.takeIf { it > 0L }
            ?: playlist?.optString("anchorSongId")?.toLongOrNull()?.takeIf { it > 0L }
            ?: PlayerEngine.current?.id?.takeIf { it > 0L }
            ?: songIdList.firstOrNull()?.takeIf { it > 0L }
        if (targetId == null || targetId <= 0) return true
        roomTargetId = targetId // 房间当前目标歌：GOTO 回声抑制基准
        val all = (songIdList + targetId).distinct()
        if (all.isEmpty()) return true

        val shouldPlay = cmd?.optString("playStatus")?.uppercase()
            ?.let { when (it) { "PLAY", "PLAYING" -> true; "PAUSE", "PAUSED" -> false; else -> null } }
            ?: when (cmd?.optString("commandType")?.uppercase()) {
                "PLAY", "GOTO", "NEXT", "PREV" -> true
                "PAUSE" -> false
                else -> PlayerEngine.isPlaying
            }
        val progressMs = cmd?.optLong("progress", 0L) ?: 0L
        val shouldSeek = initial || commandChanged

        // 命中本地缓存队列（且元数据齐全，有封面）则不重新拉详情
        val local = PlayerEngine.queue
        var songs: List<Song> =
            if (local.map { it.id } == all && local.all { it.coverUrl != null || it.id < 0L }) local
            else runCatching { NcmApi.songDetail(all) }.getOrDefault(emptyList())
        if (songs.size < all.size) {
            // ★ 详情拉取失败/部分失败：占位歌保底——歌曲列表先显示出来（id 占位，
            //   当前歌用单首详情补全），并撤销签名 + 节流重试，下一轮拉全量详情替换。
            //   旧逻辑失败即 return 且签名已更新 → 永不重试，歌单永久空白
            if (SystemClock.elapsedRealtime() - lastDetailRetryRt > 5000L) {
                lastDetailRetryRt = SystemClock.elapsedRealtime()
                val byId = songs.associateBy { it.id }
                val single = if (targetId > 0L && byId[targetId] == null)
                    runCatching { NcmApi.songDetail(listOf(targetId)) }.getOrDefault(emptyList())
                else emptyList()
                val merged = (songs + single).associateBy { it.id }
                songs = all.map { id -> merged[id] ?: Song(id, "…", "", 0L, "", 0L, 0, null) }
                lastPlaylistSig = null
                lastCommandSig = null
            } else return true
        }
        if (songs.isEmpty()) return true

        // ★ 命令进度时间补偿：playCommand.progress 是命令发出时刻的静态快照（无时间戳字段，
        //   官方靠 IM 毫秒级推送所以不用补；HTTP 轮询有至多一个轮询周期的滞后）。
        //   播放中按「取回→落地」的流逝时间补齐，误差压进官方 2s 容差内；上限 5s——
        //   详情拉取耗时太久时命令已不可信，宁少勿多。暂停态用静态值。
        val elapsed = (SystemClock.elapsedRealtime() - fetchedAtRt).coerceIn(0L, 5000L)
        val effectiveProgress = if (shouldPlay) progressMs + elapsed else progressMs

        // 远端模式映射（RANDOM→随机，其余顺序循环）
        PlayerEngine.playMode = if (playMode.contains("RANDOM", true) || playMode.contains("SHUFFLE", true))
            PlayMode.SHUFFLE else PlayMode.LOOP

        // ★ 用户意图保护窗：本地 暂停/播放/seek 后 5s 内，远端播放态命令不落地（签名已更新，
        //   不会反复触发）。背景：服务端会把心跳聚合回吐成 playCommand（progress 逐次变化 →
        //   签名永远「有变化」），或对方设备状态回灌——旧版会把用户刚按下的暂停顶回播放
        //   （「没法自己暂停」）。歌曲切换（GOTO）不受窗限制，房间切歌仍即时跟随。
        val songChangedNow = PlayerEngine.current?.id != targetId
        if (!initial && !songChangedNow && SystemClock.elapsedRealtime() < userIntentUntilMs) {
            android.util.Log.i("LTDiag",
                "apply-shielded type=${cmd?.optString("commandType")} status=${cmd?.optString("playStatus")} " +
                    "prog=$effectiveProgress user=${cmd?.optString("userId")}")
            return true
        }
        android.util.Log.i("LTDiag",
            "apply cmd me=${SessionStore.uid} user=${cmd?.optString("userId")} type=${cmd?.optString("commandType")} " +
                "status=${cmd?.optString("playStatus")} prog=$effectiveProgress target=$targetId initial=$initial")

        applyingRemote = true
        suppressUntilMs = SystemClock.elapsedRealtime() + 1000
        try {
            PlayerEngine.applyListenSync(songs, targetId, effectiveProgress, shouldPlay, shouldSeek)
        } finally {
            applyingRemote = false
        }
        return true
    }

    /** 歌单 id 数组解析：兼容 {listKey:{result:[...]}} 包裹形态（服务端正规化），不匹配返回 null */
    private fun parseSongIds(playlist: JSONObject?, listKey: String): List<Long>? {
        val arr = playlist?.optJSONObject(listKey)?.optJSONArray("result") ?: return null
        return parseBareIds(arr)
    }

    /** 双形态解析：优先包裹形态，不匹配再试裸数组；两者皆无返回 null */
    private fun parseListEither(playlist: JSONObject?, listKey: String): List<Long>? =
        parseSongIds(playlist, listKey) ?: playlist?.optJSONArray(listKey)?.let { parseBareIds(it) }

    /** 裸数组 id 解析（元素为数字或字符串），负值/0 过滤、去重保序 */
    private fun parseBareIds(arr: JSONArray?): List<Long> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) { is Number -> v.toLong(); is String -> v.toLongOrNull(); else -> null }
        }.filter { it > 0 }.distinct()
    }

    /** 裸 id 数组解析（rcmdSongIds 是数组本身，不像 displayList 那样套一层 result） */
    private fun parseArrayIds(arr: JSONArray?): List<Long> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) { is Number -> v.toLong(); is String -> v.toLongOrNull(); else -> null }
        }.filter { it > 0 }.distinct()
    }

    /**
     * 房间推荐 id 解析（对齐官方字段，三级兜底，2026-10-01）。
     *
     * 官方 DTO（dex 实证）：
     * ```
     * LTPlaylist.playlist            → PlaylistBean
     *   PlaylistBean.displayList     → DisplayListBean{ changed, result, rcmdSongIds }
     * ```
     * 即标准路径是 `playlist.displayList.rcmdSongIds`。
     *
     * 但服务端对 `displayList` 存在**两种形态**（本文件 parseListEither 早已为此做过兜底）：
     * ① 对象 `{result:[...], rcmdSongIds:[...]}`；② 官方 PlaylistCommand 直传的**裸数组**。
     * 形态②下 `optJSONObject("displayList")` 恒为 null —— 旧版只认形态①，于是
     * 「房间推荐」整块永远空着（用户反馈「推荐和官方不一致 / 出不来」）。
     * 所以这里补两级：裸数组形态下推荐与它同级（`playlist.rcmdSongIds`），
     * 再兜一层顶层 `rcmdSongIds`。
     */
    private fun parseRcmdIds(d: JSONObject?, playlist: JSONObject?): List<Long> {
        parseArrayIds(playlist?.optJSONObject("displayList")?.optJSONArray("rcmdSongIds"))
            .takeIf { it.isNotEmpty() }?.let { return it }
        parseArrayIds(playlist?.optJSONArray("rcmdSongIds"))
            .takeIf { it.isNotEmpty() }?.let { return it }
        return parseArrayIds(d?.optJSONArray("rcmdSongIds"))
    }

    /**
     * 主动刷一次房间推荐（官方通道，2026-10-01 新增）。
     *
     * 官方**没有**独立的「取推荐」端点：推荐歌曲挂在播放列表响应里随每次 sync 下发
     * （dex 里 `listen/together/heart/rcmd/change` 只吃 `{roomId, status}`，是开关语义，
     * 不是取推荐）。所以「换一批」= 重新拉一次 `listen/together/sync/playlist/get`，
     * 与官方客户端 `Lzc0/t0$m;->f` 的调用路径完全一致。
     *
     * @return 是否拿到推荐（拿到即为最新一批）
     */
    suspend fun refreshRecommendation(): Boolean {
        if (!active) return false
        val rid = roomId ?: return false
        val d = runCatching { NcmApi.ltSyncPayload(rid) }.getOrNull() ?: return false
        val playlist = d.optJSONObject("playlist")
        rcmdSongIds = parseRcmdIds(d, playlist)
        rcmdUpdatedAt = System.currentTimeMillis()
        android.util.Log.i(
            "LTDiag",
            "rcmd refresh n=${rcmdSongIds.size} ids=${rcmdSongIds.take(12)} " +
                "displayListType=${playlist?.opt("displayList")?.javaClass?.simpleName}",
        )
        return rcmdSongIds.isNotEmpty()
    }

    /**
     * 房间推荐（解析成可播放的 Song，并剔除已在房间歌单里的）。
     * 明细走官方批量接口 `/v3/song/detail`（[NcmApi.songDetail]），与队列同一口径。
     */
    suspend fun recommendSongs(): List<Song> {
        val ids = rcmdSongIds.filterNot { id -> PlayerEngine.queue.any { it.id == id } }
        if (ids.isEmpty()) return emptyList()
        return runCatching { NcmApi.songDetail(ids) }.getOrDefault(emptyList())
    }

    /**
     * 往房间歌单追加一首（2026-09-11 新增）：
     * 只追加到队尾 → 本地 index 不变、当前播放不受影响；随后按 REPLACE 口径
     * 上报完整快照，房内其他成员的队列随之更新。
     */
    fun addToRoomQueue(song: Song): Boolean {
        if (!active) return false
        if (PlayerEngine.queue.any { it.id == song.id }) return false
        val wasEmpty = PlayerEngine.queue.isEmpty()
        PlayerEngine.queue = PlayerEngine.queue + song
        reportQueueAfterAdd(if (wasEmpty) listOf(song.id) else null)
        return true
    }

    /**
     * 批量追加进房间歌单（多选添加用）：本地一次拼接 + **一次**快照上报
     * （循环调 addToRoomQueue 会逐首上报 N 次完整快照）。返回 (新增数, 已存在数)。
     */
    fun addMultiToRoomQueue(songs: List<Song>): Pair<Int, Int> {
        if (!active || songs.isEmpty()) return 0 to songs.size
        val wasEmpty = PlayerEngine.queue.isEmpty()
        val fresh = songs.filter { s -> PlayerEngine.queue.none { it.id == s.id } }
        if (fresh.isEmpty()) return 0 to songs.size
        PlayerEngine.queue = PlayerEngine.queue + fresh
        reportQueueAfterAdd(if (wasEmpty) fresh.map { it.id } else null)
        return fresh.size to (songs.size - fresh.size)
    }

    /**
     * 加歌后的队列上报 —— 带**基座保护**。
     *
     * 正常路径：本地队列就是房间歌单（已同步）→ 直接上报完整快照，别人加/删都能同步。
     *
     * 兜底路径（[newIdsOnly] 非空 = 加之前本地队列是空的）：说明本地还没同步到房间歌单
     * （刚进房 / 同步失败）。这时若把「只有这一首」的本地快照上报，服务端会把房间歌单
     * **整个 REPLACE 成一首** —— 房主和其他成员的歌全丢（用户担心的「别人推的歌进不来」
     * 的另一面）。所以改为以服务端已同步的 id 表 [syncedSongIds] 为基座，只把新增拼上去。
     */
    private fun reportQueueAfterAdd(newIdsOnly: List<Long>?) {
        if (newIdsOnly == null) {
            syncPlaylist(PlayerEngine.queue)
            return
        }
        val base = if (syncedSongIds.isNotEmpty()) syncedSongIds + newIdsOnly else newIdsOnly
        android.util.Log.i(
            "LTDiag",
            "addQueue base-guard localEmpty=true base=${base.size} add=${newIdsOnly.size}",
        )
        syncPlaylistIds(base)
    }

    /** 房间推荐歌曲是否已在队列里（UI 用来标记「已在房间歌单」） */
    fun inQueue(songId: Long): Boolean = PlayerEngine.queue.any { it.id == songId }

    /**
     * 从房间歌单移除第 [i] 首（2026-10-01 长按删除）。
     * 本地移除后按 REPLACE 口径重报完整快照，房内其他成员的队列同步收缩。
     * 注意：这是从**房间歌单**里删，与「取消收藏」无关，不动任何收藏数据。
     */
    fun removeFromRoomQueue(i: Int): Boolean {
        if (!active) return false
        if (!PlayerEngine.removeFromQueue(i)) return false
        syncPlaylist(PlayerEngine.queue)
        return true
    }

    // ---------- 房间歌单自动续播（2026-10-01） ----------

    /** 房间初始歌单条数：随机挑选这么多首起播，之后每播完一首补一首 */
    const val ROOM_SEED_SIZE = 5

    private var refilling = false

    /**
     * 房间「播完一首补一首」：每首歌播完后，按用户收听喜好取一首新歌追加到**歌单末尾**，
     * 并按 REPLACE 口径同步给全房。
     *
     * 选歌口径：以**当前这首歌**为种子取相似推荐（心动模式同一套接口，天然是「按你的口味」）；
     * 拿不到相似流就退个人 FM。全程排除队列里已有的歌，避免重复补进同一首。
     *
     * 为什么只让房主补：`sync/playlist` 是整表 REPLACE，若每个成员都补，同一拍会互相覆盖，
     * 队列反复横跳。房主补完由远端下发给成员，全房看到的顺序一致。
     */
    fun refillRoomQueueAsync() {
        if (!active || fltMode || !isHost) return
        if (refilling) return
        refilling = true
        scope.launch {
            try {
                val queued = PlayerEngine.queue.map { it.id }.toHashSet()
                val seed = PlayerEngine.current?.id ?: 0L
                val fresh = runCatching {
                    if (seed > 0L) NcmApi.heartModeList(seed, count = 20) else emptyList()
                }.getOrDefault(emptyList()).filterNot { it.id in queued }
                    .ifEmpty {
                        runCatching { NcmApi.personalFM() }.getOrDefault(emptyList())
                            .filterNot { it.id in queued }
                    }
                val one = fresh.firstOrNull()
                // 期间可能已退房 / 换房 / 不再是房主：任一发生就丢弃这次补歌
                if (one == null || !active || fltMode || !isHost) return@launch
                PlayerEngine.queue = PlayerEngine.queue + one
                syncPlaylist(PlayerEngine.queue)
                android.util.Log.i(
                    "LTDiag",
                    "refill +${one.id} queue=${PlayerEngine.queue.size} idx=${PlayerEngine.index}")
            } finally {
                refilling = false
            }
        }
    }

    // ---------- 本地操作上报（房内所有人可控） ----------

    fun reportCommand(commandType: String, targetSongId: Long, progressMs: Long) {
        if (fltMode) return // FLT 房无 sync/command 体系，本地控制不上报
        val rid = roomId ?: return
        // GOTO 回声抑制：目标歌就是房间当前歌（远端刚带动本机切过/本机已上报过）时不再上报——
        // 播完竞态里迟到的一方上报 GOTO(x, 0) 会把已在 x 歌中段的房友拽回歌首
        if (commandType == "GOTO") {
            if (targetSongId == roomTargetId) return
            roomTargetId = targetSongId
        }
        // 用户意图保护窗（5s）：在 gate 之前刷新——即使本次上报被抑制窗丢弃，
        // 本地的暂停/播放/seek 事实已发生，后续远端状态回灌不许把它顶回去
        userIntentUntilMs = SystemClock.elapsedRealtime() + 5000
        if (!gate()) return
        val info = JSONObject()
            .put("commandType", commandType)
            .put("progress", progressMs.coerceAtLeast(0))
            .put("playStatus", if (PlayerEngine.isPlaying) "PLAY" else "PAUSE")
            .put("formerSongId", (PlayerEngine.current?.id ?: 0).toString())
            .put("targetSongId", targetSongId.toString())
            .put("clientSeq", nextSeq())
        scope.launch { runCatching { NcmApi.ltPlayCommand(rid, info) } }
    }

    fun reportGoto(targetSongId: Long, progressMs: Long) =
        reportCommand("GOTO", targetSongId, progressMs)

    fun reportSeek(progressMs: Long) = reportCommand("PROGRESS", PlayerEngine.current?.id ?: 0, progressMs)

    /** 队列快照上报（350ms 防抖；房内任意成员的队列变化都同步） */
    fun syncPlaylist(songs: List<Song>) {
        if (fltMode) return
        val rid = roomId ?: return
        if (!gate() || songs.isEmpty()) return
        queueReport?.cancel()
        queueReport = scope.launch {
            delay(350)
            if (!gate()) return@launch
            syncPlaylistNow(songs)
        }
    }

    /**
     * 按 **id 表**上报队列快照（与 [syncPlaylist] 同款防抖 + 门禁）。
     * 给「本地队列还没同步下来」的兜底路径用 —— 那时本地没有对应的 Song 对象，
     * 只有服务端已同步的 id 表（见 [addToRoomQueue] 的基座保护）。
     */
    private fun syncPlaylistIds(ids: List<Long>) {
        if (fltMode) return
        val rid = roomId ?: return
        if (!gate() || ids.isEmpty()) return
        queueReport?.cancel()
        queueReport = scope.launch {
            delay(350)
            if (!gate()) return@launch
            reportQueueIds(ids)
        }
    }

    private suspend fun syncPlaylistNow(songs: List<Song>) = reportQueueIds(songs.map { it.id })

    /** 队列快照上报实体（REPLACE 口径，官方 sync/list/command/report） */
    private suspend fun reportQueueIds(ids: List<Long>) {
        val rid = roomId ?: return
        val idStrs = ids.map { it.toString() }
        val param = JSONObject()
            .put("commandType", "REPLACE")
            .put("version", JSONArray().put(JSONObject().put("userId", SessionStore.uid).put("version", nextSeq())))
            .put("anchorSongId", "")
            .put("anchorPosition", -1)
            .put("randomList", JSONArray(idStrs))
            .put("displayList", JSONArray(idStrs))
        runCatching { NcmApi.ltSyncList(rid, param.toString()) }
    }

    /** 兼容旧调用点：PlayerEngine 载歌后同步队列 */
    fun syncPlaylistIfHost(songs: List<Song>) = syncPlaylist(songs)

    /** 邀请链接（MeloX/官方同款格式） */
    fun shareLink(): String? {
        val rid = roomId ?: return null
        val sid = PlayerEngine.current?.id ?: 0
        return "https://st.music.163.com/listen-together/share/?songId=$sid&roomId=$rid&inviterId=${SessionStore.uid}"
    }

    /** 邀请结果：区分「已投递进对方一起听消息箱」/「只发出去了分享链接」/「失败」 */
    enum class InviteResult { DELIVERED, LINK_ONLY, FAILED }

    /**
     * 拉好友一起听（2026-10-01 新增）：走官方**一起听邀请接口**，不是私信。
     *
     * 链路：`listen/together/invite/message/send {roomId, acceptorId, ltType}`（单发）。
     * 成功后邀请会出现在对方「一起听」页的消息箱里，对方点一下即可进房 ——
     * 这才是官方语义的「拉好友一起听」；旧实现发私信文本+链接，对方得手动复制粘贴。
     *
     * 失败时**不**静默：返回 LINK_ONLY，由 UI 提示「已复制邀请链接，可手动发给对方」，
     * 保证功能在任何房型/风控状态下都还有一条可用路径。
     */
    suspend fun invite(uid: Long): InviteResult {
        val rid = roomId ?: return InviteResult.FAILED
        if (uid <= 0L) return InviteResult.FAILED
        val code = runCatching {
            NcmApi.ltInviteFriend(rid, uid, roomType.ifEmpty { "listenTogether" })
        }.getOrDefault(-1)
        return if (code == 200) InviteResult.DELIVERED else InviteResult.LINK_ONLY
    }

    /**
     * 批量邀请（官方 `multi/invite`）：一次邀多人，成功返回已投递人数。
     * 乐迷团房带 groupIds（当前会话未解析分组时传空数组，服务端按默认分组处理）。
     */
    suspend fun inviteAll(uids: List<Long>): Int {
        val rid = roomId ?: return 0
        val list = uids.filter { it > 0L }.distinct()
        if (list.isEmpty()) return 0
        val code = runCatching { NcmApi.ltMultiInvite(rid, list) }.getOrDefault(-1)
        return if (code == 200) list.size else 0
    }

    fun end(notice: String? = null, silent: Boolean = false) {
        val rid = roomId
        val wasFlt = fltMode
        poll?.cancel(); poll = null
        queueReport?.cancel(); queueReport = null
        active = false
        if (rid != null) saveRoomSecs(rid) // 退房前把本房间累计时长落盘（须在清 roomId 之前）
        // 把进房前的播放模式还回来（房间同步期间全局模式被房间接管）
        modeBeforeJoin?.let { PlayerEngine.playMode = it }
        modeBeforeJoin = null
        if (rid != null && !silent) {
            scope.launch { runCatching { if (wasFlt) NcmApi.fltExit(rid) else NcmApi.ltEnd(rid) } }
        }
        roomId = null; creatorId = null; chatRoomId = null; members = emptyList()
        fltMode = false; fltTitle = ""; fltUsers = 0L; fltCurUsers = 0L
        fltLocalPause = false; fltSyncKick = false
        roomType = ""
        totalUsers = 0L
        curUsers = 0L
        roomListenMs = 0L
        sessionStartWallMs = 0L
        resetRoomTime()
        statusText = notice ?: ""
        connected = true
        syncedSongIds = emptyList()
        rcmdSongIds = emptyList()
        lastCommandText = null
        lastPlaylistSig = null; lastCommandSig = null
        roomTargetId = 0L
        pendingAdvanceRt = 0L
        queueSyncState = ""
        queueSyncNote = ""
    }
}
