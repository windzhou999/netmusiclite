package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 首启协议同意标记：同意一次后不再弹出 */
object ConsentStore {
    private lateinit var prefs: SharedPreferences

    var agreed by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ncm_consent", Context.MODE_PRIVATE)
        agreed = prefs.getBoolean("agreed", false)
    }

    fun agree() {
        prefs.edit().putBoolean("agreed", true).apply()
        agreed = true
    }
}
