package com.netmusiclite.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedIconCard
import com.netmusiclite.ui.components.SwipeBackBox
import com.netmusiclite.ui.components.sinkFromCenter
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.TextPrimary

/** 主页：ColorOS 风格卡片堆叠（搜索→…→设置），表冠滚动+震动，右缘弧形进度条 */
private data class HomeItem(val title: String, val icon: ImageVector, val route: String)

private val homeItems = listOf(
    HomeItem("搜索", NcmIcons.Search, Routes.SEARCH),
    HomeItem("正在播放", NcmIcons.NowPlaying, Routes.PLAYER),
    HomeItem("每日推荐", NcmIcons.DailyRec, Routes.DAILY_RECOMMEND),
    HomeItem("我喜欢", NcmIcons.Heart, Routes.LIKED_SONGS),
    HomeItem("听歌排行", NcmIcons.Chart, Routes.RECORD),
    HomeItem("最近播放", NcmIcons.History, Routes.PLAY_HISTORY),
    HomeItem("我创建的歌单", NcmIcons.Playlist, Routes.MY_PLAYLISTS),
    HomeItem("本地音乐", NcmIcons.LocalMusic, Routes.LOCAL_MUSIC),
    HomeItem("私人漫游", NcmIcons.Roam, Routes.PRIVATE_ROAM),
    // 2026-09-11 补齐：一起听入口页此前只有路由注册、全工程无调用点（好友页/艺人页都是直跳房间页），
    // 导致入口页实际不可达。此处补主页入口。
    HomeItem("一起听", NcmIcons.Together, Routes.LISTEN_TOGETHER),
    HomeItem("我的好友", NcmIcons.Friends, Routes.FRIENDS),
    HomeItem("设置", NcmIcons.Settings, Routes.SETTINGS),
)

@Composable
fun HomeScreen(navController: NavHostController) {
    // 流畅度 R4：播放状态改在「正在播放」卡片内部读取（见 nowPlayingSubtitle），
    // 切歌/暂停只重组那一张卡，不再整页 10 卡重组
    // 左划进播放页：统一走 SwipeBackBox 的 onSwipeLeft（子级挂横滑检测会饿死右滑返回）
    SwipeBackBox(
        onBack = null,
        onSwipeLeft = {
            // 滑动来源：保留传送带滑动转场（NavMotionKind.Slide）；点击入口一律走缩放
            navController.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Slide))
        },
    ) {
        StackedCardList(progressTotal = homeItems.size + 1, horizontalInsetPx = 10) {
            // 头像+昵称 = 列表首项：进度最顶（吸附居中=页面正中），随列表滚动，非悬浮层；
            // 往下所有卡片与原主页保持一致（2026-09-13：解决主页没有视觉中心的问题）
            item(key = "profile", contentType = { "home_profile" }) {
                HomeProfileBadge(onAccount = {
                    navController.navigateWithMotion(Routes.ACCOUNT, NavMotionSpec(NavMotionKind.Pill))
                })
            }
            items(
                homeItems,
                key = { it.route },
                contentType = { "home" }, // 流畅度 R5：同构卡片标注类型，提高 Lazy 布局复用
            ) { item ->
                StackedIconCard(
                    title = item.title,
                    icon = item.icon,
                    subtitle = if (item.route == Routes.PLAYER) nowPlayingSubtitle() else null,
                    iconTint = if (item.route == Routes.LIKED_SONGS) Accent else TextPrimary,
                    navMotionKind = NavMotionKind.Pill,
                    onClick = {
                        navController.navigateWithMotion(item.route)
                    },
                )
            }
        }
    }
}

/**
 * 主页视觉中心：用户头像（白圈描边）+ 昵称胶囊，列表首项随进度滚动，
 * 滚到顶时吸附在页面正中最上方；点击进「我的账号」。
 */
@Composable
private fun HomeProfileBadge(onAccount: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(94.dp)
            .sinkFromCenter()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onAccount,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape),
        ) {
            CoverImage(SessionStore.avatarUrl.ifEmpty { null }, 58.dp, shape = CircleShape)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            SessionStore.nickname.ifEmpty { "未登录" },
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.34f), RoundedCornerShape(50))
                .padding(horizontal = 9.dp, vertical = 2.dp),
        )
    }
}

/** 副标题独立小 composable：状态读取下沉，重组范围 = 这一行文本 */
@Composable
private fun nowPlayingSubtitle(): String {
    val song = PlayerEngine.current
    val playing = PlayerEngine.isPlaying
    return if (song != null) "${if (playing) "正在播放" else "已暂停"} · ${song.title}" else "未在播放"
}
