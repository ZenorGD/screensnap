package com.tommyapps.recorder

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.reflect.KProperty

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private class P<T : Any>(val key: String, val def: T) {
        private var s: MutableState<T>? = null
        @Suppress("UNCHECKED_CAST")
        private fun read(): T = when (def) {
            is Int -> sp.getInt(key, def) as T
            is Boolean -> sp.getBoolean(key, def) as T
            else -> sp.getString(key, def as String) as T
        }
        operator fun getValue(o: Any?, p: KProperty<*>): T =
            (s ?: mutableStateOf(read()).also { s = it }).value
        operator fun setValue(o: Any?, p: KProperty<*>, v: T) {
            (s ?: mutableStateOf(v).also { s = it }).value = v
            sp.edit().apply {
                when (v) {
                    is Int -> putInt(key, v)
                    is Boolean -> putBoolean(key, v)
                    else -> putString(key, v as String)
                }
            }.apply()
        }
    }

    var onboarded by P("onboarded", false)
    var height by P("resolution", 0)        // 0 = native
    var fps by P("fps", 30)
    var audio by P("audio_source", 0)       // 0 none, 1 mic, 2 device, 3 mic + device
    var bitrate by P("bitrate", 8)          // Mbps
    var saveUri by P("saveUri", "")
    var theme by P("theme", 0)              // 0 system, 1 light, 2 dark
    var showTouches by P("touches", false)
    var overlay by P("overlay", true)
    var overlayAlpha by P("alpha", 90)
    var penColor by P("penColor", 0xFFFF4D8D.toInt())
}