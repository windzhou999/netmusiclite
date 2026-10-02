package com.netmusiclite.ui.screens.local

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.SongCover
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary

/**
 * 下载管理：下载中 / 排队中 / 失败 三段队列。
 * 数据来自 DownloadStore 的可组合观测态（currentDownload/pendingQueue/failed），状态变化自动刷新。
 * 失败条目点击即重新入队（-110 需 VIP / -105 登录失效 等原因直接展示在卡片上）。
 */
@Composable
fun DownloadManagerScreen(nav: NavHostController) {
    // 先读版本号建立重组观测，再取锁内快照拷贝遍历——300 首并发批量下载时 IO 线程
    // 高频增删队列/失败表，直接迭代 SnapshotStateList 会 CME 崩溃（2026-09-25）
    val pv = DownloadStore.pendingVersion
    val fv = DownloadStore.failedVersion
    val downloading = listOfNotNull(DownloadStore.currentDownload)
    val pending = DownloadStore.pendingCopy()
    val failed = DownloadStore.failedCopy()
    val total = downloading.size + pending.size + failed.size

    Box {
        StackedCardList(title = "下载管理", progressTotal = total, cardHeight = 56.dp) {
            if (total == 0) {
                item(key = "empty") {
                    CenterHint("没有下载任务\n在歌单/艺人页长按多选即可批量下载", Modifier.fillMaxWidth().height(240.dp))
                }
            } else {
                if (downloading.isNotEmpty()) {
                    item(key = "sec_dl") { SectionLabel("下载中") }
                    downloading.forEach { s ->
                        item(key = "dl_${s.id}") { TaskCard(s, "下载中…") }
                    }
                }
                if (pending.isNotEmpty()) {
                    item(key = "sec_pd") { SectionLabel("排队中") }
                    pending.forEach { s ->
                        item(key = "pd_${s.id}") { TaskCard(s, "排队中") }
                    }
                }
                if (failed.isNotEmpty()) {
                    item(key = "sec_fl") { SectionLabel("失败") }
                    failed.forEach { f ->
                        item(key = "fl_${f.song.id}") {
                            TaskCard(f.song, f.reason, reasonTint = true, onClick = {
                                DownloadStore.enqueue(listOf(f.song))
                            })
                        }
                    }
                }
            }
        }
        // 悬浮暂停/继续胶囊（标题正下方，不与标题重叠）：批量下载时一键暂停，再点恢复剩余队列
        if (total > 0) {
            val pillMod = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.5.dp) // 50dp + 5px（用户口径下移 5 像素）
            if (DownloadStore.paused) {
                PausePill("继续下载", pillMod) {
                    DownloadStore.resumeAll()
                }
            } else {
                PausePill("暂停下载", pillMod) {
                    DownloadStore.pauseAll()
                }
            }
        }
    }
}

/** 顶部悬浮胶囊按钮（半透明白底全圆角；缩小版 2026-09-25） */
@Composable
private fun PausePill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 8.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

/** 下载任务行：封面 + 歌名/艺人 + 状态/原因；onClick 提供时点击 = 重新入队 */
@Composable
private fun TaskCard(song: Song, status: String, reasonTint: Boolean = false, onClick: (() -> Unit)? = null) {
    StackedCard(onClick = onClick, height = 56.dp) {
        SongCover(song, 38.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                status,
                fontSize = 9.sp,
                color = if (reasonTint) Accent else TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
