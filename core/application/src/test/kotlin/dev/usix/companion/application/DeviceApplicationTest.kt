package dev.usix.companion.application

import dev.usix.companion.domain.ActionKind
import dev.usix.companion.domain.DeviceErrorCode
import dev.usix.companion.domain.DeviceResult
import dev.usix.companion.domain.PackageId
import dev.usix.companion.domain.ScreenNode
import dev.usix.companion.testing.FakeDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceApplicationTest {
    @Test fun disconnectedUiNeverDispatchesAndDoesNotBlockAppLaunch() = runTest {
        val fake = FakeDevice().apply { accessibility = false }
        val application = fake.application()
        listOf(DeviceAction.Tap(1, 2), DeviceAction.Type("한글"), DeviceAction.Back, DeviceAction.Scroll("down")).forEach {
            assertEquals(DeviceErrorCode.ACCESSIBILITY_UNAVAILABLE, (application.execute(it) as DeviceResult.Rejected).error.code)
        }
        assertTrue(fake.calls.isEmpty())
        assertEquals(DeviceErrorCode.ACCESSIBILITY_UNAVAILABLE, (application.screen(null) as DeviceResult.Rejected).error.code)
        assertTrue((application.execute(DeviceAction.OpenEmail()) as DeviceResult.Success).value.accepted)
        assertEquals(PackageId("net.thunderbird.android"), fake.selectedPackage)
    }

    @Test fun invalidExplicitScopeCannotBecomeAnUnscopedAction() = runTest {
        val fake = FakeDevice()
        val application = fake.application()
        listOf(InputField.Invalid, InputField.Value(""), InputField.Value("not a package")).forEach { field ->
            listOf(DeviceAction.Type("secret", field), DeviceAction.Scroll("down", field), DeviceAction.OpenEmail(field),
                DeviceAction.ComposeEmail("a@example.com", packageName = field)).forEach {
                assertTrue(application.execute(it) is DeviceResult.Rejected)
            }
        }
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun scopedTextScrollAndObservationKeepTheirPackageAndDirection() = runTest {
        val fake = FakeDevice()
        val application = fake.application()
        val pkg = InputField.Value("mail.app")
        application.execute(DeviceAction.Type("안녕하세요", pkg))
        assertEquals(PackageId("mail.app"), fake.selectedPackage)
        application.execute(DeviceAction.Scroll("up", pkg))
        assertEquals("scroll:false", fake.calls.last())
        assertEquals(PackageId("mail.app"), fake.selectedPackage)
        fake.nodes = listOf(ScreenNode("reply", 1, 2, false, true, false))
        assertEquals(fake.nodes, (application.screen(PackageId("mail.app")) as DeviceResult.Success).value)
        assertEquals(PackageId("mail.app"), fake.selectedPackage)
    }

    @Test fun plainRecipientAndFieldValidationPrecedeDraftLaunch() = runTest {
        val fake = FakeDevice()
        val application = fake.application()
        listOf(null, "", "a@example.com,b@example.com", "a%40example.com", "a@example.com\nbcc:b@example.com").forEach {
            assertTrue(application.execute(DeviceAction.ComposeEmail(it)) is DeviceResult.Rejected)
        }
        assertTrue(application.execute(DeviceAction.ComposeEmail("a@example.com", body = InputField.Invalid)) is DeviceResult.Rejected)
        assertTrue(fake.calls.isEmpty())
        val outcome = application.execute(DeviceAction.ComposeEmail("a+work@example.com",
            InputField.Value("회의 & ?"), InputField.Value("한글\n&bcc=someone@example.com"))) as DeviceResult.Success
        assertEquals(ActionKind.MAIL_DRAFT, outcome.value.kind)
        assertEquals("한글\n&bcc=someone@example.com", fake.draft?.body)
        assertEquals("a+work@example.com", fake.draft?.to)
    }

    @Test fun failedDraftRemainsADraftAndReplyFailureRemainsUnverified() = runTest {
        val fake = FakeDevice().apply { accept = false }
        val application = fake.application()
        val draft = (application.execute(DeviceAction.ComposeEmail("a@example.com")) as DeviceResult.Success).value
        assertFalse(draft.accepted)
        assertEquals(ActionKind.MAIL_DRAFT, draft.kind)
        val reply = (application.execute(DeviceAction.Reply("key", "reply")) as DeviceResult.Success).value
        assertFalse(reply.accepted)
        assertEquals(ActionKind.NOTIFICATION_REPLY, reply.kind)
        assertTrue(reply.detail.orEmpty().contains("expired"))
    }

    @Test fun missingRequiredFieldsDoNotReachAdapters() = runTest {
        val fake = FakeDevice()
        val application = fake.application()
        listOf(DeviceAction.Tap(null, 2), DeviceAction.Type(""), DeviceAction.Open(null), DeviceAction.Scroll("left"),
            DeviceAction.Reply("", "hello"), DeviceAction.Reply("key", null)).forEach {
            assertTrue(application.execute(it) is DeviceResult.Rejected)
        }
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun healthReflectsInjectedStateAndInstancesAreIndependent() {
        val first = FakeDevice().apply { listener = true; accessibility = false }.application()
        val second = FakeDevice().application()
        assertTrue(first.health().listenerConnected)
        assertFalse(first.health().accessibilityConnected)
        assertFalse(second.health().listenerConnected)
        assertTrue(second.health().accessibilityConnected)
    }

    @Test fun suspendedGestureSerializesUiAndCancellingAQueuedActionNeverDispatchesIt() = runTest {
        val release = CompletableDeferred<Boolean>()
        val fake = FakeDevice().apply { tapHandler = { release.await() } }
        val application = fake.application()
        val tap = async { application.execute(DeviceAction.Tap(1, 2)) }
        runCurrent()
        val back = launch { application.execute(DeviceAction.Back) }
        runCurrent()
        assertEquals(listOf("tap:1,2"), fake.calls)
        back.cancelAndJoin()
        release.complete(true)
        assertTrue((tap.await() as DeviceResult.Success).value.accepted)
        application.execute(DeviceAction.Back)
        assertEquals(listOf("tap:1,2", "back"), fake.calls)
    }

    @Test fun cancellationOfAnActiveAdapterReleasesUiOwnership() = runTest {
        val fake = FakeDevice().apply { tapHandler = { CompletableDeferred<Boolean>().await() } }
        val application = fake.application()
        val tap = launch { application.execute(DeviceAction.Tap(1, 2)) }
        runCurrent()
        tap.cancelAndJoin()
        assertTrue((application.execute(DeviceAction.Back) as DeviceResult.Success).value.accepted)
    }

    @Test fun readinessIsRecheckedWhenQueuedActionAcquiresUi() = runTest {
        val release = CompletableDeferred<Boolean>()
        val fake = FakeDevice().apply { tapHandler = { release.await() } }
        val application = fake.application()
        val tap = async { application.execute(DeviceAction.Tap(1, 2)) }
        runCurrent()
        val back = async { application.execute(DeviceAction.Back) }
        runCurrent()
        fake.accessibility = false
        release.complete(true)
        tap.await()
        assertEquals(DeviceErrorCode.ACCESSIBILITY_UNAVAILABLE, (back.await() as DeviceResult.Rejected).error.code)
        assertEquals(listOf("tap:1,2"), fake.calls)
    }

    @Test fun appLaunchAndComposerShareUiSerializationWithoutRequiringAccessibility() = runTest {
        val release = CompletableDeferred<Boolean>()
        val fake = FakeDevice().apply { tapHandler = { release.await() } }
        val application = fake.application()
        val tap = async { application.execute(DeviceAction.Tap(1, 2)) }
        runCurrent()
        val compose = async { application.execute(DeviceAction.ComposeEmail("a@example.com")) }
        val open = async { application.execute(DeviceAction.OpenEmail()) }
        runCurrent()
        assertEquals(listOf("tap:1,2"), fake.calls)
        fake.accessibility = false
        release.complete(true)
        tap.await()
        assertTrue((compose.await() as DeviceResult.Success).value.accepted)
        assertTrue((open.await() as DeviceResult.Success).value.accepted)
        assertEquals(listOf("tap:1,2", "compose", "open"), fake.calls)
    }
}
