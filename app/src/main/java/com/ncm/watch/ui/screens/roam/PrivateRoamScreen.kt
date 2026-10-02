package com.ncm.watch.ui.screens.roam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.ui.components.CircularSideProgress
import com.ncm.watch.ui.components.RotatingCover
import com.ncm.watch.ui.nav.Routes
import com.ncm.watch.ui.nav.NavMotionKind
import com.ncm.watch.ui.nav.NavMotionSpec
import com.ncm.watch.ui.nav.navigateWithMotion
import com.ncm.watch.ui.theme.Bg
import com.ncm.watch.ui.theme.screenBg
import com.ncm.watch.ui.theme.TextSecondary
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp

/**
 * 私人漫游：按系统偏好随机推歌（playmode/song/list，失败退每日推荐），
 * 拉到歌单后直接进入播放页开始播放。
 */
@Composable
fun PrivateRoamScreen(nav: NavHostController) {
    var status by remember { mutableStateOf("正在挑歌…") }

    LaunchedEffect(Unit) {
        // FM 是逐批推流接口（每批约 3 首），只拉一批起播必然听着听着就断 →
        // 交给 startRoam：连拉多批起播 + 挂上 FM 续流（边播边续取，不再三首打转）
        PlayerEngine.startRoam { ok ->
            if (!ok) {
                status = "没有拿到推荐歌曲"
            } else {
                // 过场页不留在栈里：进播放页的同时把自己弹出，
                // 否则从播放页返回会再次触发本页 LaunchedEffect → 再推一层播放页，越划越深
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Directional)) {
                    popUpTo(Routes.PRIVATE_ROAM) { inclusive = true }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(screenBg()), contentAlignment = Alignment.Center) {
        RotatingCover(PlayerEngine.current, 120.dp, PlayerEngine.isPlaying)
        CircularSideProgress(progress = { 0.2f }, modifier = Modifier.fillMaxSize())
        Text(status, fontSize = 11.sp, color = TextSecondary,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp))
    }
}
