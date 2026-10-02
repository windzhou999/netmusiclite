package com.ncm.watch.ui.nav

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import com.ncm.watch.data.PlayerEngine
import com.ncm.watch.data.SessionStore
import com.ncm.watch.ui.screens.detail.AlbumScreen
import com.ncm.watch.ui.screens.detail.PlaylistDetailScreen
import com.ncm.watch.ui.screens.friends.ChatScreen
import com.ncm.watch.ui.screens.friends.MessagesScreen
import com.ncm.watch.ui.screens.friends.SharePickScreen
import com.ncm.watch.ui.screens.friends.UserProfileScreen
import com.ncm.watch.ui.screens.history.HistoryScreen
import com.ncm.watch.ui.screens.local.DownloadManagerScreen
import com.ncm.watch.ui.screens.player.SongBaikeScreen
import com.ncm.watch.ui.screens.friends.FriendsScreen
import com.ncm.watch.ui.screens.home.HomeScreen
import com.ncm.watch.ui.screens.library.DailyRecommendScreen
import com.ncm.watch.ui.screens.library.LikedSongsScreen
import com.ncm.watch.ui.screens.library.MyPlaylistsScreen
import com.ncm.watch.ui.screens.listen.ListenRoomScreen
import com.ncm.watch.ui.screens.listen.ListenTogetherScreen
import com.ncm.watch.ui.screens.local.LocalMusicScreen
import com.ncm.watch.ui.screens.login.LoginQrScreen
import com.ncm.watch.ui.screens.player.CollectScreen
import com.ncm.watch.ui.screens.player.LyricsScreen
import com.ncm.watch.ui.screens.player.MoreScreen
import com.ncm.watch.ui.screens.player.PlayerScreen
import com.ncm.watch.ui.screens.player.QueueScreen
import com.ncm.watch.ui.screens.roam.PrivateRoamScreen
import com.ncm.watch.ui.screens.search.ArtistScreen
import com.ncm.watch.ui.screens.search.SearchScreen
import com.ncm.watch.ui.screens.settings.AccountScreen
import com.ncm.watch.ui.screens.settings.AddAccountQrScreen
import com.ncm.watch.ui.screens.settings.CacheCleanScreen
import com.ncm.watch.ui.screens.settings.DisclaimerScreen
import com.ncm.watch.ui.screens.settings.QualityScreen
import com.ncm.watch.ui.screens.settings.SettingsScreen
import com.ncm.watch.ui.screens.settings.SwitchAccountScreen
import com.ncm.watch.ui.theme.Bg
import com.ncm.watch.ui.theme.screenBg
import com.ncm.watch.ui.components.SwipeBackBox
import com.ncm.watch.ui.components.isRoundScreen

/**
 * 应用导航：未登录 → 扫码登录；已登录 → 主页。
 * 转场为轻缩放+淡入（iOS 观感）。
 * 返回统一走「全局左缘滑动」（SwipeBackBox）；
 * PLAYER/LYRICS/LISTEN_ROOM 有自定义手势语义，不包滑动返回。
 */
@Composable
fun AppRoot() {
    // 起始路由同步计算（只是内存标记读取）：已登录默认进播放页（左划切主页）
    val dest = remember {
        val d = when {
            !com.ncm.watch.data.ConsentStore.agreed -> Routes.CONSENT
            SessionStore.loggedIn -> Routes.PLAYER
            else -> Routes.LOGIN_QR
        }
        // 首帧即就位：登录态起步就是播放页（环境色底），全局背景层不能先闪一帧自定义图
        com.ncm.watch.ui.components.AmbientSurface.active = d in AMBIENT_SURFACE_ROUTES
        d
    }
    // ★ 启动加载页实现为「不透明浮层」：先只组合浮层（首帧极快），
    //   重量级导航图在浮层遮挡下组合 + 首帧落屏后才淡出 —— 揭开即为满帧。
    var showSplash by remember { mutableStateOf(true) }
    /** 导航图（NcmBackground + NavHost）是否开始组合：见下面启动编排 R4 */
    var contentReady by remember { mutableStateOf(false) }
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(Unit) {
        // ★ 冷启动专项 R3/R4（2026-09-30）：上次播放状态（队列/当前歌/进度）的恢复与首帧并行开跑，
        //   并且**在导航图组合之前**落地 —— 播放页一次成型（旧版先按空态组合、状态回来再整体重组，
        //   等于把最重的一页组合两遍）。同时修掉「封面预解码拿不到 current 一直空转」的老问题。
        com.ncm.watch.data.PlayerEngine.preloadState()
        // 最多等 400ms 让状态落地：这段时间屏幕上只有启动浮层，用户无感
        kotlinx.coroutines.withTimeoutOrNull(400L) {
            androidx.compose.runtime.snapshotFlow { com.ncm.watch.data.PlayerEngine.current }
                .first { it != null }
        }
        // 封面/头像预解码：splash 窗口内把冷启动首屏要用的图片读进 Coil 内存缓存
        //（播放页封面 62dp 档 + 主页头像 58dp 档），揭开时直接命中，
        // 省掉首次磁盘/网络读+解码+GPU 上传的一次小顿
        val coverPx = with(density) { 62.dp.roundToPx() }
        val avatarPx = with(density) { 58.dp.roundToPx() }
        val loader = coil.Coil.imageLoader(appCtx)
        // 与显示端同源解析：已下载歌预热落盘封面文件（零网络），未下载仍预热网络 URL
        com.ncm.watch.data.PlayerEngine.current?.let { s ->
            com.ncm.watch.ui.components.songCoverModel(s)?.let { model ->
                loader.enqueue(
                    coil.request.ImageRequest.Builder(appCtx)
                        .data(model)
                        .size(coverPx)
                        .allowHardware(true)
                        .build(),
                )
            }
        }
        com.ncm.watch.data.SessionStore.avatarUrl.takeIf { it.isNotEmpty() }?.let { url ->
            loader.enqueue(
                coil.request.ImageRequest.Builder(appCtx)
                    .data(url)
                    .size(avatarPx)
                    .allowHardware(true)
                    .build(),
            )
        }
        // 第一帧只画浮层：组合量极小 → 冷启动首帧从 ~790ms 降到 ~250ms 级
        androidx.compose.runtime.withFrameNanos { }
        // 开始组合重量级导航图（播放页 + 全局背景层）：这段主线程被占（实测 ~900ms），
        // 但整段都在浮层遮挡下完成，用户看不到任何掉帧
        contentReady = true
        // 等两帧：让导航图的组合/布局/首次绘制走完再揭开
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        kotlinx.coroutines.delay(120)
        showSplash = false
        // 冷启动优化 R2（2026-09-25）：warmUp 移出首帧窗口——ExoPlayer 后台构建虽不堵主线程，
        // 但在 W5 的 4 小核上与首帧渲染/首屏组合抢 CPU，正是「刚启动滑动掉帧」的帮凶。
        // ★ R3（2026-09-30）实测：揭示瞬间仍有 1s 级卡顿（Davey 1073ms / Skipped 62 frames），
        //   那一下正是「浮层撤掉 → 播放页首次真正绘制」的窗口，ExoPlayer 构建 + 解码器扫描 +
        //   MediaSession 注册 + 前台服务拉起全挤在里面抢 CPU。现把整段推迟到揭开之后，
        //   期间点歌由 ensurePlayer() 同步兜底，功能不受影响（实测这段仅 ~140ms）。
        kotlinx.coroutines.delay(1000)
        PlayerEngine.warmUp()
    }
    LaunchedEffect(Unit) {
        // 冷启动错峰：网络自愈/房间恢复/缓存预热全部延后 1.5s —— 这些回包回主线程重组，
        // 若在首帧立即执行，正好撞上用户冷启动后的第一轮滑动（W5 + JIT 冷态下重组代价放大数倍）
        kotlinx.coroutines.delay(1500)
        // ★ 启动自愈：登录态但 uid 缺失（登录时 account 恰好失败/多账号旧档）→ 补抓用户资料。
        //   旧条件 dest == Routes.HOME 永远为假（dest 只会是 CONSENT/PLAYER/LOGIN_QR），
        //   补抓从未执行过 → uid=0 的账号听歌排行/歌曲百科云端次数整个拿不到（百科退显本地计数）
        if (SessionStore.loggedIn && SessionStore.uid == 0L) {
            runCatching { com.ncm.watch.data.NcmApi.ensureUid() }
        }
        // 红心列表同步（原在 warmUp 预热期，错峰到此处）
        if (SessionStore.loggedIn) PlayerEngine.refreshLikes()
        // ★ 一起听断线恢复：服务端仍报在房（杀进程/掉线后重进）→ 直接复活房间会话，
        //   轮询/心跳/房间聊天全部接上 —— 「退出软件重进实际仍在歌房」
        if (SessionStore.loggedIn && !com.ncm.watch.data.ListenSession.active) {
            val restored = runCatching { com.ncm.watch.data.ListenSession.tryRestore() }.getOrDefault(false)
            if (restored) {
                android.widget.Toast.makeText(
                    appCtx, "一起听房间已恢复，可在「一起听」回到房间",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        // 预加载：我喜欢/歌单/每日推荐后台预热，进页面秒开
        com.ncm.watch.data.PageCache.prefetch()
    }

    // 全局底层背景层：默认纯色，设置背景图后为高斯模糊图（玻璃材质的底）
    //
    // ★ 2026-10-02 方形设备适配（用户口径「检测到安装设备是方形的时候，填充空缺区域」）：
    //   圆形裁切只在**圆屏**上做。方屏（方形表 / 手机 / 模拟器）照旧裁圆的话，四角会被裁空，
    //   而 MainActivity 为了冷启动首帧优化把窗口背景设成了 null ⇒ 四角直接露黑，
    //   也就是用户看到的「空缺区域」。方屏改为不裁切：背景层与所有页面（含启动浮层）
    //   一起铺满整屏，四角由环境色/自定义背景图填满。
    //   判据见 components/Common.kt 的 isRoundScreen()（读系统 SCREENROUND 位）。
    Box(
        Modifier
            .fillMaxSize()
            .then(if (isRoundScreen()) Modifier.clip(CircleShape) else Modifier),
    ) {
        // ★ 冷启动专项 R4（2026-09-30）：导航图与全局背景层等 contentReady 才组合。
        //   实测首帧窗口主线程被占 ~900ms（Choreographer: Skipped 55 frames / Davey 936ms），
        //   全部来自「播放页首轮组合」。现在先只组合浮层（极轻，首帧 200ms 级就到），
        //   再在浮层遮挡下组合重量级导航图 —— 用户看到的是「秒开启动页 → 平滑揭开」，
        //   不再有揭开瞬间的 1s 大帧。同时 restoreState 提前到组合之前落地，
        //   播放页一次成型（旧版先按空态组合、状态回来后再整体重组，等于组合两遍）。
        if (contentReady) {
        com.ncm.watch.ui.components.NcmBackground()
        val nav = rememberNavController()
        // 2026-10-01 晚（用户要求）：原来这里有一个「艺人页歌曲列表左划 → 专辑 Tab」的手势信号
        //   （待消费布尔 + onSwipeLeft 回调），现连同 Album Tab 的过渡动画一并移除，
        //   所以这个状态和它下面那串透传参数都不需要了。
        // 播放器组页面在栈顶 → 全局背景层切环境色（NcmBackground.AmbientSurface）：
        // 手势拖拽半透明/滑出淡出/转场缝隙透出的全是环境色，自定义图任何时刻都不会闪现。
        // ★ 路由必须在组合期读取（下面 currentRoute 这行）：只在 SideEffect lambda 里读
        //   是组合后执行、不订阅状态 —— 路由变化不会重组本层，active 会卡死在初值，
        //   表现为「所有页面的自定义背景全没了」（2026-09-26 首版就栽在这里）。
        val currentEntry by nav.currentBackStackEntryAsState()
        val currentRoute = currentEntry?.destination?.route
        androidx.compose.runtime.SideEffect {
            com.ncm.watch.ui.components.AmbientSurface.active = currentRoute in AMBIENT_SURFACE_ROUTES
        }
        DisposableEffect(nav) {
            TransitionCoordinator.bind(nav)
            onDispose { TransitionCoordinator.unbind(nav) }
        }
        NavHost(
        navController = nav,
        startDestination = dest!!,
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned(TransitionCoordinator::updateViewport),
        // ★★ 转场按来源分两套（2026-09-30 曲线统一 → 2026-10-01 按来源分派动效）
        //   ① 滑动来源（NavMotionKind.Slide）：标准传送带 —— 旧页整幅滑出、新页并排推入，
        //      两页硬拼接、全程零淡化。分界线把画面切成两半 ⇒ 全帧平均亮度就是归一化位移的
        //      直接读数（旧页《新建歌单6》195.90 / 新页《我的》238.10），据此反解出曲线，
        //      RMSE 0.0078，优于最好的内置缓动（FastOutSlowIn 0.0300）近 4 倍。
        //      用户明确要求「滑动切换不变」，这套一个字没动。
        //   ② 点击 / 自动来源（Pill / Circle / Directional）：统一缩放（用户要求「所有点击
        //      切换场景换成缩放动画」）。曲线与时长仍共用 ① 的 NavGlide / NAV_MS，
        //      只有位移换成了缩放 + 淡化，所以两套动效的「节奏」还是同一个。
        //   （旧守则「按目标页是否播放器组分支」已废弃：滑动对开时两页不重叠，
        //     入页本身不透明，既不会露自定义背景图，也不会有静止底衬的同屏感。）
        enterTransition = {
            // ★ 2026-10-01：栈底右滑（见 PlayerScreen）走的是「前进导航到主页」，但必须装成
            //   返回 —— 否则下层页会从右边滑入、被拖出去的页往左飞（用户报的「第一次右滑
            //   退出动画往左飘」）。武装了手势旗标时，入场页从左侧滑入，与 popEnter 同款。
            if (TransitionCoordinator.isGesturePop(initialState)) {
                // ★ 2026-10-01：右滑返回整条链路改为**缩放转场**（用户两次反馈「右滑不是缩放」）。
                //   退出页的缩小由它自己的 SwipeBackFader 完成（跟手缩到 0.55 → 松手收向 0.50），
                //   下层页照轻舟实测「不缩放、只在后半程淡入」，所以这里把原来的左侧滑入换成
                //   延迟淡入 —— 两页之间不再有任何横向位移，只剩缩放 + 淡化。
                val ms = TransitionCoordinator.gesturePopExitMs(initialState)
                fadeIn(
                    tween(
                        (ms * 0.75f).toInt().coerceAtLeast(60),
                        delayMillis = (ms * 0.25f).toInt(),
                        easing = LinearEasing,
                    ),
                )
            } else {
                val spec = TransitionCoordinator.read(targetState)
                if (spec.kind.isSwipe) {
                    // 滑动来源：传送带对开（退页左移滑出、入页同速从右滑入）—— 用户要求「滑动切换不变」
                    slideInHorizontally(navSpec()) { it }
                } else {
                    // 点击/自动来源：轻舟「缩放」转场规格（见文件末尾 QZ_* 常量）。
                    // 入页 50% → 100%，带回弹（峰值 1.038 @ 62% 进度）；缩放原点固定屏幕正中
                    // （实测 0.496/0.496）；内容在总时长 ~32% 内淡入完毕（实测 α 0.30→1.00）
                    scaleIn(
                        animationSpec = tween(QZ_MS, easing = QzZoom),
                        initialScale = QZ_FROM,
                        transformOrigin = TransformOrigin.Center,
                    ) + fadeIn(tween(QZ_FADE_IN_MS, easing = QzFadeIn))
                }
            }
        },
        exitTransition = {
            // ★ 2026-10-01：同上 —— 武装了手势旗标的「前进导航」，被滑出页的位移完全由
            //   它自己的 SwipeBackFader 接续（手指方向，向右），NavHost 只负责淡出。
            //   （pop 场景不会走到这里 —— pop 用的是 popExit，所以这个分支只服务栈底右滑。）
            if (TransitionCoordinator.isGesturePop(initialState)) {
                // 淡出与 SwipeBackFader 的缩放**同长收束**：旧版是 ms*0.75，比缩放早结束，
                // 页面缩到一半就透明了，观感像"没缩完就消失"（用户反馈的"犹豫"来源之一）。
                val ms = TransitionCoordinator.gesturePopExitMs(initialState)
                fadeOut(tween(ms))
            } else {
                val spec = TransitionCoordinator.read(targetState)
                if (spec.kind.isSwipe) {
                    slideOutHorizontally(navSpec()) { -it }
                } else {
                    // 轻舟的退页**完全不缩放**（实测恒 1.000，模板匹配 NCC 0.996~0.999，四帧无漂移），
                    // 只是在总时长前约 46% 内淡出（实测亮度因子 0.951→0.562）。
                    // 这条是与旧版最大的差别：旧版退页反向放大到 108%，轻舟没有这个「掠镜」效果。
                    fadeOut(tween(QZ_FADE_OUT_MS, easing = LinearEasing))
                }
            }
        },
        // 返回方向就是参考录屏里那条曲线本身：旧页整幅右移滑出、下层页从左侧并排滑入。
        // 两页不重叠，所以不再需要「下层页提前淡入盖住退页」的补丁（旧版 60-90ms 微淡入
        // 的观感是「下层页直接闪现」，2026-09-26 的注释已记录）。
        popEnterTransition = {
            if (TransitionCoordinator.isGesturePop(initialState)) {
                // ★ 2026-10-01：同上 —— 手势返回改为缩放转场，被揭示的下层页不缩放、
                //   只在后半程淡入（与轻舟实测一致），不再从左缘并排滑入。
                val ms = TransitionCoordinator.gesturePopExitMs(initialState)
                fadeIn(
                    tween(
                        (ms * 0.75f).toInt().coerceAtLeast(60),
                        delayMillis = (ms * 0.25f).toInt(),
                        easing = LinearEasing,
                    ),
                )
            } else {
                val spec = TransitionCoordinator.read(initialState)
                if (spec.kind.isSwipe) {
                    // 整幅不透明滑入，与 popExit 同速对开（返回传送带）
                    slideInHorizontally(navSpec()) { -it }
                } else {
                    // 轻舟返回时，被揭示的下层页同样**不缩放**（实测恒 1.000），
                    // 淡入发生在后半程 —— 前半程屏幕上只有正在缩小的上层页（见 popExit）。
                    fadeIn(
                        tween(
                            QZ_FADE_OUT_MS,
                            delayMillis = QZ_MS - QZ_FADE_OUT_MS,
                            easing = LinearEasing,
                        ),
                    )
                }
            }
        },
        popExitTransition = {
            if (TransitionCoordinator.isGesturePop(initialState)) {
                // ★ 2026-10-01：手势返回的**缩放由退出页自己的 SwipeBackFader 负责**
                //   （跟手缩到 0.55 → 松手收向 0.50），NavHost 这里只做淡出。
                //   ⚠ 不要在这里再叠 scaleOut —— SwipeBackFader 已经在缩，两层缩放会相乘，
                //   页面会直接缩成一个小点。
                val ms = TransitionCoordinator.gesturePopExitMs(initialState)
                // 与 SwipeBackFader 的缩放同长收束（旧版 ms*0.75 会早于缩放结束，页面缩到
                // 一半就透明，显得"没缩完就没了"）。
                fadeOut(tween(ms))
            } else {
                val spec = TransitionCoordinator.read(initialState)
                if (spec.kind.isSwipe) {
                    slideOutHorizontally(navSpec()) { it }
                } else {
                    // 轻舟的返回 = 入场曲线的**时间反演**（实测：全程 494ms 里，前 38% 仍是
                    // 1.000→1.038→1.000 的回弹，后段才收向 0.50）。但真机上那一下回弹读起来
                    // 就是「犹豫」——用户要求优化，故改用严格单调的 QzExitMono（直接缩放）。
                    scaleOut(
                        animationSpec = tween(QZ_MS, easing = QzExitMono),
                        targetScale = QZ_FROM,
                        transformOrigin = TransformOrigin.Center,
                    ) + fadeOut(
                        tween(
                            QZ_FADE_IN_MS,
                            delayMillis = QZ_MS - QZ_FADE_IN_MS,
                            easing = QzFadeIn,
                        ),
                    )
                }
            }
        },
    ) {
        // 带滑动返回的页面
        fun backable(
            onSwipeLeft: (() -> Unit)? = null,
            content: @Composable androidx.compose.animation.AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit,
        ): @Composable androidx.compose.animation.AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit = { e ->
            SwipeBackBox(onBack = { nav.popBackStack() }, onSwipeLeft = onSwipeLeft) { content(e) }
        }

        composable(Routes.CONSENT) {
            com.ncm.watch.ui.screens.settings.ConsentScreen(
                onAgree = {
                    com.ncm.watch.data.ConsentStore.agree() // 修复：此前漏存同意标记导致每次启动都弹
                    nav.navigateWithMotion(
                        if (SessionStore.loggedIn) Routes.PLAYER else Routes.LOGIN_QR,
                        NavMotionSpec(NavMotionKind.Directional),
                    ) {
                        popUpTo(Routes.CONSENT) { inclusive = true }
                    }
                },
                onDecline = {
                    (nav.context as? android.app.Activity)?.finishAffinity()
                },
            )
        }
        composable(Routes.LOGIN_QR) {
            LoginQrScreen(onLoggedIn = {
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Directional)) {
                    popUpTo(Routes.LOGIN_QR) { inclusive = true }
                }
            })
        }
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.SEARCH, content = backable(
            onSwipeLeft = {
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Slide))
            },
        ) { SearchScreen(nav) })

        composable(
            Routes.SEARCH_Q,
            arguments = listOf(navArgument("q") { type = NavType.StringType }),
            content = backable(
                onSwipeLeft = {
                    nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Slide))
                },
            ) { e ->
                val q = e.arguments?.getString("q") ?: ""
                SearchScreen(nav, initialQuery = java.net.URLDecoder.decode(q, "UTF-8"))
            },
        )
        composable(Routes.DAILY_RECOMMEND, content = backable { DailyRecommendScreen(nav) })
        // 左划进播放页（与我喜欢的/歌单列表页，用户要求 2026-09-25）
        composable(
            Routes.LIKED_SONGS,
            content = backable(onSwipeLeft = {
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Slide))
            }) { LikedSongsScreen(nav) },
        )
        composable(Routes.MY_PLAYLISTS, content = backable { MyPlaylistsScreen(nav) })
        composable(Routes.LOCAL_MUSIC, content = backable { LocalMusicScreen(nav) })
        composable(Routes.PRIVATE_ROAM, content = backable { PrivateRoamScreen(nav) })
        composable(Routes.FRIENDS, content = backable { FriendsScreen(nav) })
        composable(Routes.SETTINGS, content = backable { SettingsScreen(nav) })
        composable(Routes.RECORD, content = backable {
            com.ncm.watch.ui.screens.record.RecordScreen(nav)
        })
        composable(Routes.SLEEP_TIMER, content = backable {
            com.ncm.watch.ui.screens.player.SleepTimerScreen(nav)
        })

        composable(
            Routes.ALBUM_DETAIL,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
            content = backable { e -> AlbumScreen(nav, e.arguments?.getLong("albumId") ?: 0L) },
        )

        composable(
            Routes.PLAYLIST_DETAIL,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
            content = backable(onSwipeLeft = {
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Slide))
            }) { e -> PlaylistDetailScreen(nav, e.arguments?.getLong("playlistId") ?: 0L) },
        )

        composable(
            Routes.ARTIST_DETAIL,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType }),
            // 2026-10-01 晚（用户要求）：去掉「歌曲列表左划切到专辑 Tab」的手势 ——
            //   改回普通 backable，左划不再有动作（右划返回保留）。
            //   艺人页 Tab 切换同时去掉过渡动画，所以也不再需要向下透传手势状态。
            content = backable { e ->
                ArtistScreen(nav, e.arguments?.getLong("artistId") ?: 0L)
            },
        )

        composable(
            Routes.ARTIST_FANS,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType }),
            content = backable { e ->
                com.ncm.watch.ui.screens.artist.ArtistFansScreen(
                    nav, e.arguments?.getLong("artistId") ?: 0L,
                )
            },
        )

        composable(
            Routes.FAN_POST,
            content = backable { _ ->
                com.ncm.watch.ui.screens.artist.PostDetailScreen(nav)
            },
        )

        composable(
            Routes.COMMENTS,
            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
            content = backable { e ->
                com.ncm.watch.ui.screens.comments.CommentsScreen(
                    nav, e.arguments?.getLong("songId") ?: 0L,
                )
            },
        )

        composable(Routes.PLAYER) { e ->
            // ★ 2026-10-01：栈底右滑走「前进导航到主页」后，这个 entry 没被 pop、仍留在栈里，
            //   手势旗标会一直挂着 —— 之后它自己被 pop 时会被误判成手势返回（页面原地淡出、
            //   不滑动）。所以每次重新入场要清一次。
            //   ⚠ 必须放 LaunchedEffect，不能写在组合期：组合期写导航状态会在**离场转场进行中**
            //   被反复执行（播放页每 500ms 因 positionMs 重组一次），正是「右滑退出后页面残留
            //   闪一下再消失」的一帧级怪象来源。离场转场期间旧内容的副作用不会重跑，旗标安全。
            androidx.compose.runtime.LaunchedEffect(e) { TransitionCoordinator.clearGesturePop(e) }
            PlayerScreen(nav)
        }
        composable(Routes.LYRICS) { LyricsScreen(nav) }
        composable(Routes.MORE, content = backable { MoreScreen(nav) })
        composable(Routes.QUEUE, content = backable { QueueScreen(nav) })
        composable(
            Routes.SONG_BAIKE,
            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
            content = backable { e -> SongBaikeScreen(nav, e.arguments?.getLong("songId") ?: 0L) },
        )
        composable(Routes.PLAY_HISTORY, content = backable { HistoryScreen(nav) })
        composable(Routes.DOWNLOAD_MGR, content = backable { DownloadManagerScreen(nav) })
        composable(Routes.QUALITY, content = backable { QualityScreen(nav) })
        composable(
            Routes.USER_DETAIL,
            arguments = listOf(navArgument("userId") { type = NavType.LongType }),
            content = backable { e -> UserProfileScreen(nav, e.arguments?.getLong("userId") ?: 0L) },
        )
        composable(Routes.COLLECT, content = backable { CollectScreen(nav) })

        composable(Routes.LISTEN_TOGETHER, content = backable { ListenTogetherScreen(nav) })
        composable(Routes.LISTEN_ROOM) { ListenRoomScreen(nav) }

        composable(
            Routes.FRIEND_CHAT,
            arguments = listOf(navArgument("friendId") { type = NavType.LongType }),
            content = backable { e -> ChatScreen(nav, e.arguments?.getLong("friendId") ?: 0L) },
        )
        composable(Routes.MESSAGES, content = backable { MessagesScreen(nav) })
        composable(Routes.SHARE_PICK, content = backable { SharePickScreen(nav) })

        composable(Routes.ACCOUNT, content = backable { AccountScreen(nav) })
        composable(Routes.CACHE_CLEAN, content = backable { CacheCleanScreen(nav) })
        composable(Routes.SWITCH_ACCOUNT, content = backable { SwitchAccountScreen(nav) })
        composable(Routes.ADD_ACCOUNT_QR, content = backable { AddAccountQrScreen(nav) })

        composable(Routes.THEME, content = backable {
            com.ncm.watch.ui.screens.settings.ThemeSettingsScreen(nav)
        })
        composable(Routes.DISCLAIMER, content = backable { DisclaimerScreen(nav) })
        }

        } // end if (contentReady)：重量级导航图组合结束（这段耗时被启动浮层遮住）
        // 启动加载浮层：盖在目标页上方，目标页首帧落屏后才淡出（见上面的启动编排）
        androidx.compose.animation.AnimatedVisibility(
            visible = showSplash,
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)),
            modifier = Modifier.fillMaxSize(),
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxSize()
                    // ★ 99.5% 不透明（2026-09-30 冷启动专项 R5）：完全不透明时，合成器会
                    //   跳过浮层下方内容的绘制 —— 于是「揭开那一帧」才第一次真正画播放页
                    //   （实测揭开瞬间仍有一帧 228ms：着色器首次编译 + 背景图纹理上传）。
                    //   留 0.5% 透光（肉眼与纯色底完全无异）强制下方内容在浮层期间就已绘制，
                    //   首绘成本落在被遮挡的窗口里，揭开变成纯 alpha 动画。
                    .background(Bg.copy(alpha = 0.995f)),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                androidx.compose.material3.Icon(
                    com.ncm.watch.ui.components.NcmIcons.Play, contentDescription = "WMusic",
                    tint = com.ncm.watch.ui.theme.Accent, modifier = Modifier.size(46.dp),
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Text(
                    "WMusic",
                    fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = com.ncm.watch.ui.theme.TextPrimary,
                )
            }
        }
    }
}

/** 供外部（如设置页登出后）拿到 nav 的辅助扩展 */
fun NavHostController.backToHome() {
    navigateWithMotion(Routes.HOME, NavMotionSpec(NavMotionKind.Directional)) {
        popUpTo(0) { inclusive = true }
    }
}

/**
 * 播放器组页面（2026-09-26）：自带不透明环境色底（ambientSurfaceColor），不透出全局背景层。
 * 只有播放页/歌词页/更多页三个 —— 其余页面 screenBg()=透明，直接透出全局自定义背景图。
 */
private val AMBIENT_SURFACE_ROUTES = setOf(Routes.PLAYER, Routes.LYRICS, Routes.MORE)

/**
 * 页面转场统一参数（2026-09-30 从参考录屏 SVID_20260930_221208_1.mp4 逐帧反解）
 *
 * 参考转场是标准传送带：旧页整幅右移滑出、新页从左侧并排推入，两页硬拼接、全程零淡化。
 * 分界线把画面切成两半 ⇒ **全帧平均亮度就是归一化位移的直接读数**
 * （旧页《新建歌单6》195.90 / 新页《我的》238.10，跨度 42.2），不需要模板匹配，
 * 对 H.264 的块效应也极稳（转场②④ 两组样本逐位一致，证明该动效可重复、非 spring 抖动）。
 *
 * 采样点（归一化时长 → 归一化位移）：
 *     10% →  5.4%      20% → 20.5%      30% → 41.9%
 *     40% → 61.4%      50% → 76.0%      60% → 86.1%
 *     70% → 92.9%      80% → 97.1%      90% → 99.3%
 * 峰值斜率 2.19 @ t=0.27；起点斜率 0.16（软起步）；终点斜率 0.00（长尾滑行）。
 * 拟合 RMSE 0.0078 —— 优于最好的内置缓动 FastOutSlowIn(0.0300) 近 4 倍，
 * 其余内置（easeOutCubic 0.157 / Linear 0.165 / M3Emphasized 0.147）差一个数量级。
 * 解析验证：x'(s) = 0.9 - 1.86s + 3.09s²，判别式 < 0 恒正；y'(s) 在 [0,1] 上恒正 ⇒ 零过冲。
 *
 * 时长 388ms 直接来自测量（22.5 帧 @ 58.04fps），取整 380ms，
 * 进入/退出/返回/手势返回全部共用 —— 这是「连贯」的关键：以前 200/160/210 三种时长混用。
 *
 * ★ 2026-10-01 按**来源**分成两套动效（见 [NavMotionKind.Slide]）：
 *   - 滑动来源（左划 / 下滑 / 栈底右滑）＝ 传送带对开，**只认 NavGlide / NAV_MS**，
 *     用户明确要求「滑动切换不变」，这条路径一个字节都不要动。
 *   - 点击 / 自动来源 ＝ 轻舟「缩放」转场，走下面 QZ_* 那一组常量。
 */
internal const val NAV_MS = 380
internal val NavGlide = CubicBezierEasing(0.30f, 0.04f, 0.29f, 1.00f)

/* ==========================================================================================
 * 轻舟「缩放」转场规格（2026-10-01 真机录屏逐帧反解，非调参）
 * ------------------------------------------------------------------------------------------
 * 取证方式：手表 OWW261（466×466 圆屏 / API 30）录「更多 → 设置」与「设置 → 更多」两组
 * 入退栈转场。screenrecord 是**变帧率**容器，抽帧必须 `-fps_mode passthrough` 保真实时间轴，
 * 时间基准一律取 ffprobe 的 pts_time；缩放用「末帧为模板 + AFFINE 缩放 + 归一化互相关取峰」
 * 反解，并用「行块左右边缘跨度」几何量测交叉校验（两法同结论）。
 *
 * 轻舟 UI 里可直接读到的值：过渡动画 =「缩放」，动画时长 = 350ms（可调，拉到最大实测 494ms）。
 * 两次录制（T=350ms / T=494ms）归一化后曲线重合，证明曲线形状与时长无关。
 *
 * 实测结论：
 *   ① 缩放原点 = 屏幕正中。几何量测每一帧的放大中心恒为 (0.496, 0.496)，无一帧漂移。
 *   ② 入页缩放 0.50 → 1.00，**带回弹**：峰值 1.038 @ 进度 62%，随后单调落回 1.00。
 *      （「Flutter 官方缩放是 0.85→1.00 单调不过冲」在这里对不上：官方
 *        ZoomPageTransitionsBuilder 的 _scaleCurveSequence 峰值恰为 1.000，不产生过冲；
 *        实测 1.038 且模板匹配 / 几何量测两法交叉确认，所以那套参数不能用。）
 *      拟合（T=350ms，20 点，RMS 0.0013）：begin 0.4994，Cubic(0.170, 1.012, 0.369, 1.215)
 *      拟合（T=494ms，26 点，RMS 0.0036）：begin 0.5193，Cubic(0.097, 0.966, 0.306, 1.232)
 *      取两组均值 → [QZ_FROM] / [QzZoom]。
 *   ③ 入页内容淡入：α 0.30(t=43ms) → 0.63 → 0.80 → 0.97 → 1.00，约在总时长 32% 处到位。
 *      （线性回归 frame ≈ α·模板 + β 给出 **β≈0**，说明新页是叠在**纯黑**上淡入，
 *        不是与旧页交叉溶合。）→ [QZ_FADE_IN_MS]
 *   ④ 退页**完全不缩放**：旧页模板在环形区域拟合恒为 1.000（NCC 0.996~0.999，四帧无漂移），
 *      只是变暗：亮度因子 0.951 → 0.777 → 0.718 → 0.643 → 0.562，外推总时长 ~46% 处归零。
 *      → 退页只 fadeOut，不加 scale。
 *   ⑤ 返回 = 入场曲线的**时间反演**：退栈 494ms 里前 38% 仍是 1.000→1.038→1.000 的回弹，
 *      之后才收向 0.50；被揭示的下层页同样不缩放，只在后半程淡入。
 *      → 用 [QzZoomBack]（= 1 - QzZoom(1-u)）而不是 QzZoom。
 *
 * 与 WMusic 上一版「点击缩放」的差异（有意为之，为对齐轻舟）：
 *   - 起点 0.84 → 0.50，曲线由零过冲的 NavGlide 换成带回弹的 QzZoom；
 *   - 缩放原点由「被点控件的归一化位置」改成**固定屏幕正中**（轻舟即如此）；
 *   - 退页由「反向放大到 108% + 延迟淡出」改成**只淡出、不缩放**。
 * ========================================================================================== */

/** 轻舟默认「动画时长」350ms（其设置页可直接读到；可调量程实测约 200–500ms）。 */
internal const val QZ_MS = 350

/** 入页起始缩放。两次实测拟合 0.4994 / 0.5193，取 0.50。 */
private const val QZ_FROM = 0.50f

/** 入页缩放缓动：峰值 1.077（× 0.5 行程 ⇒ scale 峰值 1.038），峰值位于进度 0.62。 */
internal val QzZoom = CubicBezierEasing(0.13f, 0.99f, 0.34f, 1.22f)

/**
 * 返回方向的缩放缓动 = 入场曲线的时间反演。
 * `scaleOut` 算的是 `1 + (target-1)·E(u)`，要得到轻舟的 `0.5 + 0.5·E(1-u)`，
 * 必须传 `E_back(u) = 1 - E(1-u)` —— 否则会「先缩过头（0.46）再涨回来」。
 */
private object QzZoomBackEasing : androidx.compose.animation.core.Easing {
    override fun transform(fraction: Float): Float = 1f - QzZoom.transform(1f - fraction)
}
private val QzZoomBack: androidx.compose.animation.core.Easing = QzZoomBackEasing

/**
 * 退场缩放的缓动（2026-10-01 新增，已替代上面的 [QzZoomBack] 用于**点击/按钮返回**）。
 *
 * [QzZoomBack] 是入场曲线的**时间反演**：前 38% 先从 1.000 回弹放大到 1.038，之后才收向 0.50。
 * 那是轻舟的真实行为，但真机上看就是「先顿一下、再缩」，用户直接反馈
 * 「界面退出曲线需要优化，现在显得很犹豫」。这里换成**严格单调**的 easeOut：
 * 一上来就以最快速度收束、越缩越慢、干净落位，全程没有任何反向运动。
 *
 * ⚠ 入场方向仍保留 [QzZoom] 的回弹 —— 「弹入」是用户认可的观感，只改退出。
 */
internal val QzExitMono = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

/** 新页内容淡入时长：实测在总时长 ~32% 处到 1.00。 */
private const val QZ_FADE_IN_MS = 112

/** 退页淡出时长：实测在总时长 ~46% 处归零（线性）。 */
private const val QZ_FADE_OUT_MS = 160

/** 淡入用的减速缓动（≈ easeOutQuad），与实测 α 序列吻合。 */
private val QzFadeIn = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

private fun <T> navSpec(ms: Int = NAV_MS) = tween<T>(ms, easing = NavGlide)
