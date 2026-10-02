package com.netmusiclite.data

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 下载管理：eapi download/url/v1 取直链 → 流式落盘到应用私有 Music 目录。
 * 音频(ncm_{id}.mp3)落盘后连带歌词(.lrc)、注音(.roma)、封面(.jpg)一并保存，
 * 显示端断网时全部离线可用。states: 0未下载 / 1下载中 / 2已下载。
 * 已下载索引持久化(songs.json)，供本地音乐页与离线播放。
 */
object DownloadStore {
    private lateinit var ctx: Context
    // 与 NcmApi 一致：强制 HTTP/1.1（网易 CDN 对 HTTP/2 会出空响应/连接复位）
    private val http = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()
    private val dlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    val states = mutableStateMapOf<Long, Int>()
    // 下载管理页观测态：排队中（enqueue 后未开始的歌）/ 失败（含原因），均为可组合观察态
    val pendingQueue = mutableStateListOf<Song>()
    val failed = mutableStateMapOf<Long, FailedDownload>()
    var currentDownload by mutableStateOf<Song?>(null)
        private set
    // ⚠ 主线程（删除/UI 读）与 IO 线程（下载/落盘）并发访问，必须 ConcurrentHashMap，
    //   否则全选删除时主线程 remove 与 IO 线程 persist 遍历并发 → CME 闪退（真机实测）
    private val index = java.util.concurrent.ConcurrentHashMap<Long, Song>()
    private val persistLock = Any()
    // 并发批次入队锁（2026-09-25）：过滤+入队原子化。多批次并发触发（歌单/专辑/我喜欢
    // 同时批量下载）时，若同一首歌重复进入 pendingQueue，下载管理页 LazyColumn 会出现
    // 重复 key "pd_x" → IllegalArgumentException 直接崩溃
    private val queueLock = Any()
    // UI 观测版本号：pendingQueue/failed 每次结构性变更 +1，下载管理页读版本触发重组。
    // 300 首并发批量下载时 IO 线程高频增删 + 主线程组合遍历，直接迭代 SnapshotStateList
    // 会撞 ConcurrentModificationException（真机实测崩溃 2026-09-25）——组合遍历一律走
    // 锁内快照拷贝，绝不与 IO 并发迭代
    var pendingVersion by mutableStateOf(0)
        private set
    var failedVersion by mutableStateOf(0)
        private set

    /** 锁保护下的待下载队列快照（主线程组合遍历用） */
    fun pendingCopy(): List<Song> = synchronized(queueLock) { pendingQueue.toList() }

    /** 锁保护下的失败条目快照（主线程组合遍历用） */
    fun failedCopy(): List<FailedDownload> = synchronized(queueLock) { failed.values.toList() }

    // 暂停开关（2026-09-25）：暂停后正在下载的这首会下完，之后的不开始（留在队列）；
    // 恢复时把队列剩余整批重新走标准 enqueue 链路
    var paused by mutableStateOf(false)
        private set

    /** 暂停所有下载 */
    fun pauseAll() {
        if (!paused) {
            paused = true
            pendingVersion++ // 队列观测态变化即时刷 UI
        }
    }

    /** 恢复所有下载：队列剩余的歌清出后重新入队调度 */
    fun resumeAll() {
        if (!paused) return
        paused = false
        val leftovers = synchronized(queueLock) {
            val l = pendingQueue.toList()
            if (l.isNotEmpty()) {
                pendingQueue.clear()
                pendingVersion++
            }
            l
        }
        if (leftovers.isNotEmpty()) enqueue(leftovers)
    }

    fun init(context: Context) {
        ctx = context.applicationContext
        // 索引文件解析移后台，不阻塞启动；完成后把已下载标记补进状态表
        dlScope.launch {
            loadIndex()
            index.keys.forEach { states[it] = 2 }
            backfillCovers()
        }
    }

    /**
     * 老下载补封面：本功能上线前下载的歌没有落盘 jpg，启动后错峰串行补拉。
     * 断网/风控时首个失败即整体放弃（下次启动再试），绝不反复空跑刷请求。
     */
    private suspend fun backfillCovers() {
        delay(20_000) // 避开启动热身与 1.5s 错峰任务窗口
        for (s in index.values) {
            if (coverFile(s.id) != null) continue
            val cv = s.coverUrl?.takeIf { it.isNotBlank() && !it.startsWith("local://") } ?: continue
            if (!fetchCover(s.id, cv)) return
        }
    }

    fun dir(): File =
        (ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(ctx.filesDir, "music"))
            .apply { mkdirs() }

    private fun indexFile() = File(ctx.filesDir, "songs.json")

    private fun loadIndex() {
        runCatching {
            val j = JSONObject(indexFile().readText())
            val arr = j.optJSONArray("songs") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    val s = Song(o.optLong("id"), o.optString("t"), o.optString("ar"), o.optLong("arid"),
                        o.optString("al"), o.optLong("alid"), o.optInt("dt"), o.optString("cv").ifEmpty { null })
                    index[s.id] = s
                }
            }
        }
    }

    private fun persist() {
        val snapshot = synchronized(persistLock) { index.values.toList() }
        val j = JSONObject()
        val arr = org.json.JSONArray()
        snapshot.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("t", s.title).put("ar", s.artist).put("arid", s.artistId)
                .put("al", s.album).put("alid", s.albumId).put("dt", s.durationMs).put("cv", s.coverUrl ?: ""))
        }
        j.put("songs", arr)
        synchronized(persistLock) {
            runCatching { indexFile().writeText(j.toString()) }
        }
    }

    fun fileFor(id: Long): File? {
        val s = index[id] ?: return null
        val f = File(dir(), "ncm_${id}.mp3")
        return if (f.exists() && f.length() > 0) f else null
    }

    fun localFile(context: Context, id: Long): File? = fileFor(id)

    /** 已下载歌曲的落盘封面（随下载一并保存；无文件返回 null）。显示端断网/无 Coil 缓存时兜底用 */
    fun coverFile(id: Long): File? {
        val f = File(dir(), "ncm_${id}.jpg")
        return if (f.exists() && f.length() > 0L) f else null
    }

    /**
     * 封面 URL → ncm_{id}.jpg 落盘（500y500 压缩档，够播放页大图与取色/模糊）。
     * 失败静默返回 false：封面缺档只降级为网络 URL / 生成封面，绝不影响下载成败。
     */
    private fun fetchCover(id: Long, coverUrl: String): Boolean = runCatching {
        val url = if (coverUrl.contains('?')) coverUrl else "$coverUrl?param=500y500"
        val cf = File(dir(), "ncm_${id}.jpg")
        val tmp = File(dir(), "ncm_${id}.jpg.tmp") // 先写临时文件再改名，防中断留下半截图
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching false
            resp.body?.byteStream()?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching false
        }
        if (tmp.length() <= 0L) { tmp.delete(); return@runCatching false }
        if (cf.exists()) cf.delete()
        tmp.renameTo(cf)
        cf.exists()
    }.getOrDefault(false)

    fun songOf(id: Long): Song? = index[id]

    /**
     * lrc 时间戳小数部分 → 毫秒（1 位 ×100 / 2 位 ×10 / ≥3 位取前 3）。
     * ★ 2026-09-11 修复：旧版 padEnd(2).take(2) 后直接当毫秒加（.430s → 43ms），
     *   本地歌词行时间整体偏早最多 0.9s —— 注音（按正确时间解析）超出 ±500ms
     *   匹配容差大量丢失，表现为「发音显示不全」；当前行高亮提前、点行跳转偏移。
     */
    private fun fracMs(s: String): Long = when {
        s.isEmpty() -> 0L
        s.length == 1 -> s.toLong() * 100
        s.length == 2 -> s.toLong() * 10
        else -> s.take(3).toLong()
    }

    /**
     * 本地 lrc（随下载落盘的合并歌词：原文行 + 同时间戳译文行）→ (歌词行, 译文表)。
     * 已下载歌曲断网/省流量时歌词离线可用；无本地文件返回 null。
     */
    fun localLyric(id: Long): Pair<List<LyricLine>, Map<Long, String>>? {
        val f = File(dir(), "ncm_${id}.lrc")
        if (!f.exists() || f.length() <= 0L) return null
        return runCatching {
            val tag = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
            val lines = ArrayList<LyricLine>()
            val trans = HashMap<Long, String>()
            var prevKey = -1L
            f.readLines().forEach { raw ->
                val m = tag.find(raw) ?: return@forEach
                val key = m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 +
                    fracMs(m.groupValues[3])
                val text = tag.replace(raw, "").trim()
                if (text.isEmpty()) return@forEach
                if (key == prevKey && lines.isNotEmpty()) {
                    trans[key] = text          // 同时间戳第二行 = 译文
                } else {
                    lines.add(LyricLine(key, text))
                    prevKey = key
                }
            }
            if (lines.isEmpty()) null else lines to trans
        }.getOrNull()
    }

    fun allDownloaded(): List<Song> = index.values.sortedByDescending { it.id }

    /** 本地注音（romalrc，随下载落盘）原始文本；无文件返回 null */
    fun localRomaText(id: Long): String? {
        val f = File(dir(), "ncm_${id}.roma")
        if (!f.exists() || f.length() <= 0L) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 注音落盘（老下载首次打开歌词页时在线补拉回写，之后离线可用） */
    fun saveRoma(id: Long, text: String) {
        runCatching { File(dir(), "ncm_${id}.roma").writeText(text) }
    }

    /** 删除已下载歌曲：音频+歌词+注音+封面文件一并删除并更新索引 */
    fun delete(id: Long) {
        if (index.remove(id) == null) return
        states.remove(id)
        dlScope.launch(Dispatchers.IO) {
            runCatching {
                File(dir(), "ncm_${id}.mp3").delete()
                File(dir(), "ncm_${id}.lrc").delete()
                File(dir(), "ncm_${id}.roma").delete()
                File(dir(), "ncm_${id}.jpg").delete()
            }
            persist()
        }
    }

    /** lrc 原文 + 翻译合并：同时间戳的译文紧跟原文行；无翻译返回原文 */
    private fun mergeLrc(orig: String?, trans: String?): String? {
        if (orig.isNullOrBlank()) return null
        if (trans.isNullOrBlank()) return orig
        val tag = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        fun keyOf(line: String): Long? {
            val m = tag.find(line) ?: return null
            return m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 +
                fracMs(m.groupValues[3])
        }
        fun textOf(line: String) = tag.replace(line, "").trim()
        val tmap = HashMap<Long, String>()
        trans.lines().forEach { l ->
            val k = keyOf(l) ?: return@forEach
            val t = textOf(l)
            if (t.isNotEmpty()) tmap[k] = t
        }
        if (tmap.isEmpty()) return orig
        return buildString {
            orig.lines().forEach { l ->
                append(l).append('\n')
                val k = keyOf(l) ?: return@forEach
                tmap[k]?.let { append(tag.find(l)?.value.orEmpty()).append(it).append('\n') }
            }
        }
    }

    /** 单曲下载，返回 (成败, 失败原因)。原因尽量精确：接口拒绝 / HTTP 码 / 具体异常 */
    suspend fun download(song: Song, onDone: (Boolean) -> Unit = {}): Pair<Boolean, String?> {
        if (states[song.id] == 2) { onDone(true); return true to null }
        if (states[song.id] == 1) { onDone(false); return false to "正在下载中" }
        states[song.id] = 1
        currentDownload = song
        synchronized(queueLock) {
            if (pendingQueue.removeAll { it.id == song.id }) pendingVersion++
            if (failed.remove(song.id) != null) failedVersion++
        }
        var failReason: String? = null
        val ok = withContext(Dispatchers.IO) {
            try {
                val r = NcmApi.downloadUrl(song.id, QualityPrefs.level)
                val rawUrl = r.url
                if (rawUrl == null) {
                    failReason = r.reason
                    false
                } else {
                    // 与播放链路一致：直链 http → https（部分网络明文 HTTP 不通）
                    val url = if (rawUrl.startsWith("http://")) rawUrl.replace("http://", "https://") else rawUrl
                    val req = Request.Builder().url(url).build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            failReason = "下载源 HTTP ${resp.code}"
                            false
                        } else {
                            val body = resp.body
                            if (body == null) { failReason = "空响应"; false }
                            else {
                                val tmp = File(dir(), "ncm_${song.id}.tmp")
                                body.byteStream().use { input ->
                                    tmp.outputStream().use { output -> input.copyTo(output) }
                                }
                                val dst = File(dir(), "ncm_${song.id}.mp3")
                                if (dst.exists()) dst.delete()
                                tmp.renameTo(dst)
                                val okFile = dst.exists() && dst.length() > 0
                                if (!okFile) failReason = "落盘失败"
                                // 音频落盘成功 → 同时下载歌词+注音文件（失败不影响下载成败）
                                if (okFile) runCatching {
                                    val b = NcmApi.lyricV1(song.id)
                                    mergeLrc(b.lrc, b.trans)?.let { text ->
                                        File(dir(), "ncm_${song.id}.lrc").writeText(text)
                                    }
                                    b.roma?.takeIf { it.isNotBlank() }?.let { r ->
                                        File(dir(), "ncm_${song.id}.roma").writeText(r)
                                    }
                                }
                                // 音频落盘成功 → 封面一并落盘（失败不影响下载成败）：
                                // 断网 / 清了应用缓存后，本地封面仍可显示、取色、做模糊头图
                                if (okFile) song.coverUrl
                                    ?.takeIf { it.isNotBlank() && !it.startsWith("local://") }
                                    ?.let { fetchCover(song.id, it) }
                                okFile
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                failReason = "异常:${(e.message ?: e.javaClass.simpleName).take(80)}"
                false
            }
        }
        currentDownload = null
        if (ok) {
            index[song.id] = song
            persist()
            states[song.id] = 2
            synchronized(queueLock) { if (failed.remove(song.id) != null) failedVersion++ }
        } else {
            states[song.id] = 0
            synchronized(queueLock) {
                failed[song.id] = FailedDownload(song, failReason ?: "未知错误")
                failedVersion++
            }
        }
        onDone(ok)
        return ok to failReason
    }

    /**
     * 批量下载：用 DownloadStore 自己的后台作用域顺序执行，离开页面不会中断下载。
     * onDone(成功数, 失败数, 失败原因汇总) 在全部结束后回调。
     */
    fun enqueue(songs: List<Song>, onDone: (okCount: Int, failCount: Int, failReasons: String) -> Unit = { _, _, _ -> }) {
        if (songs.isEmpty()) return
        dlScope.launch {
            // 锁内「去重→过滤→入队」一体完成：多批次并发触发时同一首歌绝不会
            // 重复进入 pendingQueue（重复 key 会让下载管理页崩溃）
            val fresh = synchronized(queueLock) {
                songs.distinctBy { it.id }.filter {
                    states[it.id] != 2 && states[it.id] != 1 && pendingQueue.none { p -> p.id == it.id }
                }.also {
                    if (it.isNotEmpty()) {
                        pendingQueue.addAll(it)
                        pendingVersion++
                    }
                }
            }
            if (fresh.isEmpty()) return@launch
            var okN = 0
            val reasons = linkedMapOf<String, Int>()
            fresh.forEach {
                // 暂停：剩余歌曲留在队列，resumeAll 重新调度
                if (paused) return@launch
                // 他处抢先开始/完成的歌直接跳过：更多页单曲直下不经队列，批次循环
                // 撞上时会误记「正在下载中」失败条目
                if (states[it.id] == 2) return@forEach
                if (states[it.id] == 1 && currentDownload?.id != it.id) return@forEach
                val (ok, reason) = download(it)
                if (ok) okN++ else reasons[reason ?: "未知错误"] = (reasons[reason ?: "未知错误"] ?: 0) + 1
            }
            // failCount 按真实失败计数：暂停中断/跳过的歌不虚报为失败
            val failN = reasons.values.sum()
            val summary = reasons.entries.joinToString("；") { (r, n) -> if (n > 1) "$r×$n" else r }
            onDone(okN, failN, summary)
        }
    }
}
