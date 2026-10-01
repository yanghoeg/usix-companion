package dev.usix.companion.adapters.persistence

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BridgeCredentialStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun clearPreferences() { context.getSharedPreferences("bridge", 0).edit().clear().commit() }

    @Test fun existingInstalledTokenRetainsItsStorageAndAuthority() {
        val existing = "synthetic-existing-token"
        context.getSharedPreferences("bridge", 0).edit().putString("token", existing).commit()
        val credentials = BridgeCredentialStore(context)
        assertEquals(existing, credentials.current())
        assertTrue(credentials.verify(existing))
        assertFalse(credentials.verify(null))
        assertFalse(credentials.verify("incorrect"))
        assertFalse(credentials.verify("Bearer $existing"))
    }

    @Test fun freshTokenIsRandomAndPersistsAcrossInstances() {
        val first = BridgeCredentialStore(context)
        assertTrue(first.current().matches(Regex("[0-9a-f]{64}")))
        assertEquals(first.current(), BridgeCredentialStore(context).current())
    }

    @Test fun rotationRejectsPreviousTokenAndSurvivesReload() {
        val credentials = BridgeCredentialStore(context)
        val previous = credentials.current()
        val current = credentials.regenerate()
        assertNotEquals(previous, current)
        assertFalse(credentials.verify(previous))
        assertTrue(credentials.verify(current))
        assertEquals(current, BridgeCredentialStore(context).current())
    }
}
