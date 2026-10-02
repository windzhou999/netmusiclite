package com.netmusiclite.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.PlayMode
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.ui.components.CircleIconButton
import com.netmusiclite.ui.components.ambientSurfaceColor
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** 播放页“更多”操作：中心网格按实际窗口 dp 缩放，留出圆屏边缘安全区。 */
@Composable
fun MoreScreen(nav: NavHostController) {
    val song = PlayerEngine.current
    val mode = PlayerEngine.playMode
    val downloaded = song != null && DownloadStore.states[song.id] == 2
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        Modifier.fillMaxSize().background(ambientSurfaceColor()),
        contentAlignment = Alignment.Center,
    ) {
        val viewportHeight = maxHeight
        val safeWidth = maxWidth * 0.82f
        val titleHeight = 16.dp
        val titleGap = 5.dp
        val labelGap = 4.dp
        val labelHeight = 10.dp
        val rowGap = (viewportHeight * 0.04f).coerceAtLeast(8.dp).coerceAtMost(12.dp)
        val reservedHeight = titleHeight + titleGap + rowGap + (labelGap + labelHeight) * 2
        val maxButtonFromHeight = ((viewportHeight * 0.66f - reservedHeight) / 2).coerceAtLeast(0.dp)
        val buttonSize = minOf(54.dp, safeWidth * 0.24f, maxButtonFromHeight)
        val itemWidth = (buttonSize + 10.dp).coerceAtMost((safeWidth - 16.dp) * (1f / 3f))
        val columnGap = ((safeWidth - itemWidth * 3) / 2).coerceAtLeast(8.dp).coerceAtMost(18.dp)

        Column(
            modifier = Modifier.width(safeWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("更多", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(titleGap))

            Row(
                modifier = Modifier.width(safeWidth),
                horizontalArrangement = Arrangement.spacedBy(columnGap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MoreButton(
                    icon = when (mode) {
                        PlayMode.LOOP -> MoreIcons.Loop
                        PlayMode.SHUFFLE -> MoreIcons.Shuffle
                        PlayMode.SINGLE -> MoreIcons.Single
                        PlayMode.HEART -> MoreIcons.Heart
                    },
                    label = mode.label,
                    size = buttonSize,
                    itemWidth = itemWidth,
                ) {
                    PlayerEngine.setMode(mode.next())
                }
                MoreButton(
                    icon = MoreIcons.Download,
                    label = if (downloaded) "已下载" else "下载",
                    dim = downloaded || song == null,
                    size = buttonSize,
                    itemWidth = itemWidth,
                ) {
                    if (song == null) return@MoreButton
                    if (DownloadStore.states[song.id] != 2) {
                        scope.launch { DownloadStore.download(song) }
                    }
                }
                MoreButton(
                    icon = MoreIcons.Queue,
                    label = "列表播放",
                    size = buttonSize,
                    itemWidth = itemWidth,
                    navMotionKind = NavMotionKind.Circle,
                    onClick = {
                        nav.navigateWithMotion(Routes.QUEUE)
                    },
                )
            }

            Spacer(Modifier.height(rowGap))

            Row(
                modifier = Modifier.width(safeWidth),
                horizontalArrangement = Arrangement.spacedBy(columnGap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MoreButton(
                    icon = MoreIcons.Sleep,
                    label = "睡眠定时",
                    size = buttonSize,
                    itemWidth = itemWidth,
                    navMotionKind = NavMotionKind.Circle,
                    onClick = {
                        nav.navigateWithMotion(Routes.SLEEP_TIMER)
                    },
                )
                MoreButton(
                    icon = MoreIcons.Collect,
                    label = "收藏",
                    size = buttonSize,
                    itemWidth = itemWidth,
                    navMotionKind = NavMotionKind.Circle,
                    onClick = {
                        nav.navigateWithMotion(Routes.COLLECT)
                    },
                )
                MoreButton(
                    icon = MoreIcons.Share,
                    label = "分享",
                    size = buttonSize,
                    itemWidth = itemWidth,
                    navMotionKind = NavMotionKind.Circle,
                    onClick = {
                        nav.navigateWithMotion(Routes.SHARE_PICK)
                    },
                )
            }
        }
    }
}

@Composable
private fun MoreButton(
    icon: ImageVector,
    label: String,
    dim: Boolean = false,
    size: Dp,
    itemWidth: Dp,
    navMotionKind: NavMotionKind? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(itemWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircleIconButton(
            icon, label, onClick, size = size, iconSize = size * 0.42f,
            dim = dim, navMotionKind = navMotionKind,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            modifier = Modifier.width(itemWidth),
            fontSize = 8.5.sp,
            lineHeight = 10.sp,
            color = if (dim) TextSecondary.copy(alpha = 0.5f) else TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
