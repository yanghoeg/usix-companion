package dev.usix.companion.feature.control

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ControlScreen(
    model: ControlViewModel,
    openNotificationSettings: () -> Unit,
    openAccessibilitySettings: () -> Unit,
    copyToken: () -> Unit,
    regenerateToken: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    MaterialTheme {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("usix companion", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            Text(if (state.notificationAccess) "✅ 알림 접근 허용됨" else "❌ 알림 접근 꺼짐 — 아래 버튼으로 켜세요")
            Text("브리지: 127.0.0.1:8760 (Bearer 토큰 필요)")
            Text(if (state.listenerConnected) "✅ 리스너 연결됨" else "· 리스너 대기 중 (권한 켠 뒤 잠시)")
            Text(if (state.accessibilityConnected) "✅ 접근성(화면 제어) 연결됨" else "· 접근성 꺼짐 — 화면 제어하려면 아래 버튼으로 켜세요")
            Button(onClick = openNotificationSettings) { Text("알림 접근 권한 설정 열기") }
            Button(onClick = openAccessibilitySettings) { Text("접근성(화면 제어) 설정 열기") }
            Text("알림이 없어도 Thunderbird 메일·대화 화면을 읽고 입력할 수 있습니다. 화면 잠금을 풀고 접근성을 켜세요. 메일 계정은 Thunderbird에 등록하세요.")
            Spacer(Modifier.height(24.dp))
            Text("연결된 런타임", style = MaterialTheme.typography.titleMedium)
            Text(when (state.remoteStatus) { "connected" -> "원격 연결됨"; "connecting" -> "원격 연결 중"; "offline" -> "원격 연결 대기 중";
                "configuration_required" -> "원격 연결 설정을 확인하세요"; else -> "로컬 연결 사용 중" })
            if (state.runtimes.isEmpty()) Text("외부 CLI에서 페어링하면 여기에 표시됩니다.")
            state.runtimes.forEach { runtime ->
                Text(runtime.displayName + when { runtime.revoked -> " · 만료 또는 연결 해제"; runtime.selected -> " · 화면 제어 중"; else -> " · 대기" })
                if (!runtime.revoked) {
                    Button(onClick = { model.selectRuntime(runtime.sessionId) }) { Text("이 런타임에 제어권 주기") }
                    Button(onClick = { model.revokeRuntime(runtime.sessionId) }) { Text("이 런타임 연결 해제") }
                }
            }
            Button(onClick = model::pauseAutomation) { Text("자동 제어 중지") }
            Text("중지하면 다음 실행을 차단합니다. 이미 앱에 전달된 동작은 결과를 확인해야 합니다.")
            Spacer(Modifier.height(32.dp))
            Text("브리지 토큰 — 복사한 뒤 Termux 에서 `usix-termux pair`")
            Text("연결 토큰은 복사 버튼으로 가져오세요. 화면 관찰에 토큰을 노출하지 않습니다.")
            Button(onClick = copyToken) { Text("토큰 복사") }
            Button(onClick = regenerateToken) { Text("토큰 재생성 (Termux 재-pair 필요)") }
        }
    }
}
