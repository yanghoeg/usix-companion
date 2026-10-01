package dev.usix.companion

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.AndroidEntryPoint
import dev.usix.companion.feature.control.ControlScreen
import dev.usix.companion.feature.control.ControlViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var factory: ControlViewModel.Factory
    private lateinit var model: ControlViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, factory)[ControlViewModel::class.java]
        setContent {
            ControlScreen(model,
                openNotificationSettings = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                openAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                copyToken = {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("usix companion token", model.state.value.token))
                    Toast.makeText(this, "복사됨 — Termux: usix-termux pair", Toast.LENGTH_SHORT).show()
                },
                regenerateToken = {
                    model.regenerateToken()
                    Toast.makeText(this, "새 토큰 생성됨 — 다시 복사해 pair 하세요", Toast.LENGTH_SHORT).show()
                })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        BridgeForegroundService.start(this)
    }
    override fun onResume() {
        super.onResume()
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        model.refresh(listeners.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName })
    }
}
