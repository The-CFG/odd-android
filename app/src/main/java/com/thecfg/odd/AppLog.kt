package com.thecfg.odd

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

data class LogEntry(val time: Long, val level: Char, val text: String)   // level: I / W / E

/** 앱 로그. 최근 500건을 메모리에 두고 filesDir/odd.log 에도 남긴다. */
object AppLog {
    private const val MAX = 500
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor()
    private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private fun line(e: LogEntry) = "${e.time}\t${e.level}\t${e.text}"

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "odd.log")
        file = f
        val loaded = try {
            f.readLines().mapNotNull { ln ->
                val p = ln.split('\t', limit = 3)
                val t = p.getOrNull(0)?.toLongOrNull()
                if (p.size == 3 && t != null) LogEntry(t, p[1].firstOrNull() ?: 'I', p[2]) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
        val kept = loaded.takeLast(MAX)
        _entries.value = (kept + _entries.value).takeLast(MAX)
        // 파일이 너무 커졌으면 최근 분량만 남기고 다시 쓴다
        if (loaded.size > MAX * 2) io.execute { runCatching { f.writeText(kept.joinToString("\n", postfix = "\n") { line(it) }) } }
    }

    @Synchronized
    private fun add(level: Char, text: String) {
        val e = LogEntry(System.currentTimeMillis(), level, text.replace('\n', ' '))
        _entries.value = (_entries.value + e).takeLast(MAX)
        file?.let { f -> io.execute { runCatching { f.appendText(line(e) + "\n") } } }
    }

    fun i(t: String) = add('I', t)
    fun w(t: String) = add('W', t)
    fun e(t: String) = add('E', t)

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        file?.let { f -> io.execute { runCatching { f.writeText("") } } }
    }

    fun fmt(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms))

    /** 공유용 텍스트 (오래된 것부터, 최대 약 100KB) */
    fun asText(): String {
        val all = _entries.value
        val sb = StringBuilder()
        var used = 0
        val lines = ArrayList<String>()
        for (e in all.asReversed()) {
            val l = "${fmt(e.time)} ${e.level} ${e.text}"
            used += l.length + 1
            if (used > 100_000) break
            lines += l
        }
        sb.append("TheODDer 로그 (${lines.size}건)\n")
        lines.asReversed().forEach { sb.append(it).append('\n') }
        return sb.toString()
    }
}
