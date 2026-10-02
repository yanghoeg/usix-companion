package dev.usix.companion

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.usix.companion.adapters.android.AccessibilityOperations
import dev.usix.companion.adapters.android.ConnectedAccessibilityAdapter
import javax.inject.Inject

@AndroidEntryPoint
class UsixAccessibilityService : AccessibilityService() {
    @Inject lateinit var connection: ConnectedAccessibilityAdapter
    private val operations by lazy { AccessibilityOperations(this) }
    override fun onServiceConnected() { connection.attach(operations) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { event?.let { connection.onEvent(operations, it.eventType) } }
    override fun onInterrupt() { connection.onEvent(operations, AccessibilityEvent.TYPE_WINDOWS_CHANGED) }
    override fun onUnbind(intent: Intent?): Boolean {
        connection.detach(operations)
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        connection.detach(operations)
        super.onDestroy()
    }
}
