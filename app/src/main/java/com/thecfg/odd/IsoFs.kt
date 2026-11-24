package com.thecfg.odd

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

data class IsoEntry(val name: String, val size: Long, val isDir: Boolean, val extent: Long)
class IsoNode(val path: List<String>, val extent: Long, val size: Long, val isDir: Boolean)
class IsoInfo(
    val fs: String, val label: String, val sizeMb: Double,
    val root: IsoEntry?, val charset: Charset, val entries: List<IsoEntry>,
)

/** ISO9660 / Joliet 읽기 */
object Iso {
    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 255) or ((b[o + 1].toLong() and 255) shl 8) or
            ((b[o + 2].toLong() and 255) shl 16) or ((b[o + 3].toLong() and 255) shl 24)

    fun list(src: SectorSource, ext: Long, size: Long, cs: Charset, limit: Int = 2000): List<IsoEntry> {
        val bo = ByteArrayOutputStream()
        for (i in 0 until minOf((size + 2047) / 2048, 64L).toInt()) {
            bo.write(src.read(ext + i, 1) ?: break)
        }
        val data = bo.toByteArray()
        val files = ArrayList<IsoEntry>()
        var pos = 0
        while (pos < data.size && files.size < limit) {
            val ln = data[pos].toInt() and 255
            if (ln == 0) { pos = (pos / 2048 + 1) * 2048; continue }
            val nameLen = if (pos + 32 < data.size) data[pos + 32].toInt() and 255 else 0
            if (ln >= 34 && pos + 33 + nameLen <= data.size) {
                val nb = data.copyOfRange(pos + 33, pos + 33 + nameLen)
                val special = nb.size == 1 && (nb[0].toInt() == 0 || nb[0].toInt() == 1)
                if (!special) {
                    val nm = String(nb, cs).substringBefore(';')
                    files += IsoEntry(nm, le32(data, pos + 10), (data[pos + 25].toInt() and 2) != 0, le32(data, pos + 2))
                }
            }
            pos += ln
        }
        return files.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun open(src: SectorSource, base: Long): IsoInfo {
        var pvd: ByteArray? = null
        var svd: ByteArray? = null
        for (k in 16 until 40) {
            val sec = src.read(base + k, 1) ?: break
            if (String(sec, 1, 5, Charsets.US_ASCII) != "CD001") break
            val t = sec[0].toInt() and 255
            if (t == 1 && pvd == null) pvd = sec
            else if (t == 2 && sec[88].toInt() == 0x25 && sec[89].toInt() == 0x2F &&
                sec[90].toInt() in intArrayOf(0x40, 0x43, 0x45)) svd = sec
            else if (t == 255) break
        }
        if (pvd == null) {
            return IsoInfo("ISO9660 아님 (UDF 등: 탐색 미지원, ISO 덤프는 가능)", "", 0.0, null, Charsets.ISO_8859_1, emptyList())
        }
        val vd = svd ?: pvd
        val cs = if (svd != null) Charsets.UTF_16BE else Charsets.ISO_8859_1
        val label = String(vd, 40, 32, cs).replace("\u0000", "").trim()
        val sizeMb = Math.round(le32(pvd, 80) * 2048 / 1e5) / 10.0
        val root = IsoEntry("", le32(vd, 156 + 10), true, le32(vd, 156 + 2))
        val entries = try { list(src, root.extent, root.size, cs) } catch (e: Exception) { emptyList() }
        return IsoInfo(if (svd != null) "ISO9660 + Joliet" else "ISO9660", label, sizeMb, root, cs, entries)
    }

    fun walk(src: SectorSource, ext: Long, size: Long, cs: Charset, prefix: List<String>, depth: Int = 0): List<IsoNode> {
        val out = ArrayList<IsoNode>()
        if (depth > 16) return out
        for (e in list(src, ext, size, cs)) {
            val p = prefix + safeName(e.name)
            out += IsoNode(p, e.extent, e.size, e.isDir)
            if (e.isDir) out += walk(src, e.extent, e.size, cs, p, depth + 1)
        }
        return out
    }
}
