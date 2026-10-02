package com.ncm.watch.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面数据缓存（2026-09-04 预加载专项）：
 * - 我喜欢 / 每日推荐 / 我创建的歌单 / 专辑与歌单详情：进过一次就吃缓存，不再每次联网拉取；
 * - 只有"有新的东西"才失效：红心变化清我喜欢缓存；收藏/新建歌单清歌单缓存；
 * - 每日推荐按自然日缓存（官方口径一天一换）；
 * - 专辑/歌单详情走 LRU（12 条）防内存膨胀；
 * - prefetch() 在启动时后台预拉，用户进页面即有数据、秒开。
 * 全部是会话级内存缓存（进程被杀即清），不落盘。
 */
object PageCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- 我喜欢 ----------
    @Volatile private var likedUid = -1L
    @Volatile private var liked: List<Song>? = null

    /** 红心变化后调用：下次进「我喜欢」重新拉取 */
    fun invalidateLiked() { liked = null }

    suspend fun likedSongs(uid: Long): List<Song> {
        if (uid == likedUid) liked?.let { return it }
        val fresh = runCatching { NcmApi.likedSongs(uid) }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) {
            liked = fresh; likedUid = uid
            // 成功拉取即落盘快照：断网时「我喜欢」显示上次列表
            withContext(Dispatchers.IO) { OfflineCache.saveLiked(fresh) }
        } else if (liked == null) {
            // 本次拉取失败（断网/接口异常）→ 上次的缓存快照兜底
            OfflineCache.loadLiked()?.let { cached -> liked = cached; likedUid = uid }
        }
        return liked ?: fresh
    }

    // ---------- 每日推荐（按自然日缓存）----------
    @Volatile private var dailyDay = ""
    @Volatile private var daily: List<Song>? = null

    suspend fun dailyRecommend(): List<Song> {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        if (today == dailyDay) daily?.let { return it }
        val fresh = NcmApi.dailyRecommend()
        if (fresh.isNotEmpty()) { daily = fresh; dailyDay = today }
        return fresh
    }

    // ---------- 歌单列表（新建/收藏后失效）----------
    @Volatile private var playlistsUid = -1L
    @Volatile private var playlists: List<PlaylistItem>? = null

    fun invalidatePlaylists() { playlists = null }

    suspend fun userPlaylists(uid: Long): List<PlaylistItem> {
        if (uid == playlistsUid) playlists?.let { return it }
        val fresh = NcmApi.userPlaylists(uid)
        if (fresh.isNotEmpty()) { playlists = fresh; playlistsUid = uid }
        return fresh
    }

    // ---------- 收藏的专辑（2026-09-30：「我创建的歌单」页新增「收藏的专辑」分栏）----------
    // 与歌单列表同款会话级缓存：进过一次就吃缓存，收藏/取消收藏后由 invalidate 清理
    @Volatile private var albumsUid = -1L
    @Volatile private var albums: List<AlbumItem>? = null

    fun invalidateAlbumSublist() { albums = null }

    suspend fun albumSublist(uid: Long): List<AlbumItem> {
        if (uid == albumsUid) albums?.let { return it }
        val fresh = NcmApi.albumSublist()
        if (fresh.isNotEmpty()) { albums = fresh; albumsUid = uid }
        return fresh
    }

    // ---------- 专辑 / 歌单详情（LRU，最多 12 条）----------
    private const val DETAIL_MAX = 12
    private val details = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean =
            size > DETAIL_MAX
    }

    suspend fun albumDetail(id: Long): Triple<String, String?, List<Song>>? {
        synchronized(details) {
            (details["al:$id"] as? Triple<String, String?, List<Song>>)?.let { return it }
        }
        val fresh = NcmApi.albumDetail(id) ?: return null
        synchronized(details) { details["al:$id"] = fresh }
        return fresh
    }

    suspend fun playlistDetail(id: Long): Pair<PlaylistItem, List<Song>>? {
        synchronized(details) {
            (details["pl:$id"] as? Pair<PlaylistItem, List<Song>>)?.let { return it }
        }
        val fresh = NcmApi.playlistDetail(id) ?: return null
        synchronized(details) { details["pl:$id"] = fresh }
        return fresh
    }

    // ---------- 听歌排行（10 分钟 TTL：页面秒开，又不至于「听完歌次数不变」。
    //  旧版 12 小时 + scrobble 后不失效 → 刚听完进排行页次数纹丝不动，像统计坏了） ----------
    private const val RECORD_TTL_MS = 10L * 60 * 1000
    private val records = HashMap<String, Pair<Long, List<PlayRecord>>>()

    suspend fun userRecord(uid: Long, type: Int): List<PlayRecord> {
        // key 用自愈后的 uid：uid=0 期间 heal 成功的话，缓存直接挂到真实 uid 名下
        val u = NcmApi.ensureUid().takeIf { it > 0L } ?: uid
        val key = "${u}_$type"
        val hit = synchronized(records) { records[key] }
        if (hit != null && System.currentTimeMillis() - hit.first < RECORD_TTL_MS) return hit.second
        val fresh = NcmApi.userRecord(u, type)
        if (fresh.isNotEmpty()) synchronized(records) { records[key] = System.currentTimeMillis() to fresh }
        return fresh
    }

    /** 听歌上报（scrobble）成功后调用：排行缓存失效，下次进页即见最新次数 */
    fun invalidateRecord() { synchronized(records) { records.clear() } }

    // ---------- 热搜榜（会话级缓存） ----------
    @Volatile private var hotSearch: List<HotSearch>? = null

    suspend fun searchHot(): List<HotSearch> {
        hotSearch?.let { return it }
        val fresh = NcmApi.searchHot()
        if (fresh.isNotEmpty()) hotSearch = fresh
        return fresh
    }

    /** 收藏歌曲进某歌单后调用：该歌单详情下次重新拉取 */
    fun invalidatePlaylistDetail(id: Long) { synchronized(details) { details.remove("pl:$id") } }

    // ---------- 好友 / 私信会话（30s TTL + 秒显快照）----------
    // 旧版 FriendsScreen 每次返回都白屏等「好友列表 + 私信会话」两个串行网络请求（约 1s）。
    // 现在进页先吃 cachedFriends/cachedSessions 快照秒显，LaunchedEffect 再用 TTL 包装刷新。
    private const val SOCIAL_TTL_MS = 30L * 1000
    @Volatile private var friendsUid = -1L
    @Volatile private var friendsAt = 0L
    @Volatile private var friendsList: List<FriendInfo> = emptyList()
    @Volatile private var sessionsAt = 0L
    @Volatile private var sessionsList: List<ChatSession> = emptyList()

    /** 进页即显的缓存快照（null = 从未拉取过，仍需显示 loading） */
    fun cachedFriends(uid: Long): List<FriendInfo>? =
        friendsList.takeIf { uid == friendsUid && it.isNotEmpty() }

    fun cachedSessions(): List<ChatSession>? = sessionsList.takeIf { it.isNotEmpty() }

    /** 发送消息/分享歌曲后调用：会话的 lastText/未读已变化，下次强制重拉 */
    fun invalidateMsgSessions() { sessionsList = emptyList(); sessionsAt = 0L }

    suspend fun friends(uid: Long): List<FriendInfo> {
        if (uid == friendsUid && friendsList.isNotEmpty() &&
            System.currentTimeMillis() - friendsAt < SOCIAL_TTL_MS) return friendsList
        val fresh = NcmApi.friends(uid)
        if (fresh.isNotEmpty()) {
            friendsList = fresh; friendsUid = uid; friendsAt = System.currentTimeMillis()
        }
        return fresh.ifEmpty { friendsList }
    }

    /** 同 msgSessions()，但保留失败信号（null）：MessagesScreen 需要区分「无会话」与「接口不可用」 */
    suspend fun msgSessionsRaw(): List<ChatSession>? {
        val fresh = NcmApi.msgSessions()
        if (fresh != null) { sessionsList = fresh; sessionsAt = System.currentTimeMillis() }
        return fresh
    }

    suspend fun msgSessions(): List<ChatSession> {
        if (sessionsList.isNotEmpty() && System.currentTimeMillis() - sessionsAt < SOCIAL_TTL_MS) {
            return sessionsList
        }
        return msgSessionsRaw().orEmpty().ifEmpty { sessionsList }
    }

    /** 启动预加载：我喜欢/歌单/每日推荐后台预热，进页面秒开 */
    fun prefetch() {
        val uid = SessionStore.uid
        if (uid <= 0L) return
        scope.launch {
            runCatching { likedSongs(uid) }
            runCatching { userPlaylists(uid) }
            runCatching { dailyRecommend() }
        }
    }
}
