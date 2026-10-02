package com.netmusiclite.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 本地音乐：扫描设备媒体库 + 合并应用内已下载歌曲 */
object LocalMusic {

    suspend fun scan(ctx: Context): List<Song> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Song>()
        val collection: Uri = if (android.os.Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        runCatching {
            val proj = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
            )
            ctx.contentResolver.query(collection, proj,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iAr = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iAlId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                while (c.moveToNext()) {
                    val dur = c.getLong(iD)
                    if (dur < 30_000) continue
                    val uri = ContentUris.withAppendedId(collection, c.getLong(iId))
                    out.add(Song(
                        id = -c.getLong(iId), // 负 id 区分本地
                        title = c.getString(iT) ?: "未知",
                        artist = c.getString(iAr) ?: "未知艺人",
                        artistId = 0,
                        album = c.getString(iAl) ?: "",
                        albumId = 0,
                        durationMs = dur.toInt(),
                        coverUrl = "local://$uri",
                    ))
                }
            }
        }
        // 合并应用内下载（保留线上 id，可跳艺人/专辑）
        val downloaded = DownloadStore.allDownloaded()
            .filter { d -> out.none { it.title == d.title && it.artist == d.artist } }
        (downloaded + out).distinctBy { it.title + it.artist }
    }

    /** coverUrl 形如 local://<uri> 时取回 uri */
    fun localUri(song: Song): Uri? =
        song.coverUrl?.takeIf { it.startsWith("local://") }?.removePrefix("local://")?.let(Uri::parse)
}
