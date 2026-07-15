package com.dictation.server.relay

import android.content.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dictation.server.core.TextChunker
import com.dictation.server.model.QueueItem

/**
 * Broadcast link between the relay service and the writing plugin:
 *   1. Outgoing text is queued, sent as TEXT_TO_PLUGIN, and removed on ACK.
 *   2. PLUGIN_ALIVE triggers a paced resend of everything still queued.
 *   3. QUERY / IMAGE_QUERY / INSERT_POSITION broadcasts flow back as callbacks.
 */
class PluginBroadcastBridge(private val context: Context) {
    companion object {
        private const val TAG = "PluginBridge"

        const val ACTION_TEXT_TO_PLUGIN      = "com.dictation.TEXT_TO_PLUGIN"
        const val ACTION_ACK_FROM_PLUGIN     = "com.dictation.ACK_FROM_PLUGIN"
        const val ACTION_QUERY_FROM_PLUGIN   = "com.dictation.QUERY_FROM_PLUGIN"
        const val ACTION_IMAGE_QUERY         = "com.dictation.IMAGE_QUERY_FROM_PLUGIN"
        const val ACTION_PLUGIN_ALIVE        = "com.dictation.PLUGIN_ALIVE"
        const val ACTION_INSERT_POSITION     = "com.dictation.INSERT_POSITION"
        /** Relay → plugin: newest inbox previews for the capsule bars. */
        const val ACTION_INBOX_TO_PLUGIN     = "com.dictation.INBOX_TO_PLUGIN"
        /** Plugin → relay: user asked to insert inbox message [id] into the note. */
        const val ACTION_INSERT_REQUEST      = "com.dictation.INSERT_REQUEST_FROM_PLUGIN"

        /** Delay between resent chunks, so the plugin isn't flooded. */
        private const val RESEND_INTERVAL_MS = 200L
        private const val MAX_RETRIES = 10
        /** Un-ACKed text older than this is dropped instead of resent. */
        private const val MAX_AGE_MS = 5 * 60_000L

        // 每块约等于插件端一个文本框。300 字符在笔记页宽下只有 4-5 行，
        // 文本框太碎；600 约合 8-10 行。
        private const val MAX_CHUNK_CHARS = 600
    }

    // ── Callbacks ────────────────────────────────────────────────────────
    var onAckReceived: ((text: String, success: Boolean, error: String?) -> Unit)? = null
    var onQueryReceived: ((text: String) -> Unit)? = null
    /** Plugin-reported current insert position (page, top). */
    var onInsertPositionChanged: ((page: Int, top: Int) -> Unit)? = null
    /** Image query: imagePath, maskPath, prompt. */
    var onImageQueryReceived: ((imagePath: String, maskPath: String, prompt: String) -> Unit)? = null
    var onQueueChanged: ((List<QueueItem>) -> Unit)? = null
    /** Plugin asked to insert inbox message [id] into the note. */
    var onInsertRequest: ((id: String) -> Unit)? = null
    /** Plugin (re)started — resend state such as the inbox previews. */
    var onPluginAlive: (() -> Unit)? = null

    private val pendingQueue = mutableListOf<QueueItem>()
    private val handler = Handler(Looper.getMainLooper())

    private fun notifyQueueChanged() {
        val snapshot = synchronized(pendingQueue) { pendingQueue.map { it.copy() } }
        onQueueChanged?.invoke(snapshot)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ACK_FROM_PLUGIN -> {
                    val text = intent.getStringExtra("text") ?: ""
                    val success = intent.getBooleanExtra("success", false)
                    val error = intent.getStringExtra("error")
                    Log.i(TAG, "ACK received: success=$success textLen=${text.length}")

                    val changed = synchronized(pendingQueue) {
                        pendingQueue.removeAll { it.text == text }
                    }
                    if (changed) notifyQueueChanged()
                    onAckReceived?.invoke(text, success, error)
                }

                ACTION_QUERY_FROM_PLUGIN -> {
                    val text = intent.getStringExtra("text") ?: ""
                    Log.i(TAG, "Query received: textLen=${text.length}")
                    onQueryReceived?.invoke(text)
                }

                ACTION_PLUGIN_ALIVE -> {
                    Log.i(TAG, "PLUGIN_ALIVE received — resending pending texts")
                    resendPendingTexts()
                    onPluginAlive?.invoke()
                }

                ACTION_INSERT_REQUEST -> {
                    val id = intent.getStringExtra("id") ?: return
                    Log.i(TAG, "INSERT_REQUEST received: id=$id")
                    onInsertRequest?.invoke(id)
                }

                ACTION_IMAGE_QUERY -> {
                    val imagePath = intent.getStringExtra("imagePath") ?: return
                    val maskPath = intent.getStringExtra("maskPath") ?: ""
                    val prompt = intent.getStringExtra("prompt") ?: ""
                    Log.i(TAG, "IMAGE_QUERY received: imagePath=$imagePath")
                    onImageQueryReceived?.invoke(imagePath, maskPath, prompt)
                }

                ACTION_INSERT_POSITION -> {
                    val page = intent.getIntExtra("page", -1)
                    val top = intent.getIntExtra("top", -1)
                    if (page >= 0 && top >= 0) onInsertPositionChanged?.invoke(page, top)
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_ACK_FROM_PLUGIN)
            addAction(ACTION_QUERY_FROM_PLUGIN)
            addAction(ACTION_IMAGE_QUERY)
            addAction(ACTION_PLUGIN_ALIVE)
            addAction(ACTION_INSERT_POSITION)
            addAction(ACTION_INSERT_REQUEST)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        Log.i(TAG, "started — listening for plugin broadcasts")
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "stopped")
    }

    /** Push the newest inbox previews (JSON array string) to the plugin capsules. */
    fun sendInboxToPlugin(itemsJson: String) {
        context.sendBroadcast(Intent(ACTION_INBOX_TO_PLUGIN).apply {
            putExtra("items", itemsJson)
        })
        Log.i(TAG, "sendInboxToPlugin: ${itemsJson.length} chars")
    }

    /**
     * Chunk and send text to the plugin. Chunks stay in the queue until
     * ACKed; a later PLUGIN_ALIVE resends whatever is still pending.
     */
    fun sendTextToPlugin(text: String) {
        val chunks = TextChunker.splitAtBoundaries(text, MAX_CHUNK_CHARS)
        Log.i(TAG, "sendTextToPlugin: split textLen=${text.length} into ${chunks.size} chunks")

        chunks.forEach { chunk ->
            synchronized(pendingQueue) {
                pendingQueue.add(QueueItem(text = chunk))
            }
            notifyQueueChanged()
            context.sendBroadcast(Intent(ACTION_TEXT_TO_PLUGIN).apply {
                putExtra("text", chunk)
            })
        }
    }

    /** Resend un-ACKed texts in order, dropping stale/over-retried entries. */
    private fun resendPendingTexts() {
        val now = System.currentTimeMillis()
        val textsToResend = mutableListOf<String>()
        var queueModified = false

        synchronized(pendingQueue) {
            val iterator = pendingQueue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                val age = now - item.sentAt
                if (item.retryCount >= MAX_RETRIES || age > MAX_AGE_MS) {
                    Log.w(TAG, "Dropping stale/over-retried text: retries=${item.retryCount}, age=${age / 1000}s")
                    iterator.remove()
                    queueModified = true
                    continue
                }
                item.retryCount++
                textsToResend.add(item.text)
            }
        }

        if (queueModified) notifyQueueChanged()
        if (textsToResend.isEmpty()) {
            Log.i(TAG, "resendPendingTexts: nothing to resend")
            return
        }

        Log.i(TAG, "resendPendingTexts: resending ${textsToResend.size} texts")
        textsToResend.forEachIndexed { index, text ->
            handler.postDelayed({
                Log.i(TAG, "resend [${index + 1}/${textsToResend.size}] textLen=${text.length}")
                context.sendBroadcast(Intent(ACTION_TEXT_TO_PLUGIN).apply {
                    putExtra("text", text)
                })
            }, index * RESEND_INTERVAL_MS)
        }
    }

    /** Phone-initiated: replace the whole queue (user edited it in the UI). */
    fun replaceQueue(items: List<QueueItem>) {
        synchronized(pendingQueue) {
            pendingQueue.clear()
            pendingQueue.addAll(items)
        }
        notifyQueueChanged()
        Log.i(TAG, "queue replaced by server command, new size=${items.size}")
    }
}
