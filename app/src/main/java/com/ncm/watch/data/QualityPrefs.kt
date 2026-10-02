package com.ncm.watch.data

import android.content.Context
import android.content.SharedPreferences

/** 音质偏好：standard(标准)/exhigh(较高)/lossless(无损，需 VIP——UI 层用 vipExpire 校验后才允许写入) */
object QualityPrefs {
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) { prefs = ctx.getSharedPreferences("quality_prefs", Context.MODE_PRIVATE) }

    var level: String
        get() = prefs?.getString("level", "standard")?.takeIf { it in setOf("standard", "exhigh", "lossless") } ?: "standard"
        set(v) = prefs?.edit()?.putString("level", v)?.apply() ?: Unit

    fun label(): String = when (level) {
        "exhigh" -> "较高"
        "lossless" -> "无损"
        else -> "标准"
    }
}
