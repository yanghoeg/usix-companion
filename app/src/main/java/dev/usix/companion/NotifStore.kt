package dev.usix.companion

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** 브리지가 내보내는 알림 한 건. */
data class NotifItem(
    val key: String,
    val pkg: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val canReply: Boolean,
)

/** 인라인 답장을 다시 쏘기 위한 핸들(알림의 RemoteInput 액션). */
private class ReplyHandle(
    val pendingIntent: PendingIntent,
    val remoteInput: RemoteInput,
    val allInputs: Array<RemoteInput>,
)

/**
 * 최근 알림 링버퍼 + key→답장핸들 맵. 리스너 서비스와 브리지 서버가 같은 프로세스라 싱글턴으로 공유.
 * 다른 앱의 비공개 DB 는 못 읽는다 — 어디까지나 '알림에 실린' 내용과 답장 액션만 다룬다.
 */
object NotifStore {
    private const val MAX = 50
    private val items = ArrayDeque<NotifItem>()
    private val handles = HashMap<String, ReplyHandle>()

    @Volatile
    var listenerConnected = false

    @Volatile
    var appContext: Context? = null

    @Synchronized
    fun add(
        item: NotifItem,
        pendingIntent: PendingIntent?,
        remoteInput: RemoteInput?,
        allInputs: Array<RemoteInput>?,
    ) {
        items.removeAll { it.key == item.key }
        items.addLast(item)
        while (items.size > MAX) {
            val dropped = items.removeFirst()
            handles.remove(dropped.key)
        }
        if (pendingIntent != null && remoteInput != null && allInputs != null) {
            handles[item.key] = ReplyHandle(pendingIntent, remoteInput, allInputs)
        } else {
            handles.remove(item.key)
        }
    }

    @Synchronized
    fun remove(key: String) {
        items.removeAll { it.key == key }
        handles.remove(key)
    }

    /** 최신 우선. */
    @Synchronized
    fun snapshot(): List<NotifItem> = items.toList().asReversed()

    /** key 로 인라인 답장을 쏜다. 답장 액션이 없거나 만료면 false. */
    @Synchronized
    fun reply(key: String, text: String): Boolean {
        val h = handles[key] ?: return false
        val ctx = appContext ?: return false
        val intent = Intent()
        val bundle = Bundle()
        bundle.putCharSequence(h.remoteInput.resultKey, text)
        RemoteInput.addResultsToIntent(h.allInputs, intent, bundle)
        return try {
            h.pendingIntent.send(ctx, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }
}
