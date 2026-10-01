package dev.usix.companion.feature.control

import dev.usix.companion.application.PairingCredentials
import dev.usix.companion.testing.FakeDevice
import org.junit.Assert.*
import org.junit.Test

class ControlViewModelTest {
    private class FakeCredentials : PairingCredentials {
        var token = "synthetic-old-token"
        override fun current() = token
        override fun regenerate(): String { token = "synthetic-new-token"; return token }
    }

    @Test fun refreshDistinguishesPermissionFromConnectedReadiness() {
        val fake = FakeDevice().apply { listener = false; accessibility = false }
        val model = ControlViewModel(fake.application(), FakeCredentials())
        model.refresh(true)
        assertTrue(model.state.value.notificationAccess)
        assertFalse(model.state.value.listenerConnected)
        assertFalse(model.state.value.accessibilityConnected)
        fake.accessibility = true
        model.refresh(false)
        assertFalse(model.state.value.notificationAccess)
        assertTrue(model.state.value.accessibilityConnected)
    }

    @Test fun rotatingTokenUpdatesTheSameStateUsedForDisplayAndCopy() {
        val credentials = FakeCredentials()
        val model = ControlViewModel(FakeDevice().application(), credentials)
        assertEquals(credentials.current(), model.state.value.token)
        model.regenerateToken()
        assertEquals("synthetic-new-token", model.state.value.token)
        model.refresh(false)
        assertEquals(credentials.current(), model.state.value.token)
    }
}
