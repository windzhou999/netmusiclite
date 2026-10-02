package com.ncm.watch.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 网易云接口层。加密/路径均来自 ncm_decompile 已验证规格。
 * weapi: https://music.163.com/weapi/<去/api路径>
 * eapi:  https://interface.music.163.com/eapi/<去/api路径>
 */
object NcmApi {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    private const val WE = "https://music.163.com/weapi"
    private const val EA = "https://interface.music.163.com/eapi"
    private const val CL = "https://clientlog.music.163.com/eapi"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        // 网易 eapi CDN 对 HTTP/2 返回空响应体，强制 HTTP/1.1（已用 curl 矩阵验证）
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()

    private var lastError: String? = null
    private var lastSetCookie: String? = null
    fun error(): String? = lastError
    private const val DEBUG_LOG = false // release 置 false，减少请求路径上的日志 I/O

    // ---------- 底层请求 ----------
    private fun form(map: Map<String, String>): String =
        map.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

    private const val UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun post(
        url: String,
        body: Map<String, String>,
        pcLogin: Boolean = false,
        cookieOs: String? = null,
        extraCookie: String? = null,
    ): JSONObject =
        try {
            val b = Request.Builder().url(url)
                .header("User-Agent", if (pcLogin) UA_PC else UA)
                .header("Referer", "https://music.163.com")
                .header("Origin", "https://music.163.com")
                .apply {
                    // 登录接口必须带 os=pc 客户端标识，否则触发 8821 风控
                    val cookie = buildString {
                        when {
                            cookieOs != null -> append("os=$cookieOs; ") // 听歌打卡/发送歌曲按客户端口径
                            pcLogin -> append("os=pc; appver=9.5.70; ")
                        }
                        SessionStore.musicU?.let { append("MUSIC_U=$it") }
                        // 写操作（点赞/评论）服务端会校验 __csrf，登录回包已落盘
                        SessionStore.csrf?.let { append("; __csrf=$it") }
                        extraCookie?.let { append("; $it") }
                    }.trimEnd(' ', ';')
                    if (cookie.isNotEmpty()) header("Cookie", cookie)
                }
                .post(form(body).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            http.newCall(b).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                lastSetCookie = resp.headers("Set-Cookie").joinToString("; ")
                // `__csrf`（写接口的 checkToken）会随**任意** music.163.com 响应重新下发，
                // 不必只在登录时抓：见到就落盘。这样「登录后才加上 csrf 抓取」的老会话
                // 能在下一次请求后自愈，用户不用被逼着重新扫码登录。
                lastSetCookie?.let { sc ->
                    csrfFrom(sc)?.takeIf { it != SessionStore.csrf }?.let { SessionStore.csrf = it }
                }
                if (DEBUG_LOG) android.util.Log.d("NcmApi", "$url -> HTTP ${resp.code} ${text.take(220)} SC=${lastSetCookie?.take(120)}")
                JSONObject(text)
            }
        } catch (e: Exception) {
            lastError = e.message
            android.util.Log.w("NcmApi", "$url EXC ${e.message}")
            JSONObject().put("code", -1).put("msg", e.message ?: "network")
        }

    private fun we(path: String, data: JSONObject, pcLogin: Boolean = false): JSONObject =
        post(WE + path, NcmCrypto.weapi(data), pcLogin)
    private fun ea(path: String, data: JSONObject): JSONObject = post(EA + path.removePrefix("/api"), NcmCrypto.eapi(path, data), true)

    /**
     * 写接口（点赞 / 取消赞 / 评论）专用的 weapi 口径。
     *
     * 与只读的 [we] 差别只在**带上 `os=pc; appver=9.5.70` 客户端标识**：
     * 服务端对写操作除了校验 `__csrf`（checkToken），还要认客户端形态，
     * 裸 MUSIC_U 会被当匿名拒绝（登录接口同样必须带 os=pc，否则 8821 风控）。
     */
    private fun weWrite(path: String, data: JSONObject): JSONObject =
        post(WE + path, NcmCrypto.weapi(data), pcLogin = true)

    /** FLT 专用 eapi：官方安卓 App cookie 口径（os/appver/deviceId），对设备风控 */
    private fun eaFlt(path: String, data: JSONObject): JSONObject =
        post(EA + path.removePrefix("/api"), NcmCrypto.eapi(path, data), true,
            cookieOs = "android",
            extraCookie = "appver=9.5.90; deviceId=$fltDeviceId; _ntes_nuid=$fltDeviceId")
    /** 听歌打卡专用：clientlog.music.163.com + eapi，cookie 按 osx 客户端口径 */
    private fun eac(path: String, data: JSONObject): JSONObject =
        post(CL + path.removePrefix("/api"), NcmCrypto.eapi(path, data), true, cookieOs = "osx")

    /**
     * 只读 GET（老接口 `/api/xxx` 支持明文 GET，匿名即可用）。
     *
     * 只给**别名反查**用：`/api/search/get/web` 这条老搜索接口匿名可用、响应里直接带
     * `transNames`（译名）与 `alias`（别名），所以本地音乐做跨语言索引时**不要求用户登录**。
     * 现行的 `/cloudsearch/get/web` 走 weapi POST，匿名回空 body，不适合这条用途。
     */
    private fun getJson(url: String): JSONObject = try {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(b).execute().use { resp -> JSONObject(resp.body?.string() ?: "{}") }
    } catch (e: Exception) {
        android.util.Log.w("NcmApi", "GET $url EXC ${e.message}")
        JSONObject().put("code", -1).put("msg", e.message ?: "network")
    }

    /** 别名/译名字段名：cloudsearch 用 tns/alia，老 search 接口用 transNames/alias，四个都收 */
    private val ALIAS_KEYS = arrayOf("tns", "alia", "transNames", "alias")

    private fun parseAliases(o: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        for (key in ALIAS_KEYS) {
            val arr = o.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotEmpty()) out.add(v)
            }
        }
        return out.toList()
    }

    private fun ok(j: JSONObject): Boolean = j.optInt("code", 0) == 200

    // ---------- 解析器 ----------
    fun parseSong(o: JSONObject): Song = Song(
        id = o.optLong("id"),
        title = o.optString("name"),
        artist = o.optJSONArray("ar")?.let { ar -> (0 until ar.length()).joinToString("/") { ar.getJSONObject(it).optString("name") } }
            ?: o.optJSONArray("artists")?.let { ar -> (0 until ar.length()).joinToString("/") { ar.getJSONObject(it).optString("name") } }
            ?: o.optString("artistName"),
        artistId = o.optJSONArray("ar")?.optJSONObject(0)?.optLong("id")
            ?: o.optJSONArray("artists")?.optJSONObject(0)?.optLong("id")
            ?: o.optLong("artistId"),
        album = o.optJSONObject("al")?.optString("name")
            ?: o.optJSONObject("album")?.optString("name")
            ?: o.optString("albumName"),
        albumId = o.optJSONObject("al")?.optLong("id")
            ?: o.optJSONObject("album")?.optLong("id")
            ?: o.optLong("albumId"),
        durationMs = o.optInt("dt", o.optInt("duration", 0)),
        coverUrl = o.optJSONObject("al")?.optString("picUrl")?.takeIf { it.isNotEmpty() }
            ?: o.optJSONObject("album")?.optString("picUrl")?.takeIf { it.isNotEmpty() }
            ?: o.optString("picUrl").takeIf { it.isNotEmpty() },
        // 别名/译名（跨语言搜索的数据来源），四个字段名都收，见 ALIAS_KEYS
        aliases = parseAliases(o),
    )

    private fun songs(arr: JSONArray?): List<Song> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val raw = arr.optJSONObject(i) ?: return@mapNotNull null
            // 推荐流类接口的元素结构不统一：可能是裸 song，也可能包在 songInfo / song 里
            val o = raw.optJSONObject("songInfo") ?: raw.optJSONObject("song") ?: raw
            // 滤掉解析残缺项(id<=0)：这类空歌进队列后既播不了也会干扰去重
            runCatching { parseSong(o) }.getOrNull()?.takeIf { it.id > 0 }
        }
    }

    // ---------- 扫码登录 ----------
    suspend fun qrKey(): String? = withContext(Dispatchers.IO) {
        val j = we("/login/qrcode/unikey", JSONObject().put("type", 1), pcLogin = true)
        j.optString("unikey").takeIf { it.isNotEmpty() }
            ?: j.optJSONObject("data")?.optString("unikey")?.takeIf { it.isNotEmpty() }
    }

    /** 返回 code: 800过期 / 801等待 / 802已扫 / 803成功(cookie) */
    data class QrPoll(val code: Int, val cookie: String?)

    suspend fun qrPoll(key: String): QrPoll = withContext(Dispatchers.IO) {
        val j = we("/login/qrcode/client/login", JSONObject().put("key", key).put("type", 1), pcLogin = true)
        // 803 的 MUSIC_U 通常在 Set-Cookie 响应头而非 body；
        // 且 __csrf（写操作 checkToken）多数只在头里 → 两处合并后再解析
        val bodyCookie = j.optString("cookie").takeIf { it.isNotEmpty() }
        val headerCookie = lastSetCookie?.takeIf { it.contains("MUSIC_U=") }
        val merged = listOfNotNull(bodyCookie, headerCookie).joinToString("; ").ifEmpty { null }
        QrPoll(j.optInt("code"), merged)
    }

    fun cookieFrom(raw: String): String? =
        Regex("MUSIC_U=([A-Za-z0-9]+)").find(raw)?.groupValues?.get(1)

    /** 从登录回包的原始 cookie 串里抠出 __csrf（写操作的 checkToken 来源于此） */
    fun csrfFrom(raw: String): String? =
        Regex("__csrf=([^;\\s]+)").find(raw)?.groupValues?.get(1)

    /**
     * 写操作的 `checkToken`。
     * 官方 dex 实证：点赞（`NeteaseMusicApiImpl;->T`）与发评论（`CommentApiUtil;->b/f`）都带
     * `checkToken`，取值 `Lqz0/b;->G0()`（安全 SDK 的 securityGetToken）。web/eapi 线与之等价
     * 的是登录下发的 `__csrf`；拿不到时退化为空串。
     */
    private fun checkToken(): String = SessionStore.csrf ?: ""

    // ---------- 听歌打卡（收听时长统计，对齐官方客户端）----------
    /**
     * 上报真实收听秒数。旧 /api/feedback/weblog/scrobble 端点已废弃（上报静默无效）。
     * 对齐 2026 社区实证规格：eapi /api/feedback/weblog ——
     * ① startplay 进「最近播放」（一次收听会话只发一条，[withStart] 控制）；
     * ② play(end=playend) 涨「听歌排行」次数与时长（**计次只看这条**）。
     * host 用 clientlog.music.163.com，cookie 走 os=osx（mac 客户端口径）。
     *
     * @param withStart 本会话是否补发 startplay。旧版每次结算都发 → 一首歌循环/长暂停
     *   场景「最近播放」时间被反复刷新；现改为会话首结算发一次（PlayerEngine.startPending）。
     * @return playend 是否上报成功（计次口径；startplay 失败只影响最近播放，不影响返回值）
     */
    suspend fun scrobble(songId: Long, timeSec: Int, withStart: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
        if (songId <= 0 || timeSec <= 0) return@withContext false
        if (SessionStore.musicU.isNullOrEmpty()) return@withContext false // 未登录无云端统计
        val src = songId.toString() // 无歌单上下文时以歌曲 id 兜底来源
        val base = JSONObject()
            .put("id", songId)
            .put("type", "song")
            .put("mainsite", "1")
            .put("mainsiteWeb", "1")
            .put("content", "id=$src")
        val play = JSONObject().put(
            "json",
            JSONObject(base.toString())
                .put("download", 0)
                .put("end", "playend")
                .put("sourceId", src)
                .put("time", timeSec)
                .put("wifi", 0)
                .put("source", "list"),
        ).put("action", "play")
        var okStart = true
        if (withStart) {
            val r1 = eac("/api/feedback/weblog", JSONObject().put("logs", "[${JSONObject().put("action", "startplay").put("json", base)}]"))
            okStart = ok(r1)
        }
        val r2 = eac("/api/feedback/weblog", JSONObject().put("logs", "[$play]"))
        val success = ok(r2)
        // 低频结算（一段会话一条），日志开销可忽略；失败时 body 前 160 字符辅助定位
        if (success && okStart) android.util.Log.i("NcmApi", "scrobble($songId,${timeSec}s) ok")
        else android.util.Log.w("NcmApi", "scrobble($songId,${timeSec}s) start=$okStart play=${r2.optInt("code")}/${r2.toString().take(160)}")
        // ★ 计次虚增诊断（2026-09-13）：客户端已实锤每会话只发一条 play 日志，
        //   上报后立刻回读服务端 allData 里这首歌的 playCount —— 若单条日志后
        //   count 跳涨十几，就是服务端对我们日志形状的口径问题；若 +1，则虚增来自
        //   客户端之外（其他设备/一起听房间侧计费），据此分诊
        if (success && SessionStore.uid > 0L) runCatching {
            val rec = we("/play/record", JSONObject().put("uid", SessionStore.uid)
                .put("type", 0).put("csrf_token", ""))
            val arr = rec.optJSONArray("allData")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optJSONObject("song")?.optLong("id") == songId) {
                    android.util.Log.i("NcmApi",
                        "recordAfter song=$songId playCount=${o.optInt("playCount")}")
                    break
                }
            }
        }
        success
    }

    // ---------- 乐迷团一起听（FLT，follow/listen/*，2026 社区逆向规格见 apk_analysis/listen-together-for-ai.md） ----------

    /** FLT 设备指纹（room/rcmd/list、room/exit 等接口要求），稳定串即可 */
    private val fltDeviceId =
        "wmusic" + (android.os.Build.FINGERPRINT.hashCode().toLong() and 0xffffffffL)

    data class FltRoom(
        val roomId: String,
        val roomTitle: String,
        val artistId: Long,
        val artistName: String,
        val users: Long,
    )

    /**
     * 推荐跟听房列表（分页）：乐迷团房的发现入口。响应结构未完全确诊 —— 防御性深度遍历，
     * 收集所有「有 roomId 且带 artistInfo / roomId 以 fl_ 开头」的对象；
     * LTDiag 打原始响应（真机一轮即可确诊字段并精调）。
     */
    suspend fun fltRoomList(page: Int): List<FltRoom> = withContext(Dispatchers.IO) {
        // 首轮实测（2026-09-12 真机）：eapi 500（端点对，参数缺）/ weapi 404（通道错）。
        // 二轮：FLT 系接口普遍要求 clientDeviceId（设备指纹），补三种带设备参数的组合
        val combos: List<Triple<String, String, JSONObject>> = listOf(
            Triple("ea-dev", "ea", JSONObject().put("page", page).put("clientDeviceId", fltDeviceId)),
            Triple("ea-full-dev", "ea", JSONObject()
                .put("page", page).put("playingSongId", 0L)
                .put("sessionId", fltDeviceId).put("songSource", "")
                .put("clientDeviceId", fltDeviceId)),
            Triple("ea-sess", "ea", JSONObject().put("page", page).put("sessionId", fltDeviceId)),
            Triple("ea-min", "ea", JSONObject().put("page", page)),
            Triple("we-min", "we", JSONObject().put("page", page)),
        )
        var j: JSONObject? = null
        for ((tag, ch, data) in combos) {
            j = when (ch) {
                "we" -> runCatching { we("/api/follow/listen/room/rcmd/list", data) }.getOrNull()
                else -> runCatching { ea("/api/follow/listen/room/rcmd/list", data) }.getOrNull()
            }
            ltdiag("fltRcmd[$tag] p$page", j)
            if (j != null && ok(j)) break
            j = null
        }
        if (j == null) return@withContext emptyList()
        val out = ArrayList<FltRoom>()
        fun walk(o: JSONObject?) {
            o ?: return
            val rid = o.optString("roomId", "")
            val ai = o.optJSONObject("artistInfo")
            if (rid.isNotEmpty() && (ai != null || rid.startsWith("fl_"))) {
                out.add(FltRoom(
                    roomId = rid,
                    roomTitle = o.optString("roomTitle").ifEmpty { ai?.optString("artistName").orEmpty() },
                    artistId = ai?.optLong("artistId", 0L) ?: ai?.optLong("id", 0L) ?: o.optLong("artistId", 0L),
                    artistName = ai?.optString("artistName").orEmpty().ifEmpty { ai?.optString("name").orEmpty() },
                    users = o.optLong("totalUserNums", 0L),
                ))
            }
            for (k in o.keys()) {
                when (val v = o.opt(k)) {
                    is JSONObject -> walk(v)
                    is JSONArray -> for (i in 0 until v.length()) walk(v.optJSONObject(i))
                    else -> {}
                }
            }
        }
        walk(j)
        out.distinctBy { it.roomId }
    }

    /**
     * 加入乐迷团跟听房（follow/listen/join/room）。返回响应原文，成功 = code 200；
     * 房信息（roomInfo/artistInfo）由调用方按需提取。
     */
    suspend fun fltJoin(roomId: String): JSONObject? = withContext(Dispatchers.IO) {
        // 口径探测：base（裸 MUSIC_U）→ android（官方 App cookie 口径）。
        // 2026-09-12 实测 base = 400「当前设备存在异常」—— 服务端按设备风控，安卓口径是第二轮尝试
        val j = runCatching {
            ea("/api/follow/listen/join/room", JSONObject().put("roomId", roomId).put("refer", ""))
        }.getOrNull()
        ltdiag("fltJoin[base]", j)
        if (j != null && ok(j)) return@withContext j
        val j2 = runCatching {
            eaFlt("/api/follow/listen/join/room", JSONObject().put("roomId", roomId).put("refer", ""))
        }.getOrNull()
        ltdiag("fltJoin[android]", j2)
        if (j2 != null && ok(j2)) j2 else j ?: j2
    }

    /** FLT 房间心跳（房内维持用，≈5s 一次；不打日志避免刷屏），失败返回 null */
    suspend fun fltHeartbeat(roomId: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            eaFlt("/api/follow/listen/room/heartbeat",
                JSONObject().put("roomId", roomId).put("bizUniqueId", fltDeviceId))
        }.getOrNull()
    }

    /**
     * FLT 房全部播放列表（主态同步）。2026-09-12 真机确诊结构：
     * data.playingSong{songId,songName,artistNames[],picUrl,playing,progress(秒),timestamp(服务端ms)}
     * + data.songList[]（队列，同结构）。join/room 的安卓口径实测可用，同口径调用。
     */
    suspend fun fltPlaylistAll(roomId: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            eaFlt("/api/follow/listen/room/playlist/getAll", JSONObject().put("roomId", roomId))
        }.getOrNull()
    }

    /** 退出 FLT 房（与普通房 ltEnd 不同端点），同安卓口径 */
    suspend fun fltExit(roomId: String): JSONObject? = withContext(Dispatchers.IO) {
        val j = runCatching {
            eaFlt("/api/follow/listen/room/exit", JSONObject()
                .put("roomId", roomId)
                .put("uniqueId", fltDeviceId)
                .put("listenSongNums", 0)
                .put("topSongBizIds", "")
                .put("clientDeviceId", fltDeviceId))
        }.getOrNull()
        ltdiag("fltExit", j)
        j
    }

    /**
     * 乐迷团房第二入口探测：multi/room/create 带 artistId（文档 5.1#1，官方艺人房参数标 ★）。
     * rcmd/list 实测需官方会话上下文（五种组合全 500），改从艺人建房/匹配入口进。
     * ⚠ 这是创建/匹配动作：艺人房不存在时可能开一个新多人房（artistInfo 为空可判别）。
     */
    suspend fun fltCreateWithArtist(artistId: Long): JSONObject? = withContext(Dispatchers.IO) {
        val j = runCatching {
            ea("/api/listen/together/multi/room/create", JSONObject()
                .put("type", "MULTI_MATCH_SONG")
                .put("songId", 0L)
                .put("artistId", artistId)
                .put("from", "CREATE")
                .put("playedTime", 0L))
        }.getOrNull()
        ltdiag("fltCreate artist=$artistId", j)
        j
    }

    /** FLT 在房状态（roomStatus/roomInfo/topUsers —— 成员列表的定期刷新源），安卓口径 */
    suspend fun fltStatusGet(): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            eaFlt("/api/follow/listen/status/get", JSONObject().put("clientDeviceId", fltDeviceId))
        }.getOrNull()
    }

    // ---------- 乐迷团「正在一起听」验证（2026-09-13 官方 rn-fansgroup 包 + 匿名探针双实证） ----------

    /**
     * 探测结果：官方乐迷团页「发现正在一起听」的数据源就是
     * `/api/social/fansgroup/bff/detail/get` 回包里的 `data.fansGroupInfo.listenTogether`：
     * `{status, roomId, icon, roomUserNums, joinOrpheus, inRoom, creator, creatorProfile,
     *   identity, artistId, members, roomType}`（roomType="follow" 即 FLT 跟听体系）。
     */
    data class FanGroupListen(
        val groupId: String,
        val groupName: String,
        val artistId: Long,
        /** status != 0 且 roomId 非空 = 正在进行乐迷团一起听 */
        val listening: Boolean,
        val status: Int,
        val roomId: String?,
        val roomUserNums: Long,
        val roomType: String,
        val creatorId: Long,
        /** liveDTO.liveStatus：艺人直播间状态（旁路信息，1 = 有直播） */
        val liveStatus: Int,
    )

    /**
     * 验证某艺人当前是否有乐迷团房间正在一起听。两步链路均匿名实测 200：
     * 1) `/api/community/artist/fans/info {artistId}` → `data.archiveActionUrl` 抠 groupId（复用 fansGroupIdOf）；
     * 2) `/api/social/fansgroup/bff/detail/get {groupId, scene:""}` → `data.fansGroupInfo.listenTogether`。
     * 纯只读探测：不触发建房/匹配（旧 fltCreateWithArtist 在无房时会真的开一个新多人房，有副作用）。
     * 返回 null = groupId 拿不到（艺人无乐迷团）或接口失败；listening=false = 有乐迷团但当前没在听。
     */
    suspend fun fanGroupListenProbe(artistId: Long): FanGroupListen? = withContext(Dispatchers.IO) {
        val gid = fansGroupIdOf(artistId) ?: return@withContext null
        val j = runCatching {
            ea("/api/social/fansgroup/bff/detail/get",
                JSONObject().put("groupId", gid).put("scene", ""))
        }.getOrNull()
        ltdiag("fgListen artist=$artistId gid=$gid", j)
        if (j == null || !ok(j)) return@withContext null
        val info = j.optJSONObject("data")?.optJSONObject("fansGroupInfo") ?: return@withContext null
        val lt = info.optJSONObject("listenTogether") ?: JSONObject()
        val rid = lt.optString("roomId", "").ifEmpty { null }
        FanGroupListen(
            groupId = gid,
            groupName = info.optString("fansGroupName").ifEmpty { "乐迷团" },
            artistId = lt.optLong("artistId", 0L).takeIf { it > 0L } ?: artistId,
            listening = lt.optInt("status", 0) != 0 && rid != null,
            status = lt.optInt("status", 0),
            roomId = rid,
            roomUserNums = lt.optLong("roomUserNums", 0L),
            roomType = lt.optString("roomType", "follow"),
            creatorId = lt.optLong("creator", 0L),
            liveStatus = info.optJSONObject("liveDTO")?.optInt("liveStatus", 0) ?: 0,
        )
    }

    /** 从 join/room 或 status/get 的响应里提取乐迷团房描述（防御性 walk） */
    fun fltRoomFrom(j: JSONObject?): FltRoom? {
        j ?: return null
        var hit: FltRoom? = null
        fun walk(o: JSONObject?) {
            o ?: return
            val rid = o.optString("roomId", "")
            val ai = o.optJSONObject("artistInfo")
            if (hit == null && rid.isNotEmpty() && (ai != null || rid.startsWith("fl_"))) {
                hit = FltRoom(
                    roomId = rid,
                    roomTitle = o.optString("roomTitle").ifEmpty { ai?.optString("artistName").orEmpty() },
                    artistId = ai?.optLong("artistId", 0L) ?: ai?.optLong("id", 0L) ?: 0L,
                    artistName = ai?.optString("artistName").orEmpty().ifEmpty { ai?.optString("name").orEmpty() },
                    users = o.optLong("totalUserNums", 0L),
                )
            }
            for (k in o.keys()) {
                when (val v = o.opt(k)) {
                    is JSONObject -> walk(v)
                    is JSONArray -> for (i in 0 until v.length()) walk(v.optJSONObject(i))
                    else -> {}
                }
            }
        }
        walk(j)
        return hit
    }

    // ---------- 账号 ----------
    suspend fun account(): JSONObject? = withContext(Dispatchers.IO) {
        val j = ea("/api/nuser/account/get", JSONObject())
        if (ok(j)) j.optJSONObject("profile") else null
    }

    /**
     * 登录态 uid 自愈：扫码登录时 account() 恰好失败 / 多账号档位缺 id，uid 会以 0 落盘——
     * 之后 /play/record 的云端听歌数据全部静默为空，歌曲百科只能退显「本机播放」、
     * 排行页空白，看起来就是「次数不和云端账号同步」。在用 uid 的读路径上补抓一次
     * 并落盘（顺带补全昵称头像），恢复后所有云端个性化数据自动接上。
     * 返回可用 uid；未登录或补抓失败返回 0（幂等，uid 已在时零开销）。
     */
    suspend fun ensureUid(): Long {
        SessionStore.uid.takeIf { it > 0L }?.let { return it }
        if (SessionStore.musicU.isNullOrEmpty()) return 0L
        val p = runCatching { account() }.getOrNull() ?: return 0L
        val id = p.optLong("userId")
        if (id > 0L) {
            SessionStore.uid = id
            SessionStore.nickname = p.optString("nickname")
            SessionStore.avatarUrl = p.optString("avatarUrl")
            SessionStore.vipType = p.optInt("vipType")
        }
        return id
    }

    /** VIP 到期时间(ms)，拿不到返回 null */
    suspend fun vipExpire(uid: Long): Long? = withContext(Dispatchers.IO) {
        val j = we("/music-vip-membership/client/vip/info", JSONObject().put("userId", uid))
        if (!ok(j)) return@withContext null
        val d = j.optJSONObject("data") ?: return@withContext null
        val t = d.optLong("expireTime", 0L).takeIf { it > 0 }
            ?: d.optJSONObject("musicPackage")?.optLong("expireTime", 0L)?.takeIf { it > 0 }
        t
    }

    // ---------- 搜索 ----------
    data class SearchResult(val songs: List<Song>, val artists: List<ArtistItem>, val albums: List<AlbumItem>)

    suspend fun search(kw: String, limit: Int = 20): SearchResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("s", kw).put("limit", limit).put("offset", 0)
            .put("type", 1).put("csrf_token", "").put("hlpretag", "").put("hlposttag", "")
        val songsJ = we("/cloudsearch/get/web", body)
        val songList = songsJ.optJSONObject("result")?.optJSONArray("songs").let { songs(it) }
        val artistsJ = we("/cloudsearch/get/web", JSONObject(body.toString()).put("type", 100))
        val artists = artistsJ.optJSONObject("result")?.optJSONArray("artists")?.let { arr ->
            (0 until arr.length()).mapNotNull { o ->
                (arr.optJSONObject(o) ?: return@mapNotNull null).let {
                    ArtistItem(it.optLong("id"), it.optString("name"), it.optString("img1v1Url").ifEmpty { it.optString("picUrl") })
                }
            }
        } ?: emptyList()
        val albumsJ = we("/cloudsearch/get/web", JSONObject(body.toString()).put("type", 10))
        val albums = albumsJ.optJSONObject("result")?.optJSONArray("albums")?.let { arr ->
            (0 until arr.length()).mapNotNull { o ->
                (arr.optJSONObject(o) ?: return@mapNotNull null).let {
                    AlbumItem(it.optLong("id"), it.optString("name"), it.optString("picUrl"),
                        it.optJSONObject("artist")?.optString("name") ?: "", it.optLong("publishTime"), it.optInt("size"))
                }
            }
        } ?: emptyList()
        SearchResult(songList, artists, albums)
    }

    // ---------- 歌曲元数据 / 播放 / 歌词 ----------
    /** 分片并行拉取（80 个/片），结果严格保持传入 ids 顺序；单片段失败只丢该片段 */
    suspend fun songDetail(ids: List<Long>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        coroutineScope {
            ids.chunked(80).map { chunk ->
                async {
                    val cArr = JSONArray()
                    chunk.forEach { cArr.put(JSONObject().put("id", it)) }
                    val j = runCatching {
                        we("/v3/song/detail", JSONObject().put("c", cArr.toString()).put("ids", JSONArray(chunk).toString()))
                    }.getOrNull()
                    // 防御：服务端不保证返回顺序=请求顺序，按 id 归位，保持调用方给定的顺序
                    val byId = songs(j?.optJSONArray("songs")).associateBy { it.id }
                    chunk.mapNotNull { byId[it] }
                }
            }.flatMap { it.await() }
        }
    }

    /** 歌曲的多艺人列表（ar 数组）：供播放页点艺人名弹胶囊选择；网络失败返回空表 */
    suspend fun songArtists(id: Long): List<ArtistItem> = withContext(Dispatchers.IO) {
        val j = runCatching {
            we(
                "/v3/song/detail",
                JSONObject().put("c", JSONArray().put(JSONObject().put("id", id)).toString())
                    .put("ids", "[$id]"),
            )
        }.getOrNull()
        val ar = j?.optJSONArray("songs")?.optJSONObject(0)?.optJSONArray("ar")
            ?: return@withContext emptyList()
        (0 until ar.length()).mapNotNull { i ->
            ar.optJSONObject(i)?.let { o ->
                val aid = o.optLong("id", 0L)
                val name = o.optString("name", "").trim()
                if (aid > 0 && name.isNotEmpty()) ArtistItem(aid, name, null) else null
            }
        }
    }

    suspend fun songUrl(id: Long, level: String = "standard"): String? = withContext(Dispatchers.IO) {
        val j = ea("/api/song/enhance/player/url/v1",
            JSONObject().put("ids", "[$id]").put("level", level).put("encodeType", "aac"))
        if (!ok(j)) return@withContext null
        val d = j.optJSONArray("data")?.optJSONObject(0) ?: return@withContext null
        d.optString("url").takeIf { it.isNotEmpty() }
    }

    data class DownloadUrlResult(val url: String?, val reason: String? = null)

    /**
     * 下载直链。⚠ 此接口 data 是【对象】不是数组（player/url/v1 才是数组），
     * 且直链字段名是 url（无 downloadUrl 字段）——2026-08-29 用匿名请求实测确认。
     */
    suspend fun downloadUrl(id: Long, level: String = "standard"): DownloadUrlResult = withContext(Dispatchers.IO) {
        val j = ea("/api/song/enhance/download/url/v1",
            JSONObject().put("id", id).put("level", level).put("encodeType", "aac"))
        if (!ok(j)) return@withContext DownloadUrlResult(null, "接口失败(${j.optInt("code")})")
        val d = j.optJSONObject("data") ?: return@withContext DownloadUrlResult(null, "响应异常")
        val url = d.optString("url").takeIf { it.isNotEmpty() }
        val reason = when {
            url != null -> null
            d.optInt("code") == -110 -> "该歌曲需要 VIP"
            d.optInt("code") == -105 -> "登录态失效，请重新扫码"
            d.optInt("fee") in 1..4 -> "无版权或需要 VIP"
            else -> "无版权(${d.optInt("code")})"
        }
        DownloadUrlResult(url, reason)
    }

    /** 歌词：返回 (原文lrc, 翻译tlyric)，翻译可能为 null */
    data class LyricBundle(val lrc: String?, val trans: String?, val roma: String?)

    /** 歌词一次请求带三路：lrc 原文 / tlyric 翻译 / romalrc 罗马音 */
    suspend fun lyricV1(id: Long): LyricBundle = withContext(Dispatchers.IO) {
        val j = ea("/api/song/lyric/v1",
            JSONObject().put("id", id).put("cp", "false").put("tv", "0").put("lv", "0")
                .put("rv", "0").put("kv", "0"))
        if (!ok(j)) return@withContext LyricBundle(null, null, null)
        LyricBundle(
            j.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotEmpty() },
            j.optJSONObject("tlyric")?.optString("lyric")?.takeIf { it.isNotEmpty() },
            j.optJSONObject("romalrc")?.optString("lyric")?.takeIf { it.isNotEmpty() },
        )
    }

    suspend fun lyric(id: Long): Pair<String?, String?> =
        lyricV1(id).let { it.lrc to it.trans }

    /** 用户资料（他人主页）：/v1/user/detail/{uid} */
    suspend fun userDetail(uid: Long): UserDetail? = withContext(Dispatchers.IO) {
        val j = runCatching { we("/v1/user/detail/$uid", JSONObject().put("uid", uid)) }.getOrNull()
            ?: return@withContext null
        if (!ok(j)) return@withContext null
        val p = j.optJSONObject("profile") ?: return@withContext null
        UserDetail(
            nickname = p.optString("nickname").ifEmpty { "用户 $uid" },
            avatarUrl = p.optString("avatarUrl").takeIf { it.isNotEmpty() },
            listenSongs = j.optLong("listenSongs", 0),
            level = j.optInt("level", 0),
        )
    }

    // ---------- 红心 ----------
    suspend fun likeSong(id: Long, like: Boolean): Boolean = withContext(Dispatchers.IO) {
        val b = JSONObject().put("trackId", id).put("like", like).put("time", "0")
        // eapi 摘要路径带 /api、weapi 不带（见 likeEvent 注释）
        ok(eaFlt("/api/song/like", b)) || ok(we("/song/like", b))
    }

    suspend fun likeList(uid: Long): List<Long> = withContext(Dispatchers.IO) {
        val j = we("/song/like/get", JSONObject().put("uid", uid).put("limit", 1000).put("offset", 0))
        if (!ok(j)) return@withContext emptyList()
        val arr = j.optJSONArray("ids") ?: return@withContext emptyList()
        (0 until arr.length()).map { arr.optLong(it) }
    }

    /**
     * 我喜欢（收藏时间顺序，最新收藏在最前）。
     * ⚠ /song/like/get 返回的 ids 顺序与收藏时间无关（真机表现为乱序），不能用于列表排序；
     * 收藏时间序 = 「xxx喜欢的音乐」歌单（用户歌单首项）的 trackIds 顺序（官方我喜欢的展示序）。
     * v6/playlist/detail 传 n=0 只取 trackIds（不拉 tracks），元数据走 songDetail 分片补齐。
     */
    suspend fun likedSongs(uid: Long): List<Song> = withContext(Dispatchers.IO) {
        val pid = runCatching {
            val j = we("/user/playlist",
                JSONObject().put("uid", uid).put("limit", 1).put("offset", 0).put("includeVideo", true))
            j.optJSONArray("playlist")?.optJSONObject(0)?.optLong("id") ?: 0L
        }.getOrDefault(0L)
        if (pid > 0L) {
            val j = runCatching {
                we("/v6/playlist/detail", JSONObject().put("id", pid).put("n", "0").put("s", "0"))
            }.getOrNull()
            val ids = j?.optJSONObject("playlist")?.optJSONArray("trackIds")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optLong("id") }
            } ?: emptyList()
            if (ids.isNotEmpty()) {
                val list = songDetail(ids)
                if (list.isNotEmpty()) return@withContext list
            }
        }
        songDetail(likeList(uid)) // 兜底：歌单链路失败时退回旧接口（顺序可能非收藏时间）
    }


    /**
     * 查询扩展（跨语言搜索的关键一步）：
     * 把用户输入的词丢给服务端搜一次，取回命中歌曲的「歌名 + 艺人」当作**等价关键词**。
     *
     * 意义在于：本地音乐库 / 收藏里存的往往是外文原名（「夜に駆ける」），
     * 用户只输得出中文译名（「向夜晚奔去」）。服务端的搜索索引里两边的写法都能命中，
     * 于是**一次请求**就能把中文词"翻译"成库里认得的写法 —— 比逐首反查便宜得多，
     * 也是本地库跨语言搜索的主路（逐首反查只是它没命中时的兜底，见 [SongAliasStore]）。
     */
    suspend fun expandQueryKeys(query: String, limit: Int = 8): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://music.163.com/api/search/get/web?s=${
            URLEncoder.encode(query, "UTF-8")
        }&type=1&offset=0&limit=$limit"
        val arr = runCatching { getJson(url).optJSONObject("result")?.optJSONArray("songs") }
            .getOrNull() ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = s.optString("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            val artist = s.optJSONArray("artists")?.optJSONObject(0)?.optString("name")?.trim().orEmpty()
            if (artist.isEmpty()) name else "$name $artist"
        }
    }

    /**
     * 别名反查（本地音乐跨语言搜索用）。
     *
     * 手表上的本地文件没有网易 id，拿不到 `tns`/`alia`，只能拿「歌名 + 艺人」反查一次。
     * 走老接口 `GET /api/search/get/web`——**匿名可用**（实测），所以没登录也能建索引；
     * 返回 (歌名, 别名列表) 列表，调用方按歌名相似度挑最匹配的一条。
     */
    suspend fun aliasCandidates(keyword: String, limit: Int = 5): List<Pair<String, List<String>>> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()
            val url = "https://music.163.com/api/search/get/web?s=${
                URLEncoder.encode(keyword, "UTF-8")
            }&type=1&offset=0&limit=$limit"
            val arr = runCatching { getJson(url).optJSONObject("result")?.optJSONArray("songs") }
                .getOrNull() ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = s.optString("name").trim()
                if (name.isEmpty()) null else name to parseAliases(s)
            }
        }

    // ---------- 听歌排行 ----------
    /**
     * 播放排行。weapi /v1/play/record：type=1 本周（weekData）/ 0 所有时间（allData），
     * 每项含 playCount（次数）+ score（热度 0-100）+ 内嵌 song（allData 另有 firstPlayTime）。
     * 2026-09-13 官方 9.5.90 dex 实证：听歌排行页走 `/v1/play/record?uid=`（GET 形态，weapi POST 等价），
     * 与旧 /play/record 数据同源（服务端打卡统计），这里 v1 优先、旧路径回退。
     * 注意 allData 为 top1000 榜：榜外的歌官方也没有累计次数（勿用本地计数顶替）。
     */
    suspend fun userRecord(uid: Long, type: Int): List<PlayRecord> = withContext(Dispatchers.IO) {
        // uid 缺失（登录态但 uid=0）先自愈补抓，否则云端次数永远为空 → 百科退显本地计数
        val u = ensureUid().takeIf { it > 0L } ?: uid
        if (u <= 0L) return@withContext emptyList()
        val body = JSONObject().put("uid", u).put("type", type).put("csrf_token", "")
        val v1 = we("/v1/play/record", body)
        val j = if (ok(v1) && (v1.has("allData") || v1.has("weekData"))) v1 else we("/play/record", body)
        if (!ok(j)) return@withContext emptyList()
        val arr = j.optJSONArray(if (type == 1) "weekData" else "allData")
            ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val s = o.optJSONObject("song") ?: return@mapNotNull null
            runCatching {
                PlayRecord(parseSong(s), o.optInt("playCount"), o.optInt("score"),
                    o.optLong("firstPlayTime", 0L).takeIf { it > 0 })
            }.getOrNull()
        }
    }

    // ---------- 歌曲百科 ----------
    /**
     * 百科页区块（官方 RN 百科页的**唯一**数据源）。
     *
     * 2026-10-01 逆向官方 9.5.90 实证：
     *  · `POST /api/song/play/about/block/page {"songId":…}` → `data.blocks[]`（匿名可通，实测 HTTP 200）；
     *  · 每个 block 的 `showType` 决定渲染哪个 RN 组件，映射来自加密包 `rn-music-correlation`
     *    （0.60 / hermes=false 的明文 JS，从 `assets/default_custom_config_*.json` 的
     *    `rnBundle#releaseListUrl` 拉 releaselist 后取 `fullUrl` 下载）：
     *      `MUSIC_MEMORY_MULTI_TWO_GRID`   → 「回忆坐标」= 第一次听 + 累计听过
     *      `SONG_PLAY_ABOUT_TAB_SONG_BASIC`→ 「音乐百科」= 曲风 / 推荐标签 / 语种 / BPM / 获奖 / 影视 / 乐谱
     *      `LIST_SONG`                     → 「相似歌曲」
     *      `PLAYLIST_MULTI_THREE_GRID`     → 「相关歌单」
     *  · 旧代码用的 `/api/song/wiki/summary` 实测 **404 接口不存在**（曲风/制作人员因此一直为空），已弃用。
     */
    suspend fun songPlayAboutBlocks(songId: Long): JSONArray? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("songId", songId)
            var j = ea("/api/song/play/about/block/page", body)
            // 「回忆坐标」是**账号级**数据，匿名请求服务端会照常返回 200 但把 creatives 给成空数组。
            // 因此登录态下若这一块仍为空，多半是 cookie 的 os 口径没被接受（本机 ea() 带的是 os=pc），
            // 换官方安卓口径（os=android + deviceId，与官方 App 完全一致）再试一次；
            // 未登录就不做这次重试，省掉一次 40KB+ 的无谓请求。
            if (ok(j) && SessionStore.uid > 0L && !memoryHasData(j)) {
                val j2 = eaFlt("/api/song/play/about/block/page", body)
                if (ok(j2) && memoryHasData(j2)) j = j2
            }
            if (!ok(j)) null else j.optJSONObject("data")?.optJSONArray("blocks")
        }.getOrNull()
    }

    /** 响应里「回忆坐标」区块是否真的带回了账号数据（creatives 为空 = 未登录或该账号没听过） */
    private fun memoryHasData(resp: JSONObject): Boolean {
        val mem = blocksOfType(resp.optJSONObject("data")?.optJSONArray("blocks"), "MUSIC_MEMORY_MULTI_TWO_GRID")
            ?: return false
        val creatives = mem.optJSONArray("creatives") ?: return false
        for (i in 0 until creatives.length()) {
            if ((creatives.optJSONObject(i)?.optJSONArray("resources")?.length() ?: 0) > 0) return true
        }
        return false
    }

    /** 按 `showType` 取区块 */
    private fun blocksOfType(blocks: JSONArray?, showType: String): JSONObject? {
        if (blocks == null) return null
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            if (b.optString("showType") == showType) return b
        }
        return null
    }

    /**
     * 在区块的 `creatives[].resources[]` 里按 `resourceType` 取资源。
     * 回忆坐标的两张卡（`FIRST_LISTEN` / `TOTAL_PLAY`）就挂在这层，
     * 业务数据全在 `resource.resourceExt` 的两个 DTO 里。
     */
    private fun blockResource(block: JSONObject?, resourceType: String): JSONObject? {
        val creatives = block?.optJSONArray("creatives") ?: return null
        for (i in 0 until creatives.length()) {
            val res = creatives.optJSONObject(i)?.optJSONArray("resources") ?: continue
            for (k in 0 until res.length()) {
                val r = res.optJSONObject(k) ?: continue
                if (r.optString("resourceType") == resourceType) return r
            }
        }
        return null
    }

    /** `resource.uiElement.mainTitle.title`（曲风 / 推荐标签 / 获奖成就都在这） */
    private fun resourceTitle(resource: JSONObject?): String? =
        resource?.optJSONObject("uiElement")?.optJSONObject("mainTitle")
            ?.optString("title")?.trim()?.takeIf { it.isNotEmpty() }

    /** 区块标题（`block.uiElement.mainTitle.title`） */
    private fun blockTitle(block: JSONObject?): String? =
        block?.optJSONObject("uiElement")?.optJSONObject("mainTitle")
            ?.optString("title")?.trim()?.takeIf { it.isNotEmpty() }

    /** 某 `creativeType` 下所有资源的标题 */
    private fun creativeTitles(block: JSONObject?, creativeType: String): List<String> {
        val creatives = block?.optJSONArray("creatives") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until creatives.length()) {
            val c = creatives.optJSONObject(i) ?: continue
            if (c.optString("creativeType") != creativeType) continue
            val res = c.optJSONArray("resources") ?: continue
            for (k in 0 until res.length()) resourceTitle(res.optJSONObject(k))?.let { out += it }
        }
        return out
    }

    private fun strOrNull(o: JSONObject?, key: String): String? =
        o?.optString(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /**
     * 百科聚合。数据源：
     *  · 第一次听 / 累计听过 → `block/page` 的「回忆坐标」区块（**官方百科页同源**，需登录+有收听记录）
     *  · 曲风 / 推荐标签 / 获奖成就 → `block/page` 的「音乐百科」区块
     *  · 发行时间 → `/v1/album/{id}.publishTime`
     *  · 相似歌 → `/v1/discovery/simiSong`
     *  · 兜底（回忆坐标为空时）→ `/v1/play/record` allData（top1000 榜）
     */
    suspend fun songBaike(song: Song, uid: Long): SongBaikeData = withContext(Dispatchers.IO) {
        coroutineScope {
            val blocksD = async { songPlayAboutBlocks(song.id) }
            val recD = async {
                runCatching { userRecord(uid, 0) }.getOrDefault(emptyList())
                    .firstOrNull { it.song.id == song.id }
            }
            val albumD = async {
                runCatching {
                    if (song.albumId <= 0) null
                    else we("/v1/album/${song.albumId}", JSONObject())
                        .optJSONObject("album")?.optLong("publishTime", 0L)?.takeIf { it > 0 }
                }.getOrNull()
            }
            val simiD = async {
                runCatching {
                    val j = we("/v1/discovery/simiSong", JSONObject().put("songid", song.id).put("limit", 20))
                    songs(j.optJSONArray("songs"))
                }.getOrDefault(emptyList())
            }

            val blocks = blocksD.await()
            val basic = blocksOfType(blocks, "SONG_PLAY_ABOUT_TAB_SONG_BASIC")
            val memory = blocksOfType(blocks, "MUSIC_MEMORY_MULTI_TWO_GRID")
            val rec = recD.await()

            // ---- 回忆坐标：官方 music-first-listen 组件的取值路径，逐字段照搬 ----
            //   FIRST_LISTEN → resourceExt.musicFirstListenDto
            //   TOTAL_PLAY   → resourceExt.musicTotalPlayDto
            //   musicMemoryTextType = 1 表示服务端已排好文案（subTitle/desc），直接用；
            //   否则按旧版字段本地拼（第一次听用 season+period、累计听用 playCount+"次"）。
            val firstExt = blockResource(memory, "FIRST_LISTEN")?.optJSONObject("resourceExt")
            val totalExt = blockResource(memory, "TOTAL_PLAY")?.optJSONObject("resourceExt")
            val firstDto = firstExt?.optJSONObject("musicFirstListenDto")
            val totalDto = totalExt?.optJSONObject("musicTotalPlayDto")

            val listenCount = totalDto?.takeIf { it.has("playCount") }?.optInt("playCount")?.takeIf { it >= 0 }
            // 新版（type=1）附注在 desc、旧版在 text；两者官方都原样展示，这里原样带出
            val listenHint = if (totalExt?.optInt("musicMemoryTextType", 0) == 1) {
                strOrNull(totalDto, "desc")
            } else {
                strOrNull(totalDto, "text")
            }

            val firstWhen: String? = if (firstExt?.optInt("musicMemoryTextType", 0) == 1) {
                strOrNull(firstDto, "subTitle")
            } else {
                val season = strOrNull(firstDto, "season") ?: "未知"
                strOrNull(firstDto, "period")?.let { season + "的" + it }
            }
            val firstDate = if (firstExt?.optInt("musicMemoryTextType", 0) == 1) {
                strOrNull(firstDto, "desc")
            } else {
                strOrNull(firstDto, "date")
            }

            SongBaikeData(
                firstPlayMs = rec?.firstPlayMs,
                playCount = rec?.playCount,
                tags = creativeTitles(basic, "songTag"),
                crew = emptyList(),
                publishTime = albumD.await(),
                similar = simiD.await(),
                memoryTitle = blockTitle(memory),
                listenCount = listenCount,
                listenHint = listenHint,
                firstListenWhen = firstWhen,
                firstListenDate = firstDate,
                bizTags = creativeTitles(basic, "songBizTag"),
                awards = creativeTitles(basic, "songAward"),
            )
        }
    }

    // ---------- 云村评论 ----------
    /**
     * 歌曲评论：threadId = `R_SO_4_{songId}`，其余口径见 [commentsByThread]。
     */
    suspend fun comments(songId: Long, offset: Int, limit: Int = 20): Pair<List<CommentItem>, List<CommentItem>>? =
        if (songId <= 0L) null else commentsByThread("R_SO_4_$songId", offset, limit)

    private fun formatCommentTime(ms: Long): String {
        if (ms <= 0L) return ""
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(ms))
        }.getOrDefault("")
    }

    // ---------- 热搜榜 ----------
    /**
     * 搜索热词。2026-09-04 实测（weapi 加密探针）：
     * /hotsearchlist/get 是唯一存活路径（顶层 data 数组，searchWord/score/iconUrl）；
     * /search/hot/detail 与 /api/search/hot/detail 均返回 404「接口未找到」。
     */
    suspend fun searchHot(): List<HotSearch> = withContext(Dispatchers.IO) {
        val j = we("/hotsearchlist/get", JSONObject())
        val arr = j.optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val w = o.optString("searchWord").trim()
            if (w.isEmpty()) null else HotSearch(w, o.optInt("score"), o.optString("iconUrl").takeIf { it.isNotEmpty() })
        }
    }

    // ---------- 推荐 ----------
    suspend fun dailyRecommend(): List<Song> = withContext(Dispatchers.IO) {
        val j = we("/v3/discovery/recommend/songs", JSONObject().put("csrf_token", ""))
        songs(j.optJSONObject("data")?.optJSONArray("dailySongs"))
    }

    /**
     * 个人 FM 流（/v1/radio/get）。★ 逐批推流接口：每次只回一小批（实测匿名 1 首、登录约 3 首），
     * 服务端按调用次数推进，**不能拉一次当整个播放队列用**（续取见 PlayerEngine.extendStream）。
     */
    suspend fun personalFM(): List<Song> = withContext(Dispatchers.IO) {
        // weapi 偶发空响应体，用 eapi 兜底（该端点 eapi 同样有效，老格式解析已兼容）
        val j = runCatching { we("/v1/radio/get", JSONObject()) }.getOrNull()
            ?.takeIf { (it.optJSONArray("data")?.length() ?: 0) > 0 }
            ?: runCatching { ea("/api/v1/radio/get", JSONObject()) }.getOrNull()
            ?: return@withContext emptyList()
        val arr = j.optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching { parseSong(o.optJSONObject("mainSong") ?: o) }.getOrNull()?.takeIf { it.id > 0 }
        }
    }

    /** 起播用：连拉多批凑够起始队列（接口每批仅几首），按 id 去重保序 */
    suspend fun fmBatch(rounds: Int = 3): List<Song> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<Long, Song>()
        repeat(rounds.coerceIn(1, 6)) { personalFM().forEach { s -> out.putIfAbsent(s.id, s) } }
        android.util.Log.i("RoamDiag", "fmBatch rounds=$rounds -> ${out.size}")
        out.values.toList()
    }

    /**
     * 房间初始歌单（2026-10-01）：随机挑选 [count] 首起播。
     *
     * 以个人 FM 流为主 —— 它本身就是「按收听喜好的随机流」，最贴合房间开场的选歌语义；
     * 凑不满 [count] 首时用每日推荐补齐（FM 每批只回 1~3 首，单靠它可能不够 5 首）。
     * 之后每播完一首由 [com.ncm.watch.data.ListenSession.refillRoomQueueAsync] 补一首，
     * 所以这里只负责把**开场**的队列铺够。
     */
    suspend fun roomSeedSongs(count: Int = 5): List<Song> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<Long, Song>()
        runCatching { fmBatch(3) }.getOrDefault(emptyList())
            .forEach { if (it.id != 0L) out.putIfAbsent(it.id, it) }
        if (out.size < count) {
            runCatching { dailyRecommend() }.getOrDefault(emptyList())
                .forEach { if (it.id != 0L) out.putIfAbsent(it.id, it) }
        }
        out.values.take(count.coerceAtLeast(1))
    }

    /** 心动模式（/playmode/intelligence/list，失败退旧 /playmode/song/list），以歌曲为种子生成推荐流 */
    suspend fun heartModeList(seedSongId: Long, count: Int = 30): List<Song> = withContext(Dispatchers.IO) {
        val main = runCatching {
            we("/playmode/intelligence/list", JSONObject()
                .put("songId", seedSongId)
                .put("type", "fromPlayOne")
                .put("playlistId", 0L)
                .put("startMusicId", seedSongId)
                .put("count", count))
        }.getOrNull()
        val a = songs(main?.optJSONArray("data"))
        if (a.isNotEmpty()) {
            android.util.Log.i("RoamDiag", "heart(intelligence) seed=$seedSongId -> ${a.size}")
            return@withContext a
        }
        val fallback = songs(
            runCatching { we("/playmode/song/list", JSONObject().put("id", seedSongId)) }
                .getOrNull()?.optJSONArray("data")
        )
        android.util.Log.i("RoamDiag", "heart(song/list 兜底) seed=$seedSongId -> ${fallback.size}")
        fallback
    }

    // ---------- 歌单 ----------
    suspend fun userPlaylists(uid: Long): List<PlaylistItem> = withContext(Dispatchers.IO) {
        val j = we("/user/playlist",
            JSONObject().put("uid", uid).put("limit", 40).put("offset", 0).put("includeVideo", true))
        if (!ok(j)) return@withContext emptyList()
        val arr = j.optJSONArray("playlist") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PlaylistItem(
                id = o.optLong("id"), name = o.optString("name"), coverUrl = o.optString("coverImgUrl"),
                trackCount = o.optInt("trackCount"), playCount = o.optLong("playCount"),
                isMine = o.optJSONObject("creator")?.optLong("userId") == uid,
            )
        }
    }

    suspend fun playlistDetail(id: Long): Pair<PlaylistItem, List<Song>>? = withContext(Dispatchers.IO) {
        val j = we("/v6/playlist/detail", JSONObject().put("id", id).put("n", "1000").put("s", "8"))
        if (!ok(j)) return@withContext null
        val p = j.optJSONObject("playlist") ?: return@withContext null
        val item = PlaylistItem(p.optLong("id"), p.optString("name"), p.optString("coverImgUrl"),
            p.optInt("trackCount"), p.optLong("playCount"),
            p.optJSONObject("creator")?.optLong("userId") == SessionStore.uid)
        val tracks = songs(p.optJSONArray("tracks")).toMutableList()
        val trackIds = p.optJSONArray("trackIds")?.let { a -> (0 until a.length()).map { a.optJSONObject(it)?.optLong("id") ?: 0L } } ?: emptyList()
        val missing = trackIds.filter { id -> tracks.none { it.id == id } }
        if (missing.isNotEmpty()) tracks += songDetail(missing)
        item to tracks
    }

    suspend fun createPlaylist(name: String): Long? = withContext(Dispatchers.IO) {
        val j = we("/playlist/create", JSONObject().put("name", name).put("privacy", "false"))
        if (ok(j)) j.optLong("id").takeIf { it > 0 } else null
    }

    suspend fun addToPlaylist(pid: Long, trackId: Long): Boolean = withContext(Dispatchers.IO) {
        val j = we("/playlist/manipulate/tracks",
            JSONObject().put("op", "add").put("pid", pid).put("tracks", "[$trackId]").put("nointer", "false"))
        ok(j) || j.optJSONObject("body")?.optInt("code") == 200
    }

    // ---------- 专辑 / 艺人 ----------
    suspend fun albumDetail(id: Long): Triple<String, String?, List<Song>>? = withContext(Dispatchers.IO) {
        val j = we("/v1/album/$id", JSONObject())
        if (!ok(j)) return@withContext null
        val al = j.optJSONObject("album") ?: return@withContext null
        Triple(al.optString("name"), al.optString("picUrl"), songs(j.optJSONArray("songs")))
    }

    data class ArtistPage(val artist: ArtistItem, val songs: List<Song>)

    suspend fun artistHot(id: Long): ArtistPage? = withContext(Dispatchers.IO) {
        val j = we("/v1/artist/$id", JSONObject())
        if (!ok(j)) return@withContext null
        val a = j.optJSONObject("artist") ?: return@withContext null
        ArtistPage(
            ArtistItem(a.optLong("id"), a.optString("name"),
                a.optString("img1v1Url").ifEmpty { a.optString("picUrl").ifEmpty { a.optString("cover") } }),
            songs(j.optJSONArray("hotSongs")),
        )
    }

    suspend fun artistAlbums(id: Long): List<AlbumItem> = withContext(Dispatchers.IO) {
        val j = we("/artist/albums/$id", JSONObject().put("limit", 50).put("offset", 0))
        if (!ok(j)) return@withContext emptyList()
        val arr = j.optJSONArray("hotAlbums") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            AlbumItem(o.optLong("id"), o.optString("name"), o.optString("picUrl"),
                o.optJSONObject("artist")?.optString("name") ?: "", o.optLong("publishTime"), o.optInt("size"))
        }
    }

    /**
     * 收藏的专辑（我收藏的专辑列表）：weapi `/album/sublist`。
     * 响应 `{code, albums:[{id,name,picUrl,artist:{name},publishTime,size}], hasMore, count}`；
     * 与艺人页 hotAlbums 同构，直接复用 [AlbumItem] 映射（同一套卡片 UI 也能直接复用）。
     * 兼容：个别账号/版本把数组放在 `data` 下（同 artist/sublist 的口径差异），两条都试。
     */
    suspend fun albumSublist(limit: Int = 100, offset: Int = 0): List<AlbumItem> = withContext(Dispatchers.IO) {
        val j = runCatching {
            we("/album/sublist", JSONObject().put("limit", limit).put("offset", offset).put("total", true))
        }.getOrNull() ?: return@withContext emptyList()
        if (!ok(j)) return@withContext emptyList()
        val arr = j.optJSONArray("albums") ?: j.optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id")
            if (id <= 0L) return@mapNotNull null
            AlbumItem(id, o.optString("name"), o.optString("picUrl"),
                o.optJSONObject("artist")?.optString("name") ?: "", o.optLong("publishTime"), o.optInt("size"))
        }
    }

    suspend fun artistSub(id: Long, sub: Boolean): Boolean = withContext(Dispatchers.IO) {
        // ★ 官方两端点参数口径不同（2026-09-13 真机确诊：取消发 artistId 服务端静默失败，
        //   重进仍显示已关注）：sub 收 artistId（数字）；unsub 收 artistIds（字符串化 JSON
        //   数组，支持批量）。unsub 双带 artistId 兼容通道差异。
        val p = if (sub) "/artist/sub" else "/artist/unsub"
        val b = if (sub) JSONObject().put("artistId", id)
        else JSONObject().put("artistIds", "[$id]").put("artistId", id)
        // eapi 摘要路径带 /api、weapi 不带（见 likeEvent 注释）
        val r = ok(eaFlt("/api$p", b)) || ok(we(p, b))
        android.util.Log.i("NcmApi", "artistSub($id,$sub) -> $r")
        r
    }

    /** 是否已关注该艺人：拉收藏艺人列表匹配 id（响应形状与收藏歌单列表一致，防御性解析） */
    suspend fun artistFollowed(id: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val j = we("/artist/sublist", JSONObject().put("limit", 1000).put("offset", 0))
            if (!ok(j)) return@runCatching false
            val arr = j.optJSONArray("data") ?: return@runCatching false
            (0 until arr.length()).any { arr.optJSONObject(it)?.optLong("id") == id }
        }.getOrDefault(false)
    }

    // ---------- 好友（关注 + 粉丝合并） ----------
    private fun parseUsers(arr: JSONArray?): List<FriendInfo> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            FriendInfo(o.optLong("userId"), o.optString("nickname"), o.optString("avatarUrl"))
        }
    }

    suspend fun friends(uid: Long): List<FriendInfo> = withContext(Dispatchers.IO) {
        val follows = run {
            val j = we("/user/getfollows/$uid", JSONObject().put("limit", 50).put("offset", 0))
            parseUsers(j.optJSONObject("follow")?.optJSONArray("users")
                ?: j.optJSONArray("follow"))
        }
        val fans = run {
            val j = we("/user/getfolloweds/$uid", JSONObject().put("limit", 50).put("offset", 0))
            parseUsers(j.optJSONArray("followeds"))
        }
        (follows + fans).distinctBy { it.id }
    }

    // ---------- 私信（weapi + 明文 api；官方 web / iOS 端口径，必须带对应 os cookie） ----------
    private fun msgDiag(name: String, j: JSONObject?) {
        android.util.Log.i("MsgDiag", "$name -> ${j?.toString()?.take(600) ?: "null"}")
    }

    /** JSON 对象 → 表单键值（明文 api 通道用） */
    private fun jsonToForm(o: JSONObject): Map<String, String> =
        o.keys().asSequence().associateWith { k -> o.opt(k).toString() }

    /**
     * 官方把私信正文**又包了一层 JSON**：外层 `{msgId, msg:"{...}", type}`，内层 `{msg, type, title}`。
     * 这里把内层解出来；解不出（即纯文本消息）返回 null。
     */
    private fun innerBody(o: JSONObject?): JSONObject? =
        o?.let { runCatching { JSONObject(it.optString("msg")) }.getOrNull() }

    /** 会话预览文案：有正文用正文，只有标题（歌曲/歌单等分享）就加前缀 */
    private fun previewOf(o: JSONObject): String {
        val b = innerBody(o)
        val text = b?.optString("msg").orEmpty().ifEmpty { if (b == null) o.optString("msg") else "" }
        val title = b?.optString("title").orEmpty()
        return when {
            text.isNotEmpty() -> text
            title.isNotEmpty() -> "[分享] $title"
            else -> ""
        }
    }

    /**
     * 从内层消息体认出「分享歌曲」，返回 (歌曲id, 歌名)。四条识别路按序尝试：
     * ① body.song 是完整歌曲对象 —— 2026-09-12 真机日志确诊的官方分享单曲实际结构
     *   （bodyKeys=msg,song,forwardUserId,...，title=分享单曲，song 里才有 id/name）；
     * ② song 直接是数字 id 的兼容形态；③ 内层显式 type=song；④ 具备歌曲特征字段。
     * ③④ 要求 id 与外层消息 id 不同，避免把消息 id 误当歌曲 id。
     */
    private fun songOfBody(b: JSONObject?, outerMsgId: Long): Pair<Long, String>? {
        b ?: return null
        fun pick(o: JSONObject): Pair<Long, String>? {
            val id = listOf("id", "songId").firstNotNullOfOrNull { k ->
                o.optLong(k, 0L).takeIf { it > 0L && it != outerMsgId }
            } ?: return null
            val name = o.optString("name").ifEmpty { o.optString("title") }
            return id to name
        }
        b.optJSONObject("song")?.let { return pick(it) }
        b.optLong("song", 0L).takeIf { it > 0L && it != outerMsgId }?.let { return it to "" }
        if (b.optString("type") == "song") pick(b)?.let { return it }
        val hasSongShape = b.has("artists") || b.has("album") || b.has("duration") || b.has("alias")
        if (hasSongShape) pick(b)?.let { return it }
        return null
    }

    /**
     * 会话列表：返回 null = 接口不可用（未登录/已下线），空表 = 确实没有会话。
     * ★ 实测结构（2026-09-11 真机）：会话字段全部嵌在 `user` 对象里（fromUserId/toUserId/
     * lastMsgTime/lastMsg/newMsgCount），对方资料在同级的 fromUser / toUser，**没有 users 数组**。
     */
    suspend fun msgSessions(limit: Int = 30): List<ChatSession>? = withContext(Dispatchers.IO) {
        val j = runCatching {
            we("/msg/private/users",
                JSONObject().put("offset", 0).put("limit", limit).put("total", "true"), pcLogin = true)
        }.getOrNull()
        msgDiag("sessions", j)
        if (j == null || !ok(j)) return@withContext null
        val arr = (j.optJSONObject("data") ?: j).optJSONArray("msgs") ?: return@withContext emptyList()
        val myUid = SessionStore.uid
        (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = m.optJSONObject("user") ?: m
            val from = u.optLong("fromUserId")
            val iAmSender = myUid > 0L && from == myUid
            val other = if (iAmSender) u.optLong("toUserId") else from
            if (other <= 0L) return@mapNotNull null
            val peer = (if (iAmSender) m.optJSONObject("toUser") else m.optJSONObject("fromUser"))
                ?: m.optJSONObject("fromUser") ?: m.optJSONObject("toUser")
            ChatSession(
                userId = other,
                name = peer?.optString("nickname")?.takeIf { it.isNotEmpty() } ?: "用户 $other",
                avatarUrl = peer?.optString("avatarUrl")?.takeIf { it.isNotEmpty() },
                lastText = previewOf(u).replace('\n', ' '),
                timeMs = u.optLong("lastMsgTime"),
                unread = u.optInt("newMsgCount"),
            )
        }.sortedByDescending { it.timeMs }
    }

    /**
     * 私信记录：统一按时间**正序**返回（接口原始倒序）。before 传上一页最早一条的时间戳。
     * 认出的歌曲消息用 songDetail 补全封面/艺人，聊天页据此渲染成可点卡片。
     */
    suspend fun msgHistory(uid: Long, before: Long = 0L, limit: Int = 30): List<ChatMessage>? =
        withContext(Dispatchers.IO) {
            val req = JSONObject()
                .put("userId", uid).put("limit", limit).put("time", before).put("total", "true")
            // 旧端点字段更全（带 realFromUser/forwardUserId），/get 变体兜底；两者参数一致
            val j = runCatching { we("/msg/private/history", req, pcLogin = true) }.getOrNull()
                ?.takeIf { ok(it) }
                ?: runCatching { we("/msg/private/history/get", req, pcLogin = true) }.getOrNull()
            if (j == null || !ok(j)) {
                msgDiag("history uid=$uid FAIL", j)
                return@withContext null
            }
            val arr = (j.optJSONObject("data") ?: j).optJSONArray("msgs") ?: return@withContext emptyList()
            val myUid = SessionStore.uid
            val parsed = (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val b = innerBody(m)
                val text = b?.optString("msg").orEmpty().ifEmpty { if (b == null) m.optString("msg") else "" }
                val title = b?.optString("title").orEmpty()
                val shared = songOfBody(b, m.optLong("id"))
                val sid = shared?.first ?: 0L
                // 方向判定候选字段全打点（截断），真机一轮看清所有候选：
                // 已知 fromUser 恒为登录用户（不可用）；realFromUser.userId 取出为 0，
                // 打全文看真实键名；user 字段在 /get 变体下可能存在
                val rfObj = m.optJSONObject("realFromUser")
                val rfUid = rfObj?.optLong("userId") ?: rfObj?.optLong("id") ?: 0L
                val fromUid = m.optLong("fromUserId")
                val fuUid = m.optJSONObject("fromUser")?.optLong("userId") ?: 0L
                val uuUid = m.optJSONObject("user")?.optLong("userId") ?: m.optJSONObject("user")?.optLong("id") ?: 0L
                val toUid = m.optJSONObject("toUser")?.optLong("userId") ?: 0L
                android.util.Log.i("MsgDiag",
                    "  item msgId=${m.optLong("id")} bodyType=${b?.optInt("type") ?: -1} songId=$sid " +
                        "fu=$fuUid rf=$rfUid to=$toUid my=$myUid")
                ChatMessage(
                    id = m.optLong("id"),
                    text = when {
                        text.isNotEmpty() -> text
                        title.isNotEmpty() && sid <= 0L -> title // 非歌曲的标题类消息（歌单/专辑分享）
                        sid > 0L -> ""                            // 歌曲卡片：UI 走卡片分支
                        b?.has("picInfo") == true -> "[图片]"
                        else -> "[不支持的消息]"
                    },
                    timeMs = m.optLong("time"),
                    // 方向判定（2026-09-12 真机确诊）：fromUser = 发送者资料（标准语义，
                    // 实测会话内己方消息恒为自己 uid；会话无对方消息时看似"恒为自己"并非陷阱）。
                    // realFromUser 存在但 userId 恒 0、顶层无 fromUserId/user，/get 变体无增益。
                    fromMe = myUid > 0L && when {
                        rfUid > 0L -> rfUid == myUid
                        fromUid > 0L -> fromUid == myUid
                        uuUid > 0L -> uuUid == myUid
                        fuUid > 0L -> fuUid == myUid
                        else -> false
                    },
                    song = shared?.let { (s, name) ->
                        Song(s, name.ifEmpty { "分享的歌曲" }, "", 0L, "", 0L, 0, null)
                    },
                )
            }.sortedBy { it.timeMs }
            val need = parsed.mapNotNull { it.song?.id }.distinct()
            if (need.isEmpty()) return@withContext parsed
            val detail = runCatching { songDetail(need) }.getOrDefault(emptyList()).associateBy { it.id }
            parsed.map { msg -> detail[msg.song?.id]?.let { msg.copy(song = it, text = "") } ?: msg }
        }

    /** 发送文本私信：type=text + userIds 为**字符串形式的数组**（官方 web 端口径） */
    suspend fun msgSend(uid: Long, text: String): Boolean = withContext(Dispatchers.IO) {
        if (uid <= 0L || text.isBlank()) return@withContext false
        val j = runCatching {
            we("/msg/private/send", JSONObject()
                .put("type", "text").put("msg", text.trim()).put("userIds", "[$uid]"), pcLogin = true)
        }.getOrNull()
        msgDiag("send->$uid", j)
        j != null && ok(j)
    }

    /**
     * 发送私信并回传服务端 code（200=成功，-1=无响应）：一起听房内聊天用——
     * 失败时 UI 直接展示 code（如对方私信限制/风控），不再笼统报「通道异常」。
     */
    suspend fun msgSendCode(uid: Long, text: String): Int = withContext(Dispatchers.IO) {
        if (uid <= 0L || text.isBlank()) return@withContext -1
        val j = runCatching {
            we("/msg/private/send", JSONObject()
                .put("type", "text").put("msg", text.trim()).put("userIds", "[$uid]"), pcLogin = true)
        }.getOrNull()
        msgDiag("sendCode->$uid", j)
        j?.optInt("code", -1) ?: -1
    }

    /**
     * 发送**歌曲卡片**私信：官方 type=song，走明文 api 通道（/api/... + os=ios cookie）。
     * 这样对方在官方 App 里收到的也是一张能点开的歌曲卡片，而不是一条打不开的纯链接。
     */
    suspend fun msgSendSong(uid: Long, songId: Long, msg: String = ""): Boolean = withContext(Dispatchers.IO) {
        if (uid <= 0L || songId <= 0L) return@withContext false
        val j = runCatching {
            post(
                "https://music.163.com/api/msg/private/send",
                jsonToForm(JSONObject()
                    .put("id", songId).put("msg", msg).put("type", "song").put("userIds", "[$uid]")),
                cookieOs = "ios", extraCookie = "appver=8.7.01",
            )
        }.getOrNull()
        msgDiag("sendSong->$uid id=$songId", j)
        j != null && ok(j)
    }

    /** 分享歌曲给好友：优先官方卡片，失败退「文本 + 链接」（聊天页里链接可点开） */
    suspend fun shareSongTo(uid: Long, song: Song): Boolean {
        if (msgSendSong(uid, song.id)) return true
        return msgSend(uid, "分享单曲：${song.title} - ${song.artist}\nhttps://music.163.com/song?id=${song.id}")
    }

    // ---------- 一起听（2026-09-11 按 MeloX 逆向规格全 HTTP 化：无 ichat 长连接） ----------
    // 诊断日志：一起听请求低频，body 前 500 字符直接进 logcat（tag=LTDiag）定位加入失败原因
    private fun ltdiag(name: String, j: JSONObject?) {
        android.util.Log.i("LTDiag", "$name -> ${j?.toString()?.take(500) ?: "null"}")
    }

    private var syncLogCount = 0

    /**
     * 建房。官方契约（9.5.90 客户端 `Lzc0/t0$e;->f`）：
     * `POST /api/listen/together/room/create { refer, inviteUid, extJson }`。
     *
     * [inviteUid] > 0 = **建房同时把邀请直接投递给该好友**（对方一起听消息箱立即可见）——
     * 这是「拉好友一起听」比「建房后再发私信链接」可靠得多的一步到位路径：
     * 私信链接要对方手动复制粘贴，而官方邀请在对方的「一起听」页就是一个可点的接受卡片。
     */
    suspend fun ltCreate(inviteUid: Long = 0L): JSONObject? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("refer", "songplay_more")
        if (inviteUid > 0L) {
            body.put("inviteUid", inviteUid.toString()).put("extJson", "")
        }
        val j = ea("/api/listen/together/room/create", body)
        ltdiag("create invite=$inviteUid", j)
        if (!ok(j)) null else j.optJSONObject("data")
    }

    suspend fun ltCheckRaw(roomId: String): JSONObject = withContext(Dispatchers.IO) {
        val j = ea("/api/listen/together/room/check", JSONObject().put("roomId", roomId))
        ltdiag("check", j)
        j
    }

    /** 成员加入房间（唯一入口；join 端点不存在）。accept 不回 roomInfo 时上层用 status 兜底 */
    suspend fun ltAccept(roomId: String, inviterId: String): JSONObject? = withContext(Dispatchers.IO) {
        val j = ea("/api/listen/together/play/invitation/accept",
            JSONObject().put("refer", "inbox_invite").put("roomId", roomId).put("inviterId", inviterId))
        ltdiag("accept($inviterId)", j)
        if (!ok(j)) null else j.optJSONObject("data")
    }

    /** 房间状态（weapi！eapi 空 body 兜底）：inRoom + roomInfo，断线恢复用 */
    suspend fun ltStatus(): JSONObject? = withContext(Dispatchers.IO) {
        var j: JSONObject? = runCatching { we("/listen/together/status/get", JSONObject()) }.getOrNull()
        if (j == null || !ok(j)) j = runCatching { ea("/api/listen/together/status/get", JSONObject()) }.getOrNull()
        ltdiag("status", j)
        if (j != null && ok(j)) j!!.optJSONObject("data") else null
    }

    /** 心跳：返回 data 原文（响应可能携带房间快照：人数/成员/时长，调用方深度吸收同步），无 data 返回 null */
    suspend fun ltHeartbeat(roomId: String, songId: Long, playing: Boolean, progressMs: Long): JSONObject? =
        withContext(Dispatchers.IO) {
            val j = ea("/api/listen/together/heartbeat",
                JSONObject().put("roomId", roomId).put("songId", songId)
                    .put("playStatus", if (playing) "PLAY" else "PAUSE").put("progress", progressMs))
            j.optJSONObject("data")
        }

    /** songId 在 payload 里必须字符串、clientSeq 数字（MeloX 实测口径） */
    suspend fun ltPlayCommand(roomId: String, info: JSONObject): Boolean = withContext(Dispatchers.IO) {
        ok(ea("/api/listen/together/play/command/report",
            JSONObject().put("roomId", roomId).put("commandInfo", info.toString())))
    }

    suspend fun ltSyncList(roomId: String, param: String): Boolean = withContext(Dispatchers.IO) {
        ok(ea("/api/listen/together/sync/list/command/report",
            JSONObject().put("roomId", roomId).put("playlistParam", param)))
    }

    suspend fun ltEnd(roomId: String): Boolean = withContext(Dispatchers.IO) {
        ok(ea("/api/listen/together/end/v2", JSONObject().put("roomId", roomId)))
    }

    // ---------- 一起听「邀请好友」HTTP 通道（2026-10-01 按官方 9.5.90 dex 契约实现） ----------
    // 官方客户端邀请好友走的是**一起听自有接口**，不是私信：
    //   · 单发：POST /api/listen/together/invite/message/send { roomId, acceptorId, ltType }
    //     （`Lzc0/t0;->t`，acceptorId = 被邀请人 uid，ltType = 房间类型）
    //   · 批量：POST /api/listen/together/multi/invite { roomId, groupIds, inviteUids }
    //     （`Lad0/v$h;->f`，inviteUids 为 uid 数组，groupIds 为乐迷团分组 id 数组）
    // 成功后邀请会落进对方的「一起听」消息箱（官方 App 内是可点的接受卡片）。
    // 旧实现用私信发分享链接：对方必须手动复制房号/链接再粘贴，且被陌生人私信风控拦掉。

    /**
     * acceptorId 的线上口径（数字 / 字符串）首次命中后缓存，稳态只打一个请求。
     * `-1` = 尚未标定；`true` = 数字；`false` = 字符串。
     */
    private var inviteIdNumeric = -1

    /**
     * 邀请**单个好友**加入一起听房间。返回服务端 code（200 = 已投递进对方消息箱），
     * 其它值/-1 = 未成功（调用方据此决定是否回退到私信分享链接）。
     */
    suspend fun ltInviteFriend(
        roomId: String,
        acceptorId: Long,
        ltType: String = "listenTogether",
    ): Int = withContext(Dispatchers.IO) {
        if (roomId.isEmpty() || acceptorId <= 0L) return@withContext -1
        val order = when (inviteIdNumeric) {
            1 -> listOf(true, false)
            0 -> listOf(false, true)
            else -> listOf(false, true)
        }
        for (numeric in order) {
            val body = JSONObject().put("roomId", roomId).put("ltType", ltType)
            if (numeric) body.put("acceptorId", acceptorId) else body.put("acceptorId", acceptorId.toString())
            // 一起听系接口对设备风控敏感：安卓口径优先，失败退 pc 口径
            var j = runCatching { eaFlt("/api/listen/together/invite/message/send", body) }.getOrNull()
            if (j == null || !ok(j)) {
                j = runCatching { ea("/api/listen/together/invite/message/send", body) }.getOrNull()
            }
            ltdiag("inviteMsg uid=$acceptorId num=$numeric", j)
            if (j != null && ok(j)) {
                inviteIdNumeric = if (numeric) 1 else 0
                return@withContext j.optInt("code", 200)
            }
        }
        -1
    }

    /**
     * 批量邀请（官方 `multi/invite`）：一次把多个 uid 邀进同一房间，比逐个单发少 N-1 个请求。
     * [groupIds] 仅乐迷团房需要（把邀请归到某个团分组），普通房传空数组。
     * 返回服务端 code（200 = 成功），-1 = 失败。
     */
    suspend fun ltMultiInvite(
        roomId: String,
        inviteUids: List<Long>,
        groupIds: List<String> = emptyList(),
    ): Int = withContext(Dispatchers.IO) {
        val uids = inviteUids.filter { it > 0L }.distinct()
        if (roomId.isEmpty() || uids.isEmpty()) return@withContext -1
        val body = JSONObject()
            .put("roomId", roomId)
            .put("inviteUids", JSONArray(uids))
            .put("groupIds", JSONArray(groupIds))
        var j = runCatching { eaFlt("/api/listen/together/multi/invite", body) }.getOrNull()
        if (j == null || !ok(j)) j = runCatching { ea("/api/listen/together/multi/invite", body) }.getOrNull()
        ltdiag("multiInvite n=${uids.size}", j)
        if (j != null && ok(j)) j.optInt("code", 200) else -1
    }

    /**
     * 乐迷团/多人房建房（官方 `multi/room/create`）。
     *
     * 契约（`Lad0/v$b;->f`）：`type / songId / groupIds / inviteUids / from / playedTime /
     * nextSongIds / checkToken / autoJoinUids / artistId / playlistIds`。
     * `from` 官方取值 `CREATE`（主动建房）/`LISTEN_TOGETHER`（一起听入口）；
     * `artistId` 非 0 = 建艺人乐迷团房（对齐 `fla_` 前缀房号）；
     * `autoJoinUids` = 直接拉进房（免对方点接受）——官方用于「拉好友一起听」。
     * 返回 `data`（含 roomInfo），失败返回 null。
     */
    suspend fun ltCreateMulti(
        songId: Long,
        inviteUids: List<Long> = emptyList(),
        autoJoinUids: List<Long> = emptyList(),
        artistId: Long = 0L,
        playlistIds: List<String> = emptyList(),
        groupIds: List<String> = emptyList(),
        playedTime: Long = 0L,
        nextSongIds: List<Long> = emptyList(),
    ): JSONObject? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("type", "CREATE")
            .put("from", "CREATE")
            .put("songId", songId)
            .put("playedTime", playedTime)
            .put("groupIds", JSONArray(groupIds))
            .put("inviteUids", JSONArray(inviteUids.filter { it > 0L }.distinct()))
            .put("autoJoinUids", JSONArray(autoJoinUids.filter { it > 0L }.distinct()))
            .put("nextSongIds", JSONArray(nextSongIds.filter { it > 0L }))
            .put("playlistIds", JSONArray(playlistIds))
            .put("artistId", artistId)
            .put("checkToken", SessionStore.csrf.orEmpty())
        var j = runCatching { eaFlt("/api/listen/together/multi/room/create", body) }.getOrNull()
        if (j == null || !ok(j)) j = runCatching { ea("/api/listen/together/multi/room/create", body) }.getOrNull()
        ltdiag("multiCreate artist=$artistId invite=${inviteUids.size}", j)
        if (j != null && ok(j)) j.optJSONObject("data") else null
    }

    /**
     * 房内「开始一起听」提示消息（官方 `multi/start/msg`，参数仅 roomId）。
     * 建房/全员就绪后调用，房内会落一条系统提示（官方同款行为）。
     */
    suspend fun ltStartMsg(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val j = runCatching {
            ea("/api/listen/together/multi/start/msg", JSONObject().put("roomId", roomId))
        }.getOrNull()
        j != null && ok(j)
    }

    /**
     * 歌房聊天室消息历史（官方 middle/im 中间层，逆向 zc0/c0$a）：
     * POST chatRoomId + roomId + timeStamp + limit + reverse + queryMsgTypeList=["text"]
     * + uniqueBizId + scene=listenTogether，返回 historyMsgDTOList[]（body.msg 正文 /
     * fromUserId / sendTime / serverExt{nickname,avatarUrl,userId}）+ nextTimeStamp 游标。
     * ⚠ uniqueBizId 官方取值未标定，按 roomId→chatRoomId→空 依次探测，命中即缓存。
     * 这就是官方聊天记录页的数据源：官方 App 用户在房内发的消息也会落在这里 → 轮询即准实时。
     * ★ 2026-09-12 真机确诊补正：middle/im 是官方 App 中间层接口，用 pc 口径 eapi 调用
     *   恒返回空 body（日志：chatHist 全变体 `code:-1 End of input at character 0 of`），
     *   而 FLT 系接口普遍要求安卓口径（os=android + deviceId，见 fltJoin 实测结论）。
     *   现按「通道 × uniqueBizId」两维探测：通道 0 = 安卓口径 eaFlt（另带 clientDeviceId）、
     *   1 = pc 口径 ea、2 = weapi；命中后两个下标各自缓存，稳态每轮只打 1 个请求。
     */
    private var chatHistVariant = 0
    private var chatHistChannel = 0
    private var chatHistLogCount = 0

    /**
     * middle/im 通道「判死」计数。该通道对乐迷团/SELFHOST 房是**网关级 400 空 body**
     * （9 个通道×变体组合全部拿到空串），每 8s 轮询白打 9 个请求既刷屏又拖慢真实通道。
     * 连续 2 轮全通道未命中即判死，本轮会话不再发起（[resetRoomChatProbe] 换房时复活）。
     */
    private var chatHistDeadRounds = 0
    private var chatHistDead = false

    suspend fun chatRoomHistory(chatRoomId: String, roomId: String, timeStamp: Long, limit: Int, reverse: Boolean): List<JSONObject> =
        withContext(Dispatchers.IO) {
            if (chatHistDead) return@withContext emptyList()
            val variants = listOf(roomId, chatRoomId, "")
            val vOrder = (if (chatHistVariant in variants.indices) listOf(chatHistVariant) else emptyList()) +
                variants.indices.filter { it != chatHistVariant }
            val cOrder = (if (chatHistChannel in 0..2) listOf(chatHistChannel) else emptyList()) +
                listOf(0, 1, 2).filter { it != chatHistChannel }
            for (ch in cOrder) {
                for (vi in vOrder) {
                    val data = JSONObject()
                        .put("chatRoomId", chatRoomId)
                        .put("roomId", roomId)
                        .put("timeStamp", timeStamp)
                        .put("limit", limit)
                        .put("reverse", reverse)
                        .put("queryMsgTypeList", "[\"text\"]")
                        .put("uniqueBizId", variants[vi])
                        .put("scene", "listenTogether")
                    if (ch == 0) data.put("clientDeviceId", fltDeviceId)
                    val j = runCatching {
                        when (ch) {
                            0 -> eaFlt("/api/middle/im/chatroom/msg/history/query", data)
                            1 -> ea("/api/middle/im/chatroom/msg/history/query", data)
                            else -> we("/middle/im/chatroom/msg/history/query", data)
                        }
                    }.getOrNull()
                    val good = j != null && ok(j)
                    chatHistLogCount++
                    val arr = j?.optJSONObject("data")?.optJSONArray("historyMsgDTOList")
                        ?: j?.optJSONArray("historyMsgDTOList")
                    val n = arr?.length() ?: 0
                    if (chatHistLogCount <= 6 || !good || n > 0) {
                        ltdiag("chatHist#$chatHistLogCount c$ch v$vi n=$n", j)
                    }
                    if (!good) continue
                    chatHistChannel = ch
                    chatHistVariant = vi
                    chatHistDeadRounds = 0
                    return@withContext (0 until n).mapNotNull { arr?.optJSONObject(it) }
                }
            }
            // 9 个组合全空 → 该房型此通道不可用，连续 2 轮后判死
            if (++chatHistDeadRounds >= 2) {
                chatHistDead = true
                android.util.Log.i("LTDiag", "chatHist 判死：middle/im 对本房 9 组合全空，本轮会话不再探测")
            }
            emptyList()
        }

    /**
     * 一起听**房内公共聊天**的一条消息（官方 `LTMultiChatHistory.records[]` 归一化）。
     *
     * 归一化必要性：服务端 records 条目的 id 在 `serverExt.msgId`，顶层 `msgId` 恒为 null；
     * 旧版直接读顶层 `serverMsgId`（该键根本不存在）→ 每条都命中 `id <= 0` 被丢弃，
     * 「聊天记录拉不到」的第二层原因。昵称同理在 `serverExt.nickname`。
     */
    data class RoomChatMsg(
        /** 服务端消息 id（serverExt.msgId）；未下发时为 0 */
        val id: Long,
        /** 去重键（与 middle/im 通道同构：uid_sendTime），跨通道天然去重 */
        val key: String,
        val uid: Long,
        val nickname: String,
        val text: String,
        val timeMs: Long,
        val isSystem: Boolean,
    )

    /** 房内聊天历史分页参数（服务端硬约束，见下） */
    private const val ROOM_CHAT_PAGE_SIZE = 50
    private const val ROOM_CHAT_DIRECTION = 0

    /**
     * 一起听房内公共聊天历史 —— 官方 `Lad0/v;->e(roomId, direction, size, cursor)` 的同款调用：
     * `POST /api/listen/together/multi/special/msg/history {roomId, direction, page:{size,cursor}}`。
     *
     * ★ 2026-09-13 参数矩阵实测（房间 fla_1030001_29d65e_1785859686，匿名亦可复现）：
     *   - 路径只能 `multi/special/msg/history`；`multi/match/msg/history` 恒 `code 301 系统错误`。
     *   - **size 必须正好 50**（10/20/任意其它值一律 `code 301`）。
     *   - **direction 必须 0**（1 亦 301）。
     *   - 满足以上条件即 `code 200`，`data.records[]` 按 sendTime 升序，返回最近一页（50 条）
     *     ——含进房前的历史、`msgType=1` 的房间系统提示与 `msgType=0` 的用户发言。
     *
     * ★ 这是**唯一**对乐迷团房（`imType=SELFHOST`）可用的 HTTP 读通道。歌房那套
     *   `middle/im/chatroom/msg/history/query` 对该房型是**网关级 400 空 body**
     *   （App 日志里的 `End of input at character 0 of` 就是它拿到空串后 JSON 解析失败）。
     */
    private var roomHistLogCount = 0

    suspend fun ltRoomMsgHistory(roomId: String): List<RoomChatMsg> = withContext(Dispatchers.IO) {
        val page = JSONObject().put("size", ROOM_CHAT_PAGE_SIZE).put("cursor", "")
        val j = runCatching {
            ea("/api/listen/together/multi/special/msg/history",
                JSONObject().put("roomId", roomId)
                    .put("direction", ROOM_CHAT_DIRECTION)
                    .put("page", page.toString()))
        }.getOrNull()
        val arr = j?.optJSONObject("data")?.optJSONArray("records")
        // 轮询常态化后节流：前 2 次全打，之后只在有内容/出错时打
        roomHistLogCount++
        if (roomHistLogCount <= 2 || j == null || !ok(j) || (arr?.length() ?: 0) > 0) {
            ltdiag("roomChatHist#$roomHistLogCount n=${arr?.length() ?: 0}", j)
        }
        if (j == null || !ok(j) || arr == null) return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val ext = r.optJSONObject("serverExt")
            val text = r.optJSONObject("imChatRoomMsgBody")?.optString("text").orEmpty()
                .let { if (it == "null") "" else it.trim() }
            if (text.isEmpty()) return@mapNotNull null
            val t = r.optLong("sendTime", 0L)
            if (t <= 0L) return@mapNotNull null
            val uid = r.optLong("sendUid", ext?.optLong("userId", 0L) ?: 0L)
            val mid = ext?.optLong("msgId", 0L) ?: 0L
            val nick = (ext?.optString("nickname") ?: r.optString("nickname"))
                .let { if (it.isEmpty() || it == "null") "" else it }
            RoomChatMsg(
                id = mid,
                key = "${uid}_$t",
                uid = uid,
                nickname = nick,
                text = text,
                timeMs = t,
                isSystem = r.optInt("msgType", 0) == 1,
            )
        }
    }

    /** 会话切换时重置聊天通道探测状态（middle/im 判死标记 + 日志节流 + chatRoomId 缓存） */
    fun resetRoomChatProbe() {
        chatHistDead = false
        chatHistDeadRounds = 0
        roomHistLogCount = 0
        roomSendVariant = -1
        roomSendChannel = -1
        roomSendIdVariant = -1
        roomSendLogCount = 0
        chatMsgTypeCache = -1
        chatRoomIdCache.clear()
    }

    /**
     * 房内消息发送的探测缓存（2026-09-12 新增；2026-10-01 扩成三维：路径 × 口径 × chatroomId 变体）。
     * 候选逐个探测（同 chatRoomHistory 的多变体做法），命中即缓存三组下标，后续只打命中组合。
     * 返回 null = 全部候选失败，调用方按房型回退或提示。
     */
    private var roomSendVariant = -1
    private var roomSendChannel = -1
    private var roomSendIdVariant = -1
    private var roomSendLogCount = 0

    /** 命中的消息类型（0 = NIM 文本；-1 = 未标定）。见 [ltRoomMsgSend] 里 407 的说明 */
    private var chatMsgTypeCache = -1

    /**
     * 房内会话 id：clientExt.clientSessionId 用，进程内稳定即可 */
    private val roomSessionId: String by lazy {
        "wm" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    }

    /**
     * 房内聊天室 id 的兜底解析（2026-10-01 新增）。
     *
     * 为什么需要：房内发送/接收都要求 `chatroomId`，而它只随 `room/create`、
     * `join/room` 的 `roomInfo.roomExt.chatRoomId` 下发 —— 走 `status/get` 恢复会话、
     * 或服务端本拍没带该字段时它就是 null，房内通道整条不启动（「发不出去 / 收不到」的根因之一）。
     *
     * 兜底数据源：官方中间层 `middle/im/token-and-chatroom-addr/get`
     * （9.5.90 `Lcom/netease/cloudmusic/nim/r0;->c`，响应 data 含 `addr` / `token` / `accId`）。
     * 该接口同时下发 IM 接入凭据与聊天室地址，其中的数字 id 即聊天室 id。
     * 解析按 roomId 缓存一次，失败不缓存（下次进房重试）。
     */
    private val chatRoomIdCache = HashMap<String, String>()

    suspend fun resolveChatRoomId(roomId: String): String? = withContext(Dispatchers.IO) {
        if (roomId.isEmpty()) return@withContext null
        chatRoomIdCache[roomId]?.let { return@withContext it }
        val j = runCatching {
            ea("/api/middle/im/token-and-chatroom-addr/get", JSONObject().put("scene", "playlive"))
        }.getOrNull()
        ltdiag("chatAddr", j)
        val d = j?.optJSONObject("data") ?: return@withContext null
        // 深度遍历：chatRoomId 可能叫 chatRoomId / chatroomId / roomId / addr(数字串)
        var found: String? = null
        fun walk(o: JSONObject, depth: Int) {
            if (found != null || depth > 3) return
            for (k in o.keys()) {
                val v = o.opt(k)
                when {
                    v is JSONObject -> walk(v, depth + 1)
                    v is Number && k.lowercase().contains("chatroom") -> found = v.toString()
                    v is String && v.isNotEmpty() && k.lowercase().contains("chatroom") -> found = v
                }
            }
        }
        walk(d, 0)
        found?.takeIf { it.isNotEmpty() }?.also { chatRoomIdCache[roomId] = it }
    }

    /**
     * 房内消息发送（2026-09-12 按官方 9.5.90 dex 实证重写）。
     *
     * 实证依据（官方 APK classes18.dex）：
     * - 路径：`middle/im/chatroom/send`、`middle/im/chatroom/special/send`
     *   （旧版猜的 `/api/middle/im/chatroom/msg/send` 多了 `msg/`，必然打不通）
     * - 消息体 `msgBody` = LTChatSendBodyWireDto 的 Moshi 键名 → `{"msg":文本,"msgType":Int}`
     * - 客户端扩展 `clientExt` = LTChatSendClientExtWireDto 的 Moshi 键名 →
     *   `{"bizType","ltType","roomId","clientSessionId","syncSource","emoji","ignoreUserIds","bubbleInterestId"}`
     * - 顶层键：`roomId` + **小写 `chatroomId`** + `msgType` + `msgBody` + `clientExt`
     *   （来自 Lid0/f;->b 构建发送 Map 的常量）
     * - ⚠ 官方客户端实际走 NIM 长连接（Lmd0/c;->enterChatRoom + CM 通道 chatroom_msg_im_notice_new），
     *   HTTP 通道是否可用需真机验证；返回 null = 全部候选失败，调用方按房型回退或提示。
     */
    /**
     * 房内消息**引用回复**的载体（2026-10-01 新增）。
     *
     * 官方 Moshi DTO（LTChatSendBodyWireDto / LTChatSendClientExtWireDto）里没有引用键，
     * 服务端对未知键按忽略处理 —— 所以这里采取**双保险**：
     * ① 结构化键 `referMsgId/referUid/referNickname/referText` 同时进 msgBody 与 clientExt
     *    （若服务端/新版本支持引用，直接生效）；
     * ② 正文由调用方前置一行 `回复 @昵称：摘要`（[com.ncm.watch.ui.screens.listen.ListenChatStore]
     *    负责拼），保证任何客户端（含官方 App、本端刷新回读后）都能看到「在回复谁」。
     * 缺 ② 的后果：本端刷新一轮房内历史后拿回的只有正文，引用关系会凭空消失。
     */
    data class RoomMsgRef(
        /** 被引用消息的服务端 msgId（房内通道 `serverExt.msgId`；未下发时传 0） */
        val msgId: Long = 0L,
        val uid: Long = 0L,
        val nickname: String = "",
        /** 被引用消息正文（截断到 60 字进结构化键） */
        val text: String = "",
    )

    suspend fun ltRoomMsgSend(
        roomId: String,
        chatRoomId: String?,
        text: String,
        roomType: String = "",
        ref: RoomMsgRef? = null,
    ): Int? =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext null
            // 引用信息只有真的有内容（昵称或正文）才当引用处理，避免空引用污染请求体
            val r = ref?.takeIf { it.nickname.isNotEmpty() || it.text.isNotEmpty() }

            /**
             * ★ 消息类型 —— 2026-10-01 真机日志定位到的**根因**：
             *
             * 旧版恒发 `msgType = 1`。服务端按云信（NIM）消息类型解读这个字段，而
             * **NIM 里 1 = 图片**，于是每次发言都被回 `code 407 当前图片涉嫌违规，请遵守平台规范`
             * （真机日志 roomMsgSend#1/#3/#5/#6 全是 407，路径与 chatroomId 都对）。
             * 结果是：房内通道**明明可达**（407 是业务错误，不是 404/301/空 body），
             * 却一条也发不出去 → UI 退私信桥接兜底 → 用户侧表现就是「房间内发消息发到私信去了」。
             *
             * NIM 文本消息类型 = 0，故文本按 [0, 1] 顺序探测，命中即缓存 [chatMsgTypeCache]。
             */
            val typeOrder = when (chatMsgTypeCache) {
                0 -> listOf(0, 1)
                1 -> listOf(1, 0)
                else -> listOf(0, 1)
            }
            fun buildMsgBody(mt: Int): String =
                JSONObject().put("msg", text).put("msgType", mt).apply {
                    if (r != null) {
                        put("referMsgId", r.msgId)
                        put("referUid", r.uid)
                        put("referNickname", r.nickname)
                        put("referText", r.text.take(60))
                    }
                }.toString()
            val clientExt = JSONObject()
                .put("bizType", roomType.ifEmpty { "listenTogether" })
                .put("ltType", roomType.ifEmpty { "listenTogether" })
                .put("roomId", roomId)
                .put("clientSessionId", roomSessionId)
                .put("syncSource", "listenTogether")
                .put("ignoreUserIds", JSONArray())
                .put("bubbleInterestId", "")
                .apply {
                    if (r != null) {
                        put("referMsgId", r.msgId)
                        put("referUid", r.uid)
                    }
                }
                .toString()

            // ★ 2026-10-01 匿名实测路由存在性（见 probe_routes2.mjs）：
            //   ✅ `/api/middle/im/chatroom/send`、`/api/middle/im/chatroom/special/send`
            //      → 匿名 http 400 空 body（网关级拒绝，路由真实存在，需登录态 + 正确 chatroomId）
            //   ❌ `/api/middle/im/chatroom/msg/send`、`/api/listen/together/multi/special/msg/send`
            //      → code 404「接口未找到」，已弃用（旧版猜的路径）
            val paths = listOf(
                "/api/middle/im/chatroom/send",
                "/api/middle/im/chatroom/special/send",
            )
            // chatRoomId 兜底解析：roomInfo 未下发时（status 恢复会话 / 服务端本拍没带该字段）
            // 向中间层要一次 —— 否则 idValues[0] 为空、候选集只剩 roomId/空串，房内通道必失败
            val resolvedChatRoomId = chatRoomId?.takeIf { it.isNotEmpty() }
                ?: resolveChatRoomId(roomId)
            // chatroomId 变体：0 = 真实 chatRoomId（空则跳过）、1 = roomId、2 = 空串
            val idValues = listOf(resolvedChatRoomId.orEmpty(), roomId, "")
            val idCandidates = idValues.indices.filter { idValues[it].isNotEmpty() }.ifEmpty { listOf(2) }
            // 三维组合，按可能性排序：主路径 + eaFlt（安卓口径）+ 真实 chatRoomId 排最前，
            // weapi / 兜底路径 / 空 chatroomId 排后（这些只在主组合真失败时才轮到）
            val attempts = mutableListOf<Triple<Int, Int, Int>>()
            for (pi in paths.indices) {
                val chans = if (pi == 0) listOf(0, 1, 2) else listOf(0)
                for (ch in chans) for (iv in idCandidates) attempts.add(Triple(pi, ch, iv))
            }
            val ordered = attempts.sortedBy { a ->
                (if (a.first == 0) 0 else 2) + (if (a.second == 0) 0 else if (a.second == 1) 1 else 2) +
                    (if (a.third == 0) 0 else 1)
            }
            val hitIdx = ordered.indexOfFirst {
                it.first == roomSendVariant && it.second == roomSendChannel && it.third == roomSendIdVariant
            }
            val order = (if (hitIdx >= 0) listOf(hitIdx) else emptyList()) + ordered.indices.filter { it != hitIdx }

            for (mt in typeOrder) {
                val msgBody = buildMsgBody(mt)
                for (oi in order) {
                    val (pi, ch, iv) = ordered[oi]
                    val body = JSONObject()
                        .put("roomId", roomId)
                        .put("chatroomId", idValues[iv])
                        .put("msgType", mt)
                        .put("msgBody", msgBody)
                        .put("clientExt", clientExt)
                        .put("scene", "listenTogether")
                    if (ch == 0) body.put("clientDeviceId", fltDeviceId)
                    val j = runCatching {
                        when (ch) {
                            0 -> eaFlt(paths[pi], body)
                            1 -> ea(paths[pi], body)
                            else -> we(paths[pi].removePrefix("/api"), body)
                        }
                    }.getOrNull()
                    roomSendLogCount++
                    val good = j != null && ok(j)
                    if (roomSendLogCount <= 8 || good) {
                        ltdiag(
                            "roomMsgSend#${roomSendLogCount} mt$mt p$pi c$ch v$iv " +
                                paths[pi].substringAfterLast('/'), j)
                    }
                    if (good) {
                        chatMsgTypeCache = mt
                        roomSendVariant = pi
                        roomSendChannel = ch
                        roomSendIdVariant = iv
                        return@withContext j.optInt("code", 200)
                    }
                }
            }
            null
        }

    /** 核心轮询：data{playlist: {displayList.result, randomList.result, playMode, version[]}, playCommand} */
    suspend fun ltSyncPayload(roomId: String): JSONObject? = withContext(Dispatchers.IO) {
        val j = ea("/api/listen/together/sync/playlist/get", JSONObject().put("roomId", roomId))
        if (!ok(j)) { ltdiag("sync-err", j); return@withContext null }
        val d = j.optJSONObject("data")
        // 诊断：有内容才打日志（1s 轮询，节流到每 5 次一条），用于真机核对同步链路
        if (d != null && (d.optJSONObject("playlist") != null || d.optJSONObject("playCommand") != null)) {
            syncLogCount += 1
            if (syncLogCount % 5 == 1) ltdiag("sync#$syncLogCount", j)
        }
        d
    }

    /** 乐迷团房间发现端点探测（诊断）：逐个候选打请求，响应进 LTDiag，用于定位正确端点 */
    suspend fun ltProbeEndpoints(chatRoomId: String? = null) = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "/api/listen/together/friend/online/guide/v3",
            "/api/listen/together/fanclub/online/guide",
            "/api/listen/together/room/list",
            "/api/listen/together/room/online/list",
            "/api/fansclub/interact/room/list",
            "/api/ichat/room/list",
        )
        candidates.forEach { p ->
            ltdiag("probe $p", runCatching { ea(p, JSONObject()) }.getOrNull())
        }
        ltdiag("probe guide/v2+limit",
            runCatching { ea("/api/listen/together/friend/online/guide/v2", JSONObject().put("limit", 20)) }.getOrNull())
        if (!chatRoomId.isNullOrEmpty()) {
            val chatCandidates = listOf(
                "/api/ichat/chatroom/msg/list",
                "/api/chat/room/msg/list",
                "/api/ichat/room/message/list",
            )
            chatCandidates.forEach { p ->
                ltdiag("probe-chat $p",
                    runCatching { ea(p, JSONObject().put("roomId", chatRoomId).put("limit", 20)) }.getOrNull())
            }
        }
    }

    /**
     * 乐迷团房间发现端点探测已并入 ltDiscoverRoom 的调用方（joinDiscovered），
     * 旧的聊天/听歌排行估算接口已随 2026-09-12 一起听专项移除（无调用者）。
     */

    /** 发现一个进行中的一起听房间（好友/乐迷团在线引导）；没有返回 null */
    suspend fun ltDiscoverRoom(): String? = withContext(Dispatchers.IO) {
        val j = ea("/api/listen/together/friend/online/guide/v2", JSONObject())
        ltdiag("discover", j)
        if (!ok(j)) return@withContext null
        var roomId: String? = null
        fun walk(o: JSONObject?) {
            o ?: return
            for (key in o.keys()) {
                when (val v = o.opt(key)) {
                    is JSONObject -> walk(v)
                    is JSONArray -> for (i in 0 until v.length()) { val e = v.opt(i); if (e is JSONObject) walk(e) }
                    is String -> if (key.equals("roomId", true) && v.length > 5) roomId = v
                    is Long -> if (key.equals("roomId", true)) roomId = v.toString()
                    is Int -> if (key.equals("roomId", true)) roomId = v.toString()
                }
            }
        }
        walk(j.optJSONObject("data"))
        roomId
    }

    // ---------- 艺人乐迷团（帖子流：读 + 点赞 + 评论） ----------

    /**
     * 乐迷团帖子数据源。
     *
     * 标定过程（2026-09-12 真机 + 官方 9.5.90 dex 实证）：
     * - **实测唯一命中：`/api/event/get`**（响应 `{code, more, event:[…], lasttime}`），
     *   每页只回 1~3 条（`limit` 不生效），必须拿上一页的时间戳当 `lasttime` 续拉；
     * - `/api/artist/detail/dynamic/v2` 是艺人页**运营物料**（实体专辑商品卡 +
     *   `uiElement.type = nm.profilePage.agile.channel.info`），**不是帖子**；
     * - `/api/artist/detail/dynamic`（v1）恒 `400 参数错误`；
     * - `/api/social/event/bff/block/topic/list/get` 是话题榜；`/api/social/circle/bff/detail/fansgroup/activity`
     *   实测回 `data:{}`（官方乐迷团主页是 RN 页面，数据接口在热更 bundle 里，dex 只留 Java 桥接）。
     *
     * ⚠ 这些非帖子源一旦被当帖子解析就会吐出「CHIEF」「立即支持」这类**假帖**（真机踩过），
     * 所以候选表只留实证命中的那一条，解析端也只认 `event`/`events` 数组、**不再回落通用遍历**。
     *
     * ★★ **路径必须带 userId**（2026-09-12 二次定位）：官方 `NeteaseMusicApiImpl;->n3(...)` 里是
     * `"/api/" + String.format("event/get/%d", userId)` —— 即 **`/api/event/get/{userId}`**，
     * 请求体只放 `time` / `limit` / `fromRN`。旧实现请求的是不带 userId 的 `/api/event/get`、
     * 把 uid 塞进 body，服务端读不到 → **不报错，直接按「当前登录用户 feed」返回**，
     * 真机表现就是「乐迷团里全是我好友发的内容」。匿名探针实测对照：
     * `/api/event/get/{uid}` → `code:200 keys=[more,events,code]`；不带 userId → `code:301`。
     */
    private const val FAN_POST_PATH = "/api/event/get"

    /**
     * ★★★ 乐迷团动态的**真实主源**（2026-09-12 三度定位，终于实证）。
     *
     * 之前一直找不到，是因为这几条路径**不在 dex 里** —— 乐迷团页是官方热更 RN 包
     * `rn-fansgroup` 渲染的，接口写在该包的明文 JS 里。定位链路：
     * ① dex 扫到 AB 开关 `android_fansgroup_rn_preload_new`、`rn-fansgroup`；
     * ② `assets/default_custom_config_*.json` 的 `rnBundle#releaseListUrl` 给出 RN 包发布清单地址；
     * ③ 拉清单 → `rn-fansgroup` 条目 `hermes:false`（**明文 JS**），拿 fullUrl 下载解包；
     * ④ 包内 `__d(...)` 里直接写着 apiConfig：
     *    `home: [{url:"/api/fans/group/feed/recommend/get", method:"GET",
     *             params:{fansGroupId:"${groupId}", artistSelf:0, cursor:0, size:10}}, …]`
     *
     * 也就是说：**乐迷团动态 = `/api/fans/group/feed/recommend/get`（推荐流）**，
     * 参数是 `fansGroupId`（不是 artistId！），分页用 `cursor` + `data.page.more`。
     * 另有 `/api/fans/group/feed/time/get`（最新流，同参数口径）作同源兜底。
     *
     * 匿名探针实测：两条都回 `code:200 data:{records:null, page:{cursor:"0", more:true}}`
     * —— **结构对、内容为 null 且 more=true，即「接口存在、内容需登录」**，
     * 与 `rn-fansgroup` 里 `records` 由登录态决定的行为一致。
     */
    private const val FANS_GROUP_FEED_RECOMMEND = "/api/fans/group/feed/recommend/get"
    private const val FANS_GROUP_FEED_TIME = "/api/fans/group/feed/time/get"

    /** 艺人 → 乐迷团 groupId 的解析接口（匿名可用，回包 `data.archiveActionUrl` 里带 `groupId=`） */
    private const val ARTIST_FANS_INFO = "/api/community/artist/fans/info"

    /** 动态翻页游标：artistId → 上一页给出的 cursor（空串 = 从最新一页开始） */
    private val fanPostCursor = HashMap<Long, String>()

    /** artistId → 乐迷团 groupId（一次解析长期复用，避免每页都多打一次请求） */
    private val fansGroupIdCache = HashMap<Long, String>()

    /** 一页乐迷团动态：[posts] 本页解析出的帖子，[hasMore] 是否还有下一页 */
    data class FanPage(val posts: List<ArtistPost>, val hasMore: Boolean)

    /**
     * 拉取一页乐迷团动态（`restart = true` 表示从头拉，见上方参数说明）。
     *
     * 「只拉到几条 / 一会儿就说没有更多」的两条根因（2026-09-12 真机日志定位）：
     * ① 一页里全是**无正文的转发/活动空壳**时解析结果为空，旧实现直接当成「到底了」；
     * ② 纯图片动态的正文 `json.msg` 为空、图在 `pics[]` 里，旧解析不取 `pics` → 整条被丢。
     * 现在：`pics` 补齐图片；且**本页解析为空但 `more=true` 且游标能推进时继续补拉**
     * （单次最多 4 个子请求），不再把「空页」误判成「到底」。
     *
     * @param restart true = 从头拉（清掉游标，重新取最新一页）；false = 沿当前游标续拉下一页。
     *   ⚠ 别用 offset 判「是否续拉」：空页时条数恒为 0，会一直把游标清掉在原地打转。
     * @return null = 网络/接口失败（UI 提示重试）；否则是本页结果（可能 posts 为空且 hasMore=false）
     */
    suspend fun artistDynamic(artistId: Long, limit: Int = 30, restart: Boolean = false): FanPage? =
        withContext(Dispatchers.IO) {
            if (artistId <= 0L) return@withContext null
            if (restart) {
                fanPostCursor.remove(artistId)
                eventFallbackCursor.remove(artistId)
            }
            val acc = ArrayList<ArtistPost>()
            var anyOk = false
            var hasMore = false

            // ① 主源：乐迷团动态流（artistId → groupId → feed）
            val groupId = fansGroupIdOf(artistId)
            if (!groupId.isNullOrEmpty()) {
                var guard = 0
                while (guard < 4) {
                    guard++
                    // ★ 首页 cursor 必须是 "0"，**不能是空串**：官方包里的初值就是 0
                    //   （`cursor: h<0 ? 0 : h`）。实测空串会让服务端直接回 `data.page = null`，
                    //   于是 `more`/`cursor` 全丢 —— 真机上表现为「接口通了但永远空、也不翻页」。
                    val cursor = fanPostCursor[artistId] ?: "0"
                    val j = fetchFanGroupPage(FANS_GROUP_FEED_RECOMMEND, groupId, cursor, limit) ?: break
                    anyOk = true
                    val parsed = parseFanGroupPosts(j)
                    val next = fanGroupCursorOf(j)
                    val page2 = j.optJSONObject("data")?.optJSONObject("page")
                    val more = page2?.optBoolean("more", false) ?: false
                    val advanced = next.isNotEmpty() && next != cursor
                    if (advanced) fanPostCursor[artistId] = next else fanPostCursor.remove(artistId)
                    parsed.forEach { p -> if (acc.none { it.id == p.id }) acc.add(p) }
                    hasMore = more && advanced
                    android.util.Log.i(
                        "LTDiag",
                        "fgPage gid=$groupId cur=$cursor -> n=${parsed.size} more=$more next=$next keep=${acc.size}",
                    )
                    if (parsed.isEmpty()) logChunks("fansGroupFeed cur=$cursor", j.toString())
                    // 推荐流这一页为空：先换同源「最新流」再试一次（两端口径完全一致，只是排序不同）
                    if (acc.isEmpty() && guard == 1) {
                        val alt = fetchFanGroupPage(FANS_GROUP_FEED_TIME, groupId, cursor, limit)
                        if (alt != null) {
                            val p2 = parseFanGroupPosts(alt)
                            p2.forEach { p -> if (acc.none { it.id == p.id }) acc.add(p) }
                            val n2 = fanGroupCursorOf(alt)
                            if (p2.isNotEmpty()) {
                                if (n2.isNotEmpty() && n2 != cursor) fanPostCursor[artistId] = n2
                                hasMore = alt.optJSONObject("data")?.optJSONObject("page")
                                    ?.optBoolean("more", false) ?: false
                            }
                            android.util.Log.i("LTDiag", "fgPage/time gid=$groupId -> n=${p2.size} next=$n2")
                            if (p2.isEmpty()) logChunks("fansGroupFeed/time cur=$cursor", alt.toString())
                        }
                    }
                    // records 为 null 是「未登录 / 无可见内容」的确定信号，别再原地翻页空转
                    val noRecords = j.optJSONObject("data")?.opt("records") == null ||
                        j.optJSONObject("data")?.opt("records") === JSONObject.NULL
                    if (noRecords && acc.isEmpty()) break
                    if (acc.isNotEmpty() || !hasMore) break
                }
            }

            // ② 兜底：艺人自身动态 `/api/event/get/{artistId}`（官方 NeteaseMusicApiImpl;->n3 口径）
            if (acc.isEmpty()) {
                var guard = 0
                while (guard < 3) {
                    guard++
                    val lasttime = eventFallbackCursor[artistId] ?: 0L
                    val j = fetchFanPage(artistId, lasttime, limit) ?: break
                    anyOk = true
                    val next = nextCursorOf(j)
                    val more = j.optBoolean("more", false)
                    val advanced = next > 0L && next != lasttime
                    if (advanced) eventFallbackCursor[artistId] = next
                    val parsed = parseArtistPosts(j)
                    parsed.forEach { p -> if (acc.none { it.id == p.id }) acc.add(p) }
                    hasMore = more && advanced
                    android.util.Log.i(
                        "LTDiag",
                        "fanPage lt=$lasttime -> n=${parsed.size} more=$more next=$next keep=${acc.size}",
                    )
                    if (parsed.isEmpty()) logChunks("fansPosts lt=$lasttime", j.toString())
                    if (acc.isNotEmpty() || !hasMore) break
                }
            }

            if (!anyOk) null else FanPage(acc, hasMore)
        }

    /** artistId → 乐迷团 groupId。取 `/api/community/artist/fans/info` 回包里的 `archiveActionUrl`。 */
    private fun fansGroupIdOf(artistId: Long): String? {
        fansGroupIdCache[artistId]?.let { return it }
        val j = runCatching {
            ea(ARTIST_FANS_INFO, JSONObject().put("artistId", artistId))
        }.getOrNull()
        val url = j?.optJSONObject("data")?.optString("archiveActionUrl").orEmpty()
        val g = Regex("groupId=(\\d+)").find(url)?.groupValues?.get(1)
        if (!g.isNullOrEmpty()) fansGroupIdCache[artistId] = g
        android.util.Log.i("LTDiag", "fansGroupId artist=$artistId -> ${g ?: "null"} :: ${url.take(150)}")
        return g
    }

    /** 乐迷团 feed 请求体：官方 rn-fansgroup 明文包里就是这四个参数（`fansGroupId` 不是 artistId） */
    private fun fanGroupReq(groupId: String, cursor: String, limit: Int): JSONObject = JSONObject()
        .put("fansGroupId", groupId)
        .put("artistSelf", 0)
        .put("cursor", cursor)
        .put("size", limit)

    private fun fetchFanGroupPage(path: String, groupId: String, cursor: String, limit: Int): JSONObject? =
        runCatching { ea(path, fanGroupReq(groupId, cursor, limit)) }.getOrNull()?.takeIf { ok(it) }

    /** 乐迷团 feed 一页 → 帖子列表。每条记录形如 `{id, event:{…标准 event 对象…}}`（官方包实证）。 */
    private fun parseFanGroupPosts(root: JSONObject): List<ArtistPost> {
        val arr = root.optJSONObject("data")?.optJSONArray("records") ?: return emptyList()
        val out = ArrayList<ArtistPost>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val rec = arr.optJSONObject(i) ?: continue
            // 官方卡片取的就是 `t.event`（json / pics / user / info 都在里面）；个别口径直接给平铺对象
            val ev = rec.optJSONObject("event") ?: rec
            val p = eventToPost(ev) ?: continue
            if (p.id.isNotEmpty() && seen.add(p.id)) out.add(p)
        }
        return out
    }

    /** 乐迷团 feed 下一页游标：`data.page.cursor`（服务端给，字符串） */
    private fun fanGroupCursorOf(root: JSONObject): String =
        root.optJSONObject("data")?.optJSONObject("page")?.optString("cursor").orEmpty()

    /** 弃用兜底源 `/api/event/get/{artistId}` 的游标（时间戳口径，与乐迷团 feed 的 cursor 分开存） */
    private val eventFallbackCursor = HashMap<Long, Long>()

    /** 兜底：艺人自身动态。userId 必须在**路径**里（`/api/event/get/{userId}`）。 */
    private fun fetchFanPage(artistId: Long, lasttime: Long, limit: Int): JSONObject? {
        val path = "$FAN_POST_PATH/$artistId"
        val req = JSONObject()
            .put("time", lasttime)
            .put("limit", limit)
            .put("fromRN", "true")
        val j = runCatching { ea(path, req) }.getOrNull()
        if (j == null) {
            android.util.Log.i("LTDiag", "fansPosts $path t=$lasttime -> null")
            return null
        }
        if (!ok(j)) android.util.Log.i("LTDiag", "fansPosts $path t=$lasttime -> code=${j.optInt("code")}")
        return j.takeIf { ok(it) }
    }

    /**
     * 长响应分片落日志。logcat 单行约 4KB 上限，`take(2000)` 会让关键字段
     * 刚好落在视野外（2026-09-12 排查乐迷团时反复踩）→ 按 1100 字符切段，
     * 段号递增，日志里可拼回完整 JSON。
     */
    private fun logChunks(tag: String, s: String, maxSeg: Int = 14) {
        val step = 1100
        var i = 0
        var n = 0
        while (i < s.length && n < maxSeg) {
            android.util.Log.i("LTDiag", "$tag[seg$n] ${s.substring(i, minOf(i + step, s.length))}")
            i += step
            n++
        }
    }

    /**
     * 推断下一页游标：优先取**末条动态**的时间字段（真机实测连续 10+ 页稳定推进）；
     * 末条取不到时退回响应自带的 `lasttime`（服务端给的游标）。
     */
    private fun nextCursorOf(j: JSONObject): Long {
        val arr = j.optJSONArray("events") ?: j.optJSONArray("event")
        if (arr != null && arr.length() > 0) {
            val last = arr.optJSONObject(arr.length() - 1)
            if (last != null) {
                for (k in listOf("eventTime", "showTime", "publishTime", "createTime", "time")) {
                    val v = last.optLong(k, 0L)
                    if (v > 0L) return v
                }
                val keys = last.keys()
                while (keys.hasNext()) {
                    val v = last.optLong(keys.next(), 0L)
                    if (v in 1_000_000_000_000L..2_000_000_000_000L) return v
                }
            }
        }
        return j.optLong("lasttime", 0L)
    }

    /** 乐迷团圈子入口（`/api/circle/entrance/artist/get`）：响应进日志，用于定位圈子 id */
    suspend fun artistCircleEntrance(artistId: Long): JSONObject? = withContext(Dispatchers.IO) {
        val j = runCatching {
            ea("/api/circle/entrance/artist/get", JSONObject().put("artistId", artistId))
        }.getOrNull()
        android.util.Log.i("LTDiag", "circleEntrance id=$artistId -> ${j?.toString()?.take(1200) ?: "null"}")
        j
    }

    /**
     * 动态响应 → 帖子列表。
     *
     * ⚠ **只认 `events`（正确路径返回的字段名）/ `event`（关注流旧口径）数组**：
     * 结构对不上就返回空，**绝不回落到通用深度遍历**。
     * 旧实现有一层 `walk()` 兜底，把艺人页物料和用户徽章对象里的文案（`user.commonIdentity.title
     * = "CHIEF"`、商品卡的「立即支持」）都当成了帖子 —— 这正是真机上「乐迷团第一条莫名其妙的
     * CHIEF 帖」的来源（2026-09-12 定位）。
     */
    private fun parseArtistPosts(root: JSONObject): List<ArtistPost> {
        val arr = root.optJSONArray("events") ?: root.optJSONArray("event") ?: return emptyList()
        val out = ArrayList<ArtistPost>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val p = eventToPost(e)
            if (p != null && p.id.isNotEmpty() && seen.add(p.id)) out.add(p)
        }
        return out
    }

    /** 帖子数组里的图片：`pics[].squareUrl`（方图，省流量）→ originUrl → 通用字段 */
    private fun imagesOf(o: JSONObject?): List<String> {
        o ?: return emptyList()
        val res = ArrayList<String>()
        for (k in listOf("pics", "images", "pictures", "picList")) {
            val a = o.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) {
                when (val e = a.opt(i)) {
                    is String -> if (e.startsWith("http")) res.add(e)
                    is JSONObject -> {
                        val u = listOf("squareUrl", "originUrl", "url", "picUrl", "coverUrl", "imageUrl", "src")
                            .firstNotNullOfOrNull { key ->
                                e.optString(key, "").takeIf { it.startsWith("http") }
                            }
                        if (u != null) res.add(u)
                    }
                    else -> {}
                }
            }
        }
        return res
    }

    /** 时间：兼容秒/毫秒字段，都没有时扫描任何 13 位毫秒时间戳 */
    private fun timeOf(o: JSONObject): Long {
        for (k in listOf("eventTime", "showTime", "publishTime", "createTime", "time")) {
            val v = o.optLong(k, 0L)
            if (v > 0L) return if (v < 10_000_000_000L) v * 1000L else v
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val v = o.optLong(keys.next(), 0L)
            if (v in 1_000_000_000_000L..2_000_000_000_000L) return v
        }
        return 0L
    }

    /**
     * 单条动态 → 帖子。
     *
     * 实测结构（2026-09-12 真机 `/api/event/get`）：
     * ```
     * { discussId:"a1TkYrrBodUAlwwUw", encryptUserId, id:<资源id>, type:<eventType>,
     *   eventTime:1789143658008, topEvent:false,
     *   user:{nickname, avatarUrl, commonIdentity:{title:"CHIEF"}},   ← 注意 user 里带徽章文案
     *   json:"{\"msg\":\"\",\"title\":null}",                        ← 正文；空串 = 纯图/纯分享动态
     *   pics:[{originUrl, squareUrl}],                               ← 图片动态的图在 event 顶层
     *   info:{ commentThread:{ threadId:"A_EV_2_<资源id>_<uid>", resourceType:2,
     *          resourceInfo:{id,userId,name:"动态：[图片]",eventType}, commentCount, likedCount, liked } } }
     * ```
     * 只读 event 顶层与上述**已知**子结构，不做通用递归：`identityLabels`/`pendantData`/
     * `commonIdentity` 里混着身份、徽章、活动文案，递归会造出假帖。
     */
    private fun eventToPost(e: JSONObject): ArtistPost? {
        fun str(o: JSONObject?, vararg keys: String): String {
            o ?: return ""
            for (k in keys) {
                val v = o.optString(k, "")
                if (v.isNotEmpty() && v != "null") return v
            }
            return ""
        }

        val info = e.optJSONObject("info")
        val ct = info?.optJSONObject("commentThread")
        val ri = ct?.optJSONObject("resourceInfo")
        // 互动数/点赞态：`info.commentThread` 是 /api/event/get 的口径；乐迷团 feed 直接放
        // `info.{likedCount, commentCount, liked, threadId}`（官方 rn-fansgroup 卡片就是这么取的）。
        fun numOn(o: JSONObject?, key: String): Long? =
            (o?.optLong(key, -1L) ?: -1L).let { if (it >= 0L) it else null }

        // 正文：网易云把正文塞在 `json` 字符串字段里，要再解一层
        var text = ""
        val jraw = e.optString("json", "")
        if (jraw.startsWith("{")) {
            val io = runCatching { JSONObject(jraw) }.getOrNull()
            if (io != null) text = str(io, "msg", "content", "text").trim()
        }
        // 纯分享动态（只有歌/图没有文字）用资源名兜底，形如「分享单曲：「xxx」」；
        // 但「动态：[图片]」只是纯图动态的占位名，不能当正文，否则每条图帖都会长出一行假文案
        val resName = ri?.optString("name").orEmpty()
        if (text.isEmpty() && resName.isNotEmpty() && resName != "null" &&
            !resName.startsWith("动态：") && !resName.startsWith("动态[")
        ) {
            text = resName
        }

        val imgs = imagesOf(e)
        if (text.isEmpty() && imgs.isEmpty()) return null
        // 圈子入口卡（形如「ヨルシカ的乐迷团」，无图）不是帖子
        if (imgs.isEmpty() && text.endsWith("的乐迷团")) return null

        val u = e.optJSONObject("user") ?: e.optJSONObject("socialUser")
        val nick = str(u, "nickname", "name", "userName")
        val encrypt = e.optString("encryptUserId")
        val threadId = ct?.optString("threadId").orEmpty()
            .ifEmpty { ct?.optString("id").orEmpty() }
            .ifEmpty { info?.optString("threadId").orEmpty() }
            .ifEmpty { e.optString("threadId") }
        val resourceId = e.optLong("id", 0L).takeIf { it > 0L }
            ?: (ri?.optLong("id", 0L) ?: 0L).takeIf { it > 0L }
            ?: 0L
        return ArtistPost(
            id = str(e, "discussId", "eventId")
                .ifEmpty { e.optString("id") }
                .ifEmpty { threadId }
                .ifEmpty { "h${text.hashCode()}${imgs.size}" },
            author = nick.ifEmpty {
                // 昵称取不到时用加密用户 id 尾号占位，至少能区分不同发帖人
                if (encrypt.length >= 4) "乐迷 ${encrypt.takeLast(4)}" else "乐迷团"
            },
            avatarUrl = str(u, "avatarUrl", "avatar").ifEmpty { null },
            text = text,
            images = imgs,
            timeMs = e.optLong("eventTime", 0L).takeIf { it > 0L } ?: timeOf(e),
            likeCount = numOn(ct, "likedCount") ?: numOn(info, "likedCount") ?: 0L,
            commentCount = numOn(ct, "commentCount") ?: numOn(info, "commentCount") ?: 0L,
            threadId = threadId,
            resourceId = resourceId,
            liked = (ct?.optBoolean("liked", false) ?: false) || (info?.optBoolean("liked", false) ?: false),
            authorId = ri?.optLong("userId", 0L) ?: 0L,
        )
    }

    // ---------- 动态（帖子）点赞 / 评论 ----------

    /**
     * 动态点赞 / 取消赞。
     *
     * 官方 9.5.90 dex 实证（`Lcom/netease/cloudmusic/api/impl/NeteaseMusicApiImpl;->T(...)`）：
     * 赞与取消**靠路径区分**，参数为 `threadId` + `checkToken`（取自 `Lqz0/b;->G0()`）——
     * `resource/like` 点赞、`resource/unlike` 取消；评论点赞则走
     * `v1/comment/like` / `v1/comment/unlike` 并多带一个 `commentId`。
     */
    suspend fun likeEvent(threadId: String, like: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (threadId.isEmpty()) {
            Log.i("LTDiag", "like abort threadId empty")
            return@withContext false
        }
        val tag = "like${if (like) "+" else "-"}"
        // 两条路径形态不能混：eapi 的摘要明文里必须是 /api/xxx（服务端按 /api/xxx 校验，
        // 传 /xxx 会直接 404 —— 2026-09-13 真机实测踩坑）；weapi 走 /weapi/<去 /api 的路径>。
        val bare = if (like) "/resource/like" else "/resource/unlike"
        val api = "/api$bare"

        // ★ 与 [likeComment] 同一结论（2026-10-01 真机形态矩阵实证）：
        //   eapi(os=android) **最小参数**才能过风控；带上 checkToken（=网页会话 __csrf）
        //   会被服务端判成「异常设备」→ 250「点赞异常 / 系统检测到您的设备存在安全风险」。
        //   weapi(os=pc) 同样是 250 设备风险，故不再保留这两条兜底（它们只会白费一次请求）。
        val a = eaFlt(api, JSONObject().put("threadId", threadId))
        Log.i("LTDiag", "$tag eaFlt=${a.optInt("code")}/${a.optString("msg").take(40)}")
        ok(a)
    }

    /**
     * 评论点赞 / 取消赞（`v1/comment/like` / `v1/comment/unlike`）。
     *
     * ★★ 2026-10-01 真机形态矩阵实证（6 种组合全部打到日志，见 [diagCommentLike]），
     *    **唯一能过风控的形态只有一种**：
     *
     *   | 形态 | 结果 |
     *   |---|---|
     *   | ✅ eapi(os=android) + body 仅 `{threadId, commentId}` | **code 200**（点赞/取消赞均可） |
     *   | ❌ 同上下但多带 `checkToken` | 250「点赞异常，请稍后再试！」(data.dialog=null) |
     *   | ❌ 多带 `appLogExt` | 250「系统检测到您的设备存在安全风险」(data.dialog 有值) |
     *   | ❌ weapi(os=pc) + csrf_token/checkToken | 250 设备风险 / 点赞异常 |
     *
     *   根因：`checkToken` 在官方是安全 SDK 的 `securityGetToken`（dex `Lqz0/b;->G0()`），
     *   **拿不到时绝不能拿网页会话的 `__csrf` 顶上** —— 值不对会被服务端判成异常设备并直接拒绝；
     *   写接口的 cookie 已经带了 `MUSIC_U`，服务端凭它认人，多余参数只会招风控。
     *   → 结论：**eapi 写接口一律「最小参数」，不塞 checkToken / csrf_token / appLogExt**。
     *   （旁证：同口径的 [likeSong] 一直没带 checkToken，从来就是 200。）
     */
    suspend fun likeComment(threadId: String, commentId: Long, like: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (threadId.isEmpty() || commentId <= 0L) return@withContext false
            val tag = "cmtLike${if (like) "+" else "-"}"
            val api = if (like) "/api/v1/comment/like" else "/api/v1/comment/unlike"
            // commentId 按官方口径传字符串（dex: Long.toString()）
            val a = eaFlt(
                api,
                JSONObject().put("threadId", threadId).put("commentId", commentId.toString()),
            )
            Log.i("LTDiag", "$tag eaFlt=${a.optInt("code")}/${a.optString("msg").take(40)}")
            ok(a)
        }

    // ================== 评论点赞诊断（2026-10-01 临时，DIAG_ON=false 即彻底关闭） ==================

    /** 诊断开关：定位「点赞异常，请稍后再试」(250) 的层级；定位完改回 false */
    const val DIAG_ON: Boolean = false

    private fun logDiag(name: String, j: JSONObject) {
        Log.i(
            "LTDiag",
            "[cmt] $name => code=${j.optInt("code")} msg=${j.optString("msg")} " +
                "csrf=${SessionStore.csrf ?: "NULL"} " +
                "body=${j.toString().take(420)}",
        )
    }

    /**
     * 评论点赞形态矩阵：6 种路径/参数组合一次性打完，任一成功立即 unlike 回滚。
     * 判别目标——① 写通道整体是否可用（收藏歌曲幂等对照）；② 评论点赞卡在哪一层。
     */
    suspend fun diagCommentLike(songId: Long) = withContext(Dispatchers.IO) {
        fun L(s: String) = Log.i("LTDiag", "[cmt] $s")
        L("===== DIAG START songId=$songId =====")
        L("loggedIn=${SessionStore.loggedIn} uid=${SessionStore.uid} nick=${SessionStore.nickname}")
        L("musicU=${SessionStore.musicU?.let { it.take(10) + "…len=" + it.length } ?: "NULL"}")
        L("csrf=${SessionStore.csrf ?: "NULL"}")

        logDiag("00/account-get(weapi)", we("/w/nuser/account/get", JSONObject().put("csrf_token", checkToken())))

        val thread = "R_SO_4_$songId"
        val q = JSONObject().put("threadId", thread).put("offset", 0).put("limit", 1)
            .put("beforeTime", "0").put("total", true).put("csrf_token", checkToken())
        var cm = we("/v1/resource/comments/$thread", q)
        if (!ok(cm)) cm = ea("/api/v1/resource/comments/$thread", q)
        val cid = (cm.optJSONArray("hotComments")?.optJSONObject(0)
            ?: cm.optJSONArray("comments")?.optJSONObject(0))?.optLong("commentId") ?: 0L
        val cidStr = cid.toString()
        L("01/comments code=${cm.optInt("code")} cid=$cid")

        // 写通道整体对照：收藏自己已收藏的第一首（幂等，不改动现有收藏）
        val likes = we("/song/like/get", JSONObject().put("uid", SessionStore.uid).put("limit", 1).put("offset", 0))
        val sid = if ((likes.optJSONArray("ids")?.length() ?: 0) > 0) likes.optJSONArray("ids")!!.optLong(0) else 0L
        L("02/likeList code=${likes.optInt("code")} n=${likes.optJSONArray("ids")?.length() ?: -1} sid=$sid")
        if (sid > 0L) {
            logDiag(
                "03/likeSong-eaFlt",
                eaFlt("/api/song/like", JSONObject().put("trackId", sid).put("like", true).put("time", "0")),
            )
            logDiag(
                "04/likeSong-weWrite",
                weWrite(
                    "/song/like",
                    JSONObject().put("trackId", sid).put("like", true).put("time", "0")
                        .put("csrf_token", checkToken()),
                ),
            )
        }

        if (cid <= 0L) {
            L("===== DIAG ABORT: 未取到评论 =====")
            return@withContext
        }

        // A. weapi(os=pc) 官方 web 形态（NeteaseCloudMusicApi 口径）
        val a = weWrite(
            "/v1/comment/like",
            JSONObject().put("threadId", thread).put("commentId", cidStr)
                .put("csrf_token", checkToken()).put("checkToken", checkToken()),
        )
        logDiag("A/weapi-str+csrf+tk", a)
        if (ok(a)) {
            logDiag(
                "A-undo",
                weWrite(
                    "/v1/comment/unlike",
                    JSONObject().put("threadId", thread).put("commentId", cidStr).put("csrf_token", checkToken()),
                ),
            )
            L("===== DIAG DONE: A 成功 =====")
            return@withContext
        }

        // B. weapi 只带 csrf_token（不带 checkToken）
        val b = weWrite(
            "/v1/comment/like",
            JSONObject().put("threadId", thread).put("commentId", cidStr).put("csrf_token", checkToken()),
        )
        logDiag("B/weapi-str+csrf", b)
        if (ok(b)) {
            logDiag(
                "B-undo",
                weWrite(
                    "/v1/comment/unlike",
                    JSONObject().put("threadId", thread).put("commentId", cidStr).put("csrf_token", checkToken()),
                ),
            )
            L("===== DIAG DONE: B 成功 =====")
            return@withContext
        }

        // C. weapi commentId 用数字 + 官方 appLogExt
        val c = weWrite(
            "/v1/comment/like",
            JSONObject().put("threadId", thread).put("commentId", cid)
                .put("csrf_token", checkToken()).put("appLogExt", ""),
        )
        logDiag("C/weapi-num+appLogExt", c)
        if (ok(c)) {
            logDiag(
                "C-undo",
                weWrite(
                    "/v1/comment/unlike",
                    JSONObject().put("threadId", thread).put("commentId", cidStr).put("csrf_token", checkToken()),
                ),
            )
            L("===== DIAG DONE: C 成功 =====")
            return@withContext
        }

        // D. eapi(os=pc) 官方字段全量
        val d = ea(
            "/api/v1/comment/like",
            JSONObject().put("threadId", thread).put("commentId", cidStr)
                .put("checkToken", checkToken()).put("appLogExt", ""),
        )
        logDiag("D/eapi-os=pc+全字段", d)
        if (ok(d)) {
            logDiag(
                "D-undo",
                ea("/api/v1/comment/unlike", JSONObject().put("threadId", thread).put("commentId", cidStr).put("checkToken", checkToken())),
            )
            L("===== DIAG DONE: D 成功 =====")
            return@withContext
        }

        // E. eapi(os=android) 官方 eapi 字段全量
        val e = eaFlt(
            "/api/v1/comment/like",
            JSONObject().put("threadId", thread).put("commentId", cidStr)
                .put("checkToken", checkToken()).put("appLogExt", ""),
        )
        logDiag("E/eapi-os=android+全字段", e)
        if (ok(e)) {
            logDiag(
                "E-undo",
                eaFlt("/api/v1/comment/unlike", JSONObject().put("threadId", thread).put("commentId", cidStr).put("checkToken", checkToken())),
            )
            L("===== DIAG DONE: E 成功 =====")
            return@withContext
        }

        // F. eapi(os=android) 不带 checkToken（只用 MUSIC_U）
        val f = eaFlt(
            "/api/v1/comment/like",
            JSONObject().put("threadId", thread).put("commentId", cidStr),
        )
        logDiag("F/eapi-os=android-无tk", f)
        if (ok(f)) {
            logDiag(
                "F-undo",
                eaFlt("/api/v1/comment/unlike", JSONObject().put("threadId", thread).put("commentId", cidStr)),
            )
            L("===== DIAG DONE: F 成功 =====")
            return@withContext
        }

        L("===== DIAG END: A~F 全失败 =====")
    }

    /**
     * 任意资源的评论列表（歌曲 / 动态 / 歌单…都走同一接口，区别只在 threadId）。
     * 官方 9.5.90 dex 里该路径存为 `/api/v1/resource/comments/{threadId}`（eapi 口径，
     * 匿名实测返回 code:200 + 空 comments），故主路 weapi 失败后回落 eapi。
     * 动态的 threadId 形如 `A_EV_2_{资源id}_{作者uid}`（读帖子时一并带出来）。
     */
    suspend fun commentsByThread(
        threadId: String,
        offset: Int,
        limit: Int = 20,
    ): Pair<List<CommentItem>, List<CommentItem>>? = withContext(Dispatchers.IO) {
        if (threadId.isEmpty()) return@withContext null
        val body = JSONObject()
            .put("threadId", threadId)
            .put("offset", offset).put("limit", limit)
            .put("beforeTime", "0").put("csrf_token", checkToken()).put("total", true)
        val path = "/v1/resource/comments/$threadId"
        var j = we(path, body)
        if (!ok(j)) j = ea(path, body)
        if (!ok(j)) return@withContext null
        parseCommentArray(j.optJSONArray("hotComments")) to parseCommentArray(j.optJSONArray("comments"))
    }

    /**
     * 评论数组归一化（主列表 / 楼中楼共用，两处响应条目同构）。
     * `beReplied[0].user.nickname` 非空 = 这条评论本身是「回复某人」的楼中楼。
     */
    private fun parseCommentArray(arr: JSONArray?): List<CommentItem> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = c.optJSONObject("user") ?: return@mapNotNull null
            CommentItem(
                user = u.optString("nickname"),
                avatar = u.optString("avatarUrl").takeIf { it.isNotEmpty() },
                content = c.optString("content"),
                liked = c.optInt("likedCount"),
                time = formatCommentTime(c.optLong("time")),
                id = c.optLong("commentId"),
                mine = c.optBoolean("liked", false),
                // 回复数：新版资源评论给 replyCount，旧版 /resource/comments 给 commentCount
                replyCount = c.optInt("replyCount", c.optInt("commentCount", 0)),
                replyToUser = c.optJSONArray("beReplied")?.optJSONObject(0)
                    ?.optJSONObject("user")?.optString("nickname").orEmpty(),
            )
        }
    }

    /**
     * 某条评论的回复列表（评论区二级菜单，2026-10-01 新增）。
     *
     * ★ 2026-10-01 匿名实测**纠正了旧写法**（旧实现猜的路径从来没通过）：
     *   - ❌ `/api/v1/resource/comments/reply/{threadId}/{commentId}` → **code 404「接口未找到」**
     *   - ✅ `/api/resource/comment/floor/get {parentCommentId, threadId, limit, time}`
     *     → code 200，`data.comments[]` = 楼中楼回复（与主评论条目同构），
     *     另有 `data.ownerComment`（主楼）、`data.totalCount`、`data.hasMore`（翻页游标 `data.time`）
     *   - `/api/v2/resource/comment/floor/get` 同样可用（v2 条目字段更全），作兜底
     *   匿名即可读（无需登录态），与主评论列表口径一致。
     */
    /**
     * 楼中楼一页：[total] = 该评论的回复总条数（`data.totalCount`），[items] = 本页回复。
     * total 是**楼层数的唯一可靠来源** —— 主评论列表接口不下发 replyCount（2026-10-01 实测），
     * UI 拿到它后回填，才能显示「N 条回复」。
     */
    data class CommentReplies(val total: Int, val items: List<CommentItem>)

    suspend fun commentReplies(
        threadId: String,
        commentId: Long,
        limit: Int = 20,
        time: Long = 0L,
    ): CommentReplies = withContext(Dispatchers.IO) {
        if (threadId.isEmpty() || commentId <= 0L) return@withContext CommentReplies(0, emptyList())
        val body = JSONObject()
            .put("parentCommentId", commentId)
            .put("threadId", threadId)
            .put("limit", limit)
            .put("time", time)
            .put("csrf_token", checkToken())
        var j = ea("/api/resource/comment/floor/get", body)
        if (!ok(j)) j = ea("/api/v2/resource/comment/floor/get", body)
        if (!ok(j)) j = we("/resource/comment/floor/get", body)
        if (!ok(j)) return@withContext CommentReplies(0, emptyList())
        val d = j.optJSONObject("data")
        val arr = d?.optJSONArray("comments") ?: j.optJSONArray("comments")
            ?: return@withContext CommentReplies(0, emptyList())
        val items = parseCommentArray(arr)
        CommentReplies(d?.optInt("totalCount", items.size) ?: items.size, items)
    }

    /**
     * 发表评论（动态帖子的评论）。
     *
     * 官方 9.5.90 dex 实证：
     * - 顶层评论 `CommentApiUtil;->b(...)` → `v1/resource/comments/add`
     * - 回复他人 `CommentApiUtil;->f(...)` → `v1/resource/comments/reply`，多一个 `commentId`（被回复评论 id）
     *
     * 两者键名同构：`threadId` / `resourceType`（int，官方以字符串提交）/ `resourceId` /
     * `content` / `checkToken`（官方取 `Lqz0/b;->G0()`，本实现取登录落盘的 __csrf）。
     *
     * 写操作必须登录态（匿名实测 code:301），故按「dex 口径（weapi→eapi）→ 老版 web 口径」兜底：
     * 老版用 `t`(1 发送) + `type`(资源类型) + `threadId` + `content`。
     */
    suspend fun addComment(
        threadId: String,
        resourceId: Long,
        resourceType: Int,
        content: String,
        replyCommentId: Long = 0L,
    ): Boolean = withContext(Dispatchers.IO) {
        if (threadId.isEmpty() || content.isBlank()) return@withContext false
        val dexBody = JSONObject()
            .put("threadId", threadId)
            .put("resourceId", resourceId)
            .put("resourceType", resourceType)
            .put("content", content)
            .put("checkToken", checkToken())
            .put("appLogExt", "")
        if (replyCommentId > 0L) {
            val reply = JSONObject(dexBody.toString()).put("commentId", replyCommentId)
            // eapi 摘要路径带 /api、weapi 不带（见 likeEvent 注释）
            if (ok(eaFlt("/api/v1/resource/comments/reply", reply))) return@withContext true
            if (ok(we("/v1/resource/comments/reply", reply))) return@withContext true
            if (ok(ea("/api/v1/resource/comments/reply", reply))) return@withContext true
        }
        if (ok(eaFlt("/api/v1/resource/comments/add", dexBody))) return@withContext true
        if (ok(we("/v1/resource/comments/add", dexBody))) return@withContext true
        if (ok(ea("/api/v1/resource/comments/add", dexBody))) return@withContext true
        // 回退：老版 web 口径（同一路径，参数名不同）
        val legacy = JSONObject()
            .put("t", 1).put("type", resourceType)
            .put("threadId", threadId).put("resourceId", resourceId)
            .put("content", content).put("csrf_token", checkToken())
        if (replyCommentId > 0L) legacy.put("commentId", replyCommentId)
        if (ok(we("/resource/comments/add", legacy))) return@withContext true
        // 回退②：老版 eapi 通道
        ok(ea("/api/resource/comments/add", legacy))
    }
}
