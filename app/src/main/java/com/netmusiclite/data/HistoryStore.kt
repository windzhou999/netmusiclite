package com.netmusiclite.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import java.io.File

/**
 * 最近播放 + 本地听歌统计：load 即记录（去重置顶，上限 100 条展示，json 落盘）。
 * 另维护每首歌的本地累计次数与首次播放时间（stats 随 json 持久化，
 * 不随展示列表裁剪丢失）——歌曲百科的「听歌数据」在服务端排行（top1000）没覆盖到时，
 * 以「本机播放」名义展示这份独立计数（口径 = 起播即 +1，与服务端打卡不同源，勿混用）。
 */
object HistoryStore {
    private var file: File? = null
    private val lock = Any()
    val recent = mutableStateListOf<Song>()
    private val counts = mutableStateMapOf<Long, Int>()
    private val firstTs = mutableStateMapOf<Long, Long>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "play_history.json")
        // 冷启动优化：读盘+JSON 解析移后台线程，解析完回主线程合并进 Compose 状态。
        // 加载期间 record() 新写入的条目按 id 去重保留，不会被磁盘旧数据顶掉。
        Thread {
            val j = runCatching { org.json.JSONObject(file!!.readText()) }.getOrNull() ?: return@Thread
            val diskSongs = ArrayList<Song>()
            val arr = j.optJSONArray("songs")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching {
                        diskSongs.add(Song(o.optLong("id"), o.optString("t"), o.optString("ar"), o.optLong("arid"),
                            o.optString("al"), o.optLong("alid"), o.optInt("dt"), o.optString("cv").ifEmpty { null }))
                    }
                }
            }
            val diskCounts = HashMap<Long, Int>()
            val diskFirst = HashMap<Long, Long>()
            val stats = j.optJSONObject("stats")
            if (stats != null) {
                stats.keys().forEach { id ->
                    val o = stats.optJSONObject(id) ?: return@forEach
                    val key = id.toLongOrNull() ?: return@forEach
                    o.optInt("count", 0).takeIf { it > 0 }?.let { diskCounts[key] = it }
                    o.optLong("firstTs", 0L).takeIf { it > 0 }?.let { diskFirst[key] = it }
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                synchronized(lock) {
                    // 倒序头插 + id 去重：磁盘顺序保持在前，加载窗口内 record 的新条目仍置顶
                    for (i in diskSongs.indices.reversed()) {
                        val s = diskSongs[i]
                        if (recent.none { it.id == s.id }) recent.add(0, s)
                    }
                    while (recent.size > 100) recent.removeAt(recent.lastIndex)
                    diskCounts.forEach { (k, v) -> if (counts[k] == null) counts[k] = v }
                    diskFirst.forEach { (k, v) -> if (firstTs[k] == null) firstTs[k] = v }
                }
            }
        }.start()
    }

    fun record(song: Song) {
        if (song.id <= 0) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            recent.indexOfFirst { it.id == song.id }.takeIf { it >= 0 }?.let { recent.removeAt(it) }
            recent.add(0, song)
            while (recent.size > 100) recent.removeAt(recent.lastIndex)
            counts[song.id] = (counts[song.id] ?: 0) + 1
            val prev = firstTs[song.id]
            if (prev == null || now < prev) firstTs[song.id] = now
            persist()
        }
    }

    /** 本地累计次数（无记录为 0） */
    fun playCountOf(id: Long): Int = counts[id] ?: 0

    /** 本地首次播放时间（无记录为 null） */
    fun firstTsOf(id: Long): Long? = firstTs[id]

    fun clear() {
        synchronized(lock) {
            recent.clear()
            counts.clear()
            firstTs.clear()
            runCatching { file?.delete() }
        }
    }

    private fun persist() {
        val j = org.json.JSONObject()
        val arr = org.json.JSONArray()
        recent.forEach { s ->
            arr.put(org.json.JSONObject().put("id", s.id).put("t", s.title).put("ar", s.artist).put("arid", s.artistId)
                .put("al", s.album).put("alid", s.albumId).put("dt", s.durationMs).put("cv", s.coverUrl ?: ""))
        }
        j.put("songs", arr)
        val stats = org.json.JSONObject()
        counts.keys.forEach { id ->
            stats.put(id.toString(), org.json.JSONObject()
                .put("count", counts[id] ?: 0)
                .put("firstTs", firstTs[id] ?: 0L))
        }
        j.put("stats", stats)
        runCatching { file?.writeText(j.toString()) }
    }
}
