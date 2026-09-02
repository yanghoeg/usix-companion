package dev.usix.companion

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 상태 표시 + '알림 접근'/'접근성' 설정 바로가기 + 브리지 토큰 복사만 있는 최소 화면. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var tokenView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeAuth.init(this)
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
        val tokenLabel = TextView(this).apply {
            text = "브리지 토큰 — 복사한 뒤 Termux 에서 `usix-termux pair`"
            textSize = 13f
            setPadding(0, 40, 0, 8)
        }
        tokenView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val copyBtn = Button(this).apply {
            text = "토큰 복사"
            setOnClickListener { copyToken() }
        }
        val regenBtn = Button(this).apply {
            text = "토큰 재생성 (Termux 재-pair 필요)"
            setOnClickListener {
                BridgeAuth.regenerate(this@MainActivity)
                refresh()
                Toast.makeText(this@MainActivity, "새 토큰 생성됨 — 다시 복사해 pair 하세요", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(title)
        root.addView(status)
        root.addView(settingsBtn)
        root.addView(accessBtn)
        root.addView(tokenLabel)
        root.addView(tokenView)
        root.addView(copyBtn)
        root.addView(regenBtn)
        setContentView(root)

        // 상시 알림 권한(Android 13+) — 없으면 포그라운드 알림이 숨겨질 수 있다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // 프로세스를 포그라운드로 고정 — 브리지가 메모리 압박에 죽지 않게. (액티비티 포그라운드에서 시작해야 허용)
        BridgeForegroundService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = isListenerEnabled()
        status.text = buildString {
            append(if (enabled) "✅ 알림 접근 허용됨\n" else "❌ 알림 접근 꺼짐 — 아래 버튼으로 켜세요\n")
            append("브리지: 127.0.0.1:${BridgeServer.PORT} (Bearer 토큰 필요)\n")
            append(if (NotifStore.listenerConnected) "✅ 리스너 연결됨\n" else "· 리스너 대기 중 (권한 켠 뒤 잠시)\n")
            append(if (UiController.connected()) "✅ 접근성(화면 제어) 연결됨" else "· 접근성 꺼짐 — 화면 제어하려면 아래 버튼으로 켜세요")
        }
        tokenView.text = BridgeAuth.current() ?: "(토큰 없음)"
    }

    private fun copyToken() {
        val token = BridgeAuth.current() ?: return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("usix companion token", token))
        Toast.makeText(this, "복사됨 — Termux: usix-termux pair", Toast.LENGTH_SHORT).show()
    }

    /** enabled_notification_listeners 는 ':' 로 이어진 ComponentName 목록 — 패키지명을 정확히 대조. */
    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }
}
