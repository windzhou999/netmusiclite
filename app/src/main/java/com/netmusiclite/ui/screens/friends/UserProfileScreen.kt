package com.netmusiclite.ui.screens.friends

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PlaylistItem
import com.netmusiclite.data.UserDetail
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary

/** 他人主页：头像昵称 + 累计听歌 + Ta 的公开歌单，点歌单进歌单详情 */
@Composable
fun UserProfileScreen(nav: NavHostController, uid: Long) {
    var detail by remember { mutableStateOf<UserDetail?>(null) }
    var playlists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(uid) {
        detail = runCatching { NcmApi.userDetail(uid) }.getOrNull()
        playlists = runCatching { NcmApi.userPlaylists(uid) }.getOrDefault(emptyList())
        loading = false
    }

    StackedCardList(
        title = detail?.nickname ?: "用户主页",
        titleSubtitle = "Ta 的歌单 ${playlists.size}",
        progressTotal = playlists.size + 1,
    ) {
        item(key = "header") {
            StackedCard(height = 76.dp) {
                CoverImage(detail?.avatarUrl, 42.dp, shape = CircleShape)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        detail?.nickname ?: "加载中…",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1,
                    )
                    Text(
                        "累计听歌 ${detail?.listenSongs ?: 0} 首 · Lv.${detail?.level ?: "-"}",
                        fontSize = 9.sp, color = TextSecondary,
                    )
                }
            }
        }
        if (!loading && playlists.isEmpty()) {
            item(key = "empty") {
                CenterHint("没有可见的歌单", Modifier.fillMaxWidth().height(200.dp))
            }
        }
        itemsIndexed(playlists, key = { _, p -> p.id }, contentType = { _, _ -> "playlist" }) { _, pl ->
            StackedCard(
                onClick = { nav.navigateWithMotion(Routes.playlistDetail(pl.id)) },
                navMotionKind = NavMotionKind.Pill,
            ) {
                CoverImage(pl.coverUrl, 38.dp, shape = RoundedCornerShape(8.dp))
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(pl.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pl.trackCount} 首", fontSize = 9.sp, color = TextSecondary)
                }
            }
        }
    }
}
