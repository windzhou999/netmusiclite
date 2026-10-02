package com.ncm.watch.data

import android.content.Context

/**
 * 本地音乐的「译名/别名」索引（跨语言搜索的数据来源之一）。
 *
 * ## 为什么需要它
 * 收藏、每日推荐、歌单这些歌走网易接口，响应里本来就带 `tns`/`alia`（见 [Song.aliases]），
 * 所以能直接跨语言匹配。**本地文件是另一回事**：MediaStore 里只有文件标签（多半是日文原名），
 * 没有网易 id，也就没有译名 —— 用户输「向夜晚奔去」永远搜不到「夜に駆ける.mp3」。
 *
 * ## 做法
 * 拿「歌名 + 艺人」去老搜索接口 `GET /api/search/get/web`（**匿名可用**，与登录态无关）反查，
 * 挑歌名最像的那条取它的 `transNames`/`alias`，落盘缓存。
 *
 * ## 触发时机（省电）
 * 只在**用户真的输了关键词、且本地库里直接匹配为空**时才联网补齐，
 * 且每次最多补 [BATCH] 首、串行 + 间隔 [DELAY_MS] 毫秒，避免打满手表网络。
 * 补齐结果持久化，第二次搜同一批歌零网络开销。
 */
object SongAliasStore {
    private const val FILE = "song_alias_index"
    private const val SEP_FIELD = '\u0001' // 歌名+艺人 与 别名串 之间
    private const val SEP_ALIAS = '\u0002' // 别名之间

    /** 单次触发的最大联网反查数量（多余的下次再补，防长尾卡顿） */
    const val BATCH = 8
    private const val DELAY_MS = 260L

    private fun key(title: String, artist: String) =
        title.trim() + SEP_FIELD + artist.trim()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 已缓存的别名；返回 null 表示「还没查过」（与「查过但没有」要区分，否则会反复联网） */
    fun cached(ctx: Context, title: String, artist: String): List<String>? {
        val raw = prefs(ctx).getString(key(title, artist), null) ?: return null
        return raw.takeIf { it.isNotEmpty() }?.split(SEP_ALIAS) ?: emptyList()
    }

    private fun save(ctx: Context, title: String, artist: String, aliases: List<String>) {
        prefs(ctx).edit()
            .putString(key(title, artist), aliases.joinToString(SEP_ALIAS.toString()))
            .apply()
    }

    /** 已缓存的数量（UI 可用来判断是否还有东西可补） */
    fun cachedCount(ctx: Context, songs: List<Song>): Int {
        val all = readAll(ctx)
        return songs.count { all[key(it.title, it.artist)] != null }
    }

    /**
     * 把缓存到的别名并入歌曲（组合期调用，所以**只读一次 prefs** 再在内存里查，
     * 不能每首一次 getString —— 那会在主线程上做 N 次 IO）。
     * 接口返回的歌已自带 [Song.aliases] 时原样保留，缓存只补空的那种。
     */
    fun inject(ctx: Context, songs: List<Song>): List<Song> {
        val all = readAll(ctx)
        if (all.isEmpty()) return songs
        var changed = false
        val out = songs.map { s ->
            if (s.aliases.isNotEmpty()) return@map s
            val raw = all[key(s.title, s.artist)] as? String
            if (raw.isNullOrEmpty()) {
                s
            } else {
                changed = true
                s.copy(aliases = raw.split(SEP_ALIAS))
            }
        }
        return if (changed) out else songs
    }

    private fun readAll(ctx: Context): Map<String, *> =
        runCatching { prefs(ctx).all }.getOrNull() ?: emptyMap<String, Any>()

    /**
     * 挑出「还没查过、也没自带别名」的歌，联网补齐并落盘，返回**新增**的 歌名+艺人 → 别名 映射。
     *
     * 只处理标题里含非中文字符的歌：纯中文标题本来就是用户会输的形态，
     * 反查带来的收益极小，直接跳过省流量。
     */
    suspend fun resolveMissing(ctx: Context, songs: List<Song>, batch: Int = BATCH): Map<String, List<String>> {
        val all = readAll(ctx)
        val pending = songs
            .filter { it.aliases.isEmpty() && all[key(it.title, it.artist)] == null }
            .filter { it.title.any { ch -> !isCjk(ch) } }
            .take(batch)
        if (pending.isEmpty()) return emptyMap()

        val added = LinkedHashMap<String, List<String>>()
        for (song in pending) {
            val aliases = runCatching { lookup(ctx, song.title, song.artist) }.getOrDefault(emptyList())
            save(ctx, song.title, song.artist, aliases)
            if (aliases.isNotEmpty()) added[key(song.title, song.artist)] = aliases
            kotlinx.coroutines.delay(DELAY_MS)
        }
        return added
    }

    /**
     * 单首反查：优先用「歌名 艺人」（更准），没结果再退化成「歌名」。
     * 只接受歌名与本地标题高度一致（归一化后互为包含）的候选，避免张冠李戴把别的歌的译名挂上来。
     */
    private suspend fun lookup(ctx: Context, title: String, artist: String): List<String> {
        val art = artist.substringBefore('/').substringBefore('&').trim()
        val queries = buildList {
            if (art.isNotEmpty() && art != "未知艺人") add("$title $art")
            add(title)
        }
        val localNorm = SongQuery.normalize(title)
        for (q in queries) {
            val hit = NcmApi.aliasCandidates(q, limit = 5).firstOrNull { (name, _) ->
                val n = SongQuery.normalize(name)
                n.isNotEmpty() && localNorm.isNotEmpty() &&
                    (n.contains(localNorm) || localNorm.contains(n))
            }
            if (hit != null && hit.second.isNotEmpty()) return hit.second
        }
        return emptyList()
    }

    /** CJK 统一表意文字（判断标题是不是「中文可搜」的形态） */
    private fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF
}
