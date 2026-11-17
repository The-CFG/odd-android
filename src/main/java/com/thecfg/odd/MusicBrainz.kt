package com.thecfg.odd

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class CdInfo(val album: String, val artist: String, val titles: List<String>)

object MusicBrainz {
    fun discId(toc: List<TocEntry>, leadout: Int): String {
        val first = toc.minOf { it.no }
        val last = toc.maxOf { it.no }
        val off = toc.associate { it.no to it.lba + 150 }
        val sb = StringBuilder(String.format(Locale.ROOT, "%02X%02X%08X", first, last, leadout + 150))
        for (i in 1..99) sb.append(String.format(Locale.ROOT, "%08X", off[i] ?: 0))
        val sha = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray())
        return Base64.encodeToString(sha, Base64.NO_WRAP).replace('+', '.').replace('/', '_').replace('=', '-')
    }

    /** 캐시(cd_cache.json) 먼저, 없으면 online 일 때 조회 */
    @Synchronized
    fun get(ctx: Context, id: String, n: Int, online: Boolean): CdInfo? {
        val f = File(ctx.filesDir, "cd_cache.json")
        val cache = try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
        cache.optJSONObject(id)?.let { o ->
            val a = o.optJSONArray("titles")
            val t = ArrayList<String>()
            if (a != null) for (i in 0 until a.length()) t += a.optString(i)
            while (t.size < n) t += ""
            return CdInfo(o.optString("album"), o.optString("artist"), t)
        }
        if (!online) return null
        val r = try { fetch(id, n) } catch (e: Exception) { null } ?: return null
        cache.put(id, JSONObject().put("album", r.album).put("artist", r.artist).put("titles", JSONArray(r.titles)))
        runCatching { f.writeText(cache.toString(1)) }
        return r
    }

    private fun fetch(id: String, n: Int): CdInfo? {
        val c = URL("https://musicbrainz.org/ws/2/discid/$id?fmt=json&inc=recordings+artist-credits")
            .openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "ODDManager/1.0 (personal use)")
        c.connectTimeout = 10000
        c.readTimeout = 10000
        try {
            if (c.responseCode == 404) return null
            val d = JSONObject(c.inputStream.bufferedReader().readText())
            val rels = d.optJSONArray("releases") ?: return null
            if (rels.length() == 0) return null
            val rel = rels.getJSONObject(0)
            val ac = rel.optJSONArray("artist-credit")
            val artist = buildString {
                if (ac != null) for (i in 0 until ac.length()) {
                    val a = ac.getJSONObject(i)
                    append(a.optString("name")); append(a.optString("joinphrase"))
                }
            }
            val media = rel.optJSONArray("media")
            var pick: JSONObject? = null
            if (media != null) {
                for (i in 0 until media.length()) {
                    val m = media.getJSONObject(i)
                    val ds = m.optJSONArray("discs") ?: continue
                    for (j in 0 until ds.length()) if (ds.getJSONObject(j).optString("id") == id) pick = m
                }
                if (pick == null && media.length() > 0) pick = media.getJSONObject(0)
            }
            val tr = pick?.optJSONArray("tracks")
            val titles = ArrayList<String>()
            if (tr != null) for (i in 0 until tr.length()) titles += tr.getJSONObject(i).optString("title")
            while (titles.size < n) titles += ""
            return CdInfo(rel.optString("title"), artist, titles)
        } finally {
            c.disconnect()
        }
    }
}
