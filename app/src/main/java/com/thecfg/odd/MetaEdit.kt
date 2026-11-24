package com.thecfg.odd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 앨범/아티스트/곡명 직접 입력. 저장 위치는 앱 내부뿐이며 CD 에는 영향이 없다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetaEditDialog(s: UiState, engine: OddEngine, onClose: () -> Unit) {
    var album by remember { mutableStateOf(s.album) }
    var artist by remember { mutableStateOf(s.artist) }
    val titles = remember {
        mutableStateListOf<String>().apply { s.tracks.indices.forEach { add(s.titles.getOrNull(it) ?: "") } }
    }
    var confirmReset by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text("정보 편집") },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                    },
                    actions = {
                        if (s.customized) TextButton(onClick = { confirmReset = true }) { Text("초기화") }
                        TextButton(onClick = { engine.saveMeta(album, artist, titles.toList()); onClose() }) { Text("저장") }
                    },
                )
            }) { pad ->
                LazyColumn(
                    Modifier.fillMaxSize().padding(pad),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "입력한 내용은 이 앱에만 저장되고 CD에는 아무 영향이 없어요. 같은 CD는 다른 ODD에 넣어도 같은 정보가 나와요.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item {
                        OutlinedTextField(album, { album = it }, Modifier.fillMaxWidth(), label = { Text("앨범") },
                            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                    }
                    item {
                        OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth(), label = { Text("아티스트") },
                            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                    }
                    itemsIndexed(s.tracks) { i, t ->
                        OutlinedTextField(titles[i], { titles[i] = it }, Modifier.fillMaxWidth(), label = { Text("트랙 ${t.no}") },
                            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                    }
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("직접 입력한 정보를 지울까요?") },
            text = { Text("이 CD에 직접 입력한 앨범·곡 정보를 지우고 자동으로 가져온 정보로 되돌려요.") },
            confirmButton = { TextButton({ engine.resetMeta(); confirmReset = false; onClose() }) { Text("초기화") } },
            dismissButton = { TextButton({ confirmReset = false }) { Text("취소") } },
        )
    }
}
