package dev.usix.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 프로세스를 포그라운드로 고정하는 서비스. 상시 알림 하나를 띄워 두면 안드로이드 LMK 가
 * 메모리 압박에도 이 프로세스를 잘 안 죽인다 — 브리지(127.0.0.1:8760)가 간헐적으로 끊기던
 * 문제의 근본 대책. NotificationListenerService 와 같은 프로세스라 리스너·접근성·브리지가 함께 산다.
 */
class BridgeForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        BridgeServer.start()
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "usix bridge", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            mgr.createNotificationChannel(ch)
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return builder
            .setContentTitle("usix companion 실행 중")
            .setContentText("127.0.0.1:${BridgeServer.PORT} 브리지 유지 중")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "usix_bridge"
        private const val NOTIF_ID = 1

        /** 앱이 포그라운드일 때(예: MainActivity) 호출해야 백그라운드-시작 제한에 안 걸린다. */
        fun start(ctx: Context) {
            val intent = Intent(ctx, BridgeForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
