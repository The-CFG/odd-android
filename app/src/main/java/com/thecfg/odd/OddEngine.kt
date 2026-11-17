package com.thecfg.odd

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class Track(val no: Int, val start: Int, val end: Int)
data class JobUi(val label: String, val pct: Int)

data class UiState(
    val connected: Boolean = false,
    val vendor: String = "",
    val state: String = "",          // nodisc / loading / unsupported / unreadable / ready
    val kind: String = "",           // audio / data
    val media: String = "",
    val tracks: List<Track> = emptyList(),
    val album: String = "", val artist: String = "", val titles: List<String> = emptyList(),
    val cur: Int? = null, val posSec: Int = 0, val paused: Boolean = false,
    val volume: Int = 100, val repeat: Boolean = false,
    val fs: String = "", val label: String = "", val sizeMb: Double = 0.0,
    val path: List<String> = emptyList(), val entries: List<IsoEntry> = emptyList(),
    val job: JobUi? = null, val msg: String = "",
    val resume: ResumeInfo? = null,   // 이어듣기 질문 (null 이면 없음)
)

private val BAD_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
fun safeName(s: String): String = BAD_CHARS.replace(s, "_").trim(' ', '.').ifEmpty { "_" }

private fun wavHeader(size: Int): ByteArray =
    ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + size); put("WAVE".toByteArray()); put("fmt ".toByteArray())
        putInt(16); putShort(1); putShort(2); putInt(44100); putInt(176400); putShort(4); putShort(16)
        put("data".toByteArray()); putInt(size)
    }.array()

private fun child(dir: DocumentFile, name: String): DocumentFile =
    dir.findFile(name)?.takeIf { it.isDirectory } ?: dir.createDirectory(name) ?: throw IOException("폴더 생성 실패: $name")

private fun newFile(dir: DocumentFile, name: String, mime: String): DocumentFile {
    dir.findFile(name)?.delete()
    return dir.createFile(mime, name) ?: throw IOException("파일 생성 실패: $name")
}

private sealed interface Cmd {
    data object Quit : Cmd
    data class Play(val n: Int) : Cmd
    data object Pause : Cmd
    data object Next : Cmd
    data object Prev : Cmd
    data class SeekBy(val d: Int) : Cmd
    data class Resume(val accept: Boolean) : Cmd
    data object Eject : Cmd
    data object Load : Cmd
    data class Open(val e: IsoEntry) : Cmd
    data object Up : Cmd
    data class Rip(val nos: List<Int>) : Cmd
    data class Copy(val e: IsoEntry) : Cmd
    data object CopyAll : Cmd
    data object Dump : Cmd
}

private class Seg(val start: Long, val idx: Int, val off: Int)

/**
 * odd.py 의 리더 데몬 + 컨트롤러 로직.
 * USB 전담 워커 스레드 하나가 명령 큐를 처리하고, 상태는 StateFlow 로 UI 에 내보낸다.
 */
class OddEngine(private val ctx: Context, private val cfg: Settings) {
    private val _ui = MutableStateFlow(UiState(volume = cfg.volume, repeat = cfg.repeat))
    val ui: StateFlow<UiState> = _ui
    private val net = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    @Volatile private var volume = cfg.volume
    @Volatile private var repeatAll = cfg.repeat

    fun attach(bot: Bot) {
        detach()
        _ui.value = UiState(connected = true, volume = volume, repeat = repeatAll)
        session = Session(bot).also { it.start() }
    }

    fun detach() {
        session?.stop()
        session = null
        _ui.value = UiState(volume = volume, repeat = repeatAll)
    }

    fun release() { detach(); net.shutdown() }

    private fun send(c: Cmd) { session?.q?.offer(c) }
    fun play(n: Int) = send(Cmd.Play(n))
    fun pause() = send(Cmd.Pause)
    fun next() = send(Cmd.Next)
    fun prev() = send(Cmd.Prev)
    fun seekBy(d: Int) = send(Cmd.SeekBy(d))
    fun eject() = send(Cmd.Eject)
    fun resume(accept: Boolean) = send(Cmd.Resume(accept))
    fun load() = send(Cmd.Load)
    fun open(e: IsoEntry) = send(Cmd.Open(e))
    fun up() = send(Cmd.Up)
    fun rip(nos: List<Int>) = send(Cmd.Rip(nos))
    fun copy(e: IsoEntry) = send(Cmd.Copy(e))
    fun copyAll() = send(Cmd.CopyAll)
    fun dumpIso() = send(Cmd.Dump)
    fun cancel() { session?.cancel = true }

    fun setVolume(v: Int) {
        volume = v.coerceIn(0, 100); cfg.volume = volume
        session?.applyVolume()
        _ui.update { it.copy(volume = volume) }
    }

    fun toggleRepeat() {
        repeatAll = !repeatAll; cfg.repeat = repeatAll
        _ui.update { it.copy(repeat = repeatAll) }
    }

    private inner class Session(val bot: Bot) {
        val q = LinkedBlockingQueue<Cmd>()
        @Volatile var cancel = false
        @Volatile var alive = true
        private val th = Thread({ loop() }, "odd-worker")

        private var mode = "none"
        private var aud: List<Track> = emptyList()
        private var readIdx = 0
        private var lba = 0
        private var end = 0
        private var playing = false
        private var stype = 0x04
        private var pending: Cmd? = null
        private var charset: Charset = Charsets.ISO_8859_1
        private val stack = ArrayList<IsoEntry>()
        private var track: AudioTrack? = null
        private var w = 0L                       // AudioTrack 에 써 넣은 총 프레임
        private val segs = ArrayDeque<Seg>()     // 어느 프레임부터 어느 트랙인지 (재생 위치 표시용)
        private var errs = 0
        private var lastProbe = 0L
        private var lastPos = 0L
        private var lastProg = 0L
        private var lastState = ""
        private var discId = ""
        private var lastSave = 0L
        private var lastSaved = ""

        fun start() = th.start()
        fun stop() { alive = false; cancel = true; q.offer(Cmd.Quit) }
        fun applyVolume() { track?.setVolume(volume / 100f) }
        private fun post(f: (UiState) -> UiState) { if (alive) _ui.update(f) }
        private fun now() = System.currentTimeMillis()
        private fun logState(s: String) {
            if (s == lastState) return
            lastState = s
            when (s) {
                "nodisc" -> AppLog.i("디스크 없음")
                "loading" -> AppLog.i("디스크 읽는 중…")
                "unsupported" -> AppLog.w("지원하지 않는 디스크")
                "unreadable" -> AppLog.w("디스크를 읽지 못함")
            }
        }

        private fun setState(s: String) { logState(s); post { it.copy(state = s) } }

        // ---------- 메인 루프 ----------
        private fun loop() {
            try {
                runCatching { val v = bot.inquiry(); AppLog.i("드라이브 연결: ${v.ifBlank { "알 수 없음" }}"); post { it.copy(vendor = v) } }
                while (alive) {
                    try {
                        val p = pending
                        if (p != null) { pending = null; runJob(p); continue }
                        updatePos()
                        if (mode == "none") {
                            drain(1000)
                            if (mode != "none" || pending != null || !alive) continue
                            val s = bot.probe()
                            if (s == "ready") { identify(); lastProbe = now() } else setState(s)
                            errs = 0
                            continue
                        }
                        if (!playing) {
                            drain(300)
                            if (mode != "none" && pending == null && now() - lastProbe > 2000) {
                                lastProbe = now()
                                val s = bot.probe()
                                if (s != "ready") dropDisc(s)
                            }
                            continue
                        }
                        playChunk()
                    } catch (e: FatalException) {
                        errs++
                        AppLog.w("USB 통신 오류 ($errs/5): ${e.message}")
                        if (errs >= 5) { AppLog.e("ODD 통신이 끊어졌어요"); post { it.copy(connected = false, msg = "ODD 통신이 끊어졌어요") }; break }
                        dropDisc("unreadable")
                        Thread.sleep(1000)
                    }
                }
            } catch (_: InterruptedException) {
            } finally {
                track?.release(); track = null
                OddHub.clear(ctx, bot)
            }
        }

        private fun drain(ms: Long): Boolean {
            var changed = false
            var c = if (ms > 0) q.poll(ms, TimeUnit.MILLISECONDS) else q.poll()
            while (c != null) {
                if (handle(c)) changed = true
                c = q.poll()
            }
            return changed
        }

        /** 재생 위치가 바뀌었거나 작업이 예약되면 true */
        private fun handle(c: Cmd): Boolean {
            when (c) {
                Cmd.Quit -> alive = false
                is Cmd.Play -> if (mode == "audio") {
                    val k = aud.indexOfFirst { it.no == c.n }
                    if (k >= 0) { flushAudio(); startTrack(k); return true }
                }
                Cmd.Pause -> {
                    val t = track
                    if (t != null && _ui.value.cur != null) {
                        if (_ui.value.paused) t.play() else t.pause()
                        post { it.copy(paused = !it.paused) }
                    }
                }
                Cmd.Next, Cmd.Prev -> if (mode == "audio") {
                    val cur = _ui.value.cur
                    val k = aud.indexOfFirst { it.no == cur }
                    if (k >= 0) {
                        val to = if (c == Cmd.Next) {
                            if (k + 1 < aud.size) k + 1 else if (repeatAll) 0 else aud.size - 1
                        } else if (_ui.value.posSec > 3 || k == 0) k else k - 1
                        flushAudio(); startTrack(to); return true
                    }
                }
                is Cmd.SeekBy -> if (mode == "audio") {
                    val cur = _ui.value.cur
                    val k = aud.indexOfFirst { it.no == cur }
                    if (k >= 0) {
                        val len = (aud[k].end - aud[k].start) / 75
                        flushAudio(); startTrack(k, (_ui.value.posSec + c.d).coerceIn(0, maxOf(0, len - 1)))
                        return true
                    }
                }
                is Cmd.Resume -> {
                    val r = _ui.value.resume
                    post { it.copy(resume = null) }
                    if (mode == "audio" && r != null) {
                        if (c.accept) {
                            val k = aud.indexOfFirst { it.no == r.trackNo }
                            if (k >= 0) {
                                val len = (aud[k].end - aud[k].start) / 75
                                AppLog.i("이어듣기: 트랙 ${r.trackNo} ${r.posSec}초부터")
                                flushAudio(); startTrack(k, r.posSec.coerceIn(0, maxOf(0, len - 1)))
                                return true
                            }
                        } else {
                            ResumeStore.clear(ctx)
                            AppLog.i("이어듣기 기록 삭제")
                            if (cfg.autoplay) { flushAudio(); startTrack(0); return true }
                        }
                    }
                }
                Cmd.Eject -> { ResumeStore.clear(ctx); runCatching { bot.eject() }; dropDisc("nodisc"); return true }
                Cmd.Load -> { runCatching { bot.load() }; dropDisc("loading"); return true }
                is Cmd.Open -> if (mode == "data" && c.e.isDir) { stack.add(c.e); showDir() }
                Cmd.Up -> if (mode == "data" && stack.size > 1) { stack.removeLast(); showDir() }
                is Cmd.Rip -> if (mode == "audio") { pending = c; playing = false; return true }
                is Cmd.Copy, Cmd.CopyAll, Cmd.Dump -> if (mode == "data") { pending = c; return true }
            }
            return false
        }

        private fun showDir() {
            val e = stack.last()
            val list = try { Iso.list(bot, e.extent, e.size, charset) } catch (ex: FatalException) { emptyList() }
            post { it.copy(entries = list, path = stack.drop(1).map { s -> s.name }) }
        }

        // ---------- 디스크 인식 ----------
        private fun dropDisc(state: String) {
            mode = "none"; aud = emptyList(); playing = false
            logState(state)
            OddHub.clear(ctx, bot)
            flushAudio(); track?.pause()
            post {
                it.copy(state = state, kind = "", tracks = emptyList(), media = "", album = "", artist = "",
                    titles = emptyList(), cur = null, posSec = 0, paused = false, fs = "", label = "",
                    sizeMb = 0.0, path = emptyList(), entries = emptyList(), job = null, resume = null)
            }
        }

        private fun identify() {
            val (toc, leadout) = try {
                bot.readToc()
            } catch (e: FatalException) {
                if (lastState != "unreadable") AppLog.w("TOC 읽기 실패: ${e.message}")
                setState("unreadable"); return
            }
            runCatching { bot.setSpeed(cfg.playSpeed * 176) }
            val media = runCatching { bot.mediaName() }.getOrDefault("")
            val tracks = toc.mapIndexedNotNull { i, t ->
                if ((t.control and 4) == 0) Track(t.no, t.lba, if (i + 1 < toc.size) toc[i + 1].lba else leadout) else null
            }
            aud = tracks
            playing = false
            if (tracks.isNotEmpty()) {
                mode = "audio"
                post { it.copy(state = "ready", kind = "audio", media = media, tracks = tracks, titles = emptyList(), msg = "") }
                lastState = "ready"
                AppLog.i("오디오 CD 인식: ${tracks.size}트랙" + if (media.isNotBlank()) " · $media" else "")
                val id = MusicBrainz.discId(toc, leadout)
                discId = id
                net.execute {
                    val r = MusicBrainz.get(ctx, id, tracks.size, cfg.lookup)
                    if (r != null) {
                        AppLog.i("앨범 정보: ${r.album.ifBlank { "(제목 없음)" }}")
                        post { if (it.tracks == tracks) it.copy(album = r.album, artist = r.artist, titles = r.titles) else it }
                    } else {
                        AppLog.i(if (cfg.lookup) "앨범 정보 없음 (MusicBrainz)" else "앨범 정보 조회 꺼짐")
                    }
                }
                val saved = ResumeStore.load(ctx, id)?.takeIf { r ->
                    tracks.any { it.no == r.trackNo } && (r.posSec >= 5 || r.trackNo != tracks.first().no)
                }
                if (saved != null) {
                    AppLog.i("이어듣기 기록 발견: 트랙 ${saved.trackNo} · ${saved.posSec}초")
                    if (cfg.autoResume) {
                        val k = tracks.indexOfFirst { it.no == saved.trackNo }
                        val len = (tracks[k].end - tracks[k].start) / 75
                        AppLog.i("자동 이어듣기: 트랙 ${saved.trackNo} · ${saved.posSec}초부터")
                        startTrack(k, saved.posSec.coerceIn(0, maxOf(0, len - 1)))
                    } else {
                        post { it.copy(resume = saved) }
                    }
                } else if (cfg.autoplay) {
                    startTrack(0)
                }
            } else {
                val info = Iso.open(bot, toc[0].lba.toLong())
                charset = info.charset
                stack.clear(); info.root?.let { stack.add(it) }
                mode = "data"
                info.root?.let { OddHub.publish(ctx, bot, it, info.charset, info.label) }
                lastState = "ready"
                AppLog.i("데이터 디스크 인식: ${info.label.ifBlank { "(이름 없음)" }} · ${info.fs}" + if (media.isNotBlank()) " · $media" else "")
                post {
                    it.copy(state = "ready", kind = "data", media = media, fs = info.fs, label = info.label,
                        sizeMb = info.sizeMb, entries = info.entries, path = emptyList(), msg = "")
                }
            }
        }

        // ---------- 오디오 재생 ----------
        private fun ensureTrack(): AudioTrack {
            track?.let { return it }
            val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuf, 44100 * 4 / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.setVolume(volume / 100f)
            track = t
            return t
        }

        private fun flushAudio() {
            track?.let { it.pause(); it.flush() }
            w = 0; segs.clear()
            post { it.copy(paused = false) }
        }

        private fun startTrack(idx: Int, off: Int = 0) {
            val a = aud[idx]
            readIdx = idx; lba = a.start + off * 75; end = a.end; playing = true
            segs.addLast(Seg(w, idx, off))
            val t = ensureTrack()
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING && !_ui.value.paused) t.play()
            updatePos(true)
        }

        private fun updatePos(force: Boolean = false) {
            val t = track ?: return
            val n = now()
            if (!force && n - lastPos < 200) return
            lastPos = n
            val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            while (segs.size > 1 && segs[1].start <= played) segs.removeFirst()
            val s = segs.firstOrNull()
            if (s == null || (!playing && played >= w)) {
                if (_ui.value.cur != null) {
                    segs.clear(); ResumeStore.clear(ctx)   // 끝까지 들었으면 기록 삭제
                    post { it.copy(cur = null, posSec = 0, paused = false) }
                }
                return
            }
            val sec = s.off + (maxOf(0L, played - s.start) / 44100).toInt()
            if (mode == "audio" && discId.isNotEmpty() && n - lastSave > 5000) {   // 5초마다 위치 저장
                lastSave = n
                val key = "${aud[s.idx].no}:$sec"
                if (key != lastSaved) { lastSaved = key; ResumeStore.save(ctx, discId, aud[s.idx].no, sec) }
            }
            post { it.copy(cur = aud[s.idx].no, posSec = sec) }
        }

        private fun playChunk() {
            if (lba >= end) {
                if (readIdx + 1 < aud.size) startTrack(readIdx + 1)
                else if (repeatAll) startTrack(0)
                else playing = false   // 남은 버퍼는 끝까지 재생, updatePos 가 종료를 감지
                return
            }
            val n = minOf(cfg.chunk, end - lba)
            var r = bot.readCdAudio(lba, n, stype)
            if ((r.status != 0 || r.data.size != n * AUDIO_SECTOR) && stype == 0x04) {
                stype = 0
                r = bot.readCdAudio(lba, n, 0)
            }
            var data = r.data
            if (r.status != 0 || data.size != n * AUDIO_SECTOR) {
                val sn = bot.sense()
                if (sn != null && (sn.first == 2 || sn.first == 6)) { dropDisc("loading"); return }
                AppLog.w("읽기 오류: LBA $lba (+$n 섹터) 무음 처리")
                data = ByteArray(n * AUDIO_SECTOR)   // 오류 구간은 무음
            }
            errs = 0
            lba += n
            push(data)
        }

        private fun push(data: ByteArray): Boolean {
            val t = ensureTrack()
            var off = 0
            while (off < data.size && alive) {
                if (drain(0)) return true
                val n = t.write(data, off, data.size - off, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) throw FatalException("오디오 출력 오류 $n")
                if (n == 0) { updatePos(); Thread.sleep(15) } else { off += n; w += n / 4 }
            }
            return false
        }

        // ---------- 작업 (rip / dumpiso / copy) ----------
        private fun outRoot(): DocumentFile {
            val t = cfg.outTree
            if (t.isNotBlank()) {
                DocumentFile.fromTreeUri(ctx, Uri.parse(t))?.takeIf { it.canWrite() }?.let { return it }
            }
            return DocumentFile.fromFile(File(ctx.getExternalFilesDir(null), "ODD").apply { mkdirs() })
        }

        private fun openOut(f: DocumentFile): OutputStream =
            BufferedOutputStream(ctx.contentResolver.openOutputStream(f.uri) ?: throw IOException("파일 열기 실패"), 1 shl 16)

        private fun progress(label: String, done: Long, total: Long) {
            val n = now()
            if (n - lastProg > 300) {
                lastProg = n
                post { it.copy(job = JobUi(label, (done * 100 / maxOf(total, 1)).toInt())) }
            }
        }

        private fun runJob(c: Cmd) {
            cancel = false
            val what = when (c) {
                is Cmd.Rip -> "리핑 (${c.nos.size}트랙)"
                is Cmd.Copy -> "복사: ${c.e.name}"
                Cmd.CopyAll -> "전체 파일 복사"
                else -> "ISO로 저장"
            }
            AppLog.i("작업 시작 — $what")
            flushAudio(); playing = false
            post { it.copy(cur = null, posSec = 0, job = JobUi("준비 중", 0), msg = "") }
            var res: String
            try {
                res = when (c) {
                    is Cmd.Rip -> jobRip(c.nos)
                    is Cmd.Copy -> jobCopy(c.e)
                    Cmd.CopyAll -> jobCopyAll()
                    else -> jobDump()
                }
            } catch (e: FatalException) {
                res = "작업 실패: ${e.message}"
            } catch (e: IOException) {
                res = "작업 실패: ${e.message}"
            }
            runCatching { bot.setSpeed(cfg.playSpeed * 176) }
            when {
                res.contains("실패") -> AppLog.e(res)
                res.contains("취소") -> AppLog.w(res)
                else -> AppLog.i(res)
            }
            post { it.copy(job = null, msg = res) }
        }

        private fun jobRip(nos: List<Int>): String {
            val s = _ui.value
            val todo = aud.filter { it.no in nos }
            if (todo.isEmpty()) return "리핑할 트랙이 없어요"
            val total = todo.sumOf { (it.end - it.start).toLong() }
            runCatching { bot.setSpeed(cfg.ripSpeed * 176) }
            val folderName = listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "Audio CD" }
            val folder = child(outRoot(), safeName(folderName))
            var done = 0L
            var errors = 0
            for (t in todo) {
                val title = s.titles.getOrNull(aud.indexOf(t))?.takeIf { it.isNotBlank() } ?: "Track ${t.no}"
                val f = newFile(folder, safeName("%02d - %s.wav".format(t.no, title)), "audio/x-wav")
                var pos = t.start
                var cancelled = false
                openOut(f).use { o ->
                    o.write(wavHeader((t.end - t.start) * AUDIO_SECTOR))
                    while (pos < t.end && !cancelled) {
                        if (cancel) { cancelled = true; continue }
                        val n = minOf(cfg.chunk, t.end - pos)
                        var r = bot.readCdAudio(pos, n, stype)
                        if ((r.status != 0 || r.data.size != n * AUDIO_SECTOR) && stype == 0x04) {
                            stype = 0
                            r = bot.readCdAudio(pos, n, 0)
                        }
                        val d = if (r.status == 0 && r.data.size == n * AUDIO_SECTOR) r.data else { errors++; ByteArray(n * AUDIO_SECTOR) }
                        o.write(d)
                        pos += n; done += n
                        progress("리핑 ${t.no}번", done, total)
                    }
                }
                if (cancelled) { f.delete(); return "리핑 취소됨" }
            }
            return "리핑 완료 ${todo.size}트랙 (오류 구간 ${errors}개)"
        }

        private fun jobDump(): String {
            val (last, block) = bot.capacity() ?: return "용량 읽기 실패"
            val total = last + 1
            runCatching { bot.setSpeed(0xFFFF) }
            val f = newFile(outRoot(), safeName(_ui.value.label.ifBlank { "disc" }) + ".iso", "application/octet-stream")
            var pos = 0L
            var errors = 0
            var cancelled = false
            openOut(f).use { o ->
                while (pos < total && !cancelled) {
                    if (cancel) { cancelled = true; continue }
                    val n = minOf(32L, total - pos).toInt()
                    val d = if (block == 2048) bot.read10(pos, n) else null
                    if (d == null) errors++
                    o.write(d ?: ByteArray(n * block))
                    pos += n
                    progress("ISO 덤프", pos, total)
                }
            }
            if (cancelled) { f.delete(); return "덤프 취소됨" }
            return "ISO 덤프 완료 (오류 구간 ${errors}개)"
        }

        private fun jobCopy(e: IsoEntry): String {
            val base = listOf(safeName(e.name))
            val nodes = ArrayList<IsoNode>()
            nodes += IsoNode(base, e.extent, e.size, e.isDir)
            if (e.isDir) nodes += Iso.walk(bot, e.extent, e.size, charset, base)
            return copyNodes(nodes)
        }

        private fun jobCopyAll(): String {
            val r = stack.firstOrNull() ?: return "복사할 파일이 없어요"
            return copyNodes(Iso.walk(bot, r.extent, r.size, charset, emptyList()))
        }

        private fun copyNodes(nodes: List<IsoNode>): String {
            val total = nodes.filter { !it.isDir }.sumOf { it.size }.coerceAtLeast(1)
            runCatching { bot.setSpeed(0xFFFF) }
            val root = child(outRoot(), safeName(_ui.value.label.ifBlank { "disc" }))
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
                openOut(f).use { o ->
                    while (left > 0 && !cancelled) {
                        if (cancel) { cancelled = true; continue }
                        val n = minOf(32L, (left + 2047) / 2048).toInt()
                        val d = bot.read10(pos, n) ?: run { errors++; ByteArray(n * 2048) }
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
    }
}
