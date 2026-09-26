package dev.usix.companion

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmailTest {
    private var launched: Intent? = null
    private var launchFailure: RuntimeException? = null

    @Before
    fun setUp() {
        NotifStore.clear()
        NotifStore.listenerConnected = false
        UiController.service = null
        NotifStore.appContext = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startActivity(intent: Intent) {
                launchFailure?.let { throw it }
                launched = intent
            }
        }
    }

    @After
    fun tearDown() {
        NotifStore.appContext = null
    }

    @Test
    fun composeWithoutNotificationPreservesContentAndDoesNotReportSent() {
        val subject = "회의 & 일정? #1"
        val message = "안녕하세요\n확인 + 회신 부탁드립니다. &bcc=other@example.com"
        val (status, response) = route("/email/compose", JSONObject()
            .put("to", "person+work@example.com").put("subject", subject).put("body", message))

        assertEquals("200 OK", status)
        assertTrue(response.getBoolean("ok"))
        assertFalse(response.getBoolean("sent"))
        val intent = requireNotNull(launched)
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("net.thunderbird.android", intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val mail = requireNotNull(intent.data)
        assertEquals("person+work@example.com", Uri.decode(mail.encodedSchemeSpecificPart.substringBefore('?')))
        // Thunderbird reads the encoded query before decoding its individual values. The Android
        // 28 MailTo helper decodes too early and incorrectly splits literal '&' inside the content.
        val query = Uri.parse("https://mail.example.com/?${mail.encodedQuery}")
        assertEquals(subject, query.getQueryParameter("subject"))
        assertEquals(message, query.getQueryParameter("body"))
        assertEquals(setOf("subject", "body"), query.queryParameterNames)
        assertEquals(subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertArrayEquals(arrayOf("person+work@example.com"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals(message, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun openThunderbirdDoesNotNeedNotificationOrAccessibility() {
        val component = ComponentName("net.thunderbird.android", "net.thunderbird.android.Main")
        val pm = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        val activity = pm.addActivityIfNotPresent(component)
        val filter = android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        pm.addIntentFilterForActivity(component, filter)
        activity.exported = true

        val (status, response) = route("/email/open", JSONObject())
        assertEquals("200 OK", status)
        assertTrue(response.getBoolean("ok"))
        assertEquals(component, launched?.component)
    }

    @Test
    fun explicitEmailPackageIsRespected() {
        assertTrue(route("/email/compose", JSONObject().put("to", "a@example.com")
            .put("package", "com.fsck.k9")).second.getBoolean("ok"))
        assertEquals("com.fsck.k9", launched?.`package`)
    }

    @Test
    fun invalidRecipientsAndFieldTypesDoNotLaunch() {
        val invalid = listOf(
            JSONObject(),
            JSONObject().put("to", "a@example.com,b@example.com"),
            JSONObject().put("to", "a%40example.com%2Cb@example.com"),
            JSONObject().put("to", "a@example.com\nbcc:b@example.com"),
            JSONObject().put("to", "a@example.com").put("body", 7),
            JSONObject().put("to", "a@example.com").put("subject", JSONObject.NULL),
            JSONObject().put("to", "a@example.com").put("package", JSONObject.NULL),
            JSONObject().put("to", "a@example.com").put("package", ""),
        )
        invalid.forEach { assertEquals("400 Bad Request", route("/email/compose", it).first) }
        assertNull(launched)
    }

    @Test
    fun unavailableMailAppIsAnActionFailureNotBadJson() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            launchFailure = failure
            val (status, response) = route("/email/compose", JSONObject().put("to", "a@example.com"))
            assertEquals("200 OK", status)
            assertFalse(response.getBoolean("ok"))
            assertFalse(response.getBoolean("sent"))
            assertTrue(response.getString("error").contains("email app"))
        }
        assertFalse(route("/email/open", JSONObject()).second.getBoolean("ok"))
        assertNull(launched)
    }

    @Suppress("UNCHECKED_CAST")
    private fun route(path: String, body: JSONObject): Pair<String, JSONObject> {
        val method = BridgeServer::class.java.getDeclaredMethod(
            "route", String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val result = method.invoke(BridgeServer, "POST", path, body.toString(), true) as Pair<String, String>
        return result.first to JSONObject(result.second)
    }
}
