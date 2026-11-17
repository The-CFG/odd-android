package com.thecfg.odd

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private const val ACTION_PERM = "com.thecfg.odd.USB_PERMISSION"

private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

class MainActivity : ComponentActivity() {
    private lateinit var usb: UsbManager
    private lateinit var cfg: Settings
    private lateinit var engine: OddEngine
    private var conn: UsbDeviceConnection? = null
    private var current: UsbDevice? = null
    private var status by mutableStateOf("")
    private var outTree by mutableStateOf("")

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            cfg.outTree = uri.toString()
            outTree = cfg.outTree
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERM -> {
                    val d = i.usbDevice()
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) open(d)
                    else status = "USB 권한이 거부됐어요"
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scan()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = i.usbDevice()
                    if (d != null && d.deviceName == current?.deviceName) detach()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        cfg = Settings(this)
        engine = OddEngine(applicationContext, cfg)
        outTree = cfg.outTree
        status = "ODD를 USB로 연결해 주세요"
        val f = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { OddApp(engine, cfg, status, outTree, { picker.launch(null) }, { scan() }) }
        scan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        scan()
    }

    override fun onResume() {
        super.onResume()
        if (current == null) scan()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        detach()
        engine.release()
        super.onDestroy()
    }

    private fun scan() {
        if (current != null) return
        val dev = usb.deviceList.values.firstOrNull { findMsc(it) != null }
        if (dev == null) { status = "ODD가 감지되지 않았어요. 허브를 쓰면 허브와 ODD 전원도 확인하세요."; return }
        if (usb.hasPermission(dev)) open(dev)
        else {
            status = "USB 권한 팝업에서 '허용'을 눌러 주세요"
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(dev, pi)
        }
    }

    private fun open(dev: UsbDevice) {
        if (current != null) return
        val m = findMsc(dev) ?: return
        val c = usb.openDevice(dev) ?: run { status = "USB 장치를 열지 못했어요"; return }
        if (!c.claimInterface(m.intf, true)) { c.close(); status = "USB 인터페이스를 점유하지 못했어요"; return }
        conn = c
        current = dev
        engine.attach(Bot(c, m.epIn, m.epOut))
    }

    private fun detach() {
        engine.detach()
        conn?.close()
        conn = null
        current = null
        status = "ODD 연결이 끊어졌어요. 다시 연결해 주세요."
    }
}

private fun mmss(s: Int) = "%02d:%02d".format(maxOf(0, s) / 60, maxOf(0, s) % 60)

private fun human(n: Long): String {
    var v = n.toDouble()
    for (u in listOf("B", "KB", "MB", "GB")) {
        if (v < 1024 || u == "GB") return if (u == "B") "${v.toLong()}B" else "%.1f%s".format(v, u)
        v /= 1024
    }
    return ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OddApp(
    engine: OddEngine, cfg: Settings, status: String, outTree: String,
    onPickFolder: () -> Unit, onRescan: () -> Unit,
) {
    val s by engine.ui.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("ODD 관리자") }, actions = {
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "설정") }
            })
        }) { pad ->
            Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
                if (!s.connected) {
                    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                        Text(status)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRescan) { Text("다시 찾기") }
                    }
                } else {
                    val busy = s.job != null
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.vendor.ifBlank { "ODD" }, style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (s.state) {
                                    "ready" -> listOf(s.media, if (s.kind == "audio") "오디오 CD" else "데이터 디스크")
                                        .filter { it.isNotBlank() }.joinToString(" · ")
                                    "nodisc" -> "디스크 없음"
                                    "unsupported" -> "이 드라이브가 읽을 수 없는 디스크"
                                    "unreadable" -> "디스크를 읽지 못했어요"
                                    else -> "디스크 읽는 중…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { engine.eject() }, enabled = !busy) { Text("꺼내기") }
                        TextButton(onClick = { engine.load() }, enabled = !busy) { Text("넣기") }
                    }
                    s.job?.let { j ->
                        Text("${j.label}  ${j.pct}%", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { j.pct / 100f }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { engine.cancel() }) { Text("취소") }
                    }
                    if (s.msg.isNotBlank()) Text(s.msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    if (s.state == "ready") when (s.kind) {
                        "audio" -> AudioPane(s, engine)
                        "data" -> DataPane(s, engine)
                    }
                }
            }
        }
        if (showSettings) SettingsDialog(cfg, outTree, onPickFolder) { showSettings = false }
    }
}

@Composable
private fun AudioPane(s: UiState, engine: OddEngine) {
    val busy = s.job != null
    val curTrack = s.tracks.firstOrNull { it.no == s.cur }
    Column(Modifier.fillMaxSize()) {
        Text(s.album.ifBlank { "오디오 CD" }, style = MaterialTheme.typography.titleMedium)
        if (s.artist.isNotBlank()) Text(s.artist, style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp)) {
            itemsIndexed(s.tracks) { i, t ->
                val sel = s.cur == t.no
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) { engine.play(t.no) }
                        .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                ) {
                    Text("%02d".format(t.no), Modifier.width(32.dp))
                    Text(
                        s.titles.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "트랙 ${t.no}",
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(mmss((t.end - t.start) / 75))
                }
            }
        }
        if (curTrack != null) {
            val len = (curTrack.end - curTrack.start) / 75
            LinearProgressIndicator(progress = { s.posSec / maxOf(len, 1).toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("${mmss(s.posSec)} / ${mmss(len)}", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            OutlinedButton({ engine.prev() }, enabled = !busy) { Text("이전") }
            OutlinedButton({ engine.seekBy(-10) }, enabled = !busy) { Text("-10") }
            Button({
                if (s.cur == null) s.tracks.firstOrNull()?.let { engine.play(it.no) } else engine.pause()
            }, enabled = !busy) { Text(if (s.cur == null || s.paused) "재생" else "일시정지") }
            OutlinedButton({ engine.seekBy(10) }, enabled = !busy) { Text("+10") }
            OutlinedButton({ engine.next() }, enabled = !busy) { Text("다음") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("볼륨", Modifier.padding(end = 8.dp))
            Slider(s.volume.toFloat(), { engine.setVolume(it.toInt()) }, Modifier.weight(1f), valueRange = 0f..100f)
            Text("반복", Modifier.padding(horizontal = 8.dp))
            Switch(s.repeat, { engine.toggleRepeat() })
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ s.cur?.let { engine.rip(listOf(it)) } }, enabled = !busy && s.cur != null, modifier = Modifier.weight(1f)) { Text("현재 곡 리핑") }
            OutlinedButton({ engine.rip(s.tracks.map { it.no }) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("전체 리핑") }
        }
    }
}

@Composable
private fun DataPane(s: UiState, engine: OddEngine) {
    val busy = s.job != null
    Column(Modifier.fillMaxSize()) {
        Text(s.label.ifBlank { "데이터 디스크" }, style = MaterialTheme.typography.titleMedium)
        Text("${s.fs}  ${if (s.sizeMb > 0) "%.1fMB".format(s.sizeMb) else ""}", style = MaterialTheme.typography.bodySmall)
        Text("/" + s.path.joinToString("/"), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (s.path.isNotEmpty()) item {
                Text("..", Modifier.fillMaxWidth().clickable(enabled = !busy) { engine.up() }.padding(12.dp))
            }
            items(s.entries) { e ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy && e.isDir) { engine.open(e) }.padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (e.isDir) "▸ " else "   ")
                    Text(e.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!e.isDir) Text(human(e.size), style = MaterialTheme.typography.bodySmall)
                    TextButton({ engine.copy(e) }, enabled = !busy) { Text("복사") }
                }
            }
        }
        Button({ engine.dumpIso() }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text("디스크 전체 ISO 덤프") }
    }
}

@Composable
private fun SettingsDialog(cfg: Settings, outTree: String, onPickFolder: () -> Unit, onClose: () -> Unit) {
    var autoplay by remember { mutableStateOf(cfg.autoplay) }
    var lookup by remember { mutableStateOf(cfg.lookup) }
    var ps by remember { mutableStateOf(cfg.playSpeed) }
    var rs by remember { mutableStateOf(cfg.ripSpeed) }
    var ch by remember { mutableStateOf(cfg.chunk) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("닫기") } },
        title = { Text("설정") },
        text = {
            Column {
                Text("저장 위치", style = MaterialTheme.typography.labelMedium)
                TextButton(onPickFolder) {
                    Text(if (outTree.isBlank()) "앱 전용 폴더 (변경하려면 탭)" else Uri.parse(outTree).lastPathSegment ?: outTree)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("오디오 CD 자동 재생", Modifier.weight(1f))
                    Switch(autoplay, { autoplay = it; cfg.autoplay = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("앨범 정보 온라인 조회", Modifier.weight(1f))
                    Switch(lookup, { lookup = it; cfg.lookup = it })
                }
                Text("재생 속도 ${ps}배 (다음 디스크부터 적용)", style = MaterialTheme.typography.labelMedium)
                Slider(ps.toFloat(), { ps = it.toInt(); cfg.playSpeed = ps }, valueRange = 1f..24f)
                Text("리핑 속도 ${rs}배", style = MaterialTheme.typography.labelMedium)
                Slider(rs.toFloat(), { rs = it.toInt(); cfg.ripSpeed = rs }, valueRange = 1f..52f)
                Text("읽기 단위 ${ch}섹터 (소리가 끊기면 낮추세요)", style = MaterialTheme.typography.labelMedium)
                Slider(ch.toFloat(), { ch = it.toInt(); cfg.chunk = ch }, valueRange = 4f..32f, steps = 6)
            }
        },
    )
}
