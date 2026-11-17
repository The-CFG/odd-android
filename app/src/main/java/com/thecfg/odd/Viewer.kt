package com.thecfg.odd

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private val TEXT_EXT = setOf(
    "txt", "md", "log", "json", "xml", "csv", "ini", "cfg", "conf", "nfo", "srt", "sub", "lrc", "cue", "m3u",
    "html", "htm", "css", "js", "py", "kt", "java", "c", "h", "cpp", "bat", "sh", "yml", "yaml", "toml",
)

fun mimeOf(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: if (ext in TEXT_EXT) "text/plain" else null
}

private data class Viewing(
    val e: IsoEntry,
    val bmp: ImageBitmap? = null,
    val text: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/** 다른 앱으로 열기 — 복사 없이 DiscProvider 의 URI 를 넘긴다 */
private fun openExternal(ctx: Context, e: IsoEntry) {
    val uri = DocumentsContract.buildDocumentUri(DiscProvider.AUTHORITY, DiscProvider.docId(e))
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(e.name) ?: "*/*")
    i.clipData = ClipData.newRawUri("", uri)
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(i, e.name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show()
    }
}

private fun decodeText(b: ByteArray): String {
    val s = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
    } catch (_: CharacterCodingException) {
        String(b, Charset.forName("EUC-KR"))
    }
    return s.removePrefix("\uFEFF")
}

private fun openEntry(ctx: Context, e: IsoEntry, scope: CoroutineScope, show: (Viewing?) -> Unit) {
    val mime = mimeOf(e.name)
    val ext = e.name.substringAfterLast('.', "").lowercase()
    val asImage = mime?.startsWith("image/") == true && e.size <= (40L shl 20)
    val asText = (mime?.startsWith("text/") == true || ext in TEXT_EXT) && e.size <= (512L shl 10)
    if (!asImage && !asText) { openExternal(ctx, e); return }
    show(Viewing(e))
    scope.launch(Dispatchers.IO) {
        val result: Viewing? = try {
            val bytes = OddHub.readAll(e)
            if (asImage) {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                var ss = 1
                while (b.outWidth / ss > 2048 || b.outHeight / ss > 2048) ss *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss })
                if (bmp == null) null else Viewing(e, bmp = bmp.asImageBitmap(), loading = false)
            } else {
                Viewing(e, text = decodeText(bytes), loading = false)
            }
        } catch (ex: Exception) {
            Viewing(e, loading = false, error = "읽지 못했어요: ${ex.message}")
        }
        withContext(Dispatchers.Main) {
            if (result == null) { show(null); openExternal(ctx, e) } else show(result)
        }
    }
}

@Composable
fun DataPane(s: UiState, engine: OddEngine, nav: UiNav) {
    val busy = s.job != null
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    val browsable = !s.fs.contains("아님")
    val listState = rememberLazyListState()
    val offset = if (s.path.isNotEmpty()) 1 else 0   // '..' 줄

    LaunchedEffect(s.entries) { nav.cursor = -1 }
    LaunchedEffect(nav.cursor) { if (nav.cursor >= 0) listState.animateScrollToItem(nav.cursor + offset) }
    LaunchedEffect(nav.pendingOpen) {
        nav.pendingOpen?.let { e -> nav.pendingOpen = null; openEntry(ctx, e, scope) { viewing = it } }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(s.label.ifBlank { "데이터 디스크" }, style = MaterialTheme.typography.titleMedium)
            Text("${s.fs}  ${if (s.sizeMb > 0) "%.1fMB".format(s.sizeMb) else ""}", style = MaterialTheme.typography.bodySmall)
            Text("/" + s.path.joinToString("/"), style = MaterialTheme.typography.bodySmall)
            Text("파일을 탭하면 바로 열려요. '파일' 앱의 ODD 항목으로도 접근할 수 있어요.", style = MaterialTheme.typography.labelSmall)
            LazyColumn(
                Modifier.weight(1f).padding(top = 8.dp),
                state = listState,
                contentPadding = PaddingValues(bottom = 88.dp),   // 작업 버튼에 가리지 않게
            ) {
                if (s.path.isNotEmpty()) item {
                    Text("..", Modifier.fillMaxWidth().clickable(enabled = !busy) { engine.up() }.padding(12.dp))
                }
                itemsIndexed(s.entries) { i, e ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (i == nav.cursor) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable(enabled = !busy) {
                                if (e.isDir) engine.open(e) else openEntry(ctx, e, scope) { viewing = it }
                            }
                            .padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (e.isDir) "▸ " else "   ")
                        Text(e.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!e.isDir) Text(human(e.size), style = MaterialTheme.typography.bodySmall)
                        TextButton({ engine.copy(e) }, enabled = !busy) { Text("복사") }
                    }
                }
            }
        }
        ActionMenu(
            listOf(
                ActionItem("ISO로 저장", !busy) { engine.dumpIso() },
                ActionItem("전체 파일 복사", !busy && browsable) { engine.copyAll() },
                ActionItem("디스크 정보") { nav.showInfo = true },
                ActionItem("꺼내기", !busy) { engine.eject() },
            ),
        )
    }

    viewing?.let { v ->
        ViewerDialog(v, onExternal = { openExternal(ctx, v.e) }, onClose = { viewing = null })
    }
}

@Composable
private fun ViewerDialog(v: Viewing, onExternal: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v.e.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    TextButton(onExternal) { Text("다른 앱") }
                    TextButton(onClose) { Text("닫기") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        v.loading -> CircularProgressIndicator()
                        v.error != null -> Text(v.error)
                        v.bmp != null -> ZoomImage(v.bmp)
                        v.text != null -> Text(
                            v.text,
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomImage(bmp: ImageBitmap) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        bitmap = bmp,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}
