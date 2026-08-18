package dev.usix.companion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 상태 표시 + '알림 접근' 설정 바로가기만 있는 최소 화면. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val title = TextView(this).apply {
            text = "usix companion"
            textSize = 22f
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 32, 0, 32)
        }
        val settingsBtn = Button(this).apply {
            text = "알림 접근 권한 설정 열기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val accessBtn = Button(this).apply {
            text = "접근성(화면 제어) 설정 열기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(title)
        root.addView(status)
        root.addView(settingsBtn)
        root.addView(accessBtn)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val enabled = isListenerEnabled()
        status.text = buildString {
            append(if (enabled) "✅ 알림 접근 허용됨\n" else "❌ 알림 접근 꺼짐 — 아래 버튼으로 켜세요\n")
            append("브리지: 127.0.0.1:${BridgeServer.PORT}\n")
            append(if (NotifStore.listenerConnected) "✅ 리스너 연결됨\n" else "· 리스너 대기 중 (권한 켠 뒤 잠시)\n")
            append(if (UiController.connected()) "✅ 접근성(화면 제어) 연결됨" else "· 접근성 꺼짐 — 화면 제어하려면 아래 버튼으로 켜세요")
        }
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.contains(packageName) }
    }
}
