package dev.usix.companion.adapters.android

import android.accessibilityservice.AccessibilityService
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityEvent

class TestAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
class TestNotificationService : NotificationListenerService()
