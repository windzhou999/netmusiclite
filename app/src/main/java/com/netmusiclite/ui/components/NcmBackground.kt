package com.netmusiclite.ui.components

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.netmusiclite.data.BackgroundStore
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.dominantColorOf
import com.netmusiclite.ui.theme.Bg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全局底层背景：
 * - 用户设置了背景图 → 绘制该图（背景模糊滑杆控制）+ 压暗层；毛玻璃模糊发生在卡片内部
 *   （各玻璃件用 frostedGlass() 从 BackgroundStore.blurredImage 按屏幕位置取样）。
 * - 无背景图 → 环境色模式（2026-09-13 全局生效）：底色取当前播放歌曲封面主色并向黑大幅
 *   压暗（与背景图模式同为全局），卡片同步纯黑（frostedGlass 无背景分支）。
 *   取色永不失败：主桶 → 有像素均值 → 整图均值三级兜底；封面加载失败保留上一首颜色。
 */
/**
 * 歌曲环境色（当前播放封面主色，已向黑压暗 75%）：
 * - 无背景图：NcmBackground 全局使用；
 * - 有背景图 + 「播放页/歌词页保留取色」开关开：播放页/歌词页覆盖使用。
 * 取色永不失败：主桶 → 有像素均值 → 整图均值三级兜底；封面加载失败保留上一首颜色。
 */
object SongAmbient {
    /**
     * 环境色基准色：取色结果向它插值。
     * 深色主题 → 近黑（原有行为）；浅色主题 → 近白。
     * ★ 2026-10-01：浅色主题下若仍向黑压暗，整个页面会变成深色底 + 深色文字，
     *   与卡片/文字的浅色板直接冲突，所以插值目标必须跟随主题翻转。
     */
    private fun base(): Color =
        if (com.netmusiclite.ui.theme.isLightTheme) Color(0xFFEFEFF3) else Color(0xFF0A0A0C)

    var color by mutableStateOf(Color(0xFF0A0A0C))
        private set

    /**
     * ★ 2026-10-02 新增：**原始**封面主色（未向主题基准色插值的那一份）。
     * 「自动取色」在未设背景图时的色源（见 Theme.kt 的 EffectiveSeed）——
     * 用户口径「自动取色打开以后无论是否设置壁纸都生效」：背景在无图时本就跟随
     * 封面环境色（color 字段），强调色补上同一色源后整条链路无壁纸也生效。
     * 取色失败保留上一首的值（与 color 同策略）；初始 null = 尚未取到过，回落预设种子色。
     */
    var rawColor by mutableStateOf<Color?>(null)
        private set

    suspend fun pull(model: Any?) {
        val b = base()
        if (model == null) {
            color = b
            return
        }
        val c = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(NcmContextHolder.app).data(model).size(64).allowHardware(false).build()
                (NcmContextHolder.app.imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap
                    ?.let { dominantColorOf(it, Bg) }
            }.getOrNull()
        }
        // 封面网络/解码失败：保留上一首颜色（不退默认），快速切歌不再“失效”
        // 浅色主题插值系数加大到 0.86：浅底上需要更弱的彩度才不刺眼
        if (c != null) {
            rawColor = c
            color = lerp(c, b, if (com.netmusiclite.ui.theme.isLightTheme) 0.86f else 0.75f)
        }
    }
}

/** 应用上下文持有者：供无 Compose 上下文的取色协程使用 */
object NcmContextHolder {
    @Volatile
    lateinit var app: android.content.Context
}

/**
 * 播放页/歌词页表面色（2026-09-13）：
 * 无背景图（全局环境色模式）或「保留取色」开（有背景图）→ 歌曲环境色；否则透明透出全局背景。
 */
@Composable
fun ambientSurfaceColor(): Color {
    val raw = if (!BackgroundStore.ready || BackgroundStore.keepAmbient) SongAmbient.color else Color.Transparent
    // 220ms：颜色动画每帧触发含背景色的节点重绘，W5 上 450ms 的重绘窗口在切歌瞬间可感拖尾
    return animateColorAsState(raw, tween(220), label = "ambientSurface").value
}

/**
 * 播放器组页面激活标记（2026-09-26，AppNav 按当前栈顶路由驱动）：
 * 置位时全局背景层从自定义图切到环境色。手势返回的拖拽半透明、滑出淡出、转场缝隙
 * 透出的都是与页面自身底色一致的环境色 —— 自定义背景图不再从任何透明瞬间闪现
 * （转场动画只能管住导航期间，管不住松手前的拖拽阶段，必须在这里治本）。
 */
object AmbientSurface {
    var active by mutableStateOf(false)
}

@Composable
fun NcmBackground(modifier: Modifier = Modifier) {
    val song = PlayerEngine.current
    val ctx = LocalContext.current
    remember { NcmContextHolder.app = ctx.applicationContext }
    // 取色源与封面同源（已下载用落盘文件，断网也能取色；设备本地歌解析为媒体库 Uri，
    // 旧版直接把 local:// 串丢给取色必然失败、环境色永远是上一首——顺带修掉）
    val coverModel = songCoverModel(song)
    // 主题明暗切换时也重新取色（浅色主题的环境色是向白插值，与深色结果完全不同）
    LaunchedEffect(song?.id, coverModel, com.netmusiclite.ui.theme.isLightTheme) {
        SongAmbient.pull(coverModel)
    }
    // 显示自定义图：设置了背景图，且当前不在播放器组；或用户关了「保留取色」
    // （此时播放器组页面自身透明、本来就要透出背景图）
    val showImage = BackgroundStore.ready && (!AmbientSurface.active || !BackgroundStore.keepAmbient)
    val base = animateColorAsState(if (showImage) Bg else SongAmbient.color, tween(220), label = "ambientBase").value
    Box(modifier.fillMaxSize().background(base)) {
        if (BackgroundStore.ready) {
            // 图层随 showImage 淡入淡出（与环境色底交叉溶解），alpha=0 时整层跳过绘制
            val imgAlpha by animateFloatAsState(if (showImage) 1f else 0f, tween(220), label = "bgImgAlpha")
            if (imgAlpha > 0.01f) {
                Box(Modifier.matchParentSize().graphicsLayer { alpha = imgAlpha }) {
                    val bgBitmap = if (BackgroundStore.bgBlur > 0.01f) BackgroundStore.blurredBgImage else null
                    if (bgBitmap != null) {
                        // 背景模糊开启：画模糊底图（滑杆控制强度）
                        Image(
                            bitmap = bgBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        // 默认：清晰原图（按需解码防大图卡顿）
                        // ★ 2026-09-30 冷启动专项：解码档从 932 收到 480 —— 屏幕只有 466px，
                        //   932 是 2 倍冗余：4 倍像素的解码 + GPU 纹理上传全挤在启动首次绘制那一下。
                        // ★ 2026-10-01：cacheKey 带上 version —— 换背景图时文件路径不变，
                        //   不带版本会命中 Coil 旧图缓存（用户报「背景没变、只有玻璃卡变色」）。
                        val bgVer = BackgroundStore.version
                        AsyncImage(
                            model = ImageRequest.Builder(ctx)
                                .data(BackgroundStore.file)
                                .memoryCacheKey("bg:${bgVer}")
                                .diskCacheKey("bg:${bgVer}")
                                .size(480)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    // 压暗层：滑杆可调，兼顾玻璃质感与可读性
                    Box(Modifier.matchParentSize().background(Bg.copy(alpha = BackgroundStore.dim)))
                }
            }
        }
    }
}

// 主色提取算法已抽到 `data/ColorExtract.kt` 的 dominantColorOf()：
// 封面环境色（上面的 SongAmbient）与背景图取色（BackgroundStore.seedColor）共用同一实现。

