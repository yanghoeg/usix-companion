package dev.usix.companion.adapters.persistence

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.usix.companion.application.ExecutionRepository
import dev.usix.companion.domain.*
import java.util.UUID
import java.util.concurrent.Callable

data class ContextColumns(val deviceId: String, val runtimeId: String, val sessionId: String, val taskId: String, val taskRevision: Long, val workspaceId: String) {
    fun domain() = ExecutionContext(deviceId, runtimeId, sessionId, taskId, taskRevision, workspaceId)
    companion object { fun from(c: ExecutionContext) = ContextColumns(c.deviceId, c.runtimeId, c.sessionId, c.taskId, c.taskRevision, c.workspaceId) }
}
@Entity(tableName = "sessions", indices = [Index(value = ["credentialHash"], unique = true)])
data class SessionRow(
    @PrimaryKey val id: String, @Embedded(prefix = "context_") val context: ContextColumns,
    val credentialHash: String, val expiresAtMillis: Long, val grantId: String, val packageId: String?,
    val maxActions: Int, val actionsUsed: Int, val revoked: Boolean,
    @ColumnInfo(defaultValue = "0") val deliveredCursor: Long = 0,
    @ColumnInfo(defaultValue = "0") val acknowledgedCursor: Long = 0,
    @ColumnInfo(defaultValue = "'External runtime'") val displayName: String = "External runtime",
) {
    fun domain() = ControllerSession(context.domain(), credentialHash, expiresAtMillis, grantId, packageId, maxActions, actionsUsed, revoked, deliveredCursor, acknowledgedCursor, displayName)
    companion object { fun from(s: ControllerSession) = SessionRow(s.context.sessionId, ContextColumns.from(s.context), s.credentialHash, s.expiresAtMillis,
        s.grantId, s.packageId, s.maxActions, s.actionsUsed, s.revoked, s.deliveredCursor, s.acknowledgedCursor, s.displayName) }
}
@Entity(tableName = "actions", indices = [Index("context_sessionId")])
data class ActionRow(
    @PrimaryKey val actionId: String, val receiptId: String, val requestId: String,
    @Embedded(prefix = "context_") val context: ContextColumns,
    val operation: String, val packageId: String?, val accountRef: String?, val resourceRefs: String,
    val snapshotRef: String?, val payloadHash: String, val payloadEmpty: Boolean,
    val leaseId: String?, val leaseRevision: Long?, val deadlineMillis: Long, val cancellationId: String?,
    val authorityKind: String, val authorityRef: String?, val revision: Long, val state: String,
    val updatedAtMillis: Long, val cancellationRequested: Boolean, val errorCode: String?, val errorMessage: String?,
) {
    fun domain() = ExecutionReceipt(receiptId, ExecutionCommand(requestId, context.domain(), actionId, operation,
        ExecutionScope(packageId, accountRef, resourceRefs.split('\n').filter(String::isNotEmpty), snapshotRef), payloadHash, payloadEmpty,
        leaseId?.let { LeaseRef(it, leaseRevision!!) }, deadlineMillis, cancellationId, AuthorityRef(authorityKind, authorityRef)),
        revision, ReceiptState.valueOf(state), updatedAtMillis, cancellationRequested,
        errorCode?.let { ExecutionError(ExecutionErrorCode.valueOf(it), errorMessage!!) })
    companion object { fun from(r: ExecutionReceipt): ActionRow { val c = r.command; return ActionRow(c.actionId, r.receiptId, c.requestId,
        ContextColumns.from(c.context), c.operation, c.scope.packageId, c.scope.accountRef, c.scope.resourceRefs.joinToString("\n"), c.scope.snapshotRef,
        c.payloadHash, c.payloadEmpty, c.lease?.leaseId, c.lease?.revision, c.deadlineMillis, c.cancellationId, c.authority.kind, c.authority.ref,
        r.revision, r.state.name, r.updatedAtMillis, r.cancellationRequested, r.error?.code?.name, r.error?.message) } }
}
@Entity(tableName = "controller")
data class LeaseRow(@PrimaryKey val singleton: Int = 1, val leaseId: String, val revision: Long, val sessionId: String, val runtimeId: String, val expiresAtMillis: Long) {
    fun domain() = ControllerLease(LeaseRef(leaseId, revision), sessionId, runtimeId, expiresAtMillis)
}
@Entity(tableName = "events", indices = [Index(value = ["eventId"], unique = true)])
data class EventRow(
    @PrimaryKey val cursor: Long, val eventId: String, val deviceId: String, val createdAtMillis: Long, val type: String,
    @Embedded(prefix = "context_") val context: ContextColumns?, val actionId: String?, val receiptId: String?, val receiptRevision: Long?,
    val runtimeId: String?, val leaseId: String?, val leaseRevision: Long?,
) {
    fun domain() = DeviceEvent(eventId, deviceId, cursor, createdAtMillis, if (type == "action_updated")
        EventPayload.ActionUpdated(context!!.domain(), actionId!!, receiptId!!, receiptRevision!!) else
        EventPayload.ControllerChanged(runtimeId, leaseId?.let { LeaseRef(it, leaseRevision!!) }))
    companion object { fun from(e: DeviceEvent): EventRow = when (val p = e.payload) {
        is EventPayload.ActionUpdated -> EventRow(e.cursor, e.eventId, e.deviceId, e.createdAtMillis, "action_updated", ContextColumns.from(p.context),
            p.actionId, p.receiptId, p.receiptRevision, null, null, null)
        is EventPayload.ControllerChanged -> EventRow(e.cursor, e.eventId, e.deviceId, e.createdAtMillis, "controller_changed", null, null, null, null,
            p.runtimeId, p.lease?.leaseId, p.lease?.revision)
    } }
}
@Entity(tableName = "metadata")
data class MetadataRow(@PrimaryKey val name: String, val value: String)

@Dao
interface ExecutionDao {
    @Query("SELECT * FROM sessions WHERE id = :id") fun session(id: String): SessionRow?
    @Query("SELECT * FROM sessions WHERE credentialHash = :hash") fun sessionByHash(hash: String): SessionRow?
    @Query("SELECT * FROM sessions ORDER BY id") fun sessions(): List<SessionRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSession(row: SessionRow)
    @Query("SELECT * FROM controller WHERE singleton = 1") fun lease(): LeaseRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putLease(row: LeaseRow)
    @Query("DELETE FROM controller") fun clearLease()
    @Query("SELECT * FROM actions WHERE actionId = :id") fun receipt(id: String): ActionRow?
    @Query("SELECT * FROM actions WHERE state IN ('Accepted', 'Executing')") fun unfinished(): List<ActionRow>
    @Query("SELECT * FROM actions WHERE context_sessionId = :sessionId ORDER BY updatedAtMillis LIMIT 256") fun receipts(sessionId: String): List<ActionRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putReceipt(row: ActionRow)
    @Query("SELECT * FROM metadata WHERE name = :name") fun meta(name: String): MetadataRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putMeta(row: MetadataRow)
    @Insert fun append(row: EventRow)
    @Query("DELETE FROM events WHERE cursor <= :cursor") fun prune(cursor: Long)
    @Query("SELECT * FROM events WHERE cursor > :cursor ORDER BY cursor LIMIT :limit") fun events(cursor: Long, limit: Int): List<EventRow>
    @Query("SELECT MIN(cursor) FROM events") fun oldest(): Long?
    @Query("SELECT MAX(cursor) FROM events") fun latest(): Long?
}
@Database(entities = [SessionRow::class, ActionRow::class, LeaseRow::class, EventRow::class, MetadataRow::class], version = 2, exportSchema = true)
abstract class ExecutionDatabase : RoomDatabase() {
    abstract fun execution(): ExecutionDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN deliveredCursor INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN acknowledgedCursor INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN displayName TEXT NOT NULL DEFAULT 'External runtime'")
        } }
        fun open(context: Context, name: String = "device-execution.db") = Room.databaseBuilder(context.applicationContext, ExecutionDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2).build()
    }
}
class RoomExecutionRepository(val database: ExecutionDatabase) : ExecutionRepository {
    private val dao = database.execution()
    override fun <T> transaction(block: () -> T): T = database.runInTransaction(Callable { block() })
    override fun deviceId(): String = transaction { dao.meta("deviceId")?.value ?: UUID.randomUUID().toString().also { dao.putMeta(MetadataRow("deviceId", it)) } }
    override fun nextCounter(name: String): Long = transaction { val n = (dao.meta(name)?.value?.toLong() ?: 0) + 1; dao.putMeta(MetadataRow(name, n.toString())); n }
    override fun cancelled(cancellationId: String) = dao.meta("cancelled:$cancellationId") != null
    override fun markCancelled(cancellationId: String) = dao.putMeta(MetadataRow("cancelled:$cancellationId", "1"))
    override fun session(sessionId: String) = dao.session(sessionId)?.domain()
    override fun sessionByCredentialHash(hash: String) = dao.sessionByHash(hash)?.domain()
    override fun sessions() = dao.sessions().map(SessionRow::domain)
    override fun putSession(session: ControllerSession) = dao.putSession(SessionRow.from(session))
    override fun lease() = dao.lease()?.domain()
    override fun putLease(lease: ControllerLease?) { if (lease == null) dao.clearLease() else dao.putLease(LeaseRow(leaseId = lease.ref.leaseId,
        revision = lease.ref.revision, sessionId = lease.sessionId, runtimeId = lease.runtimeId, expiresAtMillis = lease.expiresAtMillis)) }
    override fun receipt(actionId: String) = dao.receipt(actionId)?.domain()
    override fun putReceipt(receipt: ExecutionReceipt) = dao.putReceipt(ActionRow.from(receipt))
    override fun unfinishedReceipts() = dao.unfinished().map(ActionRow::domain)
    override fun receipts(sessionId: String) = dao.receipts(sessionId).map(ActionRow::domain)
    override fun append(event: DeviceEvent, retain: Int) { dao.append(EventRow.from(event)); dao.prune(event.cursor - retain) }
    override fun eventsAfter(cursor: Long, limit: Int) = dao.events(cursor, limit).map(EventRow::domain)
    override fun oldestEventCursor() = dao.oldest() ?: (latestEventCursor() + 1)
    override fun latestEventCursor() = dao.meta("event")?.value?.toLong() ?: 0
}
