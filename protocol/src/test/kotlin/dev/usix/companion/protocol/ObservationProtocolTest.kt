package dev.usix.companion.protocol

import org.junit.Assert.*
import org.junit.Test

class ObservationProtocolTest {
    @Test fun boundedTextUsesUnicodeScalarCountsConsistentlyWithTheSharedSchemas() {
        val text = "🙂".repeat(2048)
        assertEquals(text, ObservationCodec.string(text))
        assertEquals(text, ObservationCodec.selector(mapOf("text" to text))!!.fields["text"])
        try { ObservationCodec.string(text + "x"); fail() } catch (_: ProtocolFailure) { }
    }
    @Test fun selectorsAreExactAndGoalsCannotUseEphemeralNodeReferencesOrBusinessClaims() {
        assertEquals("Duplicate", ObservationCodec.selector(mapOf("text" to "Duplicate"))!!.fields["text"])
        for (value in listOf(emptyMap<String, Any>(), mapOf("text" to null), mapOf("textContains" to "send"), mapOf("windowId" to 1L))) {
            try { ObservationCodec.selector(value); fail("Invalid selector accepted") } catch (_: ProtocolFailure) { }
        }
        for (kind in listOf("mail_sent", "delivered", "node_present")) {
            try { ObservationCodec.goal(mapOf("goalId" to "00000000-0000-4000-8000-000000000001", "kind" to kind,
                "selector" to mapOf("nodeRef" to "n/0"), "expectedText" to null, "accountSelector" to null)); fail() } catch (_: ProtocolFailure) { }
        }
    }
}
