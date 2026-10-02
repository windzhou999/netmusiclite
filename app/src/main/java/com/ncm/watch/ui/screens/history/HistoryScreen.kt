package com.ncm.watch.ui.screens.history

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.ncm.watch.data.HistoryStore
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.ui.components.CenterHint
import com.ncm.watch.ui.components.StackedCardList
import com.ncm.watch.ui.components.StackedSongCard
import com.ncm.watch.ui.nav.Routes
import com.ncm.watch.ui.nav.NavMotionKind
import com.ncm.watch.ui.nav.NavMotionSpec
import com.ncm.watch.ui.nav.navigateWithMotion

/** 最近播放：本地播放历史（HistoryStore 记录，去重置顶），点歌即播整列 */
@Composable
fun HistoryScreen(nav: NavHostController) {
    val songs = HistoryStore.recent
    StackedCardList(title = "最近播放", progressTotal = songs.size, cardHeight = 56.dp, horizontalInsetPx = 10) {
        if (songs.isEmpty()) {
            item(key = "empty") {
                CenterHint("还没有播放记录\n放几首歌试试", Modifier.fillMaxWidth().height(240.dp))
            }
        } else {
            itemsIndexed(songs, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                StackedSongCard(
                    song = song,
                    isCurrent = PlayerEngine.current?.id == song.id,
                    onClick = {
                        PlayerEngine.playQueue(songs.toList(), i)
                        nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
                    },
                )
            }
        }
    }
}
