package com.ncm.watch.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 登录态 / 多账号本地存储 */
object SessionStore {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ncm_session", Context.MODE_PRIVATE)
    }

    var musicU: String?
        get() = prefs.getString("music_u", null)
        set(v) = prefs.edit().putString("music_u", v).apply()

    /**
     * 登录回包 Set-Cookie 里的 __csrf。
     * 官方客户端写操作（点赞 / 发评论）会带 `checkToken`（dex: `Lqz0/b;->G0()` → security SDK
     * 的 securityGetToken），web/eapi 线对应的就是 __csrf，故登录时把它一并落盘。
     */
    var csrf: String?
        get() = prefs.getString("csrf", null)
        set(v) = prefs.edit().putString("csrf", v).apply()

    var uid: Long
        get() = prefs.getLong("uid", 0L)
        set(v) = prefs.edit().putLong("uid", v).apply()

    var nickname: String
        get() = prefs.getString("nickname", "") ?: ""
        set(v) = prefs.edit().putString("nickname", v).apply()

    var avatarUrl: String
        get() = prefs.getString("avatar", "") ?: ""
        set(v) = prefs.edit().putString("avatar", v).apply()

    var vipType: Int
        get() = prefs.getInt("vip_type", 0)
        set(v) = prefs.edit().putInt("vip_type", v).apply()

    val loggedIn: Boolean get() = !musicU.isNullOrEmpty()

    /** 已添加的账号（多账号切换用，首项为当前） */
    fun accounts(): List<AccountEntry> {
        val s = prefs.getString("accounts", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AccountEntry(
                    o.getString("u"), o.optLong("id"), o.optString("n"), o.optString("a"),
                    o.optString("c").takeIf { it.isNotEmpty() },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveAccounts(list: List<AccountEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("u", it.musicU).put("id", it.uid).put("n", it.nickname)
                    .put("a", it.avatarUrl ?: "").put("c", it.csrf ?: "")
            )
        }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    fun addAccount(e: AccountEntry) {
        val list = accounts().filter { it.uid != e.uid }.toMutableList()
        list.add(0, e)
        saveAccounts(list)
    }

    fun applyAccount(e: AccountEntry) {
        musicU = e.musicU; uid = e.uid; nickname = e.nickname; avatarUrl = e.avatarUrl ?: ""
        csrf = e.csrf
        val rest = accounts().filter { it.uid != e.uid }
        val merged = AccountEntry(e.musicU, e.uid, e.nickname, e.avatarUrl, e.csrf)
        saveAccounts(listOf(merged) + rest)
    }

    fun logoutCurrent() {
        musicU = null; uid = 0; nickname = ""; avatarUrl = ""; vipType = 0; csrf = null
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
