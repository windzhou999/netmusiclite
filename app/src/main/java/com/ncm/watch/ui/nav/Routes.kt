package com.ncm.watch.ui.nav

object Routes {
    const val LOGIN_QR = "login/qr"
    const val HOME = "home"
    const val SEARCH = "search"
    /** 带预填关键词的搜索 */
    const val SEARCH_Q = "search?q={q}"
    fun searchQ(q: String): String {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        return "search?q=$enc"
    }
    const val DAILY_RECOMMEND = "daily_recommend"
    const val LIKED_SONGS = "liked_songs"
    const val MY_PLAYLISTS = "my_playlists"
    const val LOCAL_MUSIC = "local_music"
    const val PRIVATE_ROAM = "private_roam"
    const val FRIENDS = "friends"
    const val SETTINGS = "settings"
    const val PLAY_HISTORY = "play_history"
    const val DOWNLOAD_MGR = "download_manager"
    const val QUALITY = "settings/quality"

    const val USER_DETAIL = "user/{userId}"
    fun userPage(id: Long) = "user/$id"
    const val RECORD = "record"
    const val SLEEP_TIMER = "sleep_timer"

    const val ALBUM_DETAIL = "album/{albumId}"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    fun albumDetail(id: Long) = "album/$id"
    fun playlistDetail(id: Long) = "playlist/$id"

    const val ARTIST_DETAIL = "artist/{artistId}"

    /** 艺人乐迷团（帖子流：可点赞、可进帖子看评论/发评论） */
    const val ARTIST_FANS = "artist_fans/{artistId}"
    fun artistDetail(id: Long) = "artist/$id"

    /** 乐迷团帖子详情（帖子 + 评论 + 发评论）；帖子本体走 FanPostBus 内存交接，不带路由参数 */
    const val FAN_POST = "fan_post"

    const val COMMENTS = "comments/{songId}"
    fun comments(songId: Long) = "comments/$songId"

    const val PLAYER = "player"
    const val LYRICS = "lyrics"
    const val MORE = "more"
    const val QUEUE = "queue"
    const val SONG_BAIKE = "baike/{songId}"
    fun songBaikePage(songId: Long) = "baike/$songId"
    const val COLLECT = "collect"

    const val LISTEN_TOGETHER = "listen_together"
    const val LISTEN_ROOM = "listen_room"

    const val FRIEND_CHAT = "friend_chat/{friendId}"
    fun friendChat(id: Long) = "friend_chat/$id"

    /** 私信会话列表（最近联系人） */
    const val MESSAGES = "messages"

    /** 分享当前歌曲 → 好友选择 */
    const val SHARE_PICK = "share_pick"

    /** 主题设置：明暗模式 / 自动取色 / 种子色 / 背景与玻璃材质（2026-10-01 合并了原「背景与玻璃」页） */
    const val THEME = "settings/theme"
    const val DISCLAIMER = "settings/disclaimer"
    const val CONSENT = "consent"
    const val ACCOUNT = "settings/account"
    const val CACHE_CLEAN = "settings/cache"
    const val SWITCH_ACCOUNT = "settings/switch_account"
    const val ADD_ACCOUNT_QR = "settings/add_account_qr"
}
