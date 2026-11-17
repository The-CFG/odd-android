package com.thecfg.odd

import android.content.Context

/** odd_settings.json 대신 SharedPreferences */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("odd", Context.MODE_PRIVATE)

    var outTree: String
        get() = p.getString("outTree", "") ?: ""
        set(v) { p.edit().putString("outTree", v).apply() }
    var autoplay: Boolean
        get() = p.getBoolean("autoplay", false)
        set(v) { p.edit().putBoolean("autoplay", v).apply() }
    var lookup: Boolean
        get() = p.getBoolean("lookup", true)
        set(v) { p.edit().putBoolean("lookup", v).apply() }
    var repeat: Boolean
        get() = p.getBoolean("repeat", false)
        set(v) { p.edit().putBoolean("repeat", v).apply() }
    var volume: Int
        get() = p.getInt("volume", 100)
        set(v) { p.edit().putInt("volume", v).apply() }
    var playSpeed: Int
        get() = p.getInt("playSpeed", 4)
        set(v) { p.edit().putInt("playSpeed", v).apply() }
    var ripSpeed: Int
        get() = p.getInt("ripSpeed", 8)
        set(v) { p.edit().putInt("ripSpeed", v).apply() }
    var chunk: Int
        get() = p.getInt("chunk", 16)
        set(v) { p.edit().putInt("chunk", v).apply() }
}
