package dev.usix.companion.adapters.persistence

import dev.usix.companion.application.NotificationArchive
import dev.usix.companion.application.NotificationHistory
import dev.usix.companion.application.NotificationHistoryStorageException
import dev.usix.companion.application.NotificationHistoryStore
import dev.usix.companion.domain.ArchivedNotification
import dev.usix.companion.domain.NotificationRecordSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Private, backup-excluded append journal. No live notification keys or reply capability. */
class FileNotificationHistoryStore(private val file: File, private val io: CoroutineDispatcher) : NotificationHistoryStore {
    override suspend fun load(): NotificationArchive = storage {
        if (!file.exists()) return@storage NotificationArchive(0, emptyList())
        RandomAccessFile(file, "rw").use { input ->
            if (input.length() < HEADER || input.length() > MAX_BYTES) throw IOException("Invalid journal size")
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Unsupported journal")
            val startedAt = input.readLong()
            var recovered = input.readBoolean()
            if (startedAt < 0) throw IOException("Invalid journal date")
            val records = mutableListOf<ArchivedNotification>()
            val ids = mutableSetOf<String>()
            var goodEnd = input.filePointer
            while (input.filePointer < input.length()) {
                if (input.length() - input.filePointer < 4) { recovered = true; break }
                val size = input.readInt()
                if (size !in 1..MAX_FRAME) throw IOException("Invalid journal frame")
                if (input.length() - input.filePointer < size + 4L) { recovered = true; break }
                val bytes = ByteArray(size).also(input::readFully)
                if (input.readInt() != checksum(bytes)) throw IOException("Journal checksum failed")
                val record = decode(bytes)
                if (ids.add(record.id)) records.add(record)
                if (records.size > 10_064) throw IOException("Journal record bound exceeded")
                goodEnd = input.filePointer
            }
            if (goodEnd < input.length()) {
                // Only an incomplete final append is recoverable; complete corrupt frames fail closed.
                input.setLength(goodEnd)
                input.seek(16); input.writeBoolean(true); input.fd.sync()
            }
            NotificationArchive(startedAt, records, recovered)
        }
    }

    override suspend fun append(records: List<ArchivedNotification>) = storage {
        val frames = records.map(::encode)
        if (!file.isFile || file.length() + frames.sumOf { it.size + 8L } > MAX_BYTES) throw IOException("Journal unavailable or full")
        FileOutputStream(file, true).use { stream ->
            val output = DataOutputStream(stream)
            frames.forEach { writeFrame(output, it) }
            output.flush(); stream.fd.sync()
        }
    }

    override suspend fun replace(archive: NotificationArchive) = storage {
        val directory = file.parentFile ?: throw IOException("Missing journal directory")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create journal directory")
        val temporary = File(directory, "${file.name}.new")
        try {
            FileOutputStream(temporary).use { stream ->
                val output = DataOutputStream(stream)
                output.writeInt(MAGIC); output.writeInt(VERSION)
                output.writeLong(archive.startedAtMillis); output.writeBoolean(archive.recoveredTail)
                archive.records.forEach { writeFrame(output, encode(it)) }
                output.flush(); stream.fd.sync()
                if (temporary.length() > MAX_BYTES) throw IOException("Journal size bound exceeded")
            }
            // Same-directory rename atomically replaces the old journal on Android/Linux.
            if (!temporary.renameTo(file)) throw IOException("Cannot replace journal")
        } finally { temporary.delete() }
    }

    private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size); output.write(bytes); output.writeInt(checksum(bytes))
    }
    private fun checksum(bytes: ByteArray) = CRC32().apply { update(bytes) }.value.toInt()
    private fun encode(record: ArchivedNotification): ByteArray {
        validate(record)
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeUTF(record.id); it.writeUTF(record.packageId); it.writeUTF(record.title)
                it.writeUTF(record.sender); it.writeUTF(record.text)
                it.writeLong(record.eventTimeMillis); it.writeLong(record.capturedAtMillis)
                it.writeUTF(record.source.name); it.writeBoolean(record.truncated)
                it.writeBoolean(record.attachment); it.writeBoolean(record.recoveredActive)
            }
        }.toByteArray().also { if (it.size > MAX_FRAME) throw IOException("Oversized journal frame") }
    }
    private fun decode(bytes: ByteArray): ArchivedNotification = DataInputStream(ByteArrayInputStream(bytes)).use {
        val record = ArchivedNotification(it.readUTF(), it.readUTF(), it.readUTF(), it.readUTF(), it.readUTF(),
            it.readLong(), it.readLong(), NotificationRecordSource.valueOf(it.readUTF()), it.readBoolean(), it.readBoolean(), it.readBoolean())
        if (it.available() != 0) throw IOException("Unexpected journal fields")
        validate(record); record
    }
    private fun validate(record: ArchivedNotification) {
        if (record.packageId != NotificationHistory.KAKAO_PACKAGE || !ID.matches(record.id) ||
            record.title.length > 512 || record.sender.length > 512 || record.text.length > 8192 ||
            record.eventTimeMillis < 0 || record.capturedAtMillis < 0) throw IOException("Invalid journal record")
    }
    private suspend fun <T> storage(block: () -> T): T = withContext(io) {
        try { block() } catch (error: IOException) { throw NotificationHistoryStorageException(error) }
        catch (error: IllegalArgumentException) { throw NotificationHistoryStorageException(error) }
        catch (error: SecurityException) { throw NotificationHistoryStorageException(error) }
    }
    companion object {
        private const val MAGIC = 0x554e4831
        private const val VERSION = 1
        private const val HEADER = 17L
        private const val MAX_FRAME = 64 * 1024
        private const val MAX_BYTES = 256L * 1024 * 1024
        private val ID = Regex("[0-9a-f]{64}")
    }
}
