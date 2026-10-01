package dev.usix.companion.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyCodecTest {
    @Test fun endpointNamesMatchExactlyAndQueryPackagesAreDecoded() {
        assertEquals(LegacyCommand.Screen("wanted.app"), LegacyCodec.decode("GET", "/screen?notpackage=other.app&package=wanted%2Eapp", ""))
        assertEquals(LegacyCommand.Screen(null), LegacyCodec.decode("GET", "/screen?package=", ""))
        assertEquals(LegacyCommand.NotFound, LegacyCodec.decode("GET", "/screen-private", ""))
        assertEquals(LegacyCommand.NotFound, LegacyCodec.decode("GET", "/notifications-private", ""))
        assertEquals(LegacyCommand.Notifications, LegacyCodec.decode("GET", "/notifications?limit=50", ""))
        assertEquals(LegacyCommand.NotFound, LegacyCodec.decode("GET", "/health?anything", ""))
    }

    @Test fun optionalScopePresenceAndInvalidTypeSurviveDecoding() {
        assertEquals(WireField.Missing, (LegacyCodec.decode("POST", "/type", "{\"text\":\"reply\"}") as LegacyCommand.Type).packageName)
        for (value in listOf("null", "7", "{}")) {
            assertEquals(WireField.Invalid, (LegacyCodec.decode("POST", "/type", "{\"text\":\"reply\",\"package\":$value}") as LegacyCommand.Type).packageName)
        }
    }

    @Test fun legacyNumericTapArgumentsRemainSupported() {
        assertEquals(LegacyCommand.Tap(1, 2), LegacyCodec.decode("POST", "/tap", "{\"x\":\"1\",\"y\":2}"))
        assertEquals(LegacyCommand.Tap(null, 2), LegacyCodec.decode("POST", "/tap", "{\"y\":2}"))
    }

    @Test fun malformedJsonAndInvalidCoordinatesAreDecodeFailures() {
        for (body in listOf("{", "[]", "{\"x\":{},\"y\":1}")) {
            assertThrows(DecodeFailure::class.java) { LegacyCodec.decode("POST", "/tap", body) }
        }
    }

    @Test fun healthAndDraftWireFieldsRemainCompatible() {
        val health = JSONObject(LegacyCodec.health(true, false, true).json)
        assertEquals(setOf("ok", "auth", "paired", "listener", "accessibility"), health.keySet())
        assertTrue(health.getBoolean("paired"))
        for (ok in listOf(true, false)) {
            val draft = JSONObject(LegacyCodec.action(ok, draft = true).json)
            assertEquals(ok, draft.getBoolean("ok"))
            assertFalse(draft.getBoolean("sent"))
        }
        assertFalse(JSONObject(LegacyCodec.action(true).json).has("sent"))
    }

    @Test fun screenAndNotificationFieldsKeepTheirTypes() {
        val screen = JSONArray(LegacyCodec.screen(listOf(LegacyScreenNode("한글", 10, 20, false, true, false))).json).getJSONObject(0)
        assertEquals(setOf("text", "x", "y", "clickable", "editable", "scrollable"), screen.keySet())
        assertEquals("한글", screen.getString("text"))
        val notification = JSONArray(LegacyCodec.notifications(listOf(LegacyNotification("key", "mail.app", "title", "body", 12L, true))).json).getJSONObject(0)
        assertEquals(setOf("key", "pkg", "title", "text", "postTime", "canReply"), notification.keySet())
        assertEquals(12L, notification.getLong("postTime"))
    }
}
