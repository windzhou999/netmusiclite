package com.netmusiclite.ui.screens.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.netmusiclite.data.PageCache
import com.netmusiclite.data.PlayRecord
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.SongCover
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.AccentSoft
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary

/** 听歌排行：本周 / 所有时间，每项显示播放次数（数据走 PageCache，10 分钟内不重拉；
 *  每次听歌上报成功会主动失效缓存，进页即见最新次数） */
@Composable
fun RecordScreen(nav: NavHostController) {
    var type by remember { mutableStateOf(1) } // 1 本周 / 0 全部时间
    var records by remember { mutableStateOf<List<PlayRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(type) {
        loading = true
        records = PageCache.userRecord(SessionStore.uid, type)
        loading = false
    }

    StackedCardList(title = "听歌排行", progressTotal = records.size + 2, horizontalInsetPx = 10) {
        item(key = "switch") {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                listOf(1 to "本周", 0 to "全部时间").forEach { (t, label) ->
                    val selected = type == t
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = if (selected) Accent else TextPrimary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) AccentSoft else SurfaceGlass)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { type = t }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (loading) {
            item { CenterHint("加载中…", Modifier.fillMaxWidth().height(200.dp)) }
        } else if (records.isEmpty()) {
            item { CenterHint("暂无播放记录\n多听几首歌就会出现在这里", Modifier.fillMaxWidth().height(200.dp)) }
        } else {
            itemsIndexed(
                records,
                key = { i, r -> "${type}_${r.song.id}_$i" },
                contentType = { _, _ -> "record" },
            ) { i, r ->
                StackedCard(onClick = {
                    // ★ 2026-09-27：旧版是 PlayerEngine.playOne(r.song) —— 队列里只有这一首，
                    //   播放页「列表播放」因此只显示 1 首、播完也只能单曲原地循环。
                    //   改为把当前榜单整列入队（本周/全部时间 = 界面正在显示的这一份），
                    //   起播下标按 id 定位：playQueue 内部会过滤 id=0 的解析残缺项，
                    //   直接沿用列表下标在极端情况下会指到别的歌。
                    val list = records.map { it.song }.filter { it.id != 0L }
                    val startAt = list.indexOfFirst { it.id == r.song.id }.coerceAtLeast(0)
                    PlayerEngine.playQueue(list, startAt)
                    nav.navigateWithMotion(Routes.PLAYER)
                }, navMotionKind = NavMotionKind.Pill) {
                    Text(
                        "${i + 1}",
                        fontSize = if (i < 3) 16.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (i < 3) Accent else TextSecondary,
                        modifier = Modifier.width(26.dp),
                    )
                    SongCover(r.song, 38.dp, shape = RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.song.title, fontSize = 12.sp, color = TextPrimary,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${r.song.artist} · 播放 ${r.playCount} 次",
                            fontSize = 9.sp, color = TextSecondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
