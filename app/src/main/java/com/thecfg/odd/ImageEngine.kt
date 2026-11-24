package com.thecfg.odd

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.concurrent.Executors

/** 2048바이트 섹터를 읽는 원본 — 실제 디스크(Bot) 또는 ISO 파일 */
interface SectorSource {
    fun read(lba: Long, n: Int): ByteArray?
}

/** 데이터 화면(DataPane)이 부르는 동작 — 물리 디스크(OddEngine)와 ISO 파일(ImageEngine)이 공통으로 구현 */
interface DataActions {
    val isImage: Boolean get() = false
    fun open(e: IsoEntry)
    fun up()
    fun copy(e: IsoEntry)
    fun copyAll()
    fun dumpIso()
    fun eject()
}

/** ISO 파일을 섹터 원본으로 읽는다 (위치 지정 읽기) */
class FileSource(private val pfd: ParcelFileDescriptor) : SectorSource, Closeable {
    private val ch: FileChannel = FileInputStream(pfd.fileDescriptor).channel

    override fun read(lba: Long, n: Int): ByteArray? {
        val buf = ByteBuffer.allocate(n * 2048)
        var pos = lba * 2048
        while (buf.hasRemaining()) {
            val r = try { ch.read(buf, pos) } catch (e: IOException) { return null }
            if (r <= 0) break
            pos += r
        }
        return if (buf.hasRemaining()) null else buf.array()
    }

    override fun close() {
        runCatching { ch.close() }
        runCatching { pfd.close() }
    }
}

/** 저장해 둔 .iso 파일을 디스크처럼 탐색/열기/복사 */
class ImageEngine(
    private val ctx: Context,
    private val cfg: Settings,
    private val physical: OddEngine,
) : DataActions {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    override val isImage: Boolean get() = true

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var cancel = false
    private var source: FileSource? = null
    private var charset: Charset = Charsets.ISO_8859_1
    private val stack = ArrayList<IsoEntry>()
    private var lastProg = 0L

    private fun post(f: (UiState) -> UiState) { _ui.update(f) }

    private fun displayName(uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "image.iso"
    }

    fun openUri(uri: Uri) {
        io.execute {
            closeNow()
            _ui.value = UiState(connected = true, state = "loading")
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("파일을 열 수 없어요")
                val src = FileSource(pfd)
                val name = displayName(uri)
                val info = try { Iso.open(src, 0) } catch (e: Exception) { src.close(); throw e }
                val root = info.root
                if (root == null) {
                    src.close()
                    AppLog.w("ISO 파일 열기 실패: $name — ISO9660 형식이 아님")
                    _ui.value = UiState(msg = "ISO9660/Joliet 형식이 아니라서 열 수 없어요")
                    return@execute
                }
                source = src
                charset = info.charset
                stack.clear(); stack.add(root)
                OddHub.publish(ctx, src, root, info.charset, info.label.ifBlank { name.removeSuffix(".iso") })
                AppLog.i("ISO 파일 열기: $name · ${info.fs}")
                _ui.value = UiState(
                    connected = true, state = "ready", kind = "data", vendor = "ISO 파일", media = name,
                    fs = info.fs, label = info.label, sizeMb = info.sizeMb, entries = info.entries,
                )
            } catch (e: Exception) {
                AppLog.e("ISO 파일 열기 실패: ${e.message}")
                _ui.value = UiState(msg = "ISO 파일을 열지 못했어요: ${e.message}")
            }
        }
    }

    private fun closeNow() {
        val src = source ?: return
        source = null
        OddHub.clear(ctx, src)
        src.close()
        stack.clear()
        _ui.value = UiState()
        physical.refreshHub()   // 물리 디스크가 열려 있었다면 '파일' 앱 노출을 되돌린다
    }

    fun release() {
        io.execute { closeNow() }
        io.shutdown()
    }

    fun cancel() { cancel = true }

    override fun eject() {   // ISO 닫기
        io.execute {
            if (source != null) AppLog.i("ISO 파일 닫기")
            closeNow()
        }
    }

    private fun showDir(src: FileSource) {
        val e = stack.last()
        val list = try { Iso.list(src, e.extent, e.size, charset) } catch (ex: Exception) { emptyList() }
        post { it.copy(entries = list, path = stack.drop(1).map { s -> s.name }) }
    }

    override fun open(e: IsoEntry) {
        io.execute {
            val src = source ?: return@execute
            if (!e.isDir) return@execute
            stack.add(e)
            showDir(src)
        }
    }

    override fun up() {
        io.execute {
            val src = source ?: return@execute
            if (stack.size > 1) { stack.removeLast(); showDir(src) }
        }
    }

    override fun dumpIso() {
        post { it.copy(msg = "이미 ISO 파일이에요") }
    }

    private fun progress(label: String, done: Long, total: Long) {
        val n = System.currentTimeMillis()
        if (n - lastProg > 300) {
            lastProg = n
            post { it.copy(job = JobUi(label, (done * 100 / maxOf(total, 1)).toInt())) }
        }
    }

    private fun runJob(what: String, nodes: (FileSource) -> List<IsoNode>) {
        io.execute {
            val src = source ?: return@execute
            cancel = false
            AppLog.i("작업 시작 — $what")
            post { it.copy(job = JobUi("준비 중", 0), msg = "") }
            val s = _ui.value
            val rootName = s.label.ifBlank { s.media.removeSuffix(".iso") }
            val res = try {
                copyIsoNodes(ctx, cfg, src, nodes(src), rootName, { cancel }) { l, d, t -> progress(l, d, t) }
            } catch (e: Exception) {
                "작업 실패: ${e.message}"
            }
            when {
                res.contains("실패") -> AppLog.e(res)
                res.contains("취소") -> AppLog.w(res)
                else -> AppLog.i(res)
            }
            post { it.copy(job = null, msg = res) }
        }
    }

    override fun copy(e: IsoEntry) {
        runJob("복사: ${e.name}") { src ->
            val base = listOf(safeName(e.name))
            val nodes = ArrayList<IsoNode>()
            nodes += IsoNode(base, e.extent, e.size, e.isDir)
            if (e.isDir) nodes += Iso.walk(src, e.extent, e.size, charset, base)
            nodes
        }
    }

    override fun copyAll() {
        runJob("전체 파일 복사") { src ->
            val r = stack.firstOrNull()
            if (r == null) emptyList() else Iso.walk(src, r.extent, r.size, charset, emptyList())
        }
    }
}
