package com.thecfg.odd

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException

/** 데이터 디스크를 안드로이드 '파일' 앱과 다른 앱의 파일 선택창에 읽기 전용 저장소로 노출 */
class DiscProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "com.thecfg.odd.documents"
        const val ROOT_DOC = "root"

        /** 문서 ID: d|f, 세대, extent, size, 이름(URL 인코딩) — 상태 없이 ID 만으로 해석 가능 */
        fun docId(e: IsoEntry): String =
            (if (e.isDir) "d" else "f") + "|" + OddHub.gen + "|" + e.extent + "|" + e.size + "|" + Uri.encode(e.name)

        private val DEFAULT_ROOT = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID,
        )
        private val DEFAULT_DOC = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
    }

    private class P(val dir: Boolean, val extent: Long, val size: Long, val name: String)

    private val handler by lazy { Handler(HandlerThread("odd-proxy").also { it.start() }.looper) }

    override fun onCreate() = true

    private fun parse(id: String): P {
        val r = OddHub.root ?: throw FileNotFoundException("디스크 없음")
        if (id == ROOT_DOC) return P(true, r.extent, r.size, OddHub.label.ifBlank { "ODD" })
        val a = id.split("|", limit = 5)
        if (a.size < 5 || a[1].toIntOrNull() != OddHub.gen) throw FileNotFoundException("디스크가 바뀌었어요")
        return P(a[0] == "d", a[2].toLong(), a[3].toLong(), Uri.decode(a[4]))
    }

    private fun addRow(c: MatrixCursor, id: String, p: P) {
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, p.name)
            .add(Document.COLUMN_MIME_TYPE, if (p.dir) Document.MIME_TYPE_DIR else (mimeOf(p.name) ?: "application/octet-stream"))
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, if (p.dir) null else p.size)
            .add(Document.COLUMN_LAST_MODIFIED, null)
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_ROOT)
        if (OddHub.src != null && OddHub.root != null) {
            c.newRow()
                .add(Root.COLUMN_ROOT_ID, "odd")
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC)
                .add(Root.COLUMN_TITLE, "ODD")
                .add(Root.COLUMN_SUMMARY, OddHub.label.ifBlank { "광학 디스크" })
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_ICON, R.drawable.ic_odd)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOC)
        addRow(c, documentId, parse(documentId))
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val p = parse(parentDocumentId)
        if (!p.dir) throw FileNotFoundException("폴더가 아니에요")
        val b = OddHub.src ?: throw FileNotFoundException("연결 없음")
        val list = try {
            Iso.list(b, p.extent, p.size, OddHub.charset, 5000)
        } catch (e: Exception) {
            throw FileNotFoundException(e.message)
        }
        val c = MatrixCursor(projection ?: DEFAULT_DOC)
        for (e in list) addRow(c, docId(e), P(e.isDir, e.extent, e.size, e.name))
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val p = parse(documentId)
        if (p.dir) throw FileNotFoundException("폴더는 열 수 없어요")
        if (mode.any { it == 'w' || it == 'a' }) throw FileNotFoundException("읽기 전용이에요")
        val sm = context!!.getSystemService(StorageManager::class.java)
        return sm.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY, Reader(OddHub.gen, p.extent, p.size), handler)
    }

    private class Reader(val g: Int, val extent: Long, val fileSize: Long) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = fileSize
        override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
            try {
                OddHub.read(g, extent, fileSize, offset, size, data)
            } catch (e: Exception) {
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        override fun onRelease() {}
    }
}
