package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 主题预设色板（2026-10-01 语义升级）：从「强调色」升级为 **Material 3 种子色**。
 *
 * 选中后不再只影响一个 Accent 色值 —— 而是作为 `ColorScheme.fromSeed` 的种子，
 * 由 HCT 算法派生出完整的 36 角色色板（primary/secondary/tertiary/各 *Container …）；
 * 其中 primary 会直接取用种子色本身，保证网易云红这类品牌色不被算法改写。
 */
val PRESET_ACCENTS: List<Color> = listOf(
    Color(0xFFEC4141), // 网易云红（默认）
    Color(0xFF2196F3), // Blue
    Color(0xFF4CAF50), // Green
    Color(0xFF9C27B0), // Purple
    Color(0xFFFF9800), // Orange
    Color(0xFFE91E63), // Pink
    Color(0xFF009688), // Teal
    Color(0xFF3F51B5), // Indigo
    Color(0xFFFFFFFF), // White
)

/** 种子色中文名（设置页副标题用，顺序与 [PRESET_ACCENTS] 严格一一对应） */
val PRESET_ACCENT_NAMES: List<String> = listOf(
    "网易云红", "蓝", "绿", "紫", "橙", "粉", "青", "靛蓝", "白",
)

/**
 * 主题明暗模式。
 *
 * ⚠ 2026-10-01：外观模式已**移除「跟随系统」**。用户反馈它与「深色」效果完全相同 ——
 * 手表系统深浅恒定（ColorOS Watch 没有浅色系统 UI），「跟随系统」永远解析成深色，
 * 是个点下去看不出区别的冗余选项。这里保留 [SYSTEM] 常量**仅用于存量值迁移**：
 * 之前选过「跟随系统」的用户 sp 里存的是 0，[AppearancePrefs.init] 会把它转成 [DARK]。
 */
object ThemeMode {
    /** 跟随系统深浅（已从设置页移除，仅作存量迁移用） */
    const val SYSTEM = 0
    /** 恒深色 */
    const val DARK = 1
    /** 恒浅色 */
    const val LIGHT = 2
}

/** 外观偏好（SharedPreferences 持久化 + Compose 状态同步） */
object AppearancePrefs {
    private lateinit var sp: SharedPreferences
    private const val DEF_ACCENT = 0xFFEC4141L
    /** 手表默认深色 —— 加主题功能之前应用恒深色，默认值取 DARK 保证升级后观感不变 */
    private const val DEF_MODE = ThemeMode.DARK

    // ---- 明暗模式 ----

    private var _mode by mutableStateOf(DEF_MODE)

    /** 明暗模式（ThemeMode.SYSTEM / DARK / LIGHT） */
    val mode: Int
        get() = _mode

    /**
     * 系统当前是否深色。由 NcmTheme 每帧同步（SideEffect），
     * 初值在 [init] 里从 Configuration 读出，保证首帧就是对的。
     */
    var systemDark by mutableStateOf(true)

    /** 当前实际是否浅色。跟随系统时解析为确定值，供颜色计算使用。 */
    val isLight: Boolean
        get() = when (_mode) {
            ThemeMode.LIGHT -> true
            ThemeMode.DARK -> false
            else -> !systemDark
        }

    // ---- 动态取色（Material You，Android 12+）----

    /**
     * ★ 2026-10-02 用户口径「背景取色功能改为默认开启」：默认值由 false 改为 true。
     *
     * 为什么敢默认开：本机（Android 11）上这个开关的色源是**用户自己设的背景图主色**
     * （见 [BackgroundStore.seedColor]），没设背景图时 [EffectiveSeed] 会原样回落预设种子色 ——
     * 也就是说「开了但还没设背景图」不会把界面弄成奇怪的颜色，只是等用户设图后才开始跟随。
     * Android 12+ 则直接跟随系统壁纸色（Monet），这也是系统本身的默认观感。
     * 存量用户不受影响：sp 里已经显式存过 true/false 的，照旧读回自己的选择。
     */
    private const val DEF_DYNAMIC = true

    private var _dynamic by mutableStateOf(DEF_DYNAMIC)

    /** 是否跟随系统壁纸（Android 11 及以下：自定义背景图）动态取色 */
    val dynamicColor: Boolean
        get() = _dynamic

    /**
     * 动态取色是否**可用**（2026-10-01 起恒为 true）。
     *
     * 原实现要求 Android 12+（系统 Monet 取色），导致本机（OWW261 / Android 11）上
     * 开关是灰的、用户根本开不了。现在按系统版本分流：
     *  · Android 12+ → 系统壁纸取色（Monet 色板）；
     *  · Android 11 及以下 → 取**自定义背景图的主色**（本机 `wallpaper` 服务都不存在，
     *    背景图是这台设备上唯一可自定义的「壁纸」，见 [BackgroundStore.seedColor]）。
     * 因此任何版本都能开，只是 Android 11 上需先设置背景图才有色可依。
     */
    val dynamicSupported: Boolean
        get() = true

    // ---- 歌词扫光 ----

    /**
     * ★ 2026-10-02 用户口径「设置主题栏目中添加歌词扫光的开关」：默认开 ——
     * 歌词扫光是 1.0.2 版新增的聚焦行效果（见 LyricsScreen 的 drawLyricSweep），
     * 升级后保持与上一版一致的观感。关掉 = 聚焦行不再有移动白色光斑，
     * 但聚焦行提亮（深色主题的底噪，用户口径①「聚焦歌词提高亮度增加可读性」）仍然保留 ——
     * 那是独立于扫光的可读性改进，不该被同一个开关连带关掉。
     */
    private const val DEF_LYRIC_SWEEP = true

    private var _lyricSweep by mutableStateOf(DEF_LYRIC_SWEEP)

    /** 歌词页聚焦行的白色扫光是否开启 */
    val lyricSweep: Boolean
        get() = _lyricSweep

    fun setLyricSweep(v: Boolean) {
        _lyricSweep = v
        if (::sp.isInitialized) sp.edit().putBoolean("lyric_sweep", v).apply()
    }

    // ---- 种子色 ----

    // 私有后备状态：ui.theme.Accent 是它的公开读取口，换色即全局重组
    private var _accent by mutableStateOf(Color(DEF_ACCENT.toInt()))

    /** 当前全局主题色（所有页面的强调色 / M3 种子色） */
    val accent: Color
        get() = _accent

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        _accent = Color(sp.getLong("accent", DEF_ACCENT).toInt())
        _mode = sp.getInt("theme_mode", DEF_MODE).let {
            // 存量迁移：「跟随系统」(0) 已从设置页移除 → 转成恒深色。
            // 手表系统本就恒深色，迁移前后观感完全一致，用户无感。
            if (it == ThemeMode.SYSTEM) ThemeMode.DARK else it
        }
        _dynamic = sp.getBoolean("dynamic_color", DEF_DYNAMIC)
        _lyricSweep = sp.getBoolean("lyric_sweep", DEF_LYRIC_SWEEP)
        // 首帧正确性：跟随系统模式必须在第一次组合前就知道系统深浅，
        // 否则 NcmTheme 的 SideEffect 晚一步，会先闪一帧深色。
        systemDark = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun setAccent(c: Color) {
        _accent = c
        if (::sp.isInitialized) {
            sp.edit().putLong("accent", c.toArgb().toLong() and 0xFFFFFFFFL).apply()
        }
    }

    fun setMode(m: Int) {
        _mode = m
        if (::sp.isInitialized) sp.edit().putInt("theme_mode", m).apply()
    }

    fun setDynamicColor(v: Boolean) {
        // 不支持动态取色的机型上不落盘，避免换机后出现「开了但没生效」的悬空状态
        if (v && !dynamicSupported) return
        _dynamic = v
        if (::sp.isInitialized) sp.edit().putBoolean("dynamic_color", v).apply()
    }

    /**
     * 动态取色当前**实际生效的色源**说明（设置页副标题）。
     * Android 11 上这个开关取的是自定义背景图，副标题必须写清来源 ——
     * 否则用户开了开关却没设背景图，只会以为功能坏了。
     */
    fun dynamicSourceLabel(): String = when {
        !_dynamic -> "关闭时使用下方预设种子色"
        Build.VERSION.SDK_INT >= 31 -> "已开启 · 配色跟随系统壁纸"
        BackgroundStore.ready -> "已开启 · 配色取自下方自定义背景图"
        else -> "已开启 · 先选一张背景图才会生效"
    }

    /** 明暗模式中文名（设置页副标题用） */
    fun modeLabel(): String = when (_mode) {
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
        else -> "跟随系统"
    }

    /** 当前种子色中文名；自定义色（非预设）返回「自定义」 */
    fun accentLabel(): String {
        val i = PRESET_ACCENTS.indexOfFirst { it == _accent }
        return if (i >= 0) PRESET_ACCENT_NAMES[i] else "自定义"
    }
}
