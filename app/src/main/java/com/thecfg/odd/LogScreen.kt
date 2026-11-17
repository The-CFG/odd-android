package com.thecfg.odd

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private fun shareLog(ctx: Context) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "TheODDer 로그")
        .putExtra(Intent.EXTRA_EMAIL, arrayOf("thecfgeditor0@gmail.com"))
        .putExtra(Intent.EXTRA_TEXT, AppLog.asText())
    try {
        ctx.startActivity(Intent.createChooser(i, "로그 공유"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "공유할 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show()
    }
}

/** 전체 화면 로그 페이지 (최신 항목이 위) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDialog(onClose: () -> Unit) {
    val entries by AppLog.entries.collectAsState()
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text("로그") },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                    },
                    actions = {
                        TextButton(onClick = { shareLog(ctx) }, enabled = entries.isNotEmpty()) { Text("공유") }
                        TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) { Text("지우기") }
                    },
                )
            }) { pad ->
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                        Text("기록된 로그가 없어요")
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(pad),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        items(entries.asReversed()) { e ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row {
                                    Text(AppLog.fmt(e.time), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        when (e.level) { 'E' -> "오류"; 'W' -> "경고"; else -> "정보" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (e.level) {
                                            'E' -> MaterialTheme.colorScheme.error
                                            'W' -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Text(e.text, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("로그를 모두 지울까요?") },
            confirmButton = { TextButton({ AppLog.clear(); confirmClear = false }) { Text("지우기") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("취소") } },
        )
    }
}
