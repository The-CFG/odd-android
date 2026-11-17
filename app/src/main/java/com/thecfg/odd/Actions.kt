package com.thecfg.odd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class ActionItem(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/** 오른쪽 아래 '작업' 버튼. 누르면 버튼 위로 목록이 펼쳐진다. 부모 Box 전체를 덮는다. */
@Composable
fun ActionMenu(items: List<ActionItem>) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (open) {
            // 바깥을 탭하면 닫힘
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = false },
            )
        }
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (open) {
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)) {
                    Column(Modifier.width(IntrinsicSize.Max)) {
                        items.forEach { a ->
                            Text(
                                a.label,
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = a.enabled) { open = false; a.onClick() }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                color = if (a.enabled) Color.Unspecified
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            ExtendedFloatingActionButton(onClick = { open = !open }) { Text(if (open) "닫기" else "작업") }
        }
    }
}

@Composable
fun InfoDialog(s: UiState, onClose: () -> Unit) {
    val rows = buildList<Pair<String, String>> {
        add("드라이브" to s.vendor.ifBlank { "-" })
        add("미디어" to s.media.ifBlank { "알 수 없음" })
        if (s.kind == "audio") {
            val sec = s.tracks.sumOf { (it.end - it.start) / 75 }
            add("종류" to "오디오 CD")
            add("트랙" to "${s.tracks.size}개")
            add("총 길이" to "%d:%02d".format(sec / 60, sec % 60))
            if (s.album.isNotBlank()) add("앨범" to s.album)
            if (s.artist.isNotBlank()) add("아티스트" to s.artist)
        } else {
            add("종류" to "데이터 디스크")
            add("파일시스템" to s.fs.ifBlank { "-" })
            add("볼륨 이름" to s.label.ifBlank { "-" })
            if (s.sizeMb > 0) add("용량" to "%.1f MB".format(s.sizeMb))
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("닫기") } },
        title = { Text("디스크 정보") },
        text = {
            Column {
                rows.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(k, Modifier.width(88.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v)
                    }
                }
            }
        },
    )
}
