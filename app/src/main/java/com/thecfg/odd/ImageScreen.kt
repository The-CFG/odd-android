package com.thecfg.odd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** ISO 파일을 보는 화면: 작업 진행률 + 데이터 디스크와 같은 탐색 화면 */
@Composable
fun ImageScreen(s: UiState, image: ImageEngine, nav: UiNav) {
    Column(Modifier.fillMaxSize()) {
        s.job?.let { j ->
            Text("${j.label}  ${j.pct}%", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { j.pct / 100f }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { image.cancel() }) { Text("취소") }
        }
        Box(Modifier.weight(1f)) { DataPane(s, image, nav) }
    }
}
