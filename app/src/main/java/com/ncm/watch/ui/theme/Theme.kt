package com.ncm.watch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  Material 3 主题层（2026-10-01 主题化改造）
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 改造原则：**布局零改动，只换颜色来源**。
 *
 * 项目原本把 Bg / TextPrimary / Accent 等写成顶层 `val` 常量，被 20+ 个文件
 * 直接 `import com.ncm.watch.ui.theme.Bg` 引用。这里把它们全部改成
 * 「读组合状态的 getter」——名字、类型、语义全部不变，**调用点一个字符都不用动**，
 * 但换主题时所有引用点会自动重组（因为 getter 内部读的是 AppearancePrefs 的组合状态）。
 *
 * 非 Composable 上下文（Canvas draw 回调、取色协程、@Composable 之外的初始化）
 * 读到的同样是当前值，只是不会主动触发重组 —— 与原常量行为一致。
 * ════════════════════════════════════════════════════════════════════════════
 */

/**
 * 一套完整的界面色板。分深/浅两套，字段与项目原有语义一一对应，
 * 只做「同一个位置在不同明暗下取什么色」的映射，不引入新语义。
 */
data class NcmPalette(
    val light: Boolean,
    /** 页面底色 */
    val bg: Color,
    /** 抬升表面（卡片兜底底、对话框底） */
    val bgElevated: Color,
    /** 胶囊半透明材质（高斯玻璃填充） */
    val glass: Color,
    /** 玻璃描边（边缘高光） */
    val glassBorder: Color,
    /** 弱色块（图标底、次级填充） */
    val strong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val separator: Color,
)

// ---- 深色（原有观感，一个色值都没动）----
// 2026-09-11 文字清晰度专项：旧值 TextSecondary 60% / TextTertiary 32% 的
// 小字号在 2.0 密度屏抗锯齿后发灰发虚，抬高下限到 70% / 55%（层级关系保留）
private val DarkPalette = NcmPalette(
    light = false,
    bg = Color(0xFF0A0A0C),
    bgElevated = Color(0xFF16161A),
    glass = Color(0x22FFFFFF),
    glassBorder = Color(0x30FFFFFF),
    strong = Color(0x24FFFFFF),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xB3EBEBF5),
    textTertiary = Color(0x8CEBEBF5),
    separator = Color(0x1AFFFFFF),
)

// ---- 浅色（2026-10-01 新增）----
// 玻璃材质 alpha 重标定：深色下「白 13% 填充」靠透出深底来成材；
// 浅色下底色本身很亮，同样的白 13% 会彻底消失，必须改为「白 72% 填充 + 黑 8% 描边」，
// 才有可辨的玻璃块边界（对应《M3 换肤版预览》里的玻璃 alpha 标定表）。
// ⚠ 底色刻意压到 0xFFEFEFF3 而不是更亮的 #F6F6F9：手表上没有阴影可用（项目用描边+材质
//   区分层级），底色若太接近纯白，「白卡叠白底」会糊成一片、所有卡片边界消失。
//   压到 239 这档后，白卡（≈254）与底色的差值约 15/255，肉眼可辨。
private val LightPalette = NcmPalette(
    light = true,
    bg = Color(0xFFEFEFF3),
    bgElevated = Color(0xFFFFFFFF),
    glass = Color(0xB8FFFFFF),        // 白 72%
    glassBorder = Color(0x14000000),  // 黑 8%
    strong = Color(0x0F000000),       // 黑 6%
    textPrimary = Color(0xFF17171A),
    textSecondary = Color(0xB31A1A1E), // 黑 70%
    textTertiary = Color(0x8C1A1A1E),  // 黑 55%
    separator = Color(0x1F000000),     // 黑 12%
)

/**
 * 当前色板。getter 内部读 AppearancePrefs 的组合状态（模式 / 跟随系统），
 * 所以任何在 @Composable 里读它的地方都会自动注册依赖 —— 换主题即整树重组。
 */
val palette: NcmPalette
    get() = if (com.ncm.watch.data.AppearancePrefs.isLight) LightPalette else DarkPalette

/** 当前是否浅色。供背景取色、系统栏适配等非组件代码判断明暗方向。 */
val isLightTheme: Boolean
    get() = com.ncm.watch.data.AppearancePrefs.isLight

// ════════════════════════════════════════════════════════════════════════════
//  强调色（M3 种子色）
// ════════════════════════════════════════════════════════════════════════════

/**
 * 深色底上的最低可用亮度。
 * 背景 Bg = 0xFF0A0A0C（相对亮度 ≈0.0035），按 WCAG 对比度公式
 * (L+0.05)/(0.0035+0.05) ≥ 3.0 ⇒ L ≥ 0.12 —— 低于此值的强调色作文字/图标等于「看不清」。
 */
private const val ACCENT_MIN_LUMINANCE_DARK = 0.12f

/**
 * 浅色底上的最高可用亮度。
 * 浅底 0xFFEFEFF3（相对亮度 ≈0.87），按 WCAG 对比度公式
 * (0.87+0.05)/(L+0.05) ≥ 3.0 ⇒ L ≤ 0.26 —— 取 0.22 留安全余量。
 * 白色（1.0）、亮黄、亮绿等种子在浅底上完全不可读，必须压深。
 */
private const val ACCENT_MAX_LUMINANCE_LIGHT = 0.22f

/**
 * 深色底可读性保障：深色系预设（Indigo 0xFF3F51B5 亮度≈0.105、部分深蓝）落在 Bg 上
 * 对比度不足 3:1，作为文字/图标时不可读。不足则保持色相朝白色插值到下限；
 * 网易云红(≈0.22)、蓝、橙、绿、紫等本就达标，原样返回不受影响。
 */
private fun Color.boostForDark(): Color {
    if (luminance() >= ACCENT_MIN_LUMINANCE_DARK) return this
    var t = 0.05f
    while (t < 1f) {
        val c = lerp(this, Color.White, t)
        if (c.luminance() >= ACCENT_MIN_LUMINANCE_DARK) return c
        t += 0.05f
    }
    return Color.White
}

/**
 * 浅色底可读性保障（与 boostForDark 对称）：太亮的种子朝黑插值压深。
 * 白色种子(0xFFFFFFFF)会一路压到接近纯黑；网易云红(≈0.22)刚好达标原样保留。
 */
private fun Color.boostForLight(): Color {
    if (luminance() <= ACCENT_MAX_LUMINANCE_LIGHT) return this
    var t = 0.05f
    while (t < 1f) {
        val c = lerp(this, Color.Black, t)
        if (c.luminance() <= ACCENT_MAX_LUMINANCE_LIGHT) return c
        t += 0.05f
    }
    return Color.Black
}

/**
 * 动态取色的「系统侧」种子（Android 12+ 的 Monet 色板）。
 *
 * 由 [NcmTheme] 在组合期从 `dynamicLight/DarkColorScheme(ctx)` 读出 primary 写入。
 * ⚠ 不能在协程或普通函数里调 `dynamicXxxColorScheme` —— 它是 @Composable，
 *   而且内部要读 `android.R.color.system_accent1_*`（API 31 才有的资源），
 *   在 Android 11 上调用会直接抛异常。所以调用点必须用 `SDK_INT >= 31` 判断，
 *   **不能只看开关是否打开**（开关现在已经对所有版本可用了）。
 */
object DynamicSeed {
    /** null = 未开启动态取色，或系统侧不可用（Android 11 及以下改走背景图色） */
    var systemColor by mutableStateOf<Color?>(null)
}

/**
 * 动态取色最终采用的种子色（2026-10-01）：
 *  1. 未开启 → 用户选的预设种子色；
 *  2. Android 12+ → 系统壁纸取色（Monet 色板的 primary）；
 *  3. Android 11 及以下 → **自定义背景图主色**（本机连 wallpaper 服务都不存在，
 *     背景图是这台设备上唯一可自定义的「壁纸」，见 `BackgroundStore.seedColor`）；
 *  4. 取不到（如 Android 11 上还没设背景图）→ 回落预设种子色，永不空转。
 *
 * 之所以统一成一个「有效种子」而不是让全局 Accent 与 M3 色板各走一路：
 * 必须保证「图标 / 按钮 / 选中态的强调色」与「ColorScheme.primary」永远是同一个色，
 * 否则会出现「色板是壁纸色、按钮还是网易云红」的撕裂感。
 */
val EffectiveSeed: Color
    get() {
        val accent = com.ncm.watch.data.AppearancePrefs.accent
        if (!com.ncm.watch.data.AppearancePrefs.dynamicColor) return accent
        // 只读一次状态：写成 `x != null -> x!!` 会把同一个 Compose 状态读两遍，
        // 是应当避免的写法（快照理论上可能在两次读取之间被替换）。
        val sys = DynamicSeed.systemColor
        if (sys != null) return sys
        return com.ncm.watch.data.BackgroundStore.seedColor ?: accent
    }

// ---- 全局强调色（动态）：种子来自 EffectiveSeed（预设色或动态取色），读的是 Compose 状态，
// 所有组合中读取 Accent 的地方都会在换色 / 换明暗 / 换背景图时自动重组 ----
val Accent: Color
    get() {
        val seed = EffectiveSeed
        return if (isLightTheme) seed.boostForLight() else seed.boostForDark()
    }

/** 强调色柔光底：白色等浅色种子下改用深色半透明 ——
 *  否则「白字 + 白 20% 底」在玻璃卡上完全糊掉（2026-10-01 主题可读性修复）。
 *  浅色模式下种子已被压深，直接用同色低 alpha 即可，不需要反色。 */
val AccentSoft: Color
    get() = when {
        isLightTheme -> Accent.copy(alpha = 0.14f)
        Accent.luminance() > 0.7f -> Color(0xFF111111).copy(alpha = 0.28f)
        else -> Accent.copy(alpha = 0.2f)
    }

/** 实底强调色上的内容色：主题色为浅色（如白色）时自动切深字，保证对比度 */
val OnAccent: Color
    get() = if (Accent.luminance() > 0.75f) Color(0xFF111111) else Color.White

// ════════════════════════════════════════════════════════════════════════════
//  歌词文字取色（2026-10-01）
// ════════════════════════════════════════════════════════════════════════════

/**
 * 歌词可读性下限：WCAG 对比度 4.5:1（正文档）。
 *
 * 为什么取正文档档位而不是大字号档的 3:1：歌词正文是 15sp / W900，按 WCAG 的
 * 「大字号」定义 3:1 就够。但同等对比度下彩色字的感知清晰度低于白字（白字靠
 * 亮度差、彩字还要靠色相辨认），而且歌词页在取色之外还要再被图层 alpha 压暗
 * （相邻行 0.48、远端 0.36，见 LyricsScreen 的 lyricBrightness）——
 * 所以取色本身按 4.5:1 标定，把余量留给渐变。
 *
 * ⚠ 这**不是**「渐变后的每一行都 ≥4.5:1」。alpha 压暗会把对比度一起压下去，
 *   这是渐变本身的代价，而那条渐变是用户逐轮调出来的（见 LyricsScreen 里
 *   LYRIC_DIM_FAR 的注释：曾压到 0.13 导致远处行读不清，抬到 0.36 是用户口径），
 *   不能为了凑对比度把它拉平。这条下限保证的是**满亮那一行 —— 正在唱的那句 ——
 *   清晰可读**，其余行按与当前行的距离自然衰减。
 */
private const val LYRIC_MIN_CONTRAST = 4.5f

/**
 * 浅色主题 + 自动取色开启时的对比度下限（2026-10-02 用户口径
 * 「提亮浅色模式打开自动取色的聚焦歌词颜色，增加可读性」）。
 *
 * 为什么这条路径要单独放宽：浅色主题下自动取色色会被**两级压暗** ——
 * 先是 [Accent] 的 boostForLight 把亮度压到 ≤0.22（按页面底色标定），
 * 再被 [lyricInk] 的 4.5:1 对着歌词页的浅色环境底（SongAmbient 向近白插值 86%，
 * 相对亮度约 0.61~0.87）继续压到 ≈0.10~0.15 —— 壁纸/背景图的颜色被压成近黑的
 * 深泥色，用户看到的「聚焦歌词发黑、看不清取到的颜色」就是这两级叠加的结果。
 *
 * 放宽到 3.2:1 的依据：聚焦行是 15sp / W900，远超 WCAG「大字号」（≥14pt 粗体）的定义，
 * 大字号的 AA 线是 3:1 —— 取 3.2 留一点余量。效果：最亮底上墨色直接用 Accent 原色
 * （不再二次压暗，亮度约 +45%），最暗底上的压暗量也砍半以上。非聚焦行的明暗仍由
 * 图层 alpha 渐变承担（那是用户逐轮调出来的，不动），本下限保证的仍是满亮那行。
 */
private const val LYRIC_MIN_CONTRAST_DYNAMIC_LIGHT = 3.2f

/** WCAG 相对对比度（两色互换不影响结果） */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * 歌词文字取色 —— 把「自动取色」的范围延伸到歌词正文。
 *
 * 用户口径：「自动取色范围延伸到歌词上面，同时保证歌词可读性」。
 *
 * 色源就是 [Accent]（= [EffectiveSeed]）：本项目里「自动取色」指的正是
 * 设置页那个「跟随壁纸取色」开关 —— 它此前只作用在图标 / 按钮 / 选中态 /
 * 进度弧上，歌词正文一直是恒定的 [TextPrimary]。现在同一支颜色延伸到歌词，
 * 所以自动取色开着时歌词跟着壁纸（Android 12+）或背景图（Android 11）走，
 * 关着时跟随用户选的种子色 —— 与全站强调色始终是同一个色，不会出现撕裂。
 *
 * 可读性为什么要单独算，而不是直接拿 [Accent] 用：
 *   ① [Accent] 是按页面底色 [Bg] 标定的（boostForDark / boostForLight），
 *      而歌词页的底不是 Bg，是 [com.ncm.watch.ui.components.SongAmbient] 的
 *      歌曲环境色 —— 封面主色向近黑压暗 75%（浅色主题向近白压暗 86%）。
 *      底色本身带彩度、亮度还随歌变化：亮封面（亮黄 / 亮青）压暗后仍有
 *      ~0.05 的相对亮度，直接套用「按 Bg 标定的 Accent」在那种底上对比度不足。
 *   ② 所以这里以**歌词页的真实底色**为基准重算对比度，不达标就保持色相朝
 *      白 / 黑插值（与 boostForDark / boostForLight 同款步进插值），直到满足
 *      [LYRIC_MIN_CONTRAST]。底色偏亮朝黑压、偏暗朝白提，方向由底色亮度决定。
 *
 * ⚠ 只定「色」，不碰透明度：歌词的明暗渐变完全由 LyricRow 的图层 alpha 承担。
 *   LyricsScreen 里有一条硬约束 —— 逐帧改 Text 颜色会让 Paragraph 缓存整体失效
 *   等价于每帧重排版，所以取色结果必须是**每个重组周期内的常量**，
 *   由页面层算一次、逐行传入，行内不再重算。
 *
 * @param backdrop 歌词页的真实底色（环境色模式取 SongAmbient.color；
 *                 有自定义背景图且没开「保留取色」时页面透出背景图，按 [Bg] 标定）
 */
fun lyricInk(backdrop: Color): Color {
    val seed = Accent
    // 浅色 + 自动取色：放宽对比度下限提亮墨色（见 LYRIC_MIN_CONTRAST_DYNAMIC_LIGHT 的推导）；
    // 其余组合（深色主题 / 浅色 + 预设种子色）维持原 4.5:1 标定不变。
    val floor = if (isLightTheme && com.ncm.watch.data.AppearancePrefs.dynamicColor) {
        LYRIC_MIN_CONTRAST_DYNAMIC_LIGHT
    } else {
        LYRIC_MIN_CONTRAST
    }
    if (contrastRatio(seed, backdrop) >= floor) return seed
    val toward = if (backdrop.luminance() > 0.5f) Color.Black else Color.White
    var t = 0.05f
    while (t < 1f) {
        val c = lerp(seed, toward, t)
        if (contrastRatio(c, backdrop) >= floor) return c
        t += 0.05f
    }
    return toward
}

// ---- 语义色（随明暗换深浅，保证在各自底色上都可读）----
val Gold: Color get() = if (isLightTheme) Color(0xFF8A6000) else Color(0xFFFFD60A)
val Green: Color get() = if (isLightTheme) Color(0xFF11702C) else Color(0xFF30D158)
val Blue: Color get() = if (isLightTheme) Color(0xFF0A5CBB) else Color(0xFF0A84FF)

// ════════════════════════════════════════════════════════════════════════════
//  基础色（原顶层常量 → 改为读当前色板的 getter，调用点零改动）
// ════════════════════════════════════════════════════════════════════════════

val Bg: Color get() = palette.bg
val BgElevated: Color get() = palette.bgElevated
val SurfaceGlass: Color get() = palette.glass           // 胶囊半透明材质（高斯玻璃：透出模糊背景）
val SurfaceGlassBorder: Color get() = palette.glassBorder
val SurfaceStrong: Color get() = palette.strong
val TextPrimary: Color get() = palette.textPrimary
val TextSecondary: Color get() = palette.textSecondary
val TextTertiary: Color get() = palette.textTertiary
val Separator: Color get() = palette.separator

/**
 * 页面底色：恒透明（2026-09-04 流畅度专项 R1）。
 * 全局背景层（AppRoot 底部）已铺 Bg/背景图，页面再刷一遍底色是纯 overdraw；
 * 恒透明后每页少一层全屏填充。
 */
@Composable
fun screenBg(): Color = Color.Transparent

/** 头图底部渐变目标色：启用背景图时渐隐到透明（露出全局背景），否则渐到纯色底 */
@Composable
fun headerFade(): Color =
    if (com.ncm.watch.data.BackgroundStore.ready) Color.Transparent else Bg

/**
 * 文字样式阶梯。
 * ⚠ 必须是 getter 而不是 val 常量：常量会在 object 初始化时把颜色值冻结，
 *   后续换主题不会生效（原实现正是这个坑）。
 */
object T {
    // 字号体系（圆屏 233dp，全部偏小）
    val title: TextStyle
        get() = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, color = TextPrimary)
    val body: TextStyle
        get() = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
    val secondary: TextStyle
        get() = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = TextSecondary)
    val caption: TextStyle
        get() = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = TextTertiary)
    val button: TextStyle
        get() = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
}

// ════════════════════════════════════════════════════════════════════════════
//  种子色 → M3 角色梯度派生
// ════════════════════════════════════════════════════════════════════════════
//
// ⚠ 这里为什么不用 `ColorScheme.fromSeed`：
//   本项目 compose-bom = 2024.10.00 ⇒ material3 **1.3.0**，而 `ColorScheme.fromSeed`
//   是 material3 **1.4.0** 才加入的 API（已用 javap 核实 1.3.0 的 aar 里连
//   `ColorScheme$Companion` 都不存在，只有 `dynamicLight/DarkColorScheme`）。
//   所以改为「M3 baseline 全套角色打底 + 自己按 HSL 近似派生种子相关角色」，
//   效果等价于 fromSeed，且不引入依赖升级风险。
//   另注：1.3.0 的 ColorScheme 恰好是 **36 个角色**（无 `*Fixed` 系列，那也是 1.4.0 的），
//   所以下面 copy() 覆盖 32 个 + error 四色沿用 baseline = 36，不多不少。

/** HSL 三元组（各分量 0..1） */
private data class Hsl(val h: Float, val s: Float, val l: Float)

private fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return Hsl(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = if (max == r) {
        ((g - b) / d + if (g < b) 6f else 0f) / 6f
    } else if (max == g) {
        ((b - r) / d + 2f) / 6f
    } else {
        ((r - g) / d + 4f) / 6f
    }
    return Hsl(h, s, l)
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    val ss = s.coerceIn(0f, 1f)
    val ll = l.coerceIn(0f, 1f)
    if (ss <= 0f) return Color(ll, ll, ll)
    val q = if (ll < 0.5f) ll * (1f + ss) else ll + ss - ll * ss
    val p = 2f * ll - q
    fun ch(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return Color(ch(h + 1f / 3f), ch(h), ch(h - 1f / 3f))
}

/** 由种子色派生的一组角色（明度按 M3 tonal 规范取档：40/80/90/30/10） */
private data class AccentRamp(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

/**
 * 从种子色派生角色梯度。
 * - primary 档次（tone 40/80）：色相与饱和度直接沿用种子，只调明度
 * - secondary：饱和度压到 34%（M3 里 secondary 是低彩度版主色）
 * - tertiary：色相 +60°、饱和度 50%（与主色形成和谐对比）
 * 纯白种子（s=0）会退化成中性灰梯度，正好符合「白主题色」的语义。
 */
private fun accentRamp(seed: Color, dark: Boolean): AccentRamp {
    val h = seed.toHsl()
    val h2 = (h.h + 1f / 6f) % 1f
    val s2 = h.s * 0.34f
    val s3 = h.s * 0.5f
    return if (!dark) AccentRamp(
        primary = hsl(h.h, h.s, 0.40f),
        primaryContainer = hsl(h.h, h.s, 0.90f),
        onPrimaryContainer = hsl(h.h, h.s, 0.12f),
        secondary = hsl(h.h, s2, 0.40f),
        onSecondary = Color.White,
        secondaryContainer = hsl(h.h, s2, 0.90f),
        onSecondaryContainer = hsl(h.h, s2, 0.12f),
        tertiary = hsl(h2, s3, 0.40f),
        onTertiary = Color.White,
        tertiaryContainer = hsl(h2, s3, 0.90f),
        onTertiaryContainer = hsl(h2, s3, 0.12f),
    ) else AccentRamp(
        primary = hsl(h.h, h.s, 0.80f),
        primaryContainer = hsl(h.h, h.s, 0.30f),
        onPrimaryContainer = hsl(h.h, h.s, 0.90f),
        secondary = hsl(h.h, s2, 0.80f),
        onSecondary = hsl(h.h, s2, 0.20f),
        secondaryContainer = hsl(h.h, s2, 0.30f),
        onSecondaryContainer = hsl(h.h, s2, 0.90f),
        tertiary = hsl(h2, s3, 0.80f),
        onTertiary = hsl(h2, s3, 0.20f),
        tertiaryContainer = hsl(h2, s3, 0.30f),
        onTertiaryContainer = hsl(h2, s3, 0.90f),
    )
}

// ════════════════════════════════════════════════════════════════════════════
//  Material 3 ColorScheme（36 角色全覆盖）
// ════════════════════════════════════════════════════════════════════════════

/**
 * 构建完整的 M3 ColorScheme（36 个角色全部落到项目语义色，不留系统默认紫）：
 *
 * 1. 基底用 M3 baseline 全套角色；动态取色可用且用户开启时改走系统壁纸取色
 *    （`dynamicLight/DarkColorScheme`，Android 12+）。
 * 2. 再用「种子色派生的角色梯度」覆盖 primary/secondary/tertiary 及其 container；
 *    error 四色沿用 baseline（M3 的错误红是通用语义色，不该被种子色改写）。
 * 3. 最后把项目既有语义色覆盖回 background / surface / outline —— 必须与 20+ 个
 *    引用 `Bg` / `TextPrimary` 的页面一致，否则会出现「卡片用浅色板、页面底色用
 *    派生色」的两套皮。
 */
@Composable
private fun buildColorScheme(pal: NcmPalette, accent: Color): ColorScheme {
    val ctx = LocalContext.current
    // ⚠ 必须用 SDK_INT 判断能否走系统 Monet，**不能**用 AppearancePrefs.dynamicSupported：
    //   后者现在恒为 true（Android 11 靠背景图取色），而 dynamicXxxColorScheme 内部读的是
    //   API 31 才有的 android.R.color.system_accent1_*，在 Android 11 上调用会直接抛异常。
    //   Android 11 的路径 = baseline 打底 + accentRamp(EffectiveSeed = 背景图主色) 覆盖。
    val monetAvailable = com.ncm.watch.data.AppearancePrefs.dynamicColor &&
        android.os.Build.VERSION.SDK_INT >= 31
    val base: ColorScheme = if (monetAvailable) {
        if (pal.light) dynamicLightColorScheme(ctx) else dynamicDarkColorScheme(ctx)
    } else {
        if (pal.light) lightColorScheme() else darkColorScheme()
    }
    val r = accentRamp(accent, dark = !pal.light)
    // 中性 surface 容器阶梯：避免 Switch / Slider 落在 baseline 的紫调灰上
    val scLowest = if (pal.light) Color(0xFFFFFFFF) else Color(0xFF0E0E11)
    val scLow = if (pal.light) Color(0xFFF3F3F6) else Color(0xFF141418)
    val scMid = if (pal.light) Color(0xFFEDEDF1) else Color(0xFF1B1B1F)
    val scHigh = if (pal.light) Color(0xFFE7E7EC) else Color(0xFF232328)
    val scHighest = if (pal.light) Color(0xFFE0E0E6) else Color(0xFF2C2C32)
    return base.copy(
        primary = accent,
        onPrimary = OnAccent,
        primaryContainer = r.primaryContainer,
        onPrimaryContainer = r.onPrimaryContainer,
        inversePrimary = r.primary,
        secondary = r.secondary,
        onSecondary = r.onSecondary,
        secondaryContainer = r.secondaryContainer,
        onSecondaryContainer = r.onSecondaryContainer,
        tertiary = r.tertiary,
        onTertiary = r.onTertiary,
        tertiaryContainer = r.tertiaryContainer,
        onTertiaryContainer = r.onTertiaryContainer,
        background = pal.bg,
        onBackground = pal.textPrimary,
        surface = pal.bgElevated,
        onSurface = pal.textPrimary,
        surfaceVariant = pal.strong,
        onSurfaceVariant = pal.textSecondary,
        surfaceTint = accent,
        inverseSurface = if (pal.light) Color(0xFF2F2F33) else Color(0xFFF0F0F3),
        inverseOnSurface = if (pal.light) Color(0xFFF5F5F7) else Color(0xFF17171A),
        outline = pal.glassBorder,
        outlineVariant = pal.separator,
        scrim = Color.Black.copy(alpha = 0.6f),
        surfaceBright = if (pal.light) Color(0xFFFBFBFD) else Color(0xFF26262B),
        surfaceDim = if (pal.light) Color(0xFFE2E2E7) else Color(0xFF0C0C0F),
        surfaceContainerLowest = scLowest,
        surfaceContainerLow = scLow,
        surfaceContainer = scMid,
        surfaceContainerHigh = scHigh,
        surfaceContainerHighest = scHighest,
    )
}

@Composable
fun NcmTheme(content: @Composable () -> Unit) {
    // 把系统深浅同步到偏好（「跟随系统」模式已从设置页移除，这里保留同步做防御）。
    // 用 SideEffect 而不是组合期直接赋值 —— 组合期写状态是反模式（可能触发重组环）。
    // 首次启动的初值由 AppearancePrefs.init() 从 Configuration 读出，所以不会有一帧错色。
    val sysDark = isSystemInDarkTheme()
    SideEffect { com.ncm.watch.data.AppearancePrefs.systemDark = sysDark }

    val pal = palette        // 读组合状态 → 换主题整树重组

    // Android 12+ 且开启动态取色：从系统 Monet 色板读出 primary，回填成「有效种子」，
    // 让全局 Accent（图标/按钮/选中态）与 ColorScheme.primary 用同一个壁纸色。
    // ⚠ 这里必须在组合期调用 dynamicXxxColorScheme（它是 @Composable），不能在协程里调。
    //   回填走 SideEffect，所以 Android 12+ 上会有一帧沿用上次的种子 —— 这一帧在启动浮层
    //   之下不可见；Android 11 走背景图色，monetSeed 恒为 null，无任何延迟。
    val ctx = LocalContext.current
    val monetSeed: Color? = if (com.ncm.watch.data.AppearancePrefs.dynamicColor &&
        android.os.Build.VERSION.SDK_INT >= 31
    ) {
        if (pal.light) dynamicLightColorScheme(ctx).primary else dynamicDarkColorScheme(ctx).primary
    } else {
        null
    }
    SideEffect { DynamicSeed.systemColor = monetSeed }

    val accent = Accent
    MaterialTheme(colorScheme = buildColorScheme(pal, accent), content = content)
}
