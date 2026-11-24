package com.thecfg.odd

import android.view.KeyEvent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/** 화면 상태 중 키보드 단축키가 조작해야 하는 것들 */
class UiNav {
    var showSettings by mutableStateOf(false)
    var showHelp by mutableStateOf(false)
    var showLog by mutableStateOf(false)
    var showInfo by mutableStateOf(false)
    var showMeta by mutableStateOf(false)
    var jump by mutableStateOf<String?>(null)            // 곡 번호 입력창 (null 이면 닫힘)
    var cursor by mutableIntStateOf(-1)                   // 데이터 디스크 목록 커서 (-1: 없음)
    var pendingOpen by mutableStateOf<IsoEntry?>(null)    // 키보드로 연 파일 → DataPane 이 처리
}

/**
 * odd.py 와 같은 단축키 (하드웨어 키보드 / 미디어 키).
 * 공통: q 종료, e 꺼내기, l 넣기, d 디스크 정보, s 설정, o ISO 파일 열기
 * 오디오: Space 재생/일시정지, n/p 다음/이전, ←/→ ±10초, ↑/↓ 볼륨, = 곡 번호 이동, r 반복, x 현재 곡 리핑, a 전체 리핑, t 정보 편집
 * 데이터: ↑/↓ 커서, →/Enter 열기, ←/Backspace 상위 폴더, c 복사, i ISO 저장
 */
fun handleKey(
    nav: UiNav, engine: OddEngine, image: ImageEngine, keyCode: Int, ev: KeyEvent,
    openIso: () -> Unit, quit: () -> Unit,
): Boolean {
    if (ev.isCtrlPressed || ev.isAltPressed || ev.isMetaPressed) return false
    val first = ev.repeatCount == 0
    val s = engine.ui.value
    if (keyCode == KeyEvent.KEYCODE_Q) { if (first) quit(); return true }
    if (keyCode == KeyEvent.KEYCODE_O) { if (first) openIso(); return true }
    val img = image.ui.value
    if (img.state == "ready") {   // ISO 파일을 보는 중
        when (keyCode) {
            KeyEvent.KEYCODE_E -> { if (first) image.eject(); return true }
            KeyEvent.KEYCODE_S -> { if (first) nav.showSettings = true; return true }
            KeyEvent.KEYCODE_D -> { if (first) nav.showInfo = true; return true }
        }
        return if (img.job == null) dataKey(nav, image, img, keyCode, first) else false
    }
    if (!s.connected) return false
    when (keyCode) {
        KeyEvent.KEYCODE_E -> { if (first) engine.eject(); return true }
        KeyEvent.KEYCODE_L -> { if (first) engine.load(); return true }
        KeyEvent.KEYCODE_S -> { if (first) nav.showSettings = true; return true }
        KeyEvent.KEYCODE_D -> { if (first && s.state == "ready") nav.showInfo = true; return true }
    }
    if (s.job != null || s.state != "ready") return false   // 작업 중엔 나머지 키 무시
    return when (s.kind) {
        "audio" -> audioKey(nav, engine, s, keyCode, first)
        "data" -> dataKey(nav, engine, s, keyCode, first)
        else -> false
    }
}

private fun audioKey(nav: UiNav, engine: OddEngine, s: UiState, k: Int, first: Boolean): Boolean {
    when (k) {
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
            if (first) {
                if (s.cur == null) s.tracks.firstOrNull()?.let { engine.play(it.no) } else engine.pause()
            }
            return true
        }
        KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_MEDIA_NEXT -> { if (first) engine.next(); return true }
        KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (first) engine.prev(); return true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { engine.seekBy(10); return true }
        KeyEvent.KEYCODE_DPAD_LEFT -> { engine.seekBy(-10); return true }
        KeyEvent.KEYCODE_DPAD_UP -> { engine.setVolume(s.volume + 5); return true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { engine.setVolume(s.volume - 5); return true }
        KeyEvent.KEYCODE_EQUALS -> { if (first) nav.jump = ""; return true }
        KeyEvent.KEYCODE_R -> { if (first) engine.toggleRepeat(); return true }
        KeyEvent.KEYCODE_X -> { if (first) s.cur?.let { engine.rip(listOf(it)) }; return true }
        KeyEvent.KEYCODE_A -> { if (first) engine.rip(s.tracks.map { it.no }); return true }
        KeyEvent.KEYCODE_T -> { if (first) nav.showMeta = true; return true }
    }
    return false
}

private fun dataKey(nav: UiNav, engine: DataActions, s: UiState, k: Int, first: Boolean): Boolean {
    val n = s.entries.size
    val cur = nav.cursor
    val sel = if (cur >= 0) s.entries.getOrNull(cur) else null
    when (k) {
        KeyEvent.KEYCODE_DPAD_UP -> { if (n > 0) nav.cursor = maxOf(0, cur - 1); return true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { if (n > 0) nav.cursor = minOf(n - 1, cur + 1); return true }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
            if (first && sel != null) { if (sel.isDir) engine.open(sel) else nav.pendingOpen = sel }
            return true
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DEL -> {
            if (first && s.path.isNotEmpty()) engine.up()
            return true
        }
        KeyEvent.KEYCODE_C -> { if (first && sel != null) engine.copy(sel); return true }
        KeyEvent.KEYCODE_I -> { if (first) engine.dumpIso(); return true }
    }
    return false
}

/** '=' 키: 곡 번호 입력 */
@Composable
fun JumpDialog(s: UiState, nav: UiNav, engine: OddEngine) {
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val go = {
        val n = text.toIntOrNull()
        if (n != null && s.tracks.any { it.no == n }) { nav.jump = null; engine.play(n) } else err = true
    }
    AlertDialog(
        onDismissRequest = { nav.jump = null },
        title = { Text("곡 번호로 이동") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() }.take(3); err = false },
                singleLine = true,
                isError = err,
                supportingText = { Text(if (err) "잘못된 곡입니다." else "1–${s.tracks.lastOrNull()?.no ?: 0}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { go() }) { Text("이동") } },
        dismissButton = { TextButton(onClick = { nav.jump = null }) { Text("취소") } },
    )
}
