package dev.usix.companion.protocol

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DeviceV2ProtocolTest {
    private fun example(name: String) = File(System.getProperty("companion.contracts"), "examples/$name.json").readText()
    @Test fun sharedKoreanGoldenHashMatchesThePythonContract() {
        val command = StrictJson.objectValue(example("command-mail-send"))
        val golden = StrictJson.objectValue(example("payload-hash-vector"))
        assertEquals(golden["sha256"], DeviceV2Codec.payloadHash(command))
        assertEquals(golden["canonicalUtf8"], StrictJson.encode(command.filterKeys { it in setOf("operation", "payload", "scope") }))
        assertEquals("mail.send", DeviceV2Codec.command(example("command-mail-send")).operation)
    }
    @Test fun malformedJsonAndUnsafeNumbersNeverCoerce() {
        listOf("{\"x\":1,\"x\":2}", "{\"x\":1.0}", "{\"x\":1e2}", "{\"x\":NaN}", "{\"x\":01}",
            "{\"x\":9007199254740992}", "{\"x\":\"\\ud800\"}", "{\"한글\":1}", "{\"x\":true} trailing").forEach {
            try { StrictJson.objectValue(it); fail("Malformed input accepted") } catch (_: ProtocolFailure) { }
        }
    }
    @Test fun unknownEnvelopeFieldsAndHashMismatchFailClosed() {
        val command = StrictJson.objectValue(example("command-mail-send"))
        listOf(command + ("cwd" to "/attacker"), command + ("payloadHash" to "sha256:" + "0".repeat(64))).forEach {
            try { DeviceV2Codec.command(StrictJson.encode(it)); fail("Invalid command accepted") } catch (e: ProtocolFailure) { assertEquals("InvalidRequest", e.code) }
        }
    }
    @Test fun unsupportedVersionIsDistinctAndUtcCalendarDatesAreStrict() {
        val command = StrictJson.objectValue(example("command-mail-send")) + ("contractVersion" to "v999")
        try { DeviceV2Codec.command(StrictJson.encode(command)); fail() } catch (e: ProtocolFailure) { assertEquals("UnsupportedVersion", e.code) }
        listOf("2026-02-29T00:00:00Z", "2026-13-01T00:00:00Z", "2026-10-01T00:00:00+00:00", "1500-02-29T00:00:00Z").forEach {
            try { DeviceV2Codec.parseTime(it); fail("Invalid calendar date accepted") } catch (_: ProtocolFailure) { }
        }
        assertEquals("2026-10-01T00:00:00.100Z", DeviceV2Codec.time(DeviceV2Codec.parseTime("2026-10-01T00:00:00.1Z")))
    }
    @Test fun scalarUnicodeControlsAndNestedBoundsMatchCanonicalRules() {
        val encoded = StrictJson.encode(mapOf("text" to "안녕하세요 😀\b\t\n\u000c\r\u0001\\\"/"))
        assertEquals(mapOf("text" to "안녕하세요 😀\b\t\n\u000c\r\u0001\\\"/"), StrictJson.objectValue(encoded))
        val deep = "{\"x\":".repeat(34) + "null" + "}".repeat(34)
        try { StrictJson.objectValue(deep); fail() } catch (_: ProtocolFailure) { }
        try { StrictJson.objectValue("{\"x\":\"" + "a".repeat(8193) + "\"}"); fail() } catch (_: ProtocolFailure) { }
    }
}
