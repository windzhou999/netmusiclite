package com.netmusiclite.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.AccountEntry
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.SessionStore
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.QrCodeImage
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.screenBg
import com.netmusiclite.ui.theme.Green
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * 扫码登录（首启 / 退出后再登 / 添加账号共用）。
 * 流程：qr/key 拿 unikey → 二维码 → 每 1.8s 轮询 → 803 成功存 MUSIC_U。
 */
@Composable
fun LoginQrScreen(
    onLoggedIn: () -> Unit,
    heading: String = "网易云扫码登录",
) {
    var qrContent by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var scanned by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        outer@ while (true) {
            val key = NcmApi.qrKey()
            if (key == null) {
                status = "网络异常，正在重试…\n${NcmApi.error() ?: "无unikey"}"
                delay(2500)
                continue@outer
            }
            qrContent = "https://music.163.com/login?codekey=$key"
            status = "打开手机网易云扫一扫"
            scanned = false
            while (true) {
                delay(1800)
                val r = NcmApi.qrPoll(key)
                when (r.code) {
                    801 -> status = "等待扫码…"
                    802 -> { scanned = true; status = "已扫码，请在手机上确认" }
                    800 -> continue@outer // 过期 → 重新生成
                    8821 -> {
                        // 风控：换新 key 重试
                        status = "登录被风控，正在换新二维码…"
                        delay(1200)
                        continue@outer
                    }
                    803 -> {
                        val rawCookie = r.cookie ?: ""
                        val cookie = NcmApi.cookieFrom(rawCookie)
                        if (cookie != null) {
                            SessionStore.musicU = cookie
                            // __csrf 是点赞/发评论的 checkToken，登录时一并落盘
                            SessionStore.csrf = NcmApi.csrfFrom(rawCookie)
                            // account 失败重试 3 次：uid=0 落盘后，听歌排行/歌曲百科的
                            // 云端次数会整个失效（百科只能退显本地计数）
                            var profile = NcmApi.account()
                            for (retry in 1 until 3) {
                                if (profile != null) break
                                kotlinx.coroutines.delay(1200)
                                profile = runCatching { NcmApi.account() }.getOrNull()
                            }
                            if (profile != null) {
                                SessionStore.uid = profile.optLong("userId")
                                SessionStore.nickname = profile.optString("nickname")
                                SessionStore.avatarUrl = profile.optString("avatarUrl")
                                SessionStore.vipType = profile.optInt("vipType")
                            }
                            SessionStore.addAccount(
                                AccountEntry(
                                    cookie, SessionStore.uid, SessionStore.nickname,
                                    SessionStore.avatarUrl, SessionStore.csrf,
                                )
                            )
                            onLoggedIn()
                        } else {
                            status = "登录失败，重试中…"
                            delay(1200)
                            continue@outer
                        }
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(screenBg()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(heading, fontSize = 15.sp, color = TextPrimary, textAlign = TextAlign.Center, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Box(contentAlignment = Alignment.Center) {
                QrCodeImage(qrContent ?: "", 128.dp)
                if (scanned) {
                    Box(
                        Modifier.size(128.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(NcmIcons.Check, null, tint = Green, modifier = Modifier.size(44.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(status, fontSize = 10.sp, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 44.dp))
        }
    }
}
