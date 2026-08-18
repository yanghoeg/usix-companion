package dev.usix.companion

import android.content.Intent
import org.json.JSONArray

/**
 * 접근성 서비스(UsixAccessibilityService)로의 얇은 진입점. 브리지(BridgeServer)가 여기로 화면 읽기·
 * 탭·입력·뒤로가기를 요청한다. 서비스가 연결돼 있어야(접근성 권한 켜짐) 동작한다.
 * 앱 실행은 접근성이 필요 없어 컨텍스트로 바로 처리한다.
 */
object UiController {
    @Volatile
    var service: UsixAccessibilityService? = null

    fun connected(): Boolean = service != null

    fun screen(pkg: String?): JSONArray = service?.dumpScreen(pkg) ?: JSONArray()

    fun tap(x: Int, y: Int): Boolean = service?.tap(x, y) ?: false

    fun type(text: String): Boolean = service?.setFocusedText(text) ?: false

    fun back(): Boolean = service?.back() ?: false

    /** 접근성 없이 런처 인텐트로 앱을 연다. 컨텍스트는 알림 서비스가 채워 둔 것을 재사용. */
    fun openApp(pkg: String): Boolean {
        val ctx = NotifStore.appContext ?: return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return true
    }
}
