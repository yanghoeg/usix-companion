package dev.usix.companion.adapters.persistence

import dev.usix.companion.application.*
import dev.usix.companion.domain.*
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real file durability tests, independent of Robolectric's native SQLite support. */
class FileNotificationHistoryStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private fun file() = File(directory.root, "archive.bin")
    private fun store() = FileNotificationHistoryStore(file(), Dispatchers.Unconfined)
    private fun record(id: Int, text: String = "알림 😀 $id") = ArchivedNotification(id.toString(16).padStart(64, '0'),
        NotificationHistory.KAKAO_PACKAGE, "대화", "사람", text, 1000, 2000, NotificationRecordSource.MessagingStyle,
        false, true, true)
    @Test fun committedRecordsSurviveCloseAndReopenWithExactUnicodeAndFlags() = runBlocking {
        store().replace(NotificationArchive(500, listOf(record(1))))
        store().append(listOf(record(2)))
        assertEquals(NotificationArchive(500, listOf(record(1), record(2))), store().load())
    }
    @Test fun duplicateFramesDoNotBecomeDuplicateHistoryAfterRestart() = runBlocking {
        store().replace(NotificationArchive(500, listOf(record(1))))
        store().append(listOf(record(1), record(2)))
        assertEquals(listOf(record(1), record(2)), store().load().records)
    }
    @Test fun incompleteTailRecoversOnlyCommittedPrefixAndPersistsLossFlag() = runBlocking {
        store().replace(NotificationArchive(500, listOf(record(1))))
        val size = file().length()
        file().appendBytes(byteArrayOf(0, 0))
        val recovered = store().load()
        assertEquals(listOf(record(1)), recovered.records); assertTrue(recovered.recoveredTail)
        assertEquals(size, file().length()); assertTrue(store().load().recoveredTail)
        store().append(listOf(record(2)))
        assertEquals(2, store().load().records.size)
    }
    @Test fun completeCorruptFrameReturnsUnavailableInsteadOfEmptyOrPartialHistory() = runBlocking {
        store().replace(NotificationArchive(500, listOf(record(1), record(2))))
        RandomAccessFile(file(), "rw").use { val position = it.length() - 1; it.seek(position); val previous = it.readUnsignedByte(); it.seek(position); it.writeByte(previous xor 1) }
        try { store().load(); fail("Corrupt history accepted") } catch (_: NotificationHistoryStorageException) { }
    }
    @Test fun malformedHeaderAndOversizedFrameFailWithoutUnboundedAllocation() = runBlocking {
        file().writeBytes(byteArrayOf(0, 0))
        try { store().load(); fail("Truncated header accepted") } catch (_: NotificationHistoryStorageException) { }
        store().replace(NotificationArchive(500, emptyList()))
        RandomAccessFile(file(), "rw").use { it.seek(it.length()); it.writeInt(Int.MAX_VALUE) }
        try { store().load(); fail("Oversized frame accepted") } catch (_: NotificationHistoryStorageException) { }
    }
    @Test fun replaceAndClearPhysicallyRemoveExpiredTextAndTemporaryFiles() = runBlocking {
        store().replace(NotificationArchive(500, listOf(record(1, "expired-secret"), record(2))))
        store().replace(NotificationArchive(500, listOf(record(2))))
        assertFalse(file().readBytes().toString(Charsets.ISO_8859_1).contains("expired-secret"))
        store().replace(NotificationArchive(3000, emptyList()))
        assertEquals(NotificationArchive(3000, emptyList()), store().load())
        assertEquals(listOf("archive.bin"), directory.root.list()!!.toList())
    }
    @Test fun failedReplacementPreservesPreviouslyCommittedArchive() = runBlocking {
        val original = NotificationArchive(500, listOf(record(1)))
        store().replace(original)
        File(directory.root, "archive.bin.new").mkdir()
        try { store().replace(NotificationArchive(900, emptyList())); fail("Expected failure") }
        catch (_: NotificationHistoryStorageException) { }
        assertEquals(original, store().load())
    }
}
