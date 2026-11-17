package com.thecfg.odd

import android.content.Context

data class ResumeInfo(val trackNo: Int, val posSec: Int)

/** 오디오 CD 재생 위치 저장 — 전원 해제 등으로 끊겼다가 같은 디스크가 다시 인식되면 이어듣기를 묻는다 */
object ResumeStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("odd_resume", Context.MODE_PRIVATE)

    fun save(ctx: Context, discId: String, trackNo: Int, posSec: Int) {
        prefs(ctx).edit().putString("disc", discId).putInt("track", trackNo).putInt("pos", posSec).apply()
    }

    fun load(ctx: Context, discId: String): ResumeInfo? {
        val p = prefs(ctx)
        if (p.getString("disc", null) != discId) return null
        val t = p.getInt("track", -1)
        return if (t < 0) null else ResumeInfo(t, p.getInt("pos", 0))
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
