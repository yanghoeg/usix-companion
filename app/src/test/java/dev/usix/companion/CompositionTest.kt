package dev.usix.companion

import dagger.hilt.EntryPoints
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise the generated production graph, without binding the device's live port. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompanionApplication::class)
class CompositionTest {
    @Test fun graphSharesInjectedDeviceStateAndDoesNotStartTransportImplicitly() {
        val graph = EntryPoints.get(RuntimeEnvironment.getApplication(), CompositionEntryPoint::class.java)
        assertEquals(graph.notifications().connected(), graph.query().health().listenerConnected)
        assertEquals(graph.accessibility().accessibilityConnected(), graph.query().health().accessibilityConnected)
        assertSame(graph.bridge(), graph.bridge())
        assertNull(graph.bridge().boundPort)
        assertTrue(graph.credentials().current().matches(Regex("[0-9a-f]{64}")))
    }
}
