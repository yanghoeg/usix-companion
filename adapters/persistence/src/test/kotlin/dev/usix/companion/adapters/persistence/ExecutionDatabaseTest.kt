package dev.usix.companion.adapters.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import dev.usix.companion.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExecutionDatabaseTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private fun session() = ControllerSession(ExecutionContext(id(1), id(2), id(3), id(4), 1, id(5)), "synthetic-token-hash", 999999, id(6), "dev.usix.companion")
    private fun receipt() = ExecutionReceipt(id(7), ExecutionCommand(id(8), session().context, id(9), "app.open", ExecutionScope("dev.usix.companion"),
        "sha256:" + "a".repeat(64), true, LeaseRef(id(10), 1), 99999, id(11), AuthorityRef("grant", id(6))), 2, ReceiptState.Executing, 1000)
    private fun database(name: String) = Room.databaseBuilder(context, ExecutionDatabase::class.java, name).allowMainThreadQueries()
        .addMigrations(ExecutionDatabase.MIGRATION_1_2, ExecutionDatabase.MIGRATION_2_3).build()

    @Test fun deviceIdentityReceiptsCountersAndAcksSurviveDatabaseReopen() {
        val name = "durable-test.db"; context.deleteDatabase(name)
        var db = database(name); var store = RoomExecutionRepository(db)
        val device = store.deviceId()
        store.transaction { store.putSession(session().copy(actionsUsed = 1, deliveredCursor = 8, acknowledgedCursor = 7)); store.putReceipt(receipt()); store.markCancelled(id(11)); store.nextCounter("lease") }
        db.close(); db = database(name); store = RoomExecutionRepository(db)
        assertEquals(device, store.deviceId()); assertEquals(receipt(), store.receipt(id(9)))
        assertEquals(7L, store.session(id(3))!!.acknowledgedCursor); assertTrue(store.cancelled(id(11)))
        assertEquals(2L, store.nextCounter("lease")); db.close()
    }
    @Test fun actionBudgetReceiptAndOutboxRollbackTogether() {
        val db = Room.inMemoryDatabaseBuilder(context, ExecutionDatabase::class.java).allowMainThreadQueries().build()
        val store = RoomExecutionRepository(db); store.putSession(session())
        try { store.transaction<Unit> {
            store.putSession(session().copy(actionsUsed = 1)); store.putReceipt(receipt())
            val cursor = store.nextCounter("event")
            store.append(DeviceEvent(id(12), id(1), cursor, 1000, EventPayload.ActionUpdated(session().context, id(9), id(7), 2)), 2)
            throw IllegalStateException("synthetic crash before commit")
        }; fail() } catch (_: IllegalStateException) { }
        assertEquals(0, store.session(id(3))!!.actionsUsed); assertNull(store.receipt(id(9))); assertEquals(0L, store.latestEventCursor())
        db.close()
    }
    @Test fun boundedEventsRetainMonotonicCursorAcrossReopen() {
        val name = "outbox-test.db"; context.deleteDatabase(name)
        var db = database(name); var store = RoomExecutionRepository(db)
        repeat(5) { n -> store.transaction { val cursor = store.nextCounter("event"); store.append(DeviceEvent(id(20 + n), id(1), cursor, 1000 + n.toLong(), EventPayload.ControllerChanged(null, null)), 2) } }
        db.close(); db = database(name); store = RoomExecutionRepository(db)
        assertEquals(4L, store.oldestEventCursor()); assertEquals(5L, store.latestEventCursor()); assertEquals(listOf(4L, 5L), store.eventsAfter(0, 128).map { it.cursor })
        assertEquals(6L, store.nextCounter("event")); db.close()
    }
    @Test fun v1RelationalMigrationPreservesBoundReceiptAndBudget() {
        val name = "migration-test.db"; context.deleteDatabase(name)
        val db = database(name); val store = RoomExecutionRepository(db)
        store.putSession(session().copy(actionsUsed = 1)); store.putReceipt(receipt()); db.close()
        // Controlled old-layout fixture: v1 has no event acknowledgement/display columns.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.execSQL("CREATE TABLE sessions_v1 (id TEXT NOT NULL PRIMARY KEY, context_deviceId TEXT NOT NULL, context_runtimeId TEXT NOT NULL, context_sessionId TEXT NOT NULL, context_taskId TEXT NOT NULL, context_taskRevision INTEGER NOT NULL, context_workspaceId TEXT NOT NULL, credentialHash TEXT NOT NULL, expiresAtMillis INTEGER NOT NULL, grantId TEXT NOT NULL, packageId TEXT, maxActions INTEGER NOT NULL, actionsUsed INTEGER NOT NULL, revoked INTEGER NOT NULL)")
            old.execSQL("INSERT INTO sessions_v1 SELECT id,context_deviceId,context_runtimeId,context_sessionId,context_taskId,context_taskRevision,context_workspaceId,credentialHash,expiresAtMillis,grantId,packageId,maxActions,actionsUsed,revoked FROM sessions")
            old.execSQL("DROP TABLE sessions"); old.execSQL("ALTER TABLE sessions_v1 RENAME TO sessions")
            old.execSQL("CREATE UNIQUE INDEX index_sessions_credentialHash ON sessions(credentialHash)")
            for (column in listOf("goalId", "goalHash", "observationRef", "evidenceRefs")) old.execSQL("ALTER TABLE actions DROP COLUMN $column")
            old.version = 1
        }
        val upgraded = database(name); val migrated = RoomExecutionRepository(upgraded)
        assertEquals(receipt(), migrated.receipt(id(9))); assertEquals(1, migrated.session(id(3))!!.actionsUsed)
        assertEquals(0L, migrated.session(id(3))!!.acknowledgedCursor); assertEquals("External runtime", migrated.session(id(3))!!.displayName)
        upgraded.close()
    }
    @Test fun v2MigrationPreservesAuthorityAndVerifiedEvidenceAcrossReopen() {
        val name = "migration-v2-test.db"; context.deleteDatabase(name)
        var db = database(name); var store = RoomExecutionRepository(db)
        store.putSession(session().copy(actionsUsed = 3, deliveredCursor = 8, acknowledgedCursor = 7)); store.putReceipt(receipt()); db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            old.execSQL("ALTER TABLE sessions DROP COLUMN accountRef"); old.execSQL("ALTER TABLE sessions DROP COLUMN fixtureUi")
            for (column in listOf("goalId", "goalHash", "observationRef", "evidenceRefs")) old.execSQL("ALTER TABLE actions DROP COLUMN $column")
            old.version = 2
        }
        db = database(name); store = RoomExecutionRepository(db)
        assertEquals(receipt(), store.receipt(id(9))); assertEquals(3, store.session(id(3))!!.actionsUsed)
        assertEquals(7L, store.session(id(3))!!.acknowledgedCursor); assertFalse(store.session(id(3))!!.fixtureUi)
        val evidence = VerificationEvidence(id(30), id(31), 2000, id(32))
        val verified = receipt().copy(command = receipt().command.copy(goalId = id(31), goalHash = "sha256:" + "b".repeat(64)),
            state = ReceiptState.Verified, observationRef = id(32), evidence = listOf(evidence))
        store.putReceipt(verified); store.putSession(session().copy(accountRef = id(33), fixtureUi = true)); db.close()
        db = database(name); store = RoomExecutionRepository(db)
        assertEquals(verified, store.receipt(id(9))); assertEquals(id(33), store.session(id(3))!!.accountRef); assertTrue(store.session(id(3))!!.fixtureUi)
        db.close()
    }
}
