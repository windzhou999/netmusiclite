package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/** Persisted preference for PCM loudness normalization. */
object LoudnessPrefs {
    private const val PREFS_NAME = "ncm_loudness"
    private const val KEY_ENABLED = "enabled"

    private var preferences: SharedPreferences? = null
    private val enabledState = mutableStateOf(false)

    var enabled: Boolean
        get() = enabledState.value
        set(value) {
            enabledState.value = value
            preferences?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    fun init(context: Context) {
        preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabledState.value = preferences?.getBoolean(KEY_ENABLED, false) ?: false
    }
}
