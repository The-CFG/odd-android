package com.thecfg.odd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 사용자가 직접 입력한 앨범/아티스트/곡명. 앱 내부 저장소(cd_custom.json)에만 저장하고
 * CD 에는 아무것도 쓰지 않는다. 디스크는 TOC 로 만든 ID 로 구분한다 (같은 CD 는 어느 ODD 에서도 같은 ID).
 * 빈 칸은 "직접 입력 안 함" 이라서 자동(MusicBrainz) 정보로 채워진다.
 */
object CustomMeta {
    private fun file(ctx: Context) = File(ctx.filesDir, "cd_custom.json")

    @Synchronized
    private fun readAll(ctx: Context): JSONObject =
        try { JSONObject(file(ctx).readText()) } catch (e: Exception) { JSONObject() }

    @Synchronized
    fun get(ctx: Context, id: String): CdInfo? {
        val o = readAll(ctx).optJSONObject(id) ?: return null
        val a = o.optJSONArray("titles")
        val t = ArrayList<String>()
        if (a != null) for (i in 0 until a.length()) t += a.optString(i)
        return CdInfo(o.optString("album"), o.optString("artist"), t)
    }

    @Synchronized
    fun save(ctx: Context, id: String, info: CdInfo) {
        val all = readAll(ctx)
        all.put(id, JSONObject().put("album", info.album).put("artist", info.artist).put("titles", JSONArray(info.titles)))
        file(ctx).writeText(all.toString(1))
    }

    @Synchronized
    fun clear(ctx: Context, id: String) {
        val all = readAll(ctx)
        if (all.has(id)) { all.remove(id); file(ctx).writeText(all.toString(1)) }
    }

    /** 직접 입력한 값이 있으면 우선, 비어 있으면 자동 정보 */
    fun merge(custom: CdInfo?, auto: CdInfo?, n: Int): CdInfo = CdInfo(
        custom?.album?.takeIf { it.isNotBlank() } ?: auto?.album ?: "",
        custom?.artist?.takeIf { it.isNotBlank() } ?: auto?.artist ?: "",
        (0 until n).map { i ->
            custom?.titles?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: auto?.titles?.getOrNull(i) ?: ""
        },
    )
}
