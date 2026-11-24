package com.thecfg.odd

import android.content.Context
import android.provider.DocumentsContract
import android.util.LruCache
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * 현재 인식된 데이터 디스크의 공유 상태 + 파일 읽기 캐시.
 * 엔진이 publish/clear 하고, DiscProvider 와 앱 내 뷰어가 read 한다.
 */
object OddHub {
    private const val GROUP = 64                       // 캐시 블록 = 64섹터 = 128KB
    private const val BLOCK_BYTES = GROUP * 2048L

    @Volatile var src: SectorSource? = null            // 실제 디스크(Bot) 또는 ISO 파일
    @Volatile var root: IsoEntry? = null
    @Volatile var charset: Charset = Charsets.ISO_8859_1
    @Volatile var label: String = ""
    @Volatile var gen: Int = 0                         // 디스크가 바뀔 때마다 증가 (옛 문서 ID 무효화)

    private val cache = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val prefetcher = Executors.newSingleThreadExecutor()

    private fun notifyRoots(ctx: Context) {
        ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(DiscProvider.AUTHORITY), null)
    }

    fun publish(ctx: Context, b: SectorSource, r: IsoEntry, cs: Charset, lbl: String) {
        cache.evictAll()
        gen++
        src = b; root = r; charset = cs; label = lbl
        notifyRoots(ctx)
    }

    /** 이 bot 이 게시한 상태일 때만 지운다 (이전 세션이 새 세션의 상태를 지우는 경합 방지) */
    fun clear(ctx: Context, b: SectorSource) {
        if (src !== b) return
        src = null; root = null; label = ""
        gen++
        cache.evictAll()
        notifyRoots(ctx)
    }

    private fun block(g: Int, extent: Long, fileSize: Long, r: Long): ByteArray {
        val key = "$g:$extent:$r"
        cache.get(key)?.let { return it }
        if (g != gen) throw IOException("디스크가 바뀌었어요")
        val b = src ?: throw IOException("연결 없음")
        val total = (fileSize + 2047) / 2048
        val first = r * GROUP
        val n = minOf(GROUP.toLong(), total - first).toInt()
        val d = b.read(extent + first, n) ?: throw IOException("디스크 읽기 실패")
        cache.put(key, d)
        return d
    }

    /** 파일의 [offset, offset+size) 를 out 에 읽어 읽은 바이트 수를 반환 */
    fun read(g: Int, extent: Long, fileSize: Long, offset: Long, size: Int, out: ByteArray): Int {
        if (g != gen) throw IOException("디스크가 바뀌었어요")
        if (offset >= fileSize) return 0
        val n = minOf(size.toLong(), fileSize - offset).toInt()
        var done = 0
        while (done < n) {
            val pos = offset + done
            val r = pos / BLOCK_BYTES
            val blk = block(g, extent, fileSize, r)
            val inBlk = (pos - r * BLOCK_BYTES).toInt()
            val c = minOf(n - done, blk.size - inBlk)
            if (c <= 0) break
            System.arraycopy(blk, inBlk, out, done, c)
            done += c
        }
        // 순차 재생 대비 다음 블록 미리 읽기
        val nextR = (offset + n) / BLOCK_BYTES
        if (nextR * BLOCK_BYTES < fileSize && cache.get("$g:$extent:$nextR") == null) {
            prefetcher.execute { runCatching { block(g, extent, fileSize, nextR) } }
        }
        return done
    }

    fun readAll(e: IsoEntry): ByteArray {
        val g = gen
        val out = ByteArray(e.size.toInt())
        var off = 0L
        val tmp = ByteArray(131072)
        while (off < e.size) {
            val n = read(g, e.extent, e.size, off, minOf(tmp.size.toLong(), e.size - off).toInt(), tmp)
            if (n <= 0) throw IOException("읽기 실패")
            System.arraycopy(tmp, 0, out, off.toInt(), n)
            off += n
        }
        return out
    }
}
