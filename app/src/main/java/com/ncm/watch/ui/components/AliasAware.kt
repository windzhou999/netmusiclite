package com.ncm.watch.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ncm.watch.data.NcmApi
import com.ncm.watch.data.Song
import com.ncm.watch.data.SongAliasStore
import com.ncm.watch.data.SongQuery

/**
 * 跨语言搜索的统一入口：返回**已按 [filter] 过滤**的歌曲列表。
 *
 * 三层递进，越往后越贵，能省则省：
 *
 * 1. **直接匹配**（零成本）：歌名 / 艺人 / 专辑 / [Song.aliases] 按归一化双向包含匹配。
 *    收藏、每日推荐这类接口返回的歌本来就带 `tns`/`alia`，中文搜日文歌在这一层就命中了；
 *    命中缓存过译名的本地歌也在这一层。
 * 2. **查询扩展**（1 次请求）：直接匹配一首都没有时，把 [filter] 丢给服务端搜一次，
 *    拿回结果的「歌名 + 艺人」当等价关键词再匹配一遍 —— 这一步相当于把用户的中文词
 *    "翻译"成库里存着的原文写法，覆盖绝大多数场景，且无论库里有几首只花一次请求。
 * 3. **逐首别名反查**（兜底，最多 3 轮 × 8 首）：连查询扩展都没命中时，
 *    才怀疑是本地文件没译名，按「歌名 + 艺人」逐首反查并落盘缓存；上限是为了
 *    防止用户随手输个乱码就让手表一直联网，补到的结果下次搜索直接复用。
 *
 * 命中后 UI 可用 [SongQuery.matchedAlias] 拿到"命中的是哪个别名"，在副标题里标出来。
 */
@Composable
fun rememberAliasAwareSongs(songs: List<Song>, filter: String): List<Song> {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var extraKeys by remember(filter) { mutableStateOf<List<String>?>(null) }

    val withAlias = remember(songs, tick) { SongAliasStore.inject(ctx, songs) }
    val visible = remember(withAlias, filter, extraKeys) {
        if (filter.isEmpty()) {
            withAlias
        } else {
            val keys = extraKeys
            withAlias.filter { s ->
                SongQuery.matches(s, filter) || keys?.any { SongQuery.matches(s, it) } == true
            }
        }
    }

    LaunchedEffect(filter, songs) {
        if (filter.isBlank() || songs.isEmpty()) return@LaunchedEffect
        // ① 直接匹配（含已缓存译名）命中就不折腾
        if (SongAliasStore.inject(ctx, songs).any { SongQuery.matches(it, filter) }) return@LaunchedEffect
        // ② 查询扩展：一次请求，把中文词换成服务端认得的原名写法
        val keys = runCatching { NcmApi.expandQueryKeys(filter) }.getOrDefault(emptyList())
        if (keys.isNotEmpty()) {
            extraKeys = keys
            if (songs.any { s -> keys.any { SongQuery.matches(s, it) } }) return@LaunchedEffect
        }
        // ③ 逐首别名反查兜底
        var rounds = 0
        while (rounds < 3) {
            if (SongAliasStore.resolveMissing(ctx, songs).isEmpty()) break
            tick++
            rounds++
        }
    }
    return visible
}

/** 过滤便捷函数：空查询返回原列表（避免无谓分配） */
fun List<Song>.filterByQuery(query: String): List<Song> =
    if (query.isEmpty()) this else filter { SongQuery.matches(it, query) }
