package com.netmusiclite.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.fillMaxSize
import com.netmusiclite.ui.theme.Bg
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.rememberLazyListState
import com.netmusiclite.ui.components.rotaryList
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.LoudnessPrefs
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.NcmListPage
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedIconCard
import com.netmusiclite.ui.components.formatDate
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.BackgroundStore
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PRESET_ACCENTS
import com.netmusiclite.data.QualityPrefs
import com.netmusiclite.data.ThemeMode
import com.netmusiclite.ui.screens.login.LoginQrScreen
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.SurfaceStrong
import com.netmusiclite.ui.theme.AccentSoft
import com.netmusiclite.ui.theme.Gold
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 设置：我的账号 / 缓存清理（卡片堆叠风格） */
@Composable
fun SettingsScreen(nav: NavHostController) {
    val loudnessEnabled = LoudnessPrefs.enabled
    StackedCardList(title = "设置", progressTotal = 6, horizontalInsetPx = 10) {
        item(key = "account") {
            StackedIconCard(
                title = "我的账号",
                subtitle = SessionStore.nickname.ifEmpty { "未登录" },
                icon = NcmIcons.Note,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.ACCOUNT) }
        }
        // 2026-10-01：原「背景与玻璃」独立入口已并入本项。
        // 理由：玻璃材质的观感直接受主题明暗与种子色影响（浅色主题下玻璃 alpha 是重新标定的一套），
        // 拆成两个入口只会让人不知道该去哪调。合并后一个入口统管全部观感设置，且无重复项。
        item(key = "theme") {
            StackedIconCard(
                title = "主题",
                subtitle = themeSummary(),
                icon = NcmIcons.Theme,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.THEME) }
        }
        item(key = "quality") {
            StackedIconCard(
                title = "音质",
                subtitle = "当前：${QualityPrefs.label()}（播放与下载生效）",
                icon = NcmIcons.Note,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.QUALITY) }
        }
        item(key = "loudness") {
            StackedCard(onClick = {
                val enabled = !LoudnessPrefs.enabled
                LoudnessPrefs.enabled = enabled
                PlayerEngine.setLoudnessEnabled(enabled)
            }) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (loudnessEnabled) Accent else SurfaceStrong),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        NcmIcons.Volume, contentDescription = null,
                        tint = if (loudnessEnabled) com.netmusiclite.ui.theme.OnAccent else TextPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("歌曲响度均衡", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        if (loudnessEnabled) "已开启 · 每首歌统一到同一响度" else "已关闭",
                        fontSize = 8.sp, color = TextSecondary, maxLines = 1,
                    )
                    Text("只对齐曲目间音量，保留每首歌自身动态", fontSize = 8.sp, color = TextTertiary, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .then(if (loudnessEnabled) Modifier.background(Accent) else Modifier.border(1.dp, TextTertiary, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loudnessEnabled) {
                        Icon(NcmIcons.Check, contentDescription = "已开启", tint = com.netmusiclite.ui.theme.OnAccent, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
        item(key = "cache") {
            StackedIconCard(
                title = "缓存清理", subtitle = "清理图片与临时缓存", icon = NcmIcons.Refresh,
                navMotionKind = NavMotionKind.Pill,
            ) {
                nav.navigateWithMotion(Routes.CACHE_CLEAN)
            }
        }
        item(key = "disclaimer") {
            StackedIconCard(
                title = "免责声明",
                subtitle = "第三方客户端使用协议",
                icon = NcmIcons.Bookmark,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.DISCLAIMER) }
        }
        item(key = "credit") {
            Text(
                "作者：昼小风\n未经授权请勿转载",
                fontSize = 9.sp, color = TextTertiary, lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/** 设置页「主题」入口副标题：明暗 · 配色来源 · 背景状态（读的都是 Compose 状态，会自动刷新） */
private fun themeSummary(): String {
    val color = if (AppearancePrefs.dynamicColor) "自动取色" else AppearancePrefs.accentLabel()
    val bg = if (BackgroundStore.ready) "自定义背景" else "默认背景"
    return "${AppearancePrefs.modeLabel()} · $color · $bg"
}

/** 我的账号：账号卡(头像+昵称+VIP) + 切换账号 / 退出账号 */
@Composable
fun AccountScreen(nav: NavHostController) {
    var vipExpire by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        val uid = SessionStore.uid
        if (uid > 0) {
            val t = NcmApiVipSafe(uid)
            vipExpire = t?.takeIf { it > System.currentTimeMillis() }
        }
    }

    StackedCardList(title = "我的账号", progressTotal = 3) {
        item(key = "avatar") {
            StackedCard {
                CoverImage(SessionStore.avatarUrl.ifEmpty { null }, 42.dp, shape = CircleShape)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        SessionStore.nickname, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1,
                    )
                    // VIP 到期：拿到且有效才显示
                    vipExpire?.let {
                        Text("VIP 到期 ${formatDate(it)}", fontSize = 9.sp, color = Gold)
                    }
                }
            }
        }
        item(key = "switch") {
            StackedIconCard(
                title = "切换账号",
                subtitle = "已添加 ${SessionStore.accounts().size} 个账号",
                icon = NcmIcons.Friends,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.SWITCH_ACCOUNT) }
        }
        item(key = "logout") {
            StackedIconCard(
                title = "退出账号",
                subtitle = "退出后需重新扫码登录",
                icon = NcmIcons.Close,
                iconTint = Accent,
                navMotionKind = NavMotionKind.Pill,
            ) {
                SessionStore.logoutCurrent()
                nav.navigateWithMotion(Routes.LOGIN_QR) {
                    popUpTo(Routes.HOME) { inclusive = true }
                }
            }
        }
    }
}

private suspend fun NcmApiVipSafe(uid: Long): Long? = runCatching {
    com.netmusiclite.data.NcmApi.vipExpire(uid)
}.getOrNull()

/** 切换账号：已添加账号列表 + 添加账号（扫码） */
@Composable
fun SwitchAccountScreen(nav: NavHostController) {
    val accounts = SessionStore.accounts()

    StackedCardList(
        title = "切换账号",
        titleSubtitle = "当前账号排在最前",
        progressTotal = accounts.size + 1,
    ) {
        accounts.forEachIndexed { i, acc ->
            item(key = "acc_${acc.uid}_$i") {
                StackedCard(
                    onClick = {
                        SessionStore.applyAccount(acc)
                        PlayerEngine.refreshLikes()
                        nav.popBackStack()
                    },
                ) {
                    CoverImage(acc.avatarUrl?.ifEmpty { null }, 36.dp, shape = CircleShape)
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(acc.nickname.ifEmpty { "账号 ${acc.uid}" }, fontSize = 12.sp, color = TextPrimary, maxLines = 1)
                        if (i == 0) Text("当前使用", fontSize = 9.sp, color = Accent)
                    }
                }
            }
        }
        item(key = "add") {
            StackedIconCard(
                title = "添加账号",
                subtitle = "扫码登录新账号",
                icon = NcmIcons.Plus,
                iconTint = Accent,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.ADD_ACCOUNT_QR) }
        }
    }
}

/** 添加账号 = 扫码登录（登录成功即回到切换页） */
@Composable
fun AddAccountQrScreen(nav: NavHostController) {
    LoginQrScreen(
        heading = "添加账号\n扫码登录",
        onLoggedIn = { nav.popBackStack() },
    )
}

/** 缓存清理：统计并清理应用缓存目录（不动已下载音乐） */
@Composable
fun CacheCleanScreen(nav: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sizeText by remember { mutableStateOf("统计中…") }
    var result by remember { mutableStateOf<String?>(null) }

    suspend fun dirSize(d: File): Long = withContext(Dispatchers.IO) {
        d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    LaunchedEffect(Unit) {
        val bytes = dirSize(ctx.cacheDir)
        sizeText = "%.1f MB".format(bytes / 1024f / 1024f)
    }

    NcmListPage(title = "缓存清理", onBack = { nav.popBackStack() }, progressTotal = 4) {
        item(key = "size") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(sizeText, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("图片 / 网络临时缓存（不动已下载音乐）", fontSize = 9.sp, color = TextSecondary)
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                        .clickable {
                            java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                            }
                            result = "已清理"
                            sizeText = "0.0 MB"
                        }
                        .padding(horizontal = 26.dp, vertical = 10.dp),
                ) {
                    Text("立即清理", fontSize = 12.sp, color = com.netmusiclite.ui.theme.OnAccent, fontWeight = FontWeight.SemiBold)
                }
                result?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 10.sp, color = Accent)
                }
            }
        }
    }
}

// 2026-10-01：原 `BackgroundSettingsScreen`（背景与玻璃）已整体合并进下方 ThemeSettingsScreen。
// 主设置页不再保留独立入口，Routes.BG_SETTING 也已移除 —— 同一个功能不允许存在两套 UI。

/** 免责声明：第三方客户端使用协议全文（表冠滚动阅读） */
@Composable
fun DisclaimerScreen(nav: NavHostController) {
    val paragraphs = remember { DISCLAIMER_TEXT.trim().split("\n\n") }
    com.netmusiclite.ui.components.NcmListPage(title = "免责声明") {
        itemsIndexed(paragraphs, key = { i, _ -> i }) { _, para ->
            Text(
                para,
                fontSize = 9.sp,
                color = TextPrimary.copy(alpha = 0.9f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

private val DISCLAIMER_TEXT = """NetMusicLite 第三方客户端免责声明

欢迎您使用 NetMusicLite（以下简称"本软件"）。在下载、安装、登录或以任何方式使用本软件之前，请您务必仔细阅读并充分理解本免责声明的全部内容，特别是免除或者限制开发者责任的条款。本声明是您与开发者之间关于使用本软件的有效约定。您一旦实际安装或使用本软件，即视为您已经完整阅读、充分理解并同意接受本声明全部条款的约束；如您不同意本声明的任何内容，或者无法准确理解相关条款的含义，请立即停止使用本软件并将其从您的设备中卸载、删除。

一、软件性质声明
本软件是由音乐爱好者出于个人学习、研究、验证移动端技术与网络协议等非商业目的，利用业余时间独立开发的非官方第三方客户端。本软件基于公开可得的网络接口协议实现，目的在于让用户在智能手表等可穿戴设备上访问其本人已经依法享有的音乐服务权益。本软件并非网易云音乐官方客户端，与网易杭州网络游戏有限公司及网易公司各关联主体（以下统称"权利方"）之间不存在任何隶属、合作、授权、许可、代理、联营或投资关系。开发者亦非权利方的员工、代理人或者受托人。本软件的名称、界面与文档中出现的"网易云""NCM""网易云音乐"等字样与标识，仅为向用户客观描述本软件所适配的服务对象而作描述性使用，相关名称与标识的一切权利均归权利方所有，权利方随时可以要求开发者调整或停止此类使用。

二、非官方性与服务来源
您通过本软件访问、展示或播放的全部内容，包括但不限于歌曲音频、歌词文本、专辑与歌单封面图片、艺人照片、艺人介绍、歌单信息、用户评论、好友资料以及其他任何形式的数据与素材（以下合称"平台内容"），均来源于权利方或其他第三方平台的服务器。本软件自身不存储、不上传、不修改、不复制任何平台内容的原始数据，仅作为技术工具在您的设备与平台服务器之间转发您本人发起的请求，并将服务器返回的结果在您的设备屏幕上呈现。平台内容的可用性、完整性、准确性、时效性、音质效果以及版权授权状态，均由权利方或者相应的内容提供方单方决定，开发者对上述事项不作任何形式的明示或默示担保与承诺。

三、知识产权归属
本软件的程序代码、界面布局、图标与配套文档的著作权及其他合法权益归开发者依法享有（依据法律规定不享有权利的部分除外）。平台内容的一切知识产权以及相关权益，均归权利方或者相应的内容权利人所有，本软件不对平台内容主张任何权利，亦不因展示平台内容而获得对该内容的任何权利或许可。未经权利人事先书面许可，任何组织和个人不得以任何形式复制、摘编、转载、链接、转贴、传播、放映、出租、出售或者通过网络信息传播等方式使用通过本软件获取的平台内容，也不得将平台内容用于任何商业目的。

四、个人使用限定
本软件仅供您本人出于非商业目的，在中华人民共和国法律以及您所在地区法律允许的范围内，为个人学习、研究与欣赏的目的使用。您在此承诺并保证：不将本软件或者通过本软件获取的任何内容用于商业牟利、广告推广、公开传播、二次分发、汇编出版、营利性放映或者其他任何可能侵犯权利方或第三方知识产权及合法权益的用途；不对本软件进行反向工程、反编译、破解、篡改或者开发衍生作品后再分发；不利用本软件从事任何违反法律法规、危害网络安全、侵犯他人合法权益的活动。您因违反前述承诺而引发的一切法律责任与不利后果，均由您自行承担；因此给开发者或第三方造成损失的，您应当依法予以赔偿。

五、账号安全与使用风险
您理解并同意，使用任何非官方第三方客户端登录个人网络账号，客观上存在固有风险，包括但不限于：被平台安全风控系统临时限制部分功能、触发验证、会话异常掉线，极端情形下账号被冻结或者封禁的可能性。此类风险来源于平台自身的安全策略与算法判定，超出了开发者可以识别和控制的范围。是否使用本软件登录账号、登录哪一账号、何时登录，均系您自主独立作出的决定，由此产生的一切后果由您自行承担。开发者不会通过本软件收集、存储或者向任何服务器上传您的账号密码；您的登录凭证仅保存在您设备的本地存储中，仅用于维持您本人的登录状态，请您妥善保管设备，防止凭证被他人获取。

六、下载与离线功能限制
本软件提供的歌曲下载与离线播放功能，仅作为您在网络条件不佳场景下的个人使用补充手段，不构成对任何内容的授权或许可。您通过该功能获得的音频文件，仅限您本人在设备上离线欣赏，不得以任何方式向任何第三方提供、共享、传播、出售、公开表演或者用于任何盈利性活动。受版权保护的内容能否下载以及下载的音质档位，取决于权利方的授权策略与您本人账号的会员权益状态；下载功能的存在不代表开发者向您授予了任何超出您账号既有权益之外的权利。因下载内容被用于授权范围之外的用途而产生的一切法律责任，由实际使用人自行承担。

七、一起听与社交功能特别提示
本软件提供的"一起听"功能基于平台已有的房间机制实现，用于让您与好友同步欣赏音乐。您在使用该功能时应当遵守平台规则与法律法规，不得传播违法有害信息、骚扰他人或者从事任何破坏房间秩序的行为。受技术条件限制，该功能可能存在加入失败、同步延迟或中断等情形，开发者对该功能的可用性与同步效果不作任何保证。

八、服务可用性不保证
本软件所依赖的第三方接口可能随时因为平台版本升级、安全策略调整、接口协议变更、区域访问限制、服务器故障或者其他不可抗力因素而部分或者全部失效，进而导致登录、搜索、播放、下载、歌词、一起听等任何功能不可用或者体验下降。开发者有权根据实际情况，自行决定对本软件进行更新、修改、暂停或者终止开发与维护，而无需事先通知您，亦不因此对您承担任何形式的责任或赔偿义务。

九、免责条款
在法律允许的最大范围内，对于因下列情形直接或间接导致的一切损失（包括但不限于设备数据丢失、账号价值损失、设备故障或损坏、网络流量与电量消耗、时间成本、业务中断以及任何预期利益损失），开发者均不承担任何责任：（1）您安装、使用或者无法使用本软件的行为；（2）平台内容存在错误、延误、遗漏、侵权或者任何形式的瑕疵；（3）第三方接口变更、限制或者服务中断；（4）网络故障、设备兼容性问题、操作系统限制或者其他系统性故障；（5）您对软件的不当使用、超出声明范围的滥用，或者与其他不兼容软件共同使用；（6）黑客攻击、病毒侵入或者其他恶意行为；（7）不可抗力或者其他开发者无法合理控制的因素。

十、隐私与本地数据
本软件不会主动收集、上传您的个人身份信息，不包含任何广告、统计或者追踪组件。您在使用过程中产生的配置、缓存、下载文件、登录凭证等数据仅保存在您的设备本地存储中，您可以在系统设置或者本软件的设置页面中自行清除。您登录、播放、搜索等操作所必需的网络请求，属于实现软件功能的前提条件，其数据处理行为受相关平台隐私政策约束，不属于开发者的数据收集行为。

十一、第三方开源组件
本软件的开发与构建使用了若干开源社区成果（包括但不限于 Kotlin、Jetpack Compose、OkHttp、Coil、ZXing 等框架与工具库），谨向相关开源项目的作者与贡献者致谢。各开源组件按照其自身的开源协议提供，开发者不对上述组件的质量与安全性承担担保责任。

十二、未成年人使用提示
本软件面向具有完全民事行为能力的成年人设计。若您是未满十八周岁的未成年人，请在监护人的陪同与同意下使用本软件，并由监护人对您的使用行为与使用后果承担相应的监护责任。

十三、协议的变更与终止
开发者有权根据实际情况随时修改、补充本声明，并在软件内或者开源项目页面公布，修改后的声明自公布之时起生效。若您在声明变更后继续使用本软件，即视为您已经接受变更后的全部内容。您可以随时停止使用本软件并将其卸载；您的卸载行为不影响您在卸载之前已经发生的行为所应承担的责任与义务。

十四、适用法律与争议解决
本声明的订立、效力、解释、履行以及争议解决，均适用中华人民共和国法律（不含港澳台地区法律）。因本软件或者本声明引起的或者与之相关的任何争议，双方应当首先友好协商解决；若本声明的任何条款被有权机关认定无效或者不可执行，不影响其他条款的效力，其他条款仍然继续有效并约束双方。

十五、联系与致谢
如您对本声明的任何内容存在疑问，或者权利方认为本软件侵犯其合法权益，欢迎通过本软件的开源项目页面与开发者取得联系，并附上必要的证明材料。开发者将在核实后积极配合处理，包括在必要时停止本软件的开发、更新与分发。最后，感谢您选择并使用本软件，也恳请您在享受音乐的同时，尊重每一位音乐创作者与版权人的劳动成果，合理、合规、有节制地使用技术工具。音乐让生活更美好，尊重让音乐延续。

十六、流量与电量使用提示
本软件运行过程中需要进行网络数据传输（包括登录鉴权、内容检索、音频流播放、图片加载与文件下载等），可能消耗您的移动数据流量并占用一定的设备电量。通过手表设备在线播放音频时的流量消耗尤为明显，建议您在无线局域网环境下使用下载与播放功能，并留意运营商的流量套餐限制。因流量超出套餐、电量消耗或设备发热等造成的任何费用与损失，由您自行承担。

十七、多设备与多账号说明
本软件支持在同一设备上添加与切换多个已登录账号，该功能仅用于方便您管理本人名下的多个账号，不得用于批量操作、自动化任务或任何干扰平台正常服务的场景。您应当妥善保管每一个账号的登录凭证，因账号保管不善导致的任何损失由您自行负责。

十八、下架与停止分发
如权利方通过合法途径提出合理主张，或法律法规、监管政策要求，开发者保留随时停止本软件的更新、分发与技术服务，并从公开渠道下架安装包的权利，且无需对任何用户承担违约或赔偿责任。已经安装本软件的用户应当理解并支持前述安排。"""

/** 首启同意协议页：阅读完整协议，同意后才可进入登录/主页 */
@Composable
fun ConsentScreen(onAgree: () -> Unit, onDecline: () -> Unit) {
    val paragraphs = remember { DISCLAIMER_TEXT.trim().split("\n\n") }
    val listState = rememberLazyListState()
    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .rotaryList(listState)
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(30.dp))
            Text(
                "欢迎使用 NetMusicLite",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Text(
                "第三方客户端 · 使用前请阅读并同意以下协议",
                fontSize = 9.sp, color = TextSecondary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(paragraphs, key = { i, _ -> i }) { _, para ->
                    Text(
                        para,
                        fontSize = 9.sp, color = TextPrimary.copy(alpha = 0.9f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "不同意并退出", fontSize = 10.sp, color = TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(SurfaceStrong)
                        .clickable { onDecline() }
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "同意并继续", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = com.netmusiclite.ui.theme.OnAccent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                        .clickable { onAgree() }
                        .padding(horizontal = 15.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 主题设置（2026-10-01 新增；同日合并原「背景与玻璃」）：
 * 一个入口统管全部影响观感的设置 ——
 *   配色侧：外观模式（深色 / 浅色）/ 跟随壁纸取色 / M3 种子色板；
 *   材质侧：背景图（选图 · 压暗 · 模糊）/ 玻璃卡片（材质 · 强度）/ 播放页取色；
 *   歌词侧：歌词扫光开关（2026-10-02 新增）。
 *
 * 合并理由：玻璃材质的观感直接由主题明暗与种子色决定（浅色主题下玻璃 alpha 是另一套标定），
 * 拆成两个入口只会让人不知道该去哪调。合并后**无任何重复项**：
 *   · 「种子色」与「跟随壁纸取色」互斥 —— 自动取色开启时色板整体变淡且点击无效；
 *   · 「背景模糊」作用于底图、「卡片模糊」作用于玻璃取样，作用对象不同；
 *   · 「保留取色」只是播放页/歌词页的例外开关，仅这两页生效。
 * 全部项直接写 Compose 状态，改动立即全局生效（无需重启）。
 */
@Composable
fun ThemeSettingsScreen(nav: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) BackgroundStore.setFrom(uri, ctx.contentResolver)
    }

    val mode = AppearancePrefs.mode
    val dynamicOn = AppearancePrefs.dynamicColor
    val bgReady = BackgroundStore.ready

    StackedCardList(title = "主题") {
        // ───────────────── 配色 ─────────────────
        item(key = "mode") {
            StackedCard(height = 84.dp) {
                Column(Modifier.weight(1f)) {
                    Text("外观模式", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        // ⚠ 只保留两个选项：原「跟随系统」已移除 —— 手表系统深浅恒定，
                        //   它与「深色」效果完全相同，是个点下去看不出区别的冗余项。
                        ModeChip("深色", mode == ThemeMode.DARK) {
                            AppearancePrefs.setMode(ThemeMode.DARK)
                        }
                        ModeChip("浅色", mode == ThemeMode.LIGHT) {
                            AppearancePrefs.setMode(ThemeMode.LIGHT)
                        }
                    }
                }
            }
        }
        item(key = "dynamic") {
            StackedCard(height = 68.dp) {
                Column(Modifier.weight(1f)) {
                    Text("跟随壁纸取色", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Text(
                        AppearancePrefs.dynamicSourceLabel(),
                        fontSize = 9.sp, color = TextSecondary, maxLines = 2,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = dynamicOn,
                    onCheckedChange = { AppearancePrefs.setDynamicColor(it) },
                )
            }
        }
        item(key = "seed") {
            StackedCard(height = 142.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("种子色", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Text(
                        if (dynamicOn) "已由上方自动取色接管，关闭后生效" else "Material 3 由种子色派生整套配色",
                        fontSize = 8.sp, color = TextTertiary, maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 自动取色开启时整块变淡且不可点 —— 消除「点了没反应」的重复感
                    Column(Modifier.alpha(if (dynamicOn) 0.35f else 1f)) {
                        PRESET_ACCENTS.chunked(4).forEach { rowColors ->
                            Row {
                                rowColors.forEach { c -> AccentSwatch(c, enabled = !dynamicOn) }
                            }
                        }
                    }
                }
            }
        }
        // ───────────────── 背景与玻璃（原独立页，已合并到此） ─────────────────
        item(key = "pick") {
            StackedIconCard(
                title = if (bgReady) "更换背景图" else "选择背景图",
                subtitle = if (bgReady) "背景已启用，选择即覆盖" else "从相册选择一张图作全局背景",
                icon = NcmIcons.ImageIc,
            ) {
                pick.launch(androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        item(key = "dim") {
            StackedCard(height = 88.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("背景压暗", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    // 系统默认 Slider 样式（M3 默认配色/高度/手势）
                    androidx.compose.material3.Slider(
                        value = BackgroundStore.dim,
                        onValueChange = { BackgroundStore.updateDim(it) },
                        valueRange = 0.2f..0.8f,
                    )
                }
            }
        }
        item(key = "bg_blur") {
            StackedCard(height = 88.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("背景模糊强度", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Slider(
                        value = BackgroundStore.bgBlur,
                        onValueChange = { BackgroundStore.updateBgBlur(it) },
                        valueRange = 0f..1f,
                    )
                }
            }
        }
        // 玻璃卡片开关只在**已选背景图**时出现：没背景图时 frostedGlass 本来就固定走纯色兜底分支，
        // 这时露出开关只会让人以为能切、切了却没反应。
        if (bgReady) {
            item(key = "card_style") {
                StackedCard(height = 66.dp) {
                    Column(Modifier.weight(1f)) {
                        Text("玻璃卡片", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            if (BackgroundStore.cardGlass) "毛玻璃取样，透出背景"
                            else "纯黑半透明，更沉稳省电",
                            fontSize = 9.sp, color = TextSecondary,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = BackgroundStore.cardGlass,
                        onCheckedChange = { BackgroundStore.updateCardGlass(it) },
                    )
                }
            }
        }
        // 卡片模糊滑杆只在「玻璃卡片 **且** 有背景图」时出现 ——
        // 条件与 frostedGlass() 的分流保持一致，没可调对象时不显示。
        if (bgReady && BackgroundStore.cardGlass) {
            item(key = "card_blur") {
                StackedCard(height = 88.dp) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("卡片模糊强度", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Slider(
                            value = BackgroundStore.cardBlur,
                            onValueChange = { BackgroundStore.updateCardBlur(it) },
                            valueRange = 0f..1f,
                        )
                    }
                }
            }
        }
        if (bgReady) {
            item(key = "keep_ambient") {
                StackedCard(height = 66.dp) {
                    Column(Modifier.weight(1f)) {
                        Text("播放页/歌词页保留取色", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text("这两页改用歌曲主色底，不透出背景图", fontSize = 9.sp, color = TextSecondary)
                    }
                    androidx.compose.material3.Switch(
                        checked = BackgroundStore.keepAmbient,
                        onCheckedChange = { BackgroundStore.updateKeepAmbient(it) },
                    )
                }
            }
            item(key = "reset") {
                StackedIconCard(
                    title = "恢复默认背景",
                    subtitle = "移除自定义背景图",
                    icon = NcmIcons.Refresh,
                    iconTint = Accent,
                ) { BackgroundStore.clear() }
            }
        }
        // ★ 2026-10-02 用户口径「主题栏目中添加歌词扫光的开关」：
        //   放在条件块之外恒显示（扫光不依赖背景图）；关闭只停掉移动光斑，
        //   聚焦行提亮（深色主题底噪）保留 —— 副标题如实写清，避免「关了还有效果」的困惑。
        item(key = "lyric_sweep") {
            StackedCard(height = 66.dp) {
                Column(Modifier.weight(1f)) {
                    Text("歌词扫光", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Text(
                        if (AppearancePrefs.lyricSweep) "白色光线随当前句扫过聚焦歌词"
                        else "已关闭扫光，聚焦行提亮保留",
                        fontSize = 9.sp, color = TextSecondary,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = AppearancePrefs.lyricSweep,
                    onCheckedChange = { AppearancePrefs.setLyricSweep(it) },
                )
            }
        }
        item(key = "hint") {
            Text(
                "浅色模式会同步重标定玻璃材质与文字层级；所有改动立即生效，无需重启。",
                fontSize = 9.sp, color = TextTertiary, lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/** 明暗模式胶囊：选中态实底 + OnAccent 前景，未选中态弱色块（三项均分卡片宽度） */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ModeChip(
    label: String, selected: Boolean, onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else SurfaceStrong)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 10.sp, maxLines = 1,
            color = if (selected) com.netmusiclite.ui.theme.OnAccent else TextPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 主题色圆点：点击即切换全局强调色，选中态描边 + 对勾（白色块用黑勾保证对比度）。
 * [enabled] = false 时不可点（自动取色开启期间种子色已被接管），变淡由调用方负责。
 */
@Composable
private fun AccentSwatch(color: Color, enabled: Boolean = true) {
    val selected = enabled && AppearancePrefs.accent == color
    val isLight = color.luminance() > 0.8f
    Box(
        Modifier
            .padding(end = 10.dp, bottom = 4.dp)
            .size(26.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = when {
                    selected && isLight -> Color.Black
                    selected -> TextPrimary
                    // 浅色块（白/亮色）在深浅两种底上本身就有对比，不需要描边；
                    // 深色块用跟随主题的弱描边（深色主题白 16% / 浅色主题黑 16%）
                    isLight -> Color.Transparent
                    else -> TextPrimary.copy(alpha = 0.16f)
                },
                shape = CircleShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) {
                AppearancePrefs.setAccent(color)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                NcmIcons.Check, null,
                tint = if (isLight) Color.Black else Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** 音质选择：标准/较高/无损。无损需 VIP（vipExpire 实时校验），选择即刻对播放与下载直链生效 */
@Composable
fun QualityScreen(nav: NavHostController) {
    var current by remember { mutableStateOf(QualityPrefs.level) }
    var vipValid by remember { mutableStateOf<Boolean?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vipValid = runCatching { NcmApi.vipExpire(SessionStore.uid) }.getOrNull()
            ?.takeIf { it > System.currentTimeMillis() } != null
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2000); toast = null } }

    data class Opt(val id: String, val label: String, val desc: String)
    val opts = listOf(
        Opt("standard", "标准", "普通音质，最省流量"),
        Opt("exhigh", "较高", "320kbps，音质更好"),
        Opt("lossless", "无损", "FLAC 音质，需 VIP"),
    )

    Box(Modifier.fillMaxSize()) {
        StackedCardList(title = "音质", progressTotal = opts.size) {
            opts.forEach { o ->
                item(key = o.id) {
                    val locked = o.id == "lossless" && vipValid == false
                    val selected = current == o.id
                    StackedCard(onClick = {
                        when {
                            locked -> { toast = "无损音质需要开通 VIP" }
                            current == o.id -> Unit
                            else -> { QualityPrefs.level = o.id; current = o.id }
                        }
                    }) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                o.label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = if (selected) Accent else TextPrimary,
                            )
                            Text(
                                if (locked) "需开通 VIP（当前账号无有效会员）" else o.desc,
                                fontSize = 9.sp, color = TextSecondary,
                            )
                        }
                        if (selected) {
                            Icon(NcmIcons.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        toast?.let {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(it, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}
