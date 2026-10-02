package com.netmusiclite.data

/**
 * 跨语言（异种语言）搜索的匹配口径。
 *
 * 场景：日语歌在库里存的是原名「夜に駆ける」，用户只会输入中文「向夜晚奔去」；
 * 反过来也一样（搜「紅蓮華」要能命中「红莲华」）。这类匹配靠**归一化 + 别名集合**两条腿：
 *
 * 1. **归一化文本比较**：把查询与歌曲字段都做「大小写折叠 → 全角转半角 → 去空白与标点」，
 *    这样「Dynamite」「ｄｙｎａｍｉｔｅ」「dyna mite」视为同一个词。
 * 2. **别名集合**：[Song.aliases] 里的译名/别名一起参与匹配，
 *    于是「向夜晚奔去」命中「夜に駆ける」，「红莲华」命中「紅蓮華」。
 *
 * 依赖服务端数据而不是本地词典，所以任何语种（日/韩/英/粤…）都自动生效，
 * 不需要维护翻译表；代价是别名必须先解析出来（有 id 的歌随接口返回，
 * 本地文件由 [SongAliasStore] 反查）。
 */
object SongQuery {

    /**
     * 归一化：转小写 → 全角字母数字转半角 → 去掉空白与常见标点/括号。
     *
     * 只做形式归一，不做任何语言转换 —— 假名、谚文、汉字原样保留，
     * 因为「红莲华」与「紅蓮華」的差异靠别名集合解决，而不是靠字形转换
     * （简繁/新字体映射表体量巨大且必然有漏，服务端译名更准）。
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when {
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar() // 全角 ASCII → 半角
                ch == '\u3000' -> ' '                                    // 全角空格
                else -> ch
            }
            when {
                c.isWhitespace() -> {}
                c == '-' || c == '_' || c == '.' || c == '\'' || c == '"' || c == '·' -> {}
                c == '(' || c == ')' || c == '[' || c == ']' || c == '（' || c == '）' -> {}
                c == '「' || c == '」' || c == '《' || c == '》' || c == '【' || c == '】' -> {}
                else -> out.append(c.lowercaseChar())
            }
        }
        return out.toString()
    }

    /**
     * 单首歌是否命中查询词（歌名 / 艺人 / 专辑 / 别名，任一命中即可）。
     *
     * 匹配是**双向包含**的，这一点对跨语言很关键：查询词常是「歌名 + 艺人」这种组合
     * （例如查询扩展返回的 `夜に駆ける YOASOBI`），而歌曲字段只有「夜に駆ける」，
     * 单向 `字段.contains(查询)` 会漏掉 —— 必须允许「查询.contains(字段)」。
     * 为防误伤，反向包含要求字段长度 ≥ 2（单字歌名如「炎」只走正向）。
     */
    fun matches(song: Song, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        if (hit(normalize(song.title), q)) return true
        if (hit(normalize(song.artist), q)) return true
        if (hit(normalize(song.album), q)) return true
        for (a in song.aliases) if (hit(normalize(a), q)) return true
        return false
    }

    private fun hit(field: String, query: String): Boolean {
        if (field.isEmpty() || query.isEmpty()) return false
        if (field.contains(query)) return true
        return field.length >= 2 && query.length > field.length && query.contains(field)
    }

    /**
     * 这首歌「因为别名而命中」时，返回那个别名（用于 UI 提示「你搜的中文名对应这首」）。
     * 歌名/艺人/专辑本身命中时返回 null —— 那种情况用户能直接看懂，不需要解释。
     */
    fun matchedAlias(song: Song, query: String): String? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        if (normalize(song.title).contains(q)) return null
        if (normalize(song.artist).contains(q)) return null
        if (normalize(song.album).contains(q)) return null
        return song.aliases.firstOrNull { normalize(it).contains(q) }
    }

    /** 展示用的首选译名：第一个与歌名不同的别名（本地音乐列表里给外文歌挂个中文名） */
    fun displayAlias(song: Song): String? {
        val t = normalize(song.title)
        return song.aliases.firstOrNull { it.isNotBlank() && normalize(it) != t && normalize(it).isNotEmpty() }
    }
}
