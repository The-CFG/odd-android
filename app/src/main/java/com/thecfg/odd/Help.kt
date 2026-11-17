package com.thecfg.odd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private class HelpSection(val title: String, val lines: List<String>)

private val HELP = listOf(
    HelpSection("시작하기", listOf(
        "ODD를 USB(OTG)로 연결하면 권한 팝업이 떠요. '허용'을 눌러 주세요.",
        "USB 허브를 쓴다면 허브와 ODD의 전원도 확인하세요.",
        "연결이 안 잡히면 첫 화면의 '다시 찾기'를 눌러 보세요.",
        "디스크를 넣으면 오디오 CD인지 데이터 디스크인지 자동으로 구분해서 화면이 바뀌어요.",
        "디스크가 열려 있지 않을 때는 위쪽에 '꺼내기'와 '넣기'(트레이 닫기) 버튼이 나와요.",
    )),
    HelpSection("음악 CD", listOf(
        "트랙을 탭하면 재생돼요. 아래에서 이전, -10초, 재생/일시정지, +10초, 다음, 볼륨, 반복을 조작해요.",
        "앨범·아티스트·곡명은 MusicBrainz에서 온라인으로 가져와요. 한 번 가져온 정보는 저장돼서 다음부터는 인터넷 없이도 보여요.",
        "오른쪽 아래 '작업' 버튼: 현재 곡 리핑, 전체 리핑, 디스크 정보, 꺼내기.",
        "리핑은 WAV 파일로 저장돼요. 위치는 '저장 위치 / 아티스트 - 앨범 / 번호 - 곡명.wav'예요. 리핑 중에는 재생이 멈추고 진행률과 취소 버튼이 나와요.",
        "디스크가 긁혀서 읽지 못한 구간은 무음으로 채우고, 끝난 뒤 오류 구간 수를 알려 줘요.",
        "재생 위치는 5초마다 저장돼요. 전원이 빠지는 등 앱의 '꺼내기'가 아닌 이유로 연결이 끊겼다가 같은 디스크가 다시 인식되면 이어서 들을지 물어봐요. 설정에서 묻지 않고 자동으로 이어서 재생하게 할 수도 있어요.",
        "앱의 '꺼내기'를 눌렀거나 끝까지 들은 경우에는 이어듣기 기록이 지워져요.",
    )),
    HelpSection("데이터 디스크", listOf(
        "폴더를 탭하면 열리고, 맨 위 '..'을 탭하면 상위 폴더로 가요.",
        "파일을 탭하면 복사하지 않고 바로 열어요. 이미지(핀치로 확대)와 텍스트는 앱 안에서 보여 주고, 그 밖의 형식은 열 수 있는 앱을 골라요. 보는 화면의 '다른 앱' 버튼으로도 넘길 수 있어요.",
        "각 줄의 '복사' 버튼은 그 파일이나 폴더를 저장 위치 안의 '디스크 이름' 폴더로 복사해요.",
        "오른쪽 아래 '작업' 버튼: ISO로 저장, 전체 파일 복사, 디스크 정보, 꺼내기.",
        "'ISO로 저장'은 디스크 전체를 .iso 파일로 만들어요. CD-R이나 CD-ROM에서도 쓸 수 있어요.",
        "데이터 디스크가 인식되어 있는 동안에는 안드로이드 '파일' 앱과 다른 앱의 파일 선택창에도 'ODD' 항목이 나타나요. 읽기 전용이고, 복사 없이 필요한 부분만 읽어요.",
        "폴더 구조를 읽을 수 있는 건 ISO9660/Joliet 디스크예요. UDF 전용 디스크는 탐색이 안 되고 'ISO로 저장'만 돼요.",
    )),
    HelpSection("설정", listOf(
        "저장 위치: 리핑 파일, 복사 파일, ISO를 저장할 폴더예요. 정하지 않으면 앱 전용 폴더(Android/data/com.thecfg.odd/files/ODD)에 저장돼요.",
        "오디오 CD 자동 재생: 디스크가 인식되면 1번 트랙부터 바로 재생해요.",
        "전원 재설정 후 자동으로 이어서 재생: 끊겼다가 같은 디스크가 다시 인식되면 묻지 않고 저장된 위치부터 재생해요.",
        "앨범 정보 온라인 조회: 끄면 인터넷으로 앨범 정보를 가져오지 않아요. 이미 저장된 정보는 계속 쓰여요.",
        "재생 속도, 리핑 속도: 낮출수록 조용하고 안정적이에요. 재생 속도는 다음 디스크를 넣을 때부터 적용돼요.",
        "읽기 단위: 한 번에 읽는 섹터 수예요. 재생 중 소리가 끊기면 낮춰 보세요.",
        "로그 보기: 연결, 디스크 인식, 작업 결과, 오류가 기록돼요. 오른쪽 위 '공유'로 로그를 메일로 보낼 수 있어요.",
    )),
    HelpSection("키보드 단축키", listOf(
        "블루투스나 USB 키보드를 연결했을 때 쓸 수 있어요. 설정 같은 팝업이 떠 있을 때는 동작하지 않아요.",
        "공통: q 종료 / e 꺼내기 / l 넣기 / d 디스크 정보 / s 설정",
        "음악 CD: Space 재생·일시정지 / n 다음 / p 이전 / ←→ 10초 이동 / ↑↓ 볼륨 / = 곡 번호로 이동 / r 반복 / x 현재 곡 리핑 / a 전체 리핑",
        "데이터 디스크: ↑↓ 목록 이동(처음엔 ↓를 한 번 눌러 시작) / → 또는 Enter 열기 / ← 또는 Backspace 상위 폴더 / c 복사 / i ISO로 저장",
        "이어폰 버튼과 키보드의 미디어 키(재생/일시정지, 다음, 이전)도 쓸 수 있어요.",
    )),
    HelpSection("문제 해결", listOf(
        "ODD가 감지되지 않아요 → OTG를 지원하는 기기인지, 케이블과 ODD 전원을 확인하고 '다시 찾기'를 눌러 보세요.",
        "'디스크를 읽지 못했어요'가 떠요 → 디스크 표면을 확인하고, 꺼냈다가 다시 넣어 보세요.",
        "재생 중 소리가 끊겨요 → 설정에서 읽기 단위와 재생 속도를 낮춰 보세요.",
        "통신 오류가 5번 이어지면 연결이 끊어진 것으로 처리해요. ODD를 다시 연결해 주세요.",
        "그래도 해결되지 않으면 설정의 '로그 보기'에서 로그를 공유해 주세요.",
    )),
)

/** 전체 화면 도움말 페이지 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text("도움말") },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                    },
                )
            }) { pad ->
                Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    HELP.forEach { sec ->
                        Text(sec.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        sec.lines.forEach { Text("• $it", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.height(16.dp))
                    }
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    Text("버그 신고, 개선점 등은 thecfgeditor0@gmail.com으로 보내주세요.")
                }
            }
        }
    }
}
