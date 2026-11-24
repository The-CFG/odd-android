package com.thecfg.odd

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** 저장 위치(폴더 선택값, 없으면 앱 전용 폴더) */
internal fun rootDir(ctx: Context, cfg: Settings): DocumentFile {
    val t = cfg.outTree
    if (t.isNotBlank()) {
        DocumentFile.fromTreeUri(ctx, Uri.parse(t))?.takeIf { it.canWrite() }?.let { return it }
    }
    return DocumentFile.fromFile(File(ctx.getExternalFilesDir(null), "ODD").apply { mkdirs() })
}

internal fun openOutStream(ctx: Context, f: DocumentFile): OutputStream =
    BufferedOutputStream(ctx.contentResolver.openOutputStream(f.uri) ?: throw IOException("파일 열기 실패"), 1 shl 16)

/** ISO 이미지의 파일 트리를 저장 위치/rootName 아래로 복사한다. 결과 문구를 반환. */
fun copyIsoNodes(
    ctx: Context, cfg: Settings, src: SectorSource, nodes: List<IsoNode>, rootName: String,
    isCancelled: () -> Boolean, progress: (String, Long, Long) -> Unit,
): String {
    val total = nodes.filter { !it.isDir }.sumOf { it.size }.coerceAtLeast(1)
    val root = child(rootDir(ctx, cfg), safeName(rootName.ifBlank { "iso" }))
    var done = 0L
    var count = 0
    var errors = 0
    for (nd in nodes) {
        var dir = root
        for (seg in nd.path.dropLast(1)) dir = child(dir, seg)
        if (nd.isDir) { child(dir, nd.path.last()); continue }
        val f = newFile(dir, nd.path.last(), "application/octet-stream")
        var left = nd.size
        var pos = nd.extent
        var cancelled = false
        openOutStream(ctx, f).use { o ->
            while (left > 0 && !cancelled) {
                if (isCancelled()) { cancelled = true; continue }
                val n = minOf(32L, (left + 2047) / 2048).toInt()
                val d = src.read(pos, n) ?: run { errors++; ByteArray(n * 2048) }
                o.write(d, 0, minOf(left, d.size.toLong()).toInt())
                left -= n * 2048L; pos += n; done += n * 2048L
                progress("복사 ${nd.path.last()}", done, total)
            }
        }
        if (cancelled) { f.delete(); return "복사 취소됨" }
        count++
    }
    return "복사 완료 ${count}개 파일 (오류 구간 ${errors}개)"
}
