package com.ncm.watch.ui.screens.listen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ncm.watch.data.ArtistItem
import com.ncm.watch.data.FriendInfo
import com.ncm.watch.data.ListenSession
import com.ncm.watch.data.NcmApi
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.data.SessionStore
import com.ncm.watch.data.Song
import com.ncm.watch.ui.components.CircleIconButton
import com.ncm.watch.ui.components.CoverImage
import com.ncm.watch.ui.components.DialogScrim
import com.ncm.watch.ui.components.EdgeProgressRing
import com.ncm.watch.ui.components.NcmIcons
import com.ncm.watch.ui.components.NcmToast
import com.ncm.watch.ui.components.Pressable
import com.ncm.watch.ui.components.RotatingCover
import com.ncm.watch.ui.components.StackedCard
import com.ncm.watch.ui.components.StackedCardList
import com.ncm.watch.ui.components.StackedIconCard
import com.ncm.watch.ui.components.StackedSongCard
import com.ncm.watch.ui.components.SwipeBackFader
import com.ncm.watch.ui.components.TextInputDialog
import com.ncm.watch.ui.components.circleSafeInsetPx
import com.ncm.watch.ui.components.formatMs
import com.ncm.watch.ui.components.rotaryList
import com.ncm.watch.ui.nav.Routes
import com.ncm.watch.ui.nav.NavMotionKind
import com.ncm.watch.ui.nav.NavMotionSpec
import com.ncm.watch.ui.nav.TransitionCoordinator
import com.ncm.watch.ui.nav.navigateWithMotion
import com.ncm.watch.ui.theme.Accent
import com.ncm.watch.ui.theme.Bg
import com.ncm.watch.ui.theme.BgElevated
import com.ncm.watch.ui.theme.OnAccent
import com.ncm.watch.ui.theme.SurfaceGlass
import com.ncm.watch.ui.theme.TextPrimary
import com.ncm.watch.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 一起听界面（2026-09-11 重写批次 + 反馈修订）
 *
 * 重写要点：入口页改堆叠卡；房间页与播放页同构；右滑返回/左划歌词/上滑聊天。
 *
 * 首轮真机反馈修订（5 条）：
 * 1. 恢复聊天页——上滑进入（此前被误删）。消息走私信桥接真实收发，见 ListenChatStore。
 * 2. 底部左钮（多人图标）改为进入「邀请好友」列表，点好友即发邀请；
 *    原先只复制链接，无法落到具体的人。
 * 3. 修布局重叠——三键上移并收窄（82→76dp），成员行上提到 156dp，
 *    与弧形底栏上缘（184.5dp）留出明确间距（原为 2.5dp，视觉上贴死）。
 * 4. 歌单浮层新增「添加歌曲」——从我喜欢的音乐选曲追加进房间队列。
 * 5. 歌单浮层新增「房间推荐」——来自 sync 响应的 displayList.rcmdSongIds，
 *    官方同款字段，无需额外端点。
 *
 * 2026-09-12 六项反馈批次：
 * 1. 人数走服务端 totalUserNums 口径（roomUsers 可能只回部分成员）；
 * 2. 聊天改私信桥接（真实收发，官方 App 可互见）；
 * 3. 弧形栏加音量键（四钮：邀请/歌单/音量/退出，唤出系统音量面板）；
 * 4. 成员名单显示服务端返回的全部成员 + 总人数说明；
 * 5. 成员名单显示房内累计时长（本地跟踪：他人=在房时长，自己=实际播放时长）；
 * 6. 非房主屏蔽上一首/下一首（歌单点歌同限，防切到空白）。
 *
 * 2026-09-12 一起听同步专项（真机反馈 5 项，详见 项目结构说明.md 四点十九）：
 * 1. 进度同步：人数/成员口径换 roomExt.curUserNums 实时值；房间聊天依赖成员表，同步修复；
 * 2. 封面：房间歌单详情批量拉取失败时退「只拉当前歌」，不再永远空白；
 * 3. 聊天：成员未就绪/私信全失败分文案上屏，失败撤回待确认气泡；
 * 4. 人数：curUserNums 实时口径优先（totalUserNums 偏累计）；
 * 5. 时长：按房间持久化累计（重进接着计）+ 服务端 effectiveDurationMs 房间累计展示。
 */

// ==================== 聊天（房内公共聊天优先，私信仅普通房兜底） ====================

/**
 * 一起听房间消息。
 *
 * ★ 2026-09-13 通道定性（真机 + 匿名参数矩阵双重复现）：
 * - **读**：`listen/together/multi/special/msg/history`（官方 `Lad0/v;->e`）是**唯一**对
 *   乐迷团房（`imType=SELFHOST`）可用的 HTTP 通道，返回房内公共聊天（含进房前历史、
 *   系统提示、其他用户发言）。歌房那套 `middle/im/chatroom/msg/history/query` 对该房型
 *   是网关级 400 空 body —— 旧版日志里的 `End of input at character 0 of` 就是它。
 * - **发**：官方房内发言走云信（NIM）长连接（`MainIMManager.bindIMService` +
 *   `LTChatSendPanel`），`Lad0/v` 里**只有读方法没有写方法**，HTTP 无发送端点
 *   （`multi/special/msg/send` 实测 `404 接口未找到`；`middle/im/chatroom/send` 虽为活路由
 *   但对匿名恒 `301 系统错误`）。故乐迷团房本端只做「实时收」，发言请用官方客户端。
 * - 普通房：房内通道失败后仍保留「逐个成员发私信」的桥接兜底（成员是邀请来的熟人）。
 */
object ListenChatStore {
    data class Msg(
        val text: String,
        val isMe: Boolean,
        val isSystem: Boolean = false,
        /** 服务端消息 id（0 = 本地待确认/系统提示）；房内通道无 id 时用时间戳兜底 */
        val id: Long = 0L,
        val timeMs: Long = System.currentTimeMillis(),
        /** 非己方消息的发送者昵称 */
        val senderName: String = "",
        /** 非己方消息的发送者 uid（引用回复的结构化字段用；0 = 未知） */
        val senderUid: Long = 0L,
        /** 服务端真正的 msgId（房内 `serverExt.msgId`；0 = 未下发，引用只走正文前缀） */
        val serverMsgId: Long = 0L,
        /** 引用回复：被回复者昵称（本地回显；服务端不做结构化回传，刷新后靠正文前缀体现） */
        val replyToName: String = "",
        /** 引用回复：被回复消息正文摘要 */
        val replyToText: String = "",
    )

    private const val MARK = "[一起听]"

    private val list = mutableStateListOf<Msg>()
    private val seenIds = HashSet<Long>()
    /** 历史去重键（`uid_sendTime`，两个读通道同构 → 跨通道天然去重） */
    private val seenRoomKeys = HashSet<String>()
    private var roomLastStamp = 0L              // middle/im 增量游标（最后一条 sendTime）
    /** 房内公共聊天通道连续未命中的轮数：连续 3 轮空则本轮会话不再探测（省请求） */
    private var roomHistMiss = 0
    private var roomChatDead = false

    fun messages(): List<Msg> = list
    fun clear() {
        list.clear(); seenIds.clear()
        seenRoomKeys.clear(); roomLastStamp = 0L
        roomHistMiss = 0; roomChatDead = false
        runCatching { NcmApi.resetRoomChatProbe() }
    }

    /** 系统提示：加入/退出/远端切歌等，居中灰字 */
    fun system(text: String) = list.add(Msg(text, false, true))

    /** 消息上限：只留最近 200 条，避免长时间挂机把列表撑爆（去重集合不裁，裁了会重复上屏） */
    private fun trim() {
        while (list.size > 200) list.removeAt(0)
    }

    /** 引用回复的正文前缀（跨端可见的降级形式，见 [NcmApi.RoomMsgRef] 的为何需要它） */
    private fun quotePrefix(replyTo: Msg): String {
        val snippet = replyTo.text.replace('\n', ' ').trim()
            .let { if (it.length > 24) it.take(24) + "…" else it }
        val name = replyTo.senderName.ifEmpty { "某人" }
        return if (snippet.isEmpty()) "回复 @$name" else "回复 @$name：$snippet"
    }

    /**
     * [quotePrefix] 的逆运算：把服务端回读的正文前缀还原成结构化引用。
     * 返回 (被回复者昵称, 引用摘要, 正文)；无前缀时昵称为空、正文原样返回。
     *
     * 为什么必须做：服务端不存引用字段（见上方 DTO 说明），本端发出的引用消息
     * 下一轮回读只剩纯文本 —— 不还原就会显示成「回复 @某人：xxx」一整行糊在气泡里，
     * 而不是引用块 + 正文的两段式。别人用本端 App 发的引用消息同样靠这里还原。
     */
    private fun normalizeQuote(text: String): Triple<String, String, String> {
        val nl = text.indexOf('\n')
        if (nl <= 0) return Triple("", "", text)
        val head = text.substring(0, nl)
        if (!head.startsWith("回复 @")) return Triple("", "", text)
        val rest = head.removePrefix("回复 @")
        val ci = rest.indexOf('：')
        val name = if (ci >= 0) rest.substring(0, ci) else rest
        if (name.isEmpty() || name.length > 24) return Triple("", "", text)
        val snippet = if (ci >= 0) rest.substring(ci + 1) else ""
        return Triple(name, snippet, text.substring(nl + 1))
    }

    /**
     * 发送。**只走房内公共消息通道，不再有任何私信兜底**（2026-10-01 修订）：
     * ① 房内消息通道 `middle/im/chatroom/send` / `chatroom/special/send`（官方 dex 实证路径与参数），
     *    真机已确认可达（返回业务码而非 404）；命中即成功；
     * ② 未命中时把服务端 code 如实上屏 —— 旧版普通房会「逐个成员发私信」兜底，
     *    导致房间内发言变成私聊刷屏（用户反馈「房间内发消息发到私信去了」），已整体移除。
     *
     * [replyTo] 非空 = 引用回复房内公共聊天里的某条消息：正文前置一行
     * `回复 @昵称：摘要` —— 服务端不做结构化回传，只有把引用写进正文，本端下一轮
     * 刷新回读才看得见「在回复谁」；结构化引用字段同步进请求体（双保险）。
     */
    suspend fun send(text: String, replyTo: Msg? = null): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        val quoted = replyTo?.takeIf { !it.isMe && !it.isSystem }
        val prefix = quoted?.let { quotePrefix(it) }
        val sendText = if (prefix != null) "$prefix\n$t" else t
        val echo = Msg(
            text = sendText, isMe = true,
            replyToName = quoted?.senderName.orEmpty(),
            replyToText = quoted?.text.orEmpty(),
        )
        list.add(echo)
        val rid = ListenSession.roomId

        // ① 房内消息通道（官方 dex 实证路径 + 参数结构；引用字段一并上报）
        var failCode: Int? = null
        if (rid != null) {
            val code = runCatching {
                NcmApi.ltRoomMsgSend(
                    rid, ListenSession.chatRoomId, sendText, ListenSession.roomType,
                    quoted?.let {
                        NcmApi.RoomMsgRef(
                            // 房内通道没下发 serverMsgId 时 id 存的是时间戳（>1e12），不能当 msgId 上报
                            msgId = when {
                                it.serverMsgId > 0L -> it.serverMsgId
                                it.id in 1..999_999_999_999L -> it.id
                                else -> 0L
                            },
                            uid = it.senderUid,
                            nickname = it.senderName,
                            text = it.text,
                        )
                    },
                )
            }.getOrNull()
            if (code != null && code == 200) {
                trim()
                runCatching { refreshRoomHistory() } // 服务端回执后转正，去掉待确认半透明态
                return true
            }
            failCode = code
        }

        // ② 房内通道没命中：**不再退私信**（2026-10-01 用户明确要求「非私聊」）。
        //    旧版普通房会逐个成员发私信，于是「房间内发言」变成私聊刷屏 —— 已移除。
        //    这里只做一件事：把失败原因如实上屏，方便定位（407 = 消息类型/内容被拦，
        //    -1 = 空 body 网关拒绝，null = 全部候选组合未命中）。
        list.remove(echo)
        system(
            when {
                failCode == null -> "房内消息未发出：房内通道不可达（房间可能已结束或服务端限流）"
                failCode == 407 -> "房内消息未发出：内容被服务端拦截（code 407）"
                failCode == -1 -> "房内消息未发出：服务端拒绝了请求（网关空响应）"
                else -> "房内消息未发出（code=$failCode）"
            }
        )
        return false
    }

    /**
     * 接收（普通房的私信桥接通道）：逐成员拉私信历史合并进列表（按 id 去重）。
     * includeBeforeJoin = 拉本次进房之前的历史（limit 50、不按进房时间截断）——
     * 聊天面板首次打开时用，更早的桥接消息与本次会话之间由 UI 画分隔线。
     *
     * 乐迷团房直接跳过：成员取自全站 topUsers，不是熟人 —— 不拉他们的私信历史，
     * 也不给他们发私信（骚扰 + 风控风险）。乐迷团房的聊天只走 [refreshRoomHistory]。
     */
    suspend fun refresh(includeBeforeJoin: Boolean = false) {
        if (!ListenSession.active) return
        if (ListenSession.isFltRoom) return
        val startMs = ListenSession.sessionStartWallMs
        if (startMs <= 0L) return
        val me = SessionStore.uid
        val peers = ListenSession.members.filter { it.id > 0L && it.id != me }
        if (peers.isEmpty()) return
        var added = false
        peers.forEach { m ->
            val hist = runCatching { NcmApi.msgHistory(m.id, limit = if (includeBeforeJoin) 50 else 20) }.getOrNull()
                ?: return@forEach
            hist.forEach { h ->
                if (h.id <= 0L || h.id in seenIds) return@forEach
                if (!includeBeforeJoin && h.timeMs < startMs) return@forEach
                val body = h.text.trim()
                // 歌曲卡片/图片/不支持消息不进房间聊天
                if (body.isEmpty() || h.song != null || body == "[图片]" || body == "[不支持的消息]") return@forEach
                seenIds.add(h.id)
                list.add(Msg(
                    text = body.removePrefix(MARK).trim(),
                    isMe = h.fromMe,
                    id = h.id,
                    timeMs = h.timeMs,
                    senderName = if (h.fromMe) "" else m.name,
                    senderUid = if (h.fromMe) 0L else m.id,
                    serverMsgId = h.id,
                ))
                added = true
            }
        }
        if (!added) return
        // 服务端已回执的己方消息 → 清掉同文案的本地待确认气泡
        val mineDelivered = list.filter { !it.isSystem && it.id > 0L && it.isMe }
            .map { it.text }.toHashSet()
        if (mineDelivered.isNotEmpty()) {
            list.removeAll { !it.isSystem && it.id == 0L && it.isMe && it.text in mineDelivered }
        }
        list.sortBy { it.timeMs }
    }

    /**
     * 收：**房内公共聊天**（唯一权威通道，含进房前历史、系统提示、他人发言）。
     *
     * ① `listen/together/multi/special/msg/history`（官方 `Lad0/v;->e`，主通道）——
     *    参数硬约束 size=50 / direction=0，命中即拿到最近一页公共聊天。
     * ② `middle/im/chatroom/msg/history/query`（歌房通道，仅在①无结果时作为补充）——
     *    对乐迷团/SELFHOST 房是网关级 400 空 body，NcmApi 内部连续 2 轮全空后自动判死，
     *    不再每 8s 白打 9 个请求。
     *
     * 旧版两个致命缺陷（本次修复）：
     * - `middleHit = recs.isNotEmpty() || roomLastStamp > 0L || first`：首轮 `first` 恒为 true
     *   → `middleHit` 恒真 → 下面的 multi 兜底被 `if (!middleHit)` 直接短路，**兜底从来没跑过**；
     * - 兜底里读的是 `serverMsgId`（响应里根本没有这个键，id 在 `serverExt.msgId`）
     *   → 即便跑到了也会把每条记录当 `id<=0` 丢弃。
     */
    suspend fun refreshRoomHistory() {
        val rid = ListenSession.roomId ?: return
        val me = SessionStore.uid
        var hit = false

        // ① 房内公共聊天（官方 LT multi 通道）
        if (!roomChatDead) {
            val recs = runCatching { NcmApi.ltRoomMsgHistory(rid) }.getOrDefault(emptyList())
            if (recs.isEmpty()) {
                if (++roomHistMiss >= 3) roomChatDead = true
            } else roomHistMiss = 0
            recs.forEach { m ->
                if (m.key in seenRoomKeys) return@forEach
                seenRoomKeys.add(m.key)
                val q = normalizeQuote(m.text)
                list.add(Msg(
                    text = q.third,
                    isMe = !m.isSystem && m.uid == me,
                    isSystem = m.isSystem,
                    // id 非零 = 已确认的房内消息（不参与本地待确认气泡逻辑）
                    id = if (m.id > 0L) m.id else m.timeMs,
                    timeMs = m.timeMs,
                    senderName = if (m.isSystem || m.uid == me) "" else m.nickname,
                    senderUid = if (m.isSystem) 0L else m.uid,
                    serverMsgId = m.id,
                    replyToName = q.first,
                    replyToText = q.second,
                ))
                hit = true
            }
        }

        // ② 歌房聊天室通道（补充；普通房若有发言会在这里，乐迷团房为空/已判死）
        val chatRoomId = ListenSession.chatRoomId
        if (!hit && chatRoomId != null) {
            val recs = runCatching {
                NcmApi.chatRoomHistory(chatRoomId, rid, roomLastStamp, 50, reverse = roomLastStamp == 0L)
            }.getOrDefault(emptyList())
            recs.forEach { r ->
                val ext = r.optJSONObject("serverExt")
                val uid = r.optLong("fromUserId", ext?.optLong("userId", 0L) ?: 0L)
                val t = r.optLong("sendTime", 0L)
                val text = r.optJSONObject("body")?.optString("msg").orEmpty()
                    .let { if (it == "null") "" else it.trim() }
                if (text.isEmpty() || t <= 0L) return@forEach
                val key = "${uid}_$t"
                if (key in seenRoomKeys) return@forEach
                seenRoomKeys.add(key)
                if (t > roomLastStamp) roomLastStamp = t
                val q = normalizeQuote(text)
                list.add(Msg(
                    text = q.third,
                    isMe = uid == me,
                    id = t,
                    timeMs = t,
                    senderName = ext?.optString("nickname")?.let { if (it == "null") "" else it } ?: "",
                    senderUid = uid,
                    replyToName = q.first,
                    replyToText = q.second,
                ))
                hit = true
            }
        }

        if (!hit) return
        list.sortBy { it.timeMs }
        // 走房内通道发出的消息：服务端回执后撤掉本地待确认气泡（否则同一句话会重影两条：
        // 一条半透明的 id=0 回显 + 一条服务端回读的正式消息）。
        // ⚠ 引用回复时 echo.text = "回复 @某人：摘要\n正文"，而回读已被 normalizeQuote 剥成「正文」，
        //   所以不能只比全等，还要比「正文是回声的后缀」这两种形式。
        val delivered = list.filter { !it.isSystem && it.id > 0L && it.isMe }.map { it.text }
        if (delivered.isNotEmpty()) {
            list.removeAll { m ->
                !m.isSystem && m.id == 0L && m.isMe &&
                    delivered.any { d -> d == m.text || m.text.endsWith("\n$d") || d.endsWith("\n${m.text}") }
            }
        }
        trim()
    }
}

// ==================== 入口页：堆叠卡 ====================

@Composable
fun ListenTogetherScreen(nav: NavHostController) {
    var showJoin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // 乐迷团验证：搜艺人 → 点艺人 → 查该艺人乐迷团是否正在一起听 → 有则加入
    var fltQuery by remember { mutableStateOf(TextFieldValue("")) }
    var fltArtists by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var fltSearching by remember { mutableStateOf(false) }
    // 每个艺人的验证结果：uid -> (state, info)。state: 1=验证中 2=正在一起听 3=没在听 4=接口不可达/无团
    var fanProbe by remember { mutableStateOf<Map<Long, Pair<Int, NcmApi.FanGroupListen?>>>(emptyMap()) }
    var joining by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { ListenSession.tryRestore() } }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }
    // 搜索防抖：只取艺人（乐迷团房按艺人归组）
    LaunchedEffect(fltQuery.text) {
        val kw = fltQuery.text.trim()
        if (kw.isEmpty()) { fltArtists = emptyList(); fltSearching = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        fltSearching = true
        fltArtists = runCatching { NcmApi.search(kw, limit = 10).artists }.getOrDefault(emptyList())
        fltSearching = false
    }

    /**
     * 验证该艺人是否有乐迷团房间正在一起听（只读探测，无副作用）。
     * 探测链（2026-09-13 官方包+匿名探针双实证）：artistId → fansGroupId →
     * bff/detail/get 的 fansGroupInfo.listenTogether {status, roomId, roomUserNums, joinOrpheus…}。
     * 旧方案（follow/listen/room/rcmd/list 翻页）因服务端风控 500 且 multi/room/create 有建房副作用，已弃用。
     */
    fun probeFanGroupListen(artist: ArtistItem) {
        if (fanProbe[artist.id]?.first == 1) return
        fanProbe = fanProbe + (artist.id to Pair(1, null))
        scope.launch {
            val info = runCatching { NcmApi.fanGroupListenProbe(artist.id) }.getOrNull()
            val state = when {
                info == null -> 4       // 接口不可达 / 艺人没有乐迷团
                info.listening -> 2     // 正在进行乐迷团一起听
                else -> 3               // 有乐迷团，当前没在听
            }
            fanProbe = fanProbe + (artist.id to Pair(state, info))
        }
    }

    /** 加入正在进行的乐迷团一起听：fl_ 房号走 ListenSession.join 的 FLT 分支（安卓口径 join/room） */
    fun joinFanGroupListen(info: NcmApi.FanGroupListen) {
        val rid = info.roomId ?: return
        if (joining) return
        joining = true
        scope.launch {
            ListenChatStore.clear() // 新房间从干净的消息列表开始
            val okJoin = runCatching { ListenSession.join(rid) }.getOrDefault(false)
            joining = false
            if (okJoin) nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
            else toast = ListenSession.statusText.ifEmpty { "加入失败（房间可能刚结束）" }
        }
    }

    /**
     * 开一个乐迷团一起听房（2026-10-01 新增）：官方 `listen/together/multi/room/create`，
     * 带 artistId 建房 + autoJoinUids 直接把选中的好友拉进房。
     * 与「加入别人的团房」互补 —— 团里当前没人在听时，用户可以自己开一个。
     */
    fun createFanGroupRoom(artist: ArtistItem) {
        if (joining) return
        joining = true
        scope.launch {
            ListenChatStore.clear()
            // 开房前先垫上可连续播放的队列，否则房间刚建就断流（与普通房 create 同策略）
            if (PlayerEngine.current == null) {
                // 房间初始歌单固定 5 首（随机挑选），之后每播完一首自动补一首
                val songs = runCatching { NcmApi.roomSeedSongs(ListenSession.ROOM_SEED_SIZE) }
                    .getOrDefault(emptyList())
                if (songs.isNotEmpty()) PlayerEngine.playQueue(songs, 0, PlayerEngine.StreamSource.FM)
            }
            val okCreate = runCatching { ListenSession.createFlt(artist.id) }.getOrDefault(false)
            joining = false
            if (okCreate) nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
            else toast = ListenSession.statusText.ifEmpty { "创建乐迷团房失败" }
        }
    }

    val inRoom = ListenSession.active
    val kw = fltQuery.text.trim()
    val searchingMode = kw.isNotEmpty()
    val cardCount = (if (inRoom) 1 else 0) + 2

    Box(Modifier.fillMaxSize()) {
        StackedCardList(
            title = "一起听",
            titleSubtitle = when {
                searchingMode -> "搜索乐迷团一起听"
                ListenSession.fltMode -> "乐迷团房 · ${ListenSession.fltTitle.ifEmpty { "已加入" }}"
                inRoom -> "房间 ${roomTail()}"
                else -> "和好友同步听歌"
            },
            progressTotal = if (searchingMode) (if (fltArtists.isEmpty()) 1 else fltArtists.size) else cardCount,
            horizontalInsetPx = 10,
        ) {
            // 乐迷团搜索胶囊：置顶，与功能卡同列滚动
            item(key = "flt_search") {
                BasicTextField(
                    value = fltQuery,
                    onValueChange = { fltQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(SurfaceGlass)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (fltQuery.text.isEmpty())
                                Text("搜索艺人 · 查是否正在一起听", fontSize = 10.sp, color = TextSecondary)
                            inner()
                        }
                    },
                )
            }

            if (searchingMode) {
                when {
                    fltSearching -> item(key = "flt_wait") {
                        Text("搜索中…", fontSize = 10.sp, color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                            textAlign = TextAlign.Center)
                    }
                    fltArtists.isEmpty() -> item(key = "flt_none") {
                        Text("没有找到相关艺人", fontSize = 10.sp, color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                            textAlign = TextAlign.Center)
                    }
                    else -> items(fltArtists, key = { it.id }) { a ->
                        val probe = fanProbe[a.id]
                        val state = probe?.first ?: 0
                        val info = probe?.second
                        StackedCard(onClick = {
                            when (state) {
                                2 -> info?.let { joinFanGroupListen(it) }  // 正在听 → 点击加入
                                3 -> createFanGroupRoom(a)                 // 有团没在听 → 自己开房
                                else -> probeFanGroupListen(a)             // 其余 → 验证/重试
                            }
                        }) {
                            CoverImage(a.avatarUrl, 36.dp, shape = CircleShape)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1)
                                Text(
                                    when (state) {
                                        1 -> "验证乐迷团中…"
                                        2 -> "正在乐迷团一起听" +
                                            (info?.roomUserNums?.takeIf { it > 0 }?.let { " · ${it}人" } ?: "")
                                        3 -> "有乐迷团 · 当前没有一起听"
                                        4 -> "暂不可达，点按重试"
                                        else -> "点查是否正在一起听"
                                    },
                                    fontSize = 9.sp,
                                    color = if (state == 2) Accent else TextSecondary,
                                    fontWeight = if (state == 2) FontWeight.SemiBold else null,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                when (state) {
                                    1 -> "…"
                                    2 -> "加入"
                                    3 -> "开房"
                                    else -> "查询"
                                },
                                fontSize = 9.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                if (inRoom) {
                    item(key = "room", contentType = "listen") {
                        StackedIconCard(
                            title = "回到房间",
                            subtitle = "尾号 ${roomTail()} · ${peopleText()}",
                            icon = NcmIcons.Together,
                            iconTint = Accent,
                            iconBg = Accent.copy(alpha = 0.20f),
                            onClick = {
                                nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
                            },
                        )
                    }
                }

                item(key = "create", contentType = "listen") {
                    StackedIconCard(
                        title = "创建房间",
                        subtitle = if (busy) "正在处理…" else "从「我喜欢」随机 5 首开房",
                        icon = NcmIcons.Plus,
                        onClick = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    ListenChatStore.clear() // 新房间从干净的消息列表开始
                                    // 初始歌单播种已收进 ListenSession.create()（我喜欢随机 5 首，
                                    // 收藏为空时退口味兜底）—— 这里不再预置队列，避免出现
                                    // 「UI 播一批、create 又上报另一批」的两套口径。
                                    if (ListenSession.create()) {
                                        nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
                                    } else {
                                        toast = ListenSession.statusText.ifEmpty { "创建房间失败" }
                                    }
                                    busy = false
                                }
                            }
                        },
                    )
                }

                item(key = "join", contentType = "listen") {
                    StackedIconCard(
                        title = "加入房间",
                        subtitle = "粘贴邀请链接或房号",
                        icon = NcmIcons.Qr,
                        onClick = { showJoin = true },
                    )
                }
            }
        }

        if (showJoin) {
            TextInputDialog(
                title = "加入一起听",
                placeholder = "粘贴邀请链接或房号",
                onConfirm = { code ->
                    showJoin = false
                    if (!busy) {
                        busy = true
                        scope.launch {
                            ListenChatStore.clear() // 新房间从干净的消息列表开始
                            if (ListenSession.join(code)) {
                                nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
                            }
                            else toast = ListenSession.statusText.ifEmpty { "加入失败" }
                            busy = false
                        }
                    }
                },
                onDismiss = { showJoin = false },
            )
        }

        NcmToast(toast, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun roomTail(): String {
    val id = ListenSession.roomId ?: return "—"
    return if (id.contains('_')) id.substringAfterLast('_').takeLast(4) else id.takeLast(4)
}

/**
 * 一起听各浮层（房间歌单 / 添加歌曲 / 邀请好友 / 成员名单）的底色。
 *
 * ★ 2026-10-01 主题适配：旧版写死 `Color(0xF70A0A0C)`（近黑 97%）。深色主题下没问题，
 *   但浅色主题下整页变成一块黑蒙版 —— 白色胶囊卡浮在黑底上，**搜索框上方那条固定
 *   不滚动的区域**尤其像一条突兀的黑带（用户反馈「搜索框附近的黑色固定区域去不掉」
 *   「背景黑色蒙版效果要适配浅色主题」）。
 *
 *   现在底色跟随主题色板 [Bg]（深色 #0A0A0C / 浅色 #EFEFF3），并保持 97% 不透明 ——
 *   不透明度不能降：底下的房间页（进度环 / 旋转封面 / 弧形底栏）一旦透出来，
 *   观感就是「黑边 + 裁切」（旧版 82% 的老问题）。
 */
@Composable
private fun listenOverlayBackdrop(): Color = Bg.copy(alpha = 0.97f)

@Composable
private fun peopleText(): String {
    // 口径：roomExt.curUserNums（当前实时）> totalUserNums（偏累计）> 成员表大小
    val n = ListenSession.liveUserCount()
    return if (n > 1) "$n 人" else "等待加入"
}

// ==================== 房间页 ====================

/**
 * 手势：右滑返回入口页；左划歌词；上滑聊天（聊天内下滑回房间）。
 * 房间被服务端结束 / 账号在别处进房 → 提示后自动弹回。
 */
@Composable
fun ListenRoomScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var mode by remember { mutableStateOf("room") }        // room / chat
    var showQueue by remember { mutableStateOf(false) }    // 歌单浮层
    var showInvite by remember { mutableStateOf(false) }   // 邀请好友浮层
    var showAdd by remember { mutableStateOf(false) }      // 添加歌曲浮层
    var showMembers by remember { mutableStateOf(false) }  // 房间成员名单浮层
    var askExit by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    // 右滑退出房间淡出引擎（跟手右移+非线性淡出+速度加成，无震动；仅房间模式生效，聊天右滑是页内切换）
    // 返回行程 34dp（2026-09-13 二次下调 40%：70dp → 56dp → 34dp）
    val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }
    val backFader = remember(swipePx) { SwipeBackFader(swipePx, scope) }
    // 滑出目标 = 屏宽（松手速度接续滑出用）
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    var swipeLifted by remember { mutableStateOf(false) } // 右滑期间置顶，退出时下层接场无空白帧
    val clipboard = LocalClipboardManager.current
    val song = PlayerEngine.current
    val playing = PlayerEngine.isPlaying

    LaunchedEffect(Unit) {
        var wasActive = ListenSession.active
        while (true) {
            delay(500)
            if (wasActive && !ListenSession.active) {
                toast = ListenSession.statusText.ifEmpty { "房间已结束" }
                delay(1400)
                nav.popBackStack()
                return@LaunchedEffect
            }
            wasActive = ListenSession.active
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }

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
                val px = 70.dp.toPx()
                var totalX = 0f
                var leftX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f; leftX = 0f; backFader.onDragStart(); swipeLifted = true },
                    onDragEnd = {
                        val back = mode != "chat" && backFader.passedThreshold
                        backFader.onDragEnd(back)
                        if (back) {
                            // 松手速度接续：fader 线性滑出全屏，转场时长同步武装给 NavHost
                            val ms = backFader.flingOutDuration(screenPx)
                            TransitionCoordinator.withGesturePop(ms) { nav.popBackStack() }
                            backFader.flingOut(screenPx, ms)
                        }
                        else swipeLifted = false
                        if (totalX > px) {
                            if (mode == "chat") mode = "room"
                        } else if (leftX > px && mode == "room") {
                            // 左划进歌词：滑动来源 → 保留滑动转场
                            nav.navigateWithMotion(
                                Routes.LYRICS, NavMotionSpec(NavMotionKind.Slide),
                            )
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    totalX += amount
                    if (amount < 0) leftX += -amount else leftX = 0f
                    // 淡出只在房间模式（退出房间）生效；聊天模式右滑是页内切回房间视图
                    if (mode == "room") backFader.onDrag(change, amount)
                }
            }
            .pointerInput(Unit) {
                var totalY = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalY = 0f },
                    onDragEnd = {
                        val px = 70.dp.toPx()
                        if (mode == "room" && totalY < -px) mode = "chat"
                        else if (mode == "chat" && totalY > px) mode = "room"
                    },
                ) { change, amount -> change.consume(); totalY += amount }
            },
    ) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                if (targetState == "chat") {
                    slideInVertically { it } + fadeIn() togetherWith slideOutVertically { it } + fadeOut()
                } else {
                    slideInVertically { -it } + fadeIn() togetherWith slideOutVertically { -it } + fadeOut()
                }
            },
            label = "listenMode",
        ) { m ->
            if (m == "chat") {
                ListenChatPanel(onClose = { mode = "room" })
            } else {
                RoomPanel(
                    song = song,
                    playing = playing,
                    onOpenInvite = { showInvite = true },
                    onOpenQueue = { showQueue = true },
                    onOpenMembers = { showMembers = true },
                    onVolume = {
                        // 唤出系统音量调节面板（STREAM_MUSIC，与播放页音量钮同规格）
                        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                            as android.media.AudioManager
                        am.adjustStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC,
                            android.media.AudioManager.ADJUST_SAME,
                            android.media.AudioManager.FLAG_SHOW_UI,
                        )
                    },
                    onExit = { askExit = true },
                )
            }
        }

        if (showQueue) {
            RoomQueueOverlay(
                onDismiss = { showQueue = false },
                // 切歌权归房主（与三键区同规则）：成员点歌单切歌会被服务端拒/同步拉回，明确提示
                onPick = { i ->
                    if (ListenSession.isHost) {
                        PlayerEngine.playAt(i)
                        showQueue = false
                    } else {
                        toast = "仅房主可切歌"
                    }
                },
                onAddSong = { showQueue = false; showAdd = true },
                onPickRecommend = { s ->
                    if (ListenSession.addToRoomQueue(s)) toast = "已加入房间歌单 · ${s.title}"
                    else toast = "这首歌已在房间歌单里"
                },
                // 长按房间歌单里的某一首 → 从房间歌单删除（不是取消收藏，不动收藏数据）
                onRemove = { i ->
                    val s = PlayerEngine.queue.getOrNull(i)
                    toast = if (ListenSession.removeFromRoomQueue(i)) {
                        if (s != null) "已从房间歌单删除 · ${s.title}" else "已从房间歌单删除"
                    } else {
                        "删除失败（歌单可能已变化）"
                    }
                },
            )
        }

        if (showAdd) {
            AddSongOverlay(
                // 选完/关掉都回歌单浮层（选歌是歌单的子流程，不再两个浮层叠着渲染）
                onDismiss = { showAdd = false; showQueue = true },
                onPick = { s ->
                    if (ListenSession.addToRoomQueue(s)) toast = "已加入房间歌单 · ${s.title}"
                    else toast = "这首歌已在房间歌单里"
                    showAdd = false
                    showQueue = true
                },
                // 多选批量：一次快照上报；全重复时明确提示
                onPickMany = { list ->
                    val (added, dup) = ListenSession.addMultiToRoomQueue(list)
                    toast = when {
                        added == 0 -> "所选歌曲都已在房间歌单里"
                        dup > 0 -> "已添加 $added 首（$dup 首已在歌单）"
                        else -> "已添加 $added 首到房间"
                    }
                    showAdd = false
                    showQueue = true
                },
            )
        }

        if (showMembers) {
            MemberOverlay(onDismiss = { showMembers = false })
        }

        if (showInvite) {
            InviteFriendsOverlay(
                onDismiss = { showInvite = false },
                onInvite = { f ->
                    val link = ListenSession.shareLink()
                    if (link == null) {
                        toast = "房间信息未就绪"
                        showInvite = false
                    } else {
                        // 链接始终先复制：官方邀请接口失败时它是唯一可用路径
                        clipboard.setText(AnnotatedString(link))
                        showInvite = false
                        val rid = ListenSession.roomId ?: "-"
                        scope.launch {
                            // ① 官方一起听邀请接口（非私信）：邀请落进对方「一起听」消息箱，点一下即进房
                            when (ListenSession.invite(f.id)) {
                                ListenSession.InviteResult.DELIVERED -> {
                                    ListenChatStore.system("已邀请 ${f.name} 一起听（对方在「一起听」页接受即可）")
                                    toast = "已邀请 ${f.name}"
                                }
                                // ② 邀请接口没通：退一步发私信（带房号 + 链接），并明确告知链接已复制
                                else -> {
                                    val ok = runCatching {
                                        NcmApi.msgSend(f.id, "我在 WMusic 开了个一起听，房间号 $rid，来一起听\n$link")
                                    }.getOrDefault(false)
                                    ListenChatStore.system(
                                        if (ok) "已私信邀请 ${f.name}（房号 $rid）"
                                        else "邀请未送达：房号 $rid，邀请链接已复制，可手动发给对方")
                                    toast = if (ok) "已邀请 ${f.name} · 链接已复制"
                                    else "邀请未送达 · 链接已复制"
                                }
                            }
                        }
                    }
                },
            )
        }

        if (askExit) {
            DialogScrim(onDismiss = { askExit = false }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(BgElevated)
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (ListenSession.isHost) "结束一起听？" else "退出房间？",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (ListenSession.isHost) "房间将对所有成员结束" else "退出后不再与房间同步播放",
                        fontSize = 9.sp, color = TextSecondary, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircleIconButton(NcmIcons.Close, "取消", { askExit = false }, size = 40.dp, iconSize = 17.dp)
                        CircleIconButton(
                            NcmIcons.Check, "确定",
                            {
                                askExit = false
                                ListenSession.end()
                                ListenChatStore.clear()
                                nav.popBackStack()
                            },
                            size = 40.dp, iconSize = 17.dp, container = Accent,
                        )
                    }
                }
            }
        }

        NcmToast(toast, Modifier.align(Alignment.Center))
    }
}

/**
 * 房间主面板。垂直分配按**真机实测**重排（2026-09-11 第二轮，466px@density2.0）：
 * 实测发现 CJK 字体下 9sp 文字块实占 24dp（非理论的 13dp），
 * 底部弧形三钮顶端在 187.5dp，塞不下成员行 —— 因此成员行移到顶部。
 *
 * 2026-10-01：顶部「已连接 / 房主」状态胶囊（原占 4→36dp）删除，其余组件整体上移 20px（本机 10dp）：
 *   成员行 40→30 ｜ 歌名 64→54 ｜ 艺人行 54→78 ｜ 三键 112→102 ｜ 弧形钮顶 187.5（不动）
 * 侧边进度环与底部弧形状态栏**不参与**位移。
 */
@Composable
private fun RoomPanel(
    song: Song?,
    playing: Boolean,
    onOpenInvite: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenMembers: () -> Unit,
    onVolume: () -> Unit,
    onExit: () -> Unit,
) {
  Box(Modifier.fillMaxSize()) {
    // ★ 房间页进度环只作展示、不接 seek：① 播放进度由房间同步接管，本地拖动必然被
    //   远端状态拉回，徒劳；② 该手势挂 Initial 通道且「纵向趋势接管」，会把页面级
    //   「上滑聊天」手势整个吃掉（真机反馈：进度条没屏蔽 + 聊天打不开，同根因）。
    //   onSeek = null 时环的 pointerInput 直接跳过，纵向滑动归还根布局。
    //   ★ 侧边进度环与底部弧形状态栏**不参与**下面的整体上移。
    EdgeProgressRing(
        progress = {
            val d = PlayerEngine.durationMs.coerceAtLeast(1L)
            (PlayerEngine.positionMs.toFloat() / d).coerceIn(0f, 1f)
        },
        onSeek = null,
        modifier = Modifier.fillMaxSize(),
    )

    // 2026-10-01：顶部「已连接 / 房主」状态胶囊（原 StatusPill）整体删除。
    // 其余组件（成员行 / 歌名 / 艺人行 / 三键区）统一上移 20 **物理像素**：
    // 用户口径是像素，故按 LocalDensity 换算（本机 density 2.0 → 10dp），
    // 换到别的密度设备上仍然是 20px 的视觉位移，不会随 density 缩放。
    val shift = with(androidx.compose.ui.platform.LocalDensity.current) { 20.toDp() }

    MemberRow(
        Modifier.align(Alignment.TopCenter).padding(top = 40.dp - shift),
        onClick = onOpenMembers,
    )

    Column(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 64.dp - shift),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(104.dp), contentAlignment = Alignment.Center) {
            Text(
                song?.title ?: "等待同步…",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                maxLines = 1, modifier = Modifier.basicMarquee(),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                append(song?.artist ?: "房间暂无歌曲")
                if (PlayerEngine.durationMs > 0L) {
                    append(" · ")
                    append(formatMs(PlayerEngine.positionMs))
                    append("/")
                    append(formatMs(PlayerEngine.durationMs))
                }
            },
            fontSize = 9.sp, color = TextSecondary, maxLines = 1,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }

    // 三键区：切歌权归房主（成员点了会切到空白/被同步拉回，直接屏蔽），成员只剩播/停
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 112.dp - shift),
    ) {
        if (ListenSession.isHost) {
            CircleIconButton(NcmIcons.Prev, "上一首", { PlayerEngine.prev() }, size = 38.dp, iconSize = 18.dp)
            Spacer(Modifier.width(6.dp))
        }
        RoomPlayButton(playing = playing, song = song) { PlayerEngine.toggle() }
        if (ListenSession.isHost) {
            Spacer(Modifier.width(6.dp))
            CircleIconButton(NcmIcons.Next, "下一首", { PlayerEngine.next() }, size = 38.dp, iconSize = 18.dp)
        }
    }

    ListenArcBar(
        onInvite = onOpenInvite,
        onQueue = onOpenQueue,
        onVolume = onVolume,
        onExit = onExit,
    )
  }
}

/** 成员头像行：点击进房间成员名单（含每人房内累计时长） */
@Composable
private fun MemberRow(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val members = ListenSession.members
    Row(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (members.isEmpty()) {
            Text("等待加入 · 点左下邀请", fontSize = 9.sp, color = TextSecondary)
        } else {
            members.take(4).forEach { m -> CoverImage(m.avatarUrl, 20.dp, shape = CircleShape) }
            Spacer(Modifier.width(1.dp))
            // 人数口径：curUserNums（正在房间听）> totalUserNums（累计，仅回退）> members.size
            val n = ListenSession.liveUserCount()
            Text("$n 人正在房间听 ›", fontSize = 9.sp, color = TextSecondary)
        }
    }
}

/**
 * 房间成员名单：头像 + 昵称 + 房主标记 + 房内累计时长。
 * 人数/时长口径（2026-09-12 修正）：
 * - 实时人数 = roomExt.curUserNums（官方当前在线口径，修复 totalUserNums 偏累计的问题）；
 * - 房间累计一起听 = effectiveDurationMs（服务端统计，全员一致）；
 * - 个人时长按房间持久化累计（listen_room_secs.json），重进同房间接着计；
 *   他人只能统计本端在房期间（对方更早的听时长官方无接口提供）。
 */
@Composable
private fun MemberOverlay(onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    val members = ListenSession.members
    val creator = ListenSession.creatorId
    val roomSecs = ListenSession.memberRoomSecs
    val me = SessionStore.uid
    // 服务端统计的房间累计一起听时长（effectiveDurationMs，聊天/互动解锁同一口径）
    val roomMin = ListenSession.roomListenMs / 60_000L

    fun inRoomText(uid: Long): String {
        val secs = roomSecs[uid] ?: return "刚进房"
        val min = secs / 60
        // 时长来自服务端下发（seedServerRoomTime 命中）时，标注包含进房前部分
        val serverNote = if (uid in ListenSession.serverTimeUids) "（含进房前）" else ""
        return when {
            uid == me -> if (min >= 1) "已听 $min 分钟" else "已听不足 1 分钟"
            min >= 1 -> "在房 $min 分钟$serverNote"
            else -> "在房不足 1 分钟"
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(listenOverlayBackdrop())
            .pointerInput(Unit) {
                var totalX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onDragEnd = { if (abs(totalX) > 60.dp.toPx()) onDismiss() },
                ) { change, amount -> change.consume(); totalX += amount }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
                .rotaryList(listState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = 78.dp),
            ) {
                // 总人数口径：服务端只回部分成员时注明（实时人数来自 curUserNums/totalUserNums）
                val total = ListenSession.liveUserCount()
                if (total > members.size) {
                    item(key = "total") {
                        Text(
                            "房间实时 $total 人 · 列表为服务端返回的 ${members.size} 位",
                            fontSize = 9.sp, color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                // 房间累计一起听（服务端口径，全员同步；旧版本地口径只算自己进房后的）
                if (roomMin >= 1) {
                    item(key = "room_listen") {
                        Text(
                            "本房间已一起听 $roomMin 分钟（服务端统计）",
                            fontSize = 9.sp, color = Accent,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                item(key = "hint") {
                    Text(
                        "个人时长按房间累计，重进接着计\n他人时长=本端在房期间的观测累计\n（对方更早的听时长官方服务端不提供）",
                        fontSize = 9.sp, color = TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                if (members.isEmpty()) {
                    item(key = "empty") {
                        Text("成员读取中…", fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(top = 120.dp),
                            textAlign = TextAlign.Center)
                    }
                }
                items(members, key = { it.id }) { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(30.dp))
                            .background(SurfaceGlass)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(m.avatarUrl, 30.dp, shape = CircleShape)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(inRoomText(m.id), fontSize = 9.sp, color = TextSecondary)
                        }
                        if (m.id.toString() == creator)
                            Text("房主", fontSize = 9.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        CircleIconButton(
            NcmIcons.Close, "关闭", onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            size = 36.dp, iconSize = 15.dp,
        )
    }
}

/** 大播放键：镂空圆环 + 内嵌旋转封面（68dp；房间页顶部多了状态+成员两行，比播放页 82dp 明显收小） */
@Composable
private fun RoomPlayButton(playing: Boolean, song: Song?, onClick: () -> Unit) {
    Pressable(onClick = onClick) {
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            RotatingCover(song, 52.dp, playing)
            if (!playing) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(NcmIcons.Play, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * 底部弧形操作区（带高 40dp / 中心半径 88dp / 弧段 90°±45°）。
 * 四钮（30° 间距、44dp）：左→右 邀请好友｜房间歌单｜音量｜退出。
 * 音量 = 唤出系统音量面板（STREAM_MUSIC，与播放页音量钮同规格）。
 */
@Composable
private fun ListenArcBar(
    onInvite: () -> Unit,
    onQueue: () -> Unit,
    onVolume: () -> Unit,
    onExit: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cx = maxWidth / 2
        val cyc = maxHeight / 2
        val rBand = 88.dp

        Canvas(Modifier.fillMaxSize()) {
            val stroke = 40.dp.toPx()
            val r = rBand.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 45f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset((size.width - r * 2) / 2f, (size.height - r * 2) / 2f),
                size = Size(r * 2, r * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val rInner = r - stroke / 2 - 0.75.dp.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 45f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset((size.width - rInner * 2) / 2f, (size.height - rInner * 2) / 2f),
                size = Size(rInner * 2, rInner * 2),
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        val angles = listOf(-45f, -15f, 15f, 45f)
        val icons = listOf(NcmIcons.Together, NcmIcons.Playlist, NcmIcons.Volume, NcmIcons.Close)
        val actions = listOf(onInvite, onQueue, onVolume, onExit)
        angles.forEachIndexed { i, deg ->
            val rad = Math.toRadians(deg.toDouble())
            val x = cx + rBand * sin(rad).toFloat()
            val y = cyc + rBand * cos(rad).toFloat()
            CircleIconButton(
                icons[i], "操作", actions[i],
                modifier = Modifier.offset {
                    IntOffset((x - 22.dp).roundToPx(), (y - 22.dp).roundToPx())
                },
                size = 44.dp, iconSize = 18.dp,
                container = Color.Transparent,
            )
        }
    }
}

// ==================== 聊天面板 ====================

/**
 * 房间聊天（上滑进入，下滑或右滑返回）。
 *
 * 收：**房内公共聊天**（`listen/together/multi/special/msg/history`，官方 Lad0/v 通道）——
 *     每 8s 拉一页，按 `uid_sendTime` 去重浮出；含进房前的历史与官方客户端用户的发言。
 * 发：优先房内消息通道；乐迷团房（发言走云信长连接，HTTP 无写端点）明确提示改用官方客户端；
 *     普通房回退私信桥接（成员是邀请来的熟人）。输入区沿用好友聊天页结构。
 */
/** 聊天时间分隔用的 HH:mm */
private fun hhmm(ms: Long): String {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ms
    return "%02d:%02d".format(
        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
    )
}

@Composable
private fun ListenChatPanel(onClose: () -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    // 引用回复目标（2026-10-01）：长按他人消息选中，输入区上方出引用条，发送时随消息一起上报
    var replyTarget by remember { mutableStateOf<ListenChatStore.Msg?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val messages = ListenChatStore.messages()
    val startMs = ListenSession.sessionStartWallMs
    // 进房前的历史与本次会话之间画一条分隔线（按时间戳找第一条「进房后」消息）
    val dividerIdx = messages.indexOfFirst { it.timeMs >= startMs }

    // 面板挂载期轮询（准实时）：① 房内公共聊天每 8s 拉最新一页（新消息按 uid_sendTime
    // 去重浮出，含对方在官方客户端房内发的）；② 普通房桥接私信每 16s 拉一轮。
    // 离开面板协程自动取消，不耗后台。
    LaunchedEffect(ListenSession.roomId) {
        if (!ListenSession.active) return@LaunchedEffect
        runCatching { ListenChatStore.refreshRoomHistory() }
        runCatching { ListenChatStore.refresh(includeBeforeJoin = true) }
        var tick = 0
        while (ListenSession.active) {
            delay(8_000)
            tick++
            runCatching { ListenChatStore.refreshRoomHistory() }
            if (tick % 2 == 0) runCatching { ListenChatStore.refresh() }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val r = 96.dp.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 64f, sweepAngle = 52f, useCenter = false,
                topLeft = Offset((size.width - r * 2) / 2f, (size.height - r * 2) / 2f),
                size = Size(r * 2, r * 2),
                style = Stroke(34.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        Column(Modifier.fillMaxSize()) {
            // 2026-10-01：聊天面板顶部标题整体删除（先删两行说明文案，再删「聊天显示区域」），
            // 列表直接吃满整屏，不再占版面。
            Spacer(Modifier.height(26.dp))

            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (messages.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (ListenSession.isFltRoom)
                                "还没有消息\n这里显示乐迷团房的公共聊天\n（含官方客户端用户的发言）"
                            else
                                "还没有消息\n房内公共聊天几秒内同步（含官方客户端用户）",
                            fontSize = 9.sp, color = TextSecondary, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 40.dp, vertical = 16.dp),
                        )
                    }
                }
                items(messages.size, key = { it }) { i ->
                    val msg = messages[i]
                    // 时间分隔：与上一条间隔超过 5 分钟插一个时刻，长时间挂机后不至于分不清先后
                    val prevMsg = messages.getOrNull(i - 1)
                    if (prevMsg == null || msg.timeMs - prevMsg.timeMs > 5 * 60_000L) {
                        Text(
                            hhmm(msg.timeMs), fontSize = 8.sp, color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 1.dp),
                        )
                    }
                    if (i == dividerIdx && dividerIdx > 0) {
                        Text(
                            "── 以上为进房前的房内消息 ──",
                            fontSize = 8.sp, color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        )
                    }
                    if (msg.isSystem) {
                        Text(
                            msg.text, fontSize = 9.sp, color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 3.dp),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp, horizontal = 14.dp)
                                // 长按他人消息 → 引用回复（自己/系统提示不给回复入口）
                                .pointerInput(msg.id, msg.timeMs) {
                                    if (msg.isMe || msg.isSystem) return@pointerInput
                                    detectTapGestures(onLongPress = { replyTarget = msg })
                                },
                            contentAlignment = if (msg.isMe) Alignment.CenterEnd else Alignment.CenterStart,
                        ) {
                            Column(
                                horizontalAlignment = if (msg.isMe) Alignment.End else Alignment.Start,
                            ) {
                                // 多人房发送者名字：仅换人时显示 + 6sp 紧凑行高
                                // （此前每条都带 9sp 名字行，实测 CJK 9sp 块占 24dp，太吃空间）
                                val prev = messages.getOrNull(i - 1)
                                if (!msg.isMe && msg.senderName.isNotEmpty() && prev?.senderName != msg.senderName) {
                                    Text(
                                        msg.senderName, fontSize = 6.sp, color = TextSecondary,
                                        lineHeight = 7.sp,
                                        modifier = Modifier.padding(bottom = 1.dp),
                                    )
                                }
                                // 气泡 = 可选引用块 + 正文。id=0 = 本地待确认（私信在途/失败），半透明区分
                                // ★ 2026-10-01：己方气泡底色是主题色实底，前景必须走 OnAccent 自适应 ——
                                //   主题色选白色/浅色时「白字 + 白气泡」会整体糊掉（同 FriendScreens 的修复）
                                val onBubble = if (msg.isMe) OnAccent else TextPrimary
                                Column(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (msg.isMe) Accent.copy(alpha = 0.8f) else SurfaceGlass
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .then(if (msg.id == 0L) Modifier.alpha(0.55f) else Modifier),
                                ) {
                                    if (msg.replyToName.isNotEmpty() || msg.replyToText.isNotEmpty()) {
                                        Row(
                                            Modifier.padding(bottom = 3.dp).widthIn(max = 132.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // 引用竖线（纯绘制，不用 emoji）
                                            Box(
                                                Modifier
                                                    .width(2.dp)
                                                    .height(16.dp)
                                                    .background(
                                                        onBubble.copy(alpha = 0.5f),
                                                        RoundedCornerShape(1.dp),
                                                    ),
                                            )
                                            Text(
                                                "回复 @${msg.replyToName.ifEmpty { "某人" }}" +
                                                    if (msg.replyToText.isBlank()) ""
                                                    else "：${msg.replyToText.replace('\n', ' ')}",
                                                fontSize = 6.sp, lineHeight = 8.sp,
                                                color = onBubble.copy(alpha = 0.72f),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = 4.dp),
                                            )
                                        }
                                    }
                                    Text(msg.text, fontSize = 10.sp, color = onBubble, lineHeight = 13.sp)
                                }
                            }
                        }
                    }
                }
                item(key = "pad") { Spacer(Modifier.height(74.dp)) }
            }
        }

        // 底部输入区：左「+」/ 中输入框 / 右发送（与好友聊天页同规格）
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cx = maxWidth / 2
            val cyc = maxHeight / 2
            val rB = 96.dp
            val radL = Math.toRadians((-52).toDouble())
            CircleIconButton(
                NcmIcons.Close, "返回房间", onClose,
                modifier = Modifier.offset {
                    IntOffset(
                        (cx + rB * sin(radL).toFloat() - 16.dp).roundToPx(),
                        (cyc + rB * cos(radL).toFloat() - 16.dp).roundToPx(),
                    )
                },
                size = 32.dp, iconSize = 14.dp,
            )
            val radR = Math.toRadians(52.0)
            CircleIconButton(
                NcmIcons.Send, "发送",
                {
                    val t = input.text.trim()
                    if (t.isNotEmpty()) {
                        val rt = replyTarget
                        input = TextFieldValue("")
                        replyTarget = null
                        // 真实投递：优先房内消息通道（官方 App 的房间聊天页可见），
                        // 失败再按房型回退（普通房私信桥接 / 乐迷团房明确提示，不向陌生人发私信）
                        scope.launch { ListenChatStore.send(t, rt) }
                    }
                },
                modifier = Modifier.offset {
                    IntOffset(
                        (cx + rB * sin(radR).toFloat() - 16.dp).roundToPx(),
                        (cyc + rB * cos(radR).toFloat() - 16.dp).roundToPx(),
                    )
                },
                size = 32.dp, iconSize = 14.dp, tint = Accent,
            )
            // 引用条：长按消息后浮在输入框上方，发送时随消息一起上报（× 取消引用）
            // 尺寸约束：底部左右两个圆形按钮中心在 r=96dp 圆上、距底约 58dp、水平 ±76dp，
            // 故引用条收窄到 112dp（±56dp）并抬到距底 40dp —— 两轴都避开，不与按钮压字。
            replyTarget?.let { rt ->
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp)
                        .width(112.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceGlass)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(2.dp).height(12.dp)
                            .background(Accent, RoundedCornerShape(1.dp)),
                    )
                    Text(
                        "回复 @${rt.senderName.ifEmpty { "某人" }}：" +
                            rt.text.replace('\n', ' ').take(14),
                        fontSize = 6.sp, lineHeight = 8.sp, color = TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    Text(
                        "×", fontSize = 10.sp, lineHeight = 10.sp, color = TextSecondary,
                        modifier = Modifier.padding(start = 4.dp, end = 1.dp)
                            .clickable { replyTarget = null },
                    )
                }
            }
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 7.dp)
                    .width(112.dp)
                    .clip(RoundedCornerShape(50))
                    .padding(horizontal = 4.dp, vertical = 7.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (input.text.isEmpty()) Text("说点什么…", fontSize = 10.sp, color = TextSecondary)
                        inner()
                    }
                },
            )
        }
    }
}

// ==================== 歌单浮层（含推荐 + 添加歌曲） ====================

@Composable
private fun RoomQueueOverlay(
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onAddSong: () -> Unit,
    onPickRecommend: (Song) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    // 响应式读取：PlayerEngine.queue 已是 Compose 状态（2026-09-12），远端同步/别人加歌即刻反映
    val queue = PlayerEngine.queue
    var recommend by remember { mutableStateOf<List<Song>>(emptyList()) }
    val flt = ListenSession.fltMode
    var pulling by remember { mutableStateOf(false) }
    var pullTick by remember { mutableStateOf(0) }
    // 「换一批」：官方推荐没有独立端点，换一批 = 重新拉一次 sync/playlist/get
    var rcmdTick by remember { mutableStateOf(0) }
    var rcmdBusy by remember { mutableStateOf(false) }
    // 顶部「搜索歌曲添加」：非空时列表整体切成搜索结果，清空即回房间歌单
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // 打开即主动拉一次房间歌单（旧版只被动等轮询：轮询退避/失败时歌单永远空着，
    // 用户看到的就是「加载不出来」）。重试按钮把 pullTick +1 再跑一遍。
    // ★ 2026-10-01：这里**同时**刷一次官方推荐（sync/playlist/get 的
    //   displayList.rcmdSongIds）—— 旧版只等后台轮询写 rcmdSongIds，浮层打开时
    //   推荐往往还是上一批甚至空的，用户看到的就是「推荐和官方对不上」。
    LaunchedEffect(pullTick) {
        pulling = true
        runCatching { ListenSession.pullRoomQueueNow() }
        pulling = false
        runCatching { ListenSession.refreshRecommendation() }
        recommend = runCatching { ListenSession.recommendSongs() }.getOrDefault(emptyList())
    }

    // 「换一批」：重拉官方播放列表响应，再解析其中的 rcmdSongIds
    LaunchedEffect(rcmdTick) {
        if (rcmdTick == 0) return@LaunchedEffect
        rcmdBusy = true
        runCatching { ListenSession.refreshRecommendation() }
        recommend = runCatching { ListenSession.recommendSongs() }.getOrDefault(emptyList())
        rcmdBusy = false
    }

    // 搜索防抖 350ms（与「添加歌曲」浮层同规格）；清空即回房间歌单
    LaunchedEffect(query.text) {
        val kw = query.text.trim()
        if (kw.isEmpty()) { results = emptyList(); searching = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        searching = true
        results = runCatching { NcmApi.search(kw).songs }.getOrDefault(emptyList())
        searching = false
    }
    val kw = query.text.trim()

    Box(
        Modifier
            .fillMaxSize()
            // 浮层底：97% 不透明主题底色。旧版 82% 黑透出底下房间页（状态行/旋转封面/弧形底栏
            // 全部叠印，四周是遮罩×深色背景的宽黑边），观感即「黑边 + 裁切」
            .background(listenOverlayBackdrop())
            .pointerInput(Unit) {
                var totalX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onDragEnd = { if (abs(totalX) > 60.dp.toPx()) onDismiss() },
                ) { change, amount -> change.consume(); totalX += amount }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        // 标题区已删、关闭钮悬浮：列表吃满整屏（旧版底部固定钮区是一大块永远填不满的黑）
        // ★ 2026-10-01：搜索框从「固定表头」改为**列表第一项**。
        //   固定表头会在搜索框上方永久留出一条不滚动的空带（用户反馈「搜索框上方那条
        //   黑带，去不掉」），且列表内容永远顶不到最上面。改为列表项后：无固定表头、
        //   列表可顶到最上、搜索框随列表自然滚走。
        //   圆屏安全内缩用的纵向中心只在**列表静止（回到顶部）**时采样一次；滚动中不跟随
        //   —— 跟随会让输入框宽度随位置变化而抖动，还可能形成测量回环。
        var fieldCenterY by remember { mutableStateOf(0f) }
        val fieldInsetDp = with(androidx.compose.ui.platform.LocalDensity.current) {
            circleSafeInsetPx(fieldCenterY).toDp()
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
                .rotaryList(listState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 78.dp),
            ) {
                // ★ 最上方：搜索歌曲添加（输入即搜，点结果直接进房间歌单；清空回房间歌单）
                // ★ 圆屏适配：这一条贴着屏幕最上缘，而窗口铺满 466×466 方屏、四角被圆表圈
                //   物理切掉 —— 通宽输入框的两端会被弧边吃掉（表现为两头被裁切）。
                //   这里按输入框自己的纵向中心算出该高度处圆内的可用宽度，左右内缩；
                //   内缩只改横向宽度、不改纵向位置，因此不会产生测量回环。
                item(key = "search") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { c ->
                                // 只在列表静止时采样（见上方说明）
                                val atRest = listState.firstVisibleItemIndex == 0 &&
                                    listState.firstVisibleItemScrollOffset == 0
                                if (atRest) {
                                    val cy = c.positionInRoot().y + c.size.height / 2f
                                    if (abs(cy - fieldCenterY) > 0.5f) fieldCenterY = cy
                                }
                            }
                            .padding(horizontal = fieldInsetDp),
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(50))
                                .background(SurfaceGlass)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (query.text.isEmpty())
                                        // ⚠ 必须单行：折行会让框高随宽度变化，与「按中心算内缩」
                                        //   形成自激回环（详见 AddSongOverlay 同处注释）。
                                        Text(
                                            "搜索歌曲添加到房间",
                                            fontSize = 10.sp, color = TextSecondary,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    inner()
                                }
                            },
                        )
                    }
                }
                if (kw.isNotEmpty()) {
                    // ---- 搜索结果态：点一首即加进房间歌单 ----
                    when {
                        searching -> item(key = "s_wait") {
                            Box(
                                Modifier.fillMaxWidth().height(120.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("搜索中…", fontSize = 11.sp, color = TextSecondary) }
                        }
                        results.isEmpty() -> item(key = "s_none") {
                            Box(
                                Modifier.fillMaxWidth().height(120.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("没有找到「${kw.take(8)}」", fontSize = 11.sp, color = TextSecondary) }
                        }
                        else -> itemsIndexed(results, key = { _, s -> "s${s.id}" }) { _, s ->
                            StackedSongCard(song = s, height = 52.dp, onClick = { onPickRecommend(s) })
                        }
                    }
                } else {
                    // ★ 添加歌曲已移到列表**最上方**（原在列表最底部，得一路滚到底才点得到）
                    // FLT 乐迷团房：成员只跟听无控歌权（本地加歌会被轮询拉回），隐藏入口
                    if (!flt) item(key = "add") {
                        Text(
                            "＋ 添加歌曲到房间",
                            fontSize = 11.sp, color = Accent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(50))
                                .background(Accent.copy(alpha = 0.14f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onAddSong() }
                                .padding(vertical = 10.dp),
                        )
                    }

                    // ★ 2026-10-01：官方歌单的「换一批」从「房间推荐」标题行移到这里 ——
                    //   紧贴「＋ 添加歌曲到房间」正下方（用户口径）。玻璃底 + 主文字色，
                    //   与上面那颗强调色胶囊形成主次，不会两条抢眼。
                    if (!flt) item(key = "rcmd_refresh") {
                        Text(
                            if (rcmdBusy) "正在取官方推荐…" else "换一批官方推荐",
                            fontSize = 11.sp,
                            color = if (rcmdBusy) TextSecondary else TextPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(50))
                                .background(SurfaceGlass)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { if (!rcmdBusy) rcmdTick++ }
                                .padding(vertical = 10.dp),
                        )
                    }

                    if (queue.isEmpty()) {
                        item(key = "empty") {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 40.dp, start = 26.dp, end = 26.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    when {
                                        pulling -> "歌单加载中…"
                                        ListenSession.queueSyncNote.isNotEmpty() -> ListenSession.queueSyncNote
                                        flt -> "乐迷团歌单未返回内容"
                                        else -> "歌单还是空的\n点上方「＋ 添加歌曲到房间」或搜索添加"
                                    },
                                    fontSize = 10.sp, color = TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (!pulling) {
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        "重新拉取歌单",
                                        fontSize = 11.sp, color = Accent,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Accent.copy(alpha = 0.14f))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) { pullTick++ }
                                            .padding(horizontal = 20.dp, vertical = 9.dp),
                                    )
                                }
                            }
                        }
                    }
                    // ★ 2026-10-01：key 用「下标+id」复合键。房间歌单由服务端同步，
                    //   出现重复 id（别人重复上报、旧版「下一首播放」复制）时单键会抛
                    //   IllegalArgumentException 让整个浮层崩掉；复合键恒唯一。
                    itemsIndexed(queue, key = { i, s -> "q$i-${s.id}" }) { i, s ->
                        StackedSongCard(
                            song = s,
                            isCurrent = PlayerEngine.current?.id == s.id,
                            height = 52.dp,
                            onClick = { onPick(i) },
                            // ★ 长按从**房间歌单**里删掉这一首（与「取消收藏」无关，不动收藏数据）
                            onLongClick = { onRemove(i) },
                        )
                    }

                    // ---- 房间推荐（官方 displayList.rcmdSongIds） ----
                    // 标题常显：推荐为空时也能看出是「官方这批没给推荐」，而不是像旧版那样
                    // 整块消失、无从判断。「换一批」按钮已上移到「＋ 添加歌曲到房间」下方。
                    // FLT 乐迷团房无本地歌权，推荐入口整体隐藏。
                    if (!flt) {
                        item(key = "rcmd_head") {
                            Text(
                                "房间推荐 · 官方同款推荐歌单",
                                fontSize = 9.sp, color = TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
                            )
                        }
                        if (recommend.isEmpty()) {
                            item(key = "rcmd_empty") {
                                Text(
                                    if (rcmdBusy) "正在向官方服务取推荐…"
                                    else "官方这批还没给推荐 · 点上方「换一批官方推荐」重取",
                                    fontSize = 9.sp, color = TextSecondary,
                                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
                                )
                            }
                        } else {
                            itemsIndexed(recommend, key = { i, s -> "r$i-${s.id}" }) { _, s ->
                                StackedSongCard(
                                    song = s,
                                    height = 52.dp,
                                    onClick = { onPickRecommend(s) },
                                )
                            }
                        }
                    }
                }
            }
        }
        // 悬浮关闭钮：叠在列表上方底部居中（列表 contentPadding bottom 已留出空间）
        CircleIconButton(
            NcmIcons.Close, "关闭", onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            size = 36.dp, iconSize = 15.dp,
        )
    }
}

// ==================== 添加歌曲浮层 ====================

/**
 * 从「我喜欢的音乐」或**搜索结果**选曲追加进房间歌单（房内所有人可加）。
 * 点卡片单选即加；长按任意卡片进入多选态（右侧圆形勾选角标），选完点底部确认胶囊批量添加。
 * 顶部搜索胶囊 350ms 防抖走 /cloudsearch/get/web，清空即回我喜欢的列表。
 */
@Composable
private fun AddSongOverlay(
    onDismiss: () -> Unit,
    onPick: (Song) -> Unit,
    onPickMany: (List<Song>) -> Unit,
) {
    val listState = rememberLazyListState()
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 搜索
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // 多选
    var multi by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    var loadTick by remember { mutableStateOf(0) }
    LaunchedEffect(loadTick) {
        loading = true
        songs = runCatching { NcmApi.likedSongs(SessionStore.uid) }.getOrDefault(emptyList())
        loading = false
    }
    // 搜索防抖：清空即回我喜欢的列表
    LaunchedEffect(query.text) {
        val kw = query.text.trim()
        if (kw.isEmpty()) { results = emptyList(); searching = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        searching = true
        results = runCatching { NcmApi.search(kw).songs }.getOrDefault(emptyList())
        searching = false
    }
    val kw = query.text.trim()
    val display = if (kw.isEmpty()) songs else results

    fun exitMulti() { multi = false; selected.clear() }

    Box(
        Modifier
            .fillMaxSize()
            // 浮层底：97% 不透明主题底色。旧版 82% 黑透出底下房间页（状态行/旋转封面/弧形底栏
            // 全部叠印，四周是遮罩×深色背景的宽黑边），观感即「黑边 + 裁切」
            .background(listenOverlayBackdrop())
            .pointerInput(Unit) {
                var totalX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onDragEnd = { if (abs(totalX) > 60.dp.toPx()) onDismiss() },
                ) { change, amount -> change.consume(); totalX += amount }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        // 标题区已删、关闭钮悬浮：列表吃满整屏（与歌单浮层同规格）
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
                .rotaryList(listState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            // 搜索胶囊：搜索结果与我喜欢的共用下方列表
            // ★ 2026-10-01 圆屏适配：这一条贴在屏幕最上缘，通宽输入框的两端会被弧边吃掉
            //   （真机表现是「搜索框两头被裁平」）。按它自己的纵向中心算出该高度处圆内的
            //   可用宽度，左右内缩；只改横向、不改纵向，不会产生测量回环。
            //   （与歌单浮层同一套做法，此前只有歌单浮层做了、这一层漏了。）
            //
            // ⚠ 硬约束：**框内文字必须恒为单行**（占位文案 maxLines=1）。
            //   否则会自激：宽度变窄 → 占位文案折行 → 框变高 → 中心下移 → 内缩变小 →
            //   宽度变宽 → 折行消失 → 框变矮 → 中心上移 → 内缩变大 …… 每帧来回，
            //   真机表现就是「点开添加歌曲后整页疯狂抽动」。
            var fieldCenterY by remember { mutableStateOf(0f) }
            val fieldInsetDp = with(androidx.compose.ui.platform.LocalDensity.current) {
                circleSafeInsetPx(fieldCenterY).toDp()
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { c ->
                        val cy = c.positionInRoot().y + c.size.height / 2f
                        if (abs(cy - fieldCenterY) > 0.5f) fieldCenterY = cy
                    }
                    .padding(horizontal = fieldInsetDp),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(SurfaceGlass)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (query.text.isEmpty())
                                // 文案压到 9 字以内：内缩后可用宽度约 98dp，再长就会被省略号截断
                                Text(
                                    "搜索歌曲 / 长按多选",
                                    fontSize = 10.sp, color = TextSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            inner()
                        }
                    },
                )
            }
            Spacer(Modifier.height(6.dp))

            when {
                loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("加载中…", fontSize = 11.sp, color = TextSecondary)
                }
                searching -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("搜索中…", fontSize = 11.sp, color = TextSecondary)
                }
                display.isEmpty() -> Column(
                    Modifier.weight(1f).padding(horizontal = 26.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        when {
                            kw.isNotEmpty() -> "没有找到「${kw.take(8)}」"
                            SessionStore.uid <= 0L -> "未登录，读不到「我喜欢的音乐」\n可直接用上方搜索添加"
                            else -> "「我喜欢的音乐」为空\n可用上方搜索添加"
                        },
                        fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center,
                    )
                    if (kw.isEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "重新加载",
                            fontSize = 11.sp, color = Accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Accent.copy(alpha = 0.14f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { loadTick++ }
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                        )
                    }
                }
                else -> LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 78.dp),
                ) {
                    itemsIndexed(display, key = { _, s -> s.id }) { _, s ->
                        StackedSongCard(
                            song = s,
                            height = 52.dp,
                            selectMode = multi,
                            selected = s.id in selected,
                            onLongClick = {
                                if (!multi) { multi = true }
                                if (s.id !in selected) selected.add(s.id)
                            },
                            onClick = {
                                if (multi) {
                                    if (s.id in selected) selected.remove(s.id) else selected.add(s.id)
                                } else onPick(s)
                            },
                        )
                    }
                }
            }
        }
        // 底部悬浮钮：多选态 = 确认胶囊（0 首时即「取消多选」）；单选态 = 关闭钮
        if (multi) {
            Text(
                if (selected.isEmpty()) "取消多选" else "添加 ${selected.size} 首到房间",
                fontSize = 11.sp, color = if (selected.isEmpty()) TextPrimary else Accent,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceGlass)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (selected.isEmpty()) { exitMulti(); return@clickable }
                        val picked = display.filter { it.id in selected }
                        exitMulti()
                        onPickMany(picked)
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        } else {
            CircleIconButton(
                NcmIcons.Close, "关闭", onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                size = 36.dp, iconSize = 15.dp,
            )
        }
    }
}

// ==================== 邀请好友浮层 ====================

/** 点底部「多人」钮进入：好友列表，点谁就邀请谁 */
@Composable
private fun InviteFriendsOverlay(
    onDismiss: () -> Unit,
    onInvite: (FriendInfo) -> Unit,
) {
    val listState = rememberLazyListState()
    var friends by remember { mutableStateOf<List<FriendInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        friends = runCatching { NcmApi.friends(SessionStore.uid) }.getOrDefault(emptyList())
        loading = false
    }

    Box(
        Modifier
            .fillMaxSize()
            // 浮层底：97% 不透明主题底色。旧版 82% 黑透出底下房间页（状态行/旋转封面/弧形底栏
            // 全部叠印，四周是遮罩×深色背景的宽黑边），观感即「黑边 + 裁切」
            .background(listenOverlayBackdrop())
            .pointerInput(Unit) {
                var totalX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onDragEnd = { if (abs(totalX) > 60.dp.toPx()) onDismiss() },
                ) { change, amount -> change.consume(); totalX += amount }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        // 标题区已删、关闭钮悬浮：列表吃满整屏（与歌单浮层同规格）
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
                .rotaryList(listState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("加载好友…", fontSize = 11.sp, color = TextSecondary)
                }
            } else if (friends.isEmpty()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有关注的人\n在网易云关注对方后即可邀请",
                        fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 78.dp),
                ) {
                    items(friends, key = { it.id }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(30.dp))
                                .background(SurfaceGlass)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onInvite(f) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(f.avatarUrl, 30.dp, shape = CircleShape)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                f.name, fontSize = 12.sp, color = TextPrimary,
                                maxLines = 1, modifier = Modifier.weight(1f),
                            )
                            Text("邀请", fontSize = 9.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        CircleIconButton(
            NcmIcons.Close, "关闭", onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            size = 36.dp, iconSize = 15.dp,
        )
    }
}

/*
 * 关于聊天（2026-09-13 通道定性终稿，匿名参数矩阵 + 真机双重复现；2026-10-01 补 chatRoomId 兜底）：
 * - **读**：`listen/together/multi/special/msg/history`（官方 Lad0/v;->e）是一起听房
 *   （含乐迷团 SELFHOST 房）唯一可用的 HTTP 公共聊天通道；参数硬约束 size=50 / direction=0，
 *   其它组合一律 code 301。歌房那套 middle/im/chatroom/msg/history/query 对乐迷团房是
 *   网关级 400 空 body（即旧日志的 `End of input at character 0 of`），已作降级补充 + 自动判死。
 * - **发**：`middle/im/chatroom/send` 与 `middle/im/chatroom/special/send` 是官方 dex 里的
 *   活路由（匿名 400 空 body = 网关拒绝而非 404），本端按「路径 × 口径 × chatroomId 变体」
 *   三维探测。2026-10-01 补了 chatRoomId 兜底解析（NcmApi.resolveChatRoomId，走中间层
 *   `middle/im/token-and-chatroom-addr/get`）——此前 status 恢复会话或服务端本拍没下发
 *   `roomExt.chatRoomId` 时该字段为 null，房内发送/接收整条通道都不启动。
 *   ⚠ 官方客户端房内发言最终走云信（NIM）长连接，HTTP 通道能否落库取决于房型与登录态；
 *   未命中时不静默：乐迷团房明确提示，普通房退私信桥接（正文带 `[一起听]` 前缀）。
 * - **邀请**：`listen/together/invite/message/send {roomId, acceptorId, ltType}`（单发）与
 *   `listen/together/multi/invite {roomId, groupIds, inviteUids}`（批量）是官方邀请通道，
 *   邀请落进对方「一起听」消息箱（可点接受），比私信分享链接可靠（见 ListenSession.invite）。
 * - 历史：面板首次打开即拉到进房前的最近一页（50 条）房内消息，分隔线区分。
 */
