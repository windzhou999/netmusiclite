package com.ncm.watch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 离线兜底缓存（2026-09-04）：
 * 断网时「我喜欢」回退到上次成功拉取的列表（files/liked_cache.json）。
 * 只在网络成功时覆写；断网读到的是缓存快照，联后自动刷新。
 */
object OfflineCache {
    private lateinit var ctx: Context

    fun init(c: Context) { ctx = c.applicationContext }

    private fun likedFile() = File(ctx.filesDir, "liked_cache.json")

    private fun songJson(s: Song) = JSONObject()
        .put("id", s.id).put("t", s.title).put("ar", s.artist).put("arid", s.artistId)
        .put("al", s.album).put("alid", s.albumId).put("dt", s.durationMs)
        .put("cv", s.coverUrl ?: "")

    private fun songFrom(o: JSONObject) = Song(
        o.optLong("id"), o.optString("t"), o.optString("ar"), o.optLong("arid"),
        o.optString("al"), o.optLong("alid"), o.optInt("dt"),
        o.optString("cv").ifEmpty { null },
    )

    fun saveLiked(songs: List<Song>) {
        runCatching {
            val arr = JSONArray()
            songs.forEach { arr.put(songJson(it)) }
            val tmp = File(ctx.filesDir, "liked_cache.tmp")
            tmp.writeText(JSONObject().put("songs", arr).toString())
            tmp.renameTo(likedFile())
        }
    }

    /** 断网兜底：返回上次缓存的我喜欢，从未缓存过返回 null */
    fun loadLiked(): List<Song>? {
        val f = likedFile()
        if (!f.exists()) return null
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("songs") ?: return null
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { runCatching { songFrom(it) }.getOrNull() }
            }
        }.getOrNull()
    }
}
