package dev.usix.companion

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 브리지 공유 토큰. 안드로이드는 루프백 소켓을 앱별로 격리하지 않아 INTERNET 권한만 있는 다른 앱도
 * 127.0.0.1:8760 에 붙을 수 있다 — 알림 본문(OTP 포함)·탭·입력이 그대로 노출되므로 토큰으로 막는다.
 * 첫 실행 때 한 번 생성해 SharedPreferences 에 두고, MainActivity 의 '토큰 복사' → Termux 에서
 * `usix-termux pair` 로 ~/.usix/companion_token 에 저장하면 끝. 요청은 `Authorization: Bearer <token>`.
 */
object BridgeAuth {
    private const val PREFS = "bridge"
    private const val KEY = "token"

    @Volatile
    private var token: String? = null

    /** 저장된 토큰을 로드하고, 없으면 생성한다. 브리지 시작 전에 호출. */
    @Synchronized
    fun init(ctx: Context): String {
        token?.let { return it }
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val t = prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }
            ?: generate().also { prefs.edit().putString(KEY, it).apply() }
        token = t
        return t
    }

    fun current(): String? = token

    /** 새 토큰으로 교체(유출 의심 시). Termux 쪽도 다시 pair 해야 한다. */
    @Synchronized
    fun regenerate(ctx: Context): String {
        val t = generate()
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, t).apply()
        token = t
        return t
    }

    /** `Authorization` 헤더 값을 상수 시간으로 대조. 토큰 미로드면 무조건 거부. */
    fun check(authorization: String?): Boolean {
        val expected = token ?: return false
        val presented = authorization?.trim()?.let {
            if (it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) it.substring(7).trim() else null
        } ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            presented.toByteArray(Charsets.UTF_8),
        )
    }

    /** 32바이트 난수 → 64자 hex (클라이언트 valid_token: 16~128자 영숫자). */
    private fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
