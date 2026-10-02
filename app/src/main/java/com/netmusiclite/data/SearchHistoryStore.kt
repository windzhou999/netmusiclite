package com.netmusiclite.data

import android.content.Context

/**
 * 搜索历史：SharedPreferences 本地存储，最新在前、去重、最多 10 条。
 * 会话级仅内存不合适（历史需要跨启动），直接落盘。
 */
object SearchHistoryStore {
    private const val FILE = "search_history"
    private const val KEY = "keywords"
    private const val MAX = 10

    fun load(ctx: Context): List<String> = runCatching {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.split("\u0001")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }.getOrDefault(emptyList())

    fun add(ctx: Context, keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val next = (listOf(kw) + load(ctx)).distinct().take(MAX)
        save(ctx, next)
    }

    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, list.joinToString("\u0001")).apply()
    }
}
