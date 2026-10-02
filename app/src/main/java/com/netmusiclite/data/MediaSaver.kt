package com.netmusiclite.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 保存图片到「下载」文件夹。
 * - API 29+：MediaStore.Downloads（免存储权限，系统媒体库可见）
 * - API 26~28：外部存储公共 Download 目录直写（需 WRITE_EXTERNAL_STORAGE，
 *   manifest 已按 maxSdkVersion=28 声明）
 * 文件名：NetMusicLite_<歌名>_<艺人>_<时间戳>.jpg（非法字符统一替换为下划线）
 */
object MediaSaver {

    fun saveCover(ctx: Context, bmp: Bitmap, title: String, artist: String): Boolean =
        runCatching {
            val base = buildString {
                append("NetMusicLite")
                if (title.isNotBlank()) append("_").append(sanitize(title))
                if (artist.isNotBlank()) append("_").append(sanitize(artist))
                append("_").append(System.currentTimeMillis())
            }
            val name = "$base.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } ?: return@runCatching false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists() && !dir.mkdirs()) return@runCatching false
                File(dir, name).outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                true
            }
        }.getOrDefault(false)

    private fun sanitize(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").trim().take(48).ifBlank { "cover" }
}
