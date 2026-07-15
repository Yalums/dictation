package com.dictation.server.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dictation.server.R
import com.dictation.server.audio.AudioRecorder
import com.dictation.server.audio.SherpaSocketEngine
import com.dictation.server.bluetooth.BluetoothAudioRouter
import com.dictation.server.core.AppPrefs
import com.dictation.server.core.TextChunker
import com.dictation.server.llm.LlmBridge
import com.dictation.server.llm.SseLlmBridge
import com.dictation.server.model.QueueItem
import com.dictation.server.net.HttpJson
import com.dictation.server.relay.PluginBroadcastBridge
import com.dictation.server.relay.RelayInboxStore
import com.dictation.server.ws.DictationWebSocketClient
import com.dictation.server.ws.DictationWebSocketServer

class DictationService : Service() {

    companion object {
        private const val TAG             = "DictationService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID      = "dictation_ws_channel"
        private const val ACTION_LLM_ONLY = "LLM_ONLY"
        private const val ACTION_TYPELESS_ONLY = "TYPELESS_ONLY"
        private const val ACTION_STOP = "STOP"
        const val WS_PORT = 9528
        private const val WS_WATCHDOG_INTERVAL_MS = 5000L
        private const val MAX_CHUNK_CHARS = 500
        private const val MAX_LOG_ENTRIES = 200
        private val STT_NOISE_EXACT = setOf("你好", "嗯", "啊", "哦", "呃", "唉", "哎", "呢", "的啊")
        private val STT_NOISE_TAIL = listOf("嗯", "啊", "哦", "呃", "唉", "哎", "呢", "的啊")
        private val NOISE_CHARS = setOf('嗯', '啊', '哦', '呃', '唉', '哎', '呢')

        fun cleanSttNoise(text: String): String? {
            val t = text.trim()
            if (t.isEmpty() || t in STT_NOISE_EXACT) return null
            if (t.all { it in NOISE_CHARS }) return null
            var result = t
            var changed = true
            while (changed) {
                changed = false
                for (tail in STT_NOISE_TAIL) {
                    if (result.length > tail.length && result.endsWith(tail)) {
                        result = result.dropLast(tail.length)
                        changed = true
                        break
                    }
                }
            }
            if (result.isEmpty() || result in STT_NOISE_EXACT || result.all { it in NOISE_CHARS }) return null
            return result
        }

        fun startIntent(context: Context) = Intent(context, DictationService::class.java)
        fun llmIntent(context: Context) = Intent(context, DictationService::class.java)
            .also { it.action = ACTION_LLM_ONLY }
        fun typelessIntent(context: Context) = Intent(context, DictationService::class.java)
            .also { it.action = ACTION_TYPELESS_ONLY }
        fun stopIntent(context: Context)  = Intent(context, DictationService::class.java)
            .also { it.action = ACTION_STOP }
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@DictationService
    }
    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    var isRecording      = false; private set
    var connectedDevice: String? = null; private set
    var connectedClients = 0;     private set
    var onStateChanged: (() -> Unit)? = null

    data class InsertionRecord(
        val text: String, val clientId: String,
        val success: Boolean, val error: String?,
        val time: Long = System.currentTimeMillis(),
    )
    val insertionLog = mutableListOf<InsertionRecord>()

    data class QueryRecord(
        val text: String,
        val time: Long = System.currentTimeMillis(),
    )
    val queryLog = mutableListOf<QueryRecord>()

    var isRelayMode = false; private set
    var isTypelessMode = false; private set
    @Volatile var isSttPaused = false; private set
    private var pendingFragment = ""

    val relayQueue = mutableListOf<QueueItem>()

    /** Relay-local inbox for AI replies / transcriptions (relay mode only). */
    var relayInbox: RelayInboxStore? = null; private set

    fun pauseStt() {
        if (isSttPaused || !isRecording) return
        if (pendingFragment.isNotEmpty()) {
            splitAndBroadcast(pendingFragment, "暂停前残留")
            pendingFragment = ""
        }
        audioRecorder.stopRecording()
        isRecording = false
        isSttPaused = true
        Log.i(TAG, "STT 真暂停：录音已停止")
        updateNotification(buildStatus())
        onStateChanged?.invoke()
    }

    fun resumeStt() {
        if (!isSttPaused) return
        audioRecorder.startRecording()
        isRecording = true
        isSttPaused = false
        Log.i(TAG, "STT 恢复：录音已重启")
        updateNotification(buildStatus())
        onStateChanged?.invoke()
    }

    fun pauseLlm() {
        isLlmPaused = true
        Log.i(TAG, "LLM 已暂停")
        onStateChanged?.invoke()
    }

    fun resumeLlm() {
        isLlmPaused = false
        Log.i(TAG, "LLM 已恢复")
        onStateChanged?.invoke()
    }

    private lateinit var btRouter:      BluetoothAudioRouter
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var sherpaEngine:  SherpaSocketEngine
    lateinit var wsServer: DictationWebSocketServer
    private var wsClient: DictationWebSocketClient? = null
    private var pluginBridge: PluginBroadcastBridge? = null
    private var llmBridge: LlmBridge? = null

    /** Queries that had no live route; flushed FIFO when a link comes back. */
    private val pendingQueries = ArrayDeque<String>()
    private val maxPendingQueries = 20

    // ── LLM state ────────────────────────────────────────────────────────
    @Volatile var isLlmConnected = false; private set
    @Volatile var isLlmConnecting = false; private set
    @Volatile var lastLlmError: String? = null; private set
    @Volatile var isLlmPaused = false; private set
    // Streaming accumulation buffer
    private val llmStreamBuffer = StringBuilder()
    @Volatile private var llmStreamedAny = false

    // ── LLM priority: buffer STT text while a reply is generating ────────
    @Volatile var isLlmGenerating = false; private set
    private val sttDeferQueue = mutableListOf<String>()

    /**
     * Multi-reply fan-out in flight: branch texts arrive as one batch via the
     * blocking prefill-branches call; the SSE stream echoes the same content
     * as node updates meanwhile, so streaming delivery is suppressed.
     */
    @Volatile private var isMultiReplyInFlight = false

    /** Latest recognized query text, for the UI panel. */
    val lastQueryText: String get() = synchronized(queryLog) { queryLog.firstOrNull()?.text ?: "" }

    @Volatile private var recordingViaSco = false
    @Volatile var isStarted = false; private set

    private var llmWakeLock: PowerManager.WakeLock? = null

    private fun logInsertion(text: String, clientId: String, success: Boolean = true, error: String? = null) {
        synchronized(insertionLog) {
            insertionLog.add(0, InsertionRecord(text, clientId, success, error))
            if (insertionLog.size > MAX_LOG_ENTRIES) insertionLog.removeAt(insertionLog.size - 1)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        isRelayMode = resources.getBoolean(R.bool.is_relay)
        Log.i(TAG, "Service onCreate: isRelayMode=$isRelayMode")

        wsServer = DictationWebSocketServer(WS_PORT).apply {
            onClientConnected = { info ->
                connectedClients = connectedCount()
                Log.i(TAG, "插件连接: ${info.id}")
                updateNotification(buildStatus())
                onStateChanged?.invoke()
            }
            onClientDisconnected = { id ->
                connectedClients = connectedCount()
                Log.i(TAG, "插件断开: $id")
                updateNotification(buildStatus())
                onStateChanged?.invoke()
            }
            onAckReceived = { clientId, text, success, error ->
                logInsertion(text, clientId, success, error)
                onStateChanged?.invoke()
            }
            onQueryReceived = { text, mode, count ->
                synchronized(queryLog) {
                    queryLog.add(0, QueryRecord(text))
                    if (queryLog.size > MAX_LOG_ENTRIES) queryLog.removeAt(queryLog.size - 1)
                }
                mainHandler.post { onStateChanged?.invoke() }
                if (isLlmPaused) {
                    Log.i(TAG, "user_query dropped (LLM paused)")
                } else if (mode == "prefill_branches" && count > 0) {
                    // Relay already applied its prefill template — text is the
                    // ready-to-seed branch prefix.
                    Log.i(TAG, "user_query → LLM prefill×$count: ${text.take(50)}")
                    runMultiReply(text, count)
                } else {
                    Log.i(TAG, "user_query → LLM: ${text.take(50)}")
                    isLlmGenerating = true
                    llmStreamBuffer.clear()
                    llmStreamedAny = false
                    llmBridge?.sendUserMessage(text)
                }
            }
            onQueueSyncReceived = { arr ->
                synchronized(relayQueue) {
                    relayQueue.clear()
                    relayQueue.addAll(QueueItem.listFromJson(arr))
                }
                mainHandler.post { onStateChanged?.invoke() }
            }
        }

        if (!isRelayMode) {
            sherpaEngine = SherpaSocketEngine(
                host = AppPrefs.sttHost(this),
                port = AppPrefs.sttPort(this),
            ).apply {
                onResult = { text, isFinal ->
                    if (!isSttPaused) {
                        val cleaned = if (isFinal) cleanSttNoise(text) else null
                        if (cleaned != null) {
                            pendingFragment += cleaned
                            val lastSentenceEnd = TextChunker.lastSentenceEnd(pendingFragment)
                            if (lastSentenceEnd >= 0) {
                                val toSend = pendingFragment.substring(0, lastSentenceEnd + 1)
                                pendingFragment = pendingFragment.substring(lastSentenceEnd + 1).trimStart()
                                splitAndBroadcast(toSend, "STT", deferrable = true)
                                logInsertion(toSend, "STT")
                            } else if (pendingFragment.length > 200) {
                                splitAndBroadcast(pendingFragment, "STT-超长", deferrable = true)
                                pendingFragment = ""
                            } else {
                                Log.i(TAG, "片段累积: $pendingFragment")
                            }
                            mainHandler.post { onStateChanged?.invoke() }
                        }
                    }
                }
                onConnected    = { updateNotification(buildStatus()); onStateChanged?.invoke() }
                onDisconnected = { updateNotification(buildStatus()); onStateChanged?.invoke() }
            }

            btRouter = BluetoothAudioRouter(this).apply {
                onDeviceConnected = { name ->
                    connectedDevice = name
                    onStateChanged?.invoke()
                }
                onScoConnected = {
                    Log.i(TAG, "SCO 已连接，切换蓝牙麦克风")
                    if (isRecording && !recordingViaSco) {
                        audioRecorder.stopRecording()
                        isRecording = false
                    }
                    recordingViaSco = true
                    mainHandler.postDelayed({
                        audioRecorder.startRecording()
                        isRecording = true
                        updateNotification(buildStatus())
                        onStateChanged?.invoke()
                    }, 300)
                }
                onScoDisconnected = {
                    Log.i(TAG, "SCO 断开，降级至手机麦")
                    audioRecorder.stopRecording()
                    recordingViaSco = false
                    isRecording     = false
                    mainHandler.postDelayed({
                        audioRecorder.startRecording()
                        isRecording = true
                        updateNotification(buildStatus())
                        onStateChanged?.invoke()
                    }, 500)
                }
            }

            audioRecorder = AudioRecorder(transcriptionEngine = sherpaEngine)
        } else {
            relayInbox = RelayInboxStore(this).apply {
                load()
                onChanged = {
                    pushInboxToPlugin()
                    onStateChanged?.invoke()
                }
            }
            pluginBridge = PluginBroadcastBridge(this).apply {
                onAckReceived = { text, success, error ->
                    wsClient?.sendAck(text, success, error)
                    logInsertion(text, "Plugin", success, error)
                    onStateChanged?.invoke()
                }
                onQueueChanged = { list ->
                    wsClient?.sendQueueSync(QueueItem.listToJson(list))
                }
                onQueryReceived = { text -> routeRelayQuery(text) }
                onInsertPositionChanged = { page, top ->
                    Log.i(TAG, "Plugin insert position: page=$page top=$top")
                }
                onInsertRequest = { id -> insertInboxMessage(id) }
                onPluginAlive = { pushInboxToPlugin() }
                onImageQueryReceived = { imagePath, maskPath, prompt ->
                    Thread {
                        try {
                            sendImageQuery(AppPrefs.phoneIp(this@DictationService), imagePath, maskPath, prompt)
                        } catch (e: Exception) {
                            Log.e(TAG, "sendImageQuery failed: ${e.message}", e)
                        }
                    }.start()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand called, action=$action, isRelayMode=$isRelayMode")
        if (action == ACTION_STOP) {
            isStarted = false
            try { wsServer.stop() } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }
        if (isStarted) return START_STICKY

        val restartingLlmOnly = intent == null && AppPrefs.llmAutoStart(this)
        val foregroundType = when {
            action == ACTION_LLM_ONLY -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            action == ACTION_TYPELESS_ONLY -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            restartingLlmOnly -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            isRelayMode -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (!promoteToForeground(getString(R.string.btn_starting), foregroundType)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // LLM-only mode and its sticky restart need networking, not microphone access.
        if (action == ACTION_LLM_ONLY || restartingLlmOnly) {
            maybeAutoRestartLlmBridge()
            return START_STICKY
        }

        // Typeless mode: WebSocket server only, no microphone
        if (action == ACTION_TYPELESS_ONLY) {
            isTypelessMode = true
            if (!isRelayMode) {
                try {
                    wsServer.start()
                    Log.i(TAG, "Typeless mode: wsServer started on port $WS_PORT")
                } catch (e: IllegalStateException) {
                    Log.i(TAG, "Typeless mode: wsServer already running")
                } catch (e: Exception) {
                    Log.e(TAG, "Typeless mode: wsServer start failed: ${e.message}", e)
                }
            }
            updateNotification(buildStatus())
            onStateChanged?.invoke()
            return START_STICKY
        }

        if (isRelayMode) {
            Log.i(TAG, "Starting RELAY mode")
            pluginBridge?.start()
            val phoneIp = intent?.getStringExtra("PHONE_IP") ?: AppPrefs.phoneIp(this)
            setupWsClient(phoneIp)
            wsClient?.connect()
            Log.i(TAG, "Relay mode: wsClient.connect() 已调用 → ws://$phoneIp:$WS_PORT")
            mainHandler.removeCallbacks(wsWatchdog)
            mainHandler.postDelayed(wsWatchdog, WS_WATCHDOG_INTERVAL_MS)
        } else {
            Log.i(TAG, "Starting SERVER mode")
            btRouter.init()
            try {
                wsServer.start()
            } catch (e: IllegalStateException) {
                try { wsServer.stop(); Thread.sleep(200); wsServer.start() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Error starting wsServer: ${e.message}", e)
            }
            sherpaEngine.connect()
            btRouter.requestRoute()

            mainHandler.postDelayed({
                if (!isRecording) {
                    Log.i(TAG, "开始录音（手机麦兜底）")
                    recordingViaSco = false
                    audioRecorder.startRecording()
                    isRecording = true
                    updateNotification(buildStatus())
                    onStateChanged?.invoke()
                }
            }, 1800)
        }
        isStarted = true
        // Null-intent restart after a background kill: heal the LLM link.
        maybeAutoRestartLlmBridge()
        return START_STICKY
    }

    override fun onDestroy() {
        llmBridge?.stop()
        llmBridge = null
        llmWakeLock?.release()
        llmWakeLock = null
        if (!isRelayMode && pendingFragment.isNotEmpty()) {
            splitAndBroadcast(pendingFragment, "销毁前残留")
            pendingFragment = ""
        }
        mainHandler.removeCallbacksAndMessages(null)
        if (isRelayMode) {
            relayInbox?.flush()
            wsClient?.close()
            pluginBridge?.stop()
        } else {
            audioRecorder.stopRecording()
            sherpaEngine.disconnect()
            btRouter.release()
            wsServer.stop(500)
        }
        super.onDestroy()
        isStarted = false
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ── Broadcasting ─────────────────────────────────────────────────────

    fun manualBroadcast(text: String) {
        splitAndBroadcast(text, "手动")
    }

    /**
     * Chunk [text] and broadcast it to connected plugin clients. When
     * [deferrable] (STT-originated text) and a reply is generating, the text
     * is buffered and flushed after the reply completes.
     */
    private fun splitAndBroadcast(text: String, source: String, deferrable: Boolean = false) {
        val cleaned = text.trim().replace(Regex("\n{2,}"), "\n")
        if (cleaned.isEmpty()) return
        if (deferrable && isLlmGenerating) {
            synchronized(sttDeferQueue) { sttDeferQueue.add(cleaned) }
            Log.i(TAG, "STT deferred (LLM generating): ${cleaned.take(40)}")
            return
        }
        val chunks = TextChunker.splitAtBoundaries(cleaned, MAX_CHUNK_CHARS)
        for (chunk in chunks) {
            // Relay-direct mode: text produced locally (LLM replies) lands in
            // the relay inbox for the user to review; phone mode broadcasts
            // over WebSocket.
            if (isRelayMode) relayInbox?.append(chunk, normalizeSource(source))
            else wsServer.broadcastText(chunk, normalizeSource(source))
            Log.i(TAG, "${source}广播: ${chunk.length}字")
        }
    }

    /**
     * Drops a first line that is exactly a UUID ("<uuid>\n<body>" → "<body>").
     * Some phone-side LLM bridges leak a message/conversation id this way;
     * inserting that id into the note is wrong and the 36-char unbreakable
     * token also stresses the note lib's line-layout code.
     */
    private fun stripLeadingUuidLine(text: String): String {
        val nl = text.indexOf('\n')
        if (nl <= 0) return text
        val first = text.substring(0, nl).trim()
        val uuidRe = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        return if (uuidRe.matches(first)) text.substring(nl + 1) else text
    }

    /** Maps internal source labels to the wire-level "llm"/"stt"/"manual". */
    private fun normalizeSource(label: String): String = when {
        label.startsWith("LLM") -> "llm"
        label == "手动"          -> "manual"
        else                     -> "stt"
    }

    private fun flushSttDeferQueue() {
        val deferred = synchronized(sttDeferQueue) {
            val copy = sttDeferQueue.toList()
            sttDeferQueue.clear()
            copy
        }
        if (deferred.isNotEmpty()) {
            Log.i(TAG, "flush STT defer queue: ${deferred.size} 条")
            mainHandler.postDelayed({
                deferred.forEach { splitAndBroadcast(it, "STT-deferred") }
            }, 300)
        }
    }

    // ── Relay query routing ──────────────────────────────────────────────

    /**
     * Automatic routing, no manual mode switch: the phone owns the session
     * while its WebSocket link is up; the direct LLM link (VPS) is the
     * standing fallback; with neither up the query is queued and flushed on
     * the next reconnect of either link.
     */
    private fun routeRelayQuery(text: String) {
        val multi = AppPrefs.multiReply(this)
        if (wsClient?.isOpen == true) {
            try {
                if (multi) {
                    wsClient?.sendQuery(buildPrefill(text), "prefill_branches", AppPrefs.multiReplyCount(this))
                } else {
                    wsClient?.sendQuery(text)
                }
                Log.i(TAG, "query → phone (multi=$multi): ${text.take(50)}")
                return
            } catch (e: Exception) {
                Log.e(TAG, "sendQuery to phone failed: ${e.message}")
            }
        }
        if (llmBridge?.isConnected == true) {
            if (isLlmPaused) {
                Log.i(TAG, "user_query dropped (LLM paused)")
                return
            }
            Log.i(TAG, "query → LLM direct (multi=$multi): ${text.take(50)}")
            if (multi) {
                runMultiReply(buildPrefill(text), AppPrefs.multiReplyCount(this))
            } else {
                isLlmGenerating = true
                llmStreamBuffer.clear()
                llmStreamedAny = false
                llmBridge?.sendUserMessage(text)
            }
            return
        }
        Log.w(TAG, "no route up, queueing query and reconnecting")
        enqueuePendingQuery(text)
        wsClient?.reconnect()
    }

    private fun enqueuePendingQuery(text: String) {
        synchronized(pendingQueries) {
            pendingQueries.addLast(text)
            while (pendingQueries.size > maxPendingQueries) pendingQueries.removeFirst()
        }
    }

    /**
     * Re-route queued queries once a link is back. Drains a snapshot so a
     * query that fails again re-enqueues without looping.
     */
    private fun flushPendingQueries() {
        if (wsClient?.isOpen != true && llmBridge?.isConnected != true) return
        val drained = synchronized(pendingQueries) {
            val copy = pendingQueries.toList()
            pendingQueries.clear()
            copy
        }
        if (drained.isEmpty()) return
        Log.i(TAG, "flushing ${drained.size} pending queries")
        drained.forEach { routeRelayQuery(it) }
    }

    /** Applies the multi-reply prefill template to the query/selection text. */
    private fun buildPrefill(text: String): String {
        val tpl = AppPrefs.prefillTemplate(this)
        return if (tpl.contains("{text}")) tpl.replace("{text}", text) else tpl + text
    }

    /**
     * Multi-reply on the local LLM link: fan [prefill] out into [count]
     * assistant branches; each finished branch lands as its own inbox message
     * (relay) or is broadcast as its own turn (phone).
     */
    private fun runMultiReply(prefill: String, count: Int) {
        isLlmGenerating = true
        isMultiReplyInFlight = true
        llmBridge?.sendPrefillBranches(count, prefill) { texts ->
            Log.i(TAG, "multi-reply done: ${texts.size}/$count branches")
            texts.forEach { t ->
                splitAndBroadcast(t, "LLM")
                logInsertion(t, "LLM")
                if (isRelayMode) relayInbox?.closeHead("llm") else wsServer.broadcastTurnEnd("llm")
            }
            isMultiReplyInFlight = false
            isLlmGenerating = false
            flushSttDeferQueue()
            mainHandler.post { onStateChanged?.invoke() }
        }
    }

    // ── LLM bridge ───────────────────────────────────────────────────────

    fun startLlmBridge(llmHost: String, conversationId: String? = null) {
        llmBridge?.stop()
        isLlmConnected = false
        isLlmConnecting = true
        lastLlmError = null
        isLlmPaused = false
        AppPrefs.setLlmAutoStart(this, true)
        mainHandler.post { onStateChanged?.invoke() }

        // A REST/SSE bridge is a data-transfer workload. Promoting it as a
        // microphone FGS makes Android 14+ reject the service when no recording
        // permission/while-in-use eligibility is available.
        if (!isStarted && !promoteToForeground(
                getString(R.string.notif_llm_connecting),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )) {
            isLlmConnecting = false
            lastLlmError = getString(R.string.llm_error_connect_failed)
            mainHandler.post { onStateChanged?.invoke() }
            return
        }

        // Only leave our activity after foreground promotion has succeeded.
        maybeRequestBatteryExemption()

        // Hold a CPU wake lock so background sleep doesn't cut the SSE stream.
        llmWakeLock?.release()
        llmWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DictationService:Llm")
            .also { it.acquire() }

        if (!isRelayMode) {
            try {
                wsServer.start()
                Log.i(TAG, "startLlmBridge: wsServer started for relay connectivity")
            } catch (e: IllegalStateException) {
                Log.i(TAG, "startLlmBridge: wsServer already running")
            } catch (e: Exception) {
                Log.w(TAG, "startLlmBridge: wsServer start failed: ${e.message}")
            }
        }

        val baseUrl = HttpJson.normalizeBaseUrl(llmHost, SseLlmBridge.DEFAULT_PORT)
        HttpJson.authToken = AppPrefs.llmToken(this).ifEmpty { null }
        Log.i(TAG, "Connecting to LLM server: $baseUrl conversationId=$conversationId auth=${HttpJson.authToken != null}")

        Thread {
            // Use the caller-specified conversation, else the active one.
            val activeId = conversationId
                ?: HttpJson.getJsonOrNull("$baseUrl/api/conversations/active")
                    ?.optString("conversationId", "")?.ifEmpty { null }

            llmBridge = SseLlmBridge(baseUrl).apply {
                onStreamToken = { token ->
                    if (!isLlmPaused && !isMultiReplyInFlight) {
                        isLlmGenerating = true
                        llmStreamBuffer.append(token)
                        llmStreamedAny = true
                        if (llmStreamBuffer.length >= 500) {
                            val buf = llmStreamBuffer.toString()
                            val boundary = TextChunker.lastSentenceEnd(buf)
                            val cutAt = if (boundary >= 0) boundary + 1 else buf.length
                            val ready = buf.substring(0, cutAt)
                            llmStreamBuffer.delete(0, cutAt)
                            splitAndBroadcast(ready, "LLM-stream")
                            mainHandler.post { onStateChanged?.invoke() }
                        }
                    }
                }
                onReplyComplete = { text ->
                    if (!isLlmPaused && !isMultiReplyInFlight) {
                        Log.i(TAG, "LLM reply: ${text.take(80)}")
                        if (llmStreamedAny) {
                            val remaining = llmStreamBuffer.toString().trim()
                            if (remaining.isNotEmpty()) splitAndBroadcast(remaining, "LLM-stream-tail")
                            llmStreamBuffer.clear()
                            llmStreamedAny = false
                        } else {
                            splitAndBroadcast(text, "LLM")
                        }
                        logInsertion(text, "LLM")
                        // Close the streamed turn: locally on the relay, or
                        // notify connected relays from the phone.
                        if (isRelayMode) relayInbox?.closeHead("llm")
                        else wsServer.broadcastTurnEnd("llm")
                        isLlmGenerating = false
                        flushSttDeferQueue()
                        mainHandler.post { onStateChanged?.invoke() }
                    }
                }
                onConnectionChanged = { connected ->
                    isLlmConnected = connected
                    isLlmConnecting = false
                    if (!connected) lastLlmError = getString(R.string.llm_error_connect_failed)
                    updateNotification(buildStatus())
                    mainHandler.post { onStateChanged?.invoke() }
                    // The fallback link coming up may unblock queued queries.
                    if (connected && isRelayMode) flushPendingQueries()
                }
            }
            llmBridge?.start(activeId)
            AppPrefs.setLlmHost(this, llmHost)
        }.also { it.name = "LlmBridge-connect"; it.isDaemon = true; it.start() }
    }

    fun getLlmConversationId(): String? = llmBridge?.getConversationId()

    /** Record [text] as a final assistant turn on the current conversation (no completion). */
    fun appendAssistantMessage(text: String) {
        llmBridge?.appendAssistantMessage(text)
    }

    fun stopLlmBridge() {
        // User-initiated stop: don't resurrect the bridge on service restart.
        AppPrefs.setLlmAutoStart(this, false)
        llmBridge?.stop()
        llmBridge = null
        isLlmConnected = false
        isLlmGenerating = false
        llmWakeLock?.release()
        llmWakeLock = null
        flushSttDeferQueue()
        onStateChanged?.invoke()
    }

    /**
     * The system killed us while the LLM bridge was up (background retention
     * on phones is poor); START_STICKY restarted the service with a null
     * intent — bring the bridge back without user interaction so the
     * phone ↔ AI-client ↔ relay chain heals itself.
     */
    private fun maybeAutoRestartLlmBridge() {
        if (llmBridge != null) return
        // The relay treats a configured LLM host (VPS) as a standing fallback
        // link and always brings it up; the phone keeps opt-in semantics.
        if (!isRelayMode && !AppPrefs.llmAutoStart(this)) return
        val host = AppPrefs.llmHost(this)
        if (host.isEmpty()) return
        Log.i(TAG, "auto-restarting LLM bridge after service restart: $host")
        startLlmBridge(host)
    }

    /**
     * One-shot prompt for the battery-optimization exemption: without it the
     * phone reclaims the FGS within minutes and the SSE/WS links die.
     */
    private fun maybeRequestBatteryExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            Log.i(TAG, "requested battery-optimization exemption")
        } catch (e: Exception) {
            Log.w(TAG, "battery exemption request failed: ${e.message}")
        }
    }

    // ── Image query (relay → LLM server on the phone) ────────────────────

    private fun sendImageQuery(phoneIp: String, imagePath: String, maskPath: String, prompt: String) {
        val baseUrl = HttpJson.normalizeBaseUrl(phoneIp, SseLlmBridge.DEFAULT_PORT)

        // 1. Crop to the lasso bounding box
        val imageBytes = cropImageWithMask(imagePath, maskPath)
        if (imageBytes == null) {
            Log.e(TAG, "cropImageWithMask returned null, aborting")
            return
        }

        // 2. Upload the image
        val boundary = "----DictationBoundary${System.currentTimeMillis()}"
        val uploadConn = java.net.URL("$baseUrl/api/files/upload").openConnection() as java.net.HttpURLConnection
        val fileUrl: String
        try {
            uploadConn.requestMethod = "POST"
            uploadConn.doOutput = true
            uploadConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            uploadConn.connectTimeout = 10_000
            uploadConn.readTimeout = 15_000

            uploadConn.outputStream.use { out ->
                val crlf = "\r\n"
                val writer = out.writer(Charsets.UTF_8)
                writer.write("--$boundary$crlf")
                writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"lasso.png\"$crlf")
                writer.write("Content-Type: image/png$crlf$crlf")
                writer.flush()
                out.write(imageBytes)
                out.flush()
                writer.write("$crlf--$boundary--$crlf")
                writer.flush()
            }

            val uploadCode = uploadConn.responseCode
            val uploadBody = uploadConn.inputStream.bufferedReader().readText()
            Log.i(TAG, "upload response $uploadCode: ${uploadBody.take(200)}")
            if (uploadCode != 201) {
                Log.e(TAG, "Upload failed: $uploadCode")
                return
            }
            val url = org.json.JSONObject(uploadBody).optJSONArray("files")
                ?.optJSONObject(0)?.optString("url")
            if (url.isNullOrEmpty()) {
                Log.e(TAG, "No file url in upload response")
                return
            }
            fileUrl = url
        } finally {
            uploadConn.disconnect()
        }
        Log.i(TAG, "Uploaded image url: $fileUrl")

        // 3. Resolve the active conversation
        val conversationId = HttpJson.getJsonOrNull("$baseUrl/api/conversations/active")
            ?.optString("conversationId", "")?.ifEmpty { null }
        if (conversationId == null) {
            Log.e(TAG, "No active conversation for image query")
            return
        }

        // 4. Send the multimodal message
        val parts = org.json.JSONArray().apply {
            put(org.json.JSONObject().put("type", "image").put("url", fileUrl))
            if (prompt.isNotBlank()) {
                put(org.json.JSONObject().put("type", "text").put("text", prompt))
            }
        }
        val resp = HttpJson.postJson(
            "$baseUrl/api/conversations/$conversationId/messages",
            org.json.JSONObject().put("parts", parts),
            10_000,
        )
        Log.i(TAG, "sendImageQuery message response: ${resp.take(200)}")
    }

    private fun cropImageWithMask(imagePath: String, maskPath: String): ByteArray? {
        val src = android.graphics.BitmapFactory.decodeFile(imagePath) ?: run {
            Log.e(TAG, "Cannot decode image: $imagePath")
            return null
        }

        val box = if (maskPath.isNotEmpty()) {
            try {
                val json = org.json.JSONObject(java.io.File(maskPath).readText())
                val bb = json.optJSONObject("boundingBox")
                if (bb != null) {
                    android.graphics.Rect(
                        bb.optInt("x").coerceAtLeast(0),
                        bb.optInt("y").coerceAtLeast(0),
                        (bb.optInt("x") + bb.optInt("w")).coerceAtMost(src.width),
                        (bb.optInt("y") + bb.optInt("h")).coerceAtMost(src.height)
                    )
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Cannot parse mask, using full image: ${e.message}")
                null
            }
        } else null

        val cropped = if (box != null && box.width() > 0 && box.height() > 0) {
            android.graphics.Bitmap.createBitmap(src, box.left, box.top, box.width(), box.height())
        } else {
            src
        }

        val out = java.io.ByteArrayOutputStream()
        cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        if (cropped !== src) cropped.recycle()
        src.recycle()
        return out.toByteArray()
    }

    // ── Relay WebSocket client ───────────────────────────────────────────

    fun reconnectRelay(ip: String) {
        if (!isRelayMode) return
        Log.i(TAG, "reconnectRelay to $ip")
        AppPrefs.setPhoneIp(this, ip)
        wsClient?.close()
        setupWsClient(ip)
        wsClient?.connect()
    }

    private fun setupWsClient(phoneIp: String) {
        Log.i(TAG, "setupWsClient: ws://$phoneIp:$WS_PORT")
        wsClient = DictationWebSocketClient("ws://$phoneIp:$WS_PORT").apply {
            onTextReceived = { text, source ->
                Log.i(TAG, "Text from phone[$source]: ${text.take(50)}")
                // Defensive: some phone-side bridges prepend a UUID id line
                // ("<uuid>\n<body>"). It must never reach the inbox/note.
                val body = stripLeadingUuidLine(text)
                // Into the relay inbox — insertion now waits for the user.
                relayInbox?.append(body, source)
            }
            onTurnEnd = { source ->
                relayInbox?.closeHead(source)
            }
            onQueueActionReceived = { payload ->
                try {
                    val json = org.json.JSONObject(payload)
                    if (json.optString("action") == "replace") {
                        val arr = json.optJSONArray("queue") ?: org.json.JSONArray()
                        pluginBridge?.replaceQueue(QueueItem.listFromJson(arr))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed parsing queue_action payload", e)
                }
            }
            onConnectionChanged = { connected ->
                Log.i(TAG, "Connection changed: $connected")
                connectedClients = if (connected) 1 else 0
                updateNotification(buildStatus())
                onStateChanged?.invoke()

                if (connected) flushPendingQueries()
                // Reconnection is owned by wsWatchdog (periodic, survives
                // reconnect() throwing) — no per-event chain here.
            }
        }
    }

    /**
     * Relay-side link watchdog. The old event-chained reconnect died forever
     * if a single reconnect() threw; this self-rescheduling check cannot be
     * broken by a failed attempt, so the relay always heals once the phone
     * service comes back (e.g. after a background kill + STICKY restart).
     */
    private val wsWatchdog = object : Runnable {
        override fun run() {
            if (!isRelayMode || !isStarted) return
            try {
                if (wsClient?.isClosed == true) {
                    Log.i(TAG, "wsWatchdog: link down, reconnecting")
                    wsClient?.reconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "wsWatchdog: reconnect failed: ${e.message}")
            }
            mainHandler.postDelayed(this, WS_WATCHDOG_INTERVAL_MS)
        }
    }

    // ── Relay inbox (relay mode only) ────────────────────────────────────

    /** Number of capsule previews pushed to the plugin. */
    private val INBOX_CAPSULE_COUNT = 4
    private val INBOX_PREVIEW_CHARS = 240

    /** User-triggered: insert inbox message [id] into the note via the plugin. */
    fun insertInboxMessage(id: String) {
        val msg = relayInbox?.get(id) ?: run {
            Log.w(TAG, "insertInboxMessage: id not found: $id")
            return
        }
        Log.i(TAG, "insertInboxMessage[${msg.source}]: ${msg.text.take(50)}")
        insertInboxSelection(id, msg.text)
    }

    /** Insert only the selected payload from an inbox message, then mark it done. */
    fun insertInboxSelection(id: String, text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "insertInboxSelection: blank payload for id=$id")
            return
        }
        Log.i(TAG, "insertInboxSelection: id=$id textLen=${text.length}")
        pluginBridge?.sendTextToPlugin(text)
        relayInbox?.markInserted(id)
    }

    fun pinInboxMessage(id: String, pinned: Boolean) { relayInbox?.setPinned(id, pinned) }

    /** Re-add messages to the plugin capsules: pin them and clear inserted. */
    fun readdInboxMessages(ids: Collection<String>) { relayInbox?.readd(ids) }

    fun deleteInboxMessage(id: String) { relayInbox?.delete(id) }

    fun deleteInboxMessages(ids: Collection<String>) { relayInbox?.deleteMany(ids) }

    fun mergeInboxMessages(ids: Collection<String>) { relayInbox?.merge(ids) }

    fun clearInbox() { relayInbox?.clear() }

    /** Push the newest previews to the plugin's capsule bars. */
    private fun pushInboxToPlugin() {
        val inbox = relayInbox ?: return
        val items = org.json.JSONArray()
        // Capsules = user-pinned messages first, then the newest arrivals to
        // fill up to the cap; inserted ones never show. Snapshot is
        // newest-first; capsules stack in arrival order (oldest of the shown
        // ones at the top, new ones appended below) → reverse.
        val visible = inbox.snapshot().filter { !it.inserted }
        val chosen = (visible.filter { it.pinned } + visible.filter { !it.pinned })
            .take(INBOX_CAPSULE_COUNT)
            .sortedByDescending { it.updatedAt }
        chosen.reversed().forEach { m ->
            items.put(org.json.JSONObject().apply {
                put("id", m.id)
                put("title", m.title.take(80))
                put("preview", m.text.take(INBOX_PREVIEW_CHARS))
                put("source", m.source)
                put("time", m.updatedAt)
                put("pinned", m.pinned)
            })
        }
        pluginBridge?.sendInboxToPlugin(items.toString())
    }

    fun sendRelayQueueReplace(newQueue: List<QueueItem>) {
        if (isRelayMode) return
        val payload = org.json.JSONObject().apply {
            put("type", "queue_action")
            put("action", "replace")
            put("queue", QueueItem.listToJson(newQueue))
        }.toString()
        wsServer.sendQueueAction(payload)

        synchronized(relayQueue) {
            relayQueue.clear()
            relayQueue.addAll(newQueue)
        }
        onStateChanged?.invoke()
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun promoteToForeground(text: String, foregroundType: Int): Boolean {
        if (
            foregroundType == ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Cannot start microphone foreground service: RECORD_AUDIO is not granted")
            return false
        }

        return try {
            val notification = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isStarted = true
            val typeName = when (foregroundType) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE -> "microphone"
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC -> "dataSync"
                else -> foregroundType.toString()
            }
            Log.i(TAG, "promoted to foreground service: type=$typeName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.notif_channel_desc) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_delete, getString(R.string.notif_stop), stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildStatus(): String {
        if (isRelayMode) {
            val conn = if (connectedClients > 0) getString(R.string.status_phone_connected)
                       else getString(R.string.status_waiting)
            return getString(R.string.status_relay_mode, conn)
        }
        if (isTypelessMode && !isRecording) {
            val llm = if (isLlmConnected) " | LLM✓" else ""
            return getString(R.string.status_typeless_mode, llm, connectedClients)
        }
        val mic = when {
            isSttPaused                    -> getString(R.string.status_stt_paused)
            isRecording && recordingViaSco -> getString(R.string.status_mic_device, connectedDevice ?: getString(R.string.status_bt_mic))
            isRecording                    -> getString(R.string.status_phone_mic)
            else                           -> getString(R.string.status_idle)
        }
        val llm = when {
            isLlmConnected && isLlmPaused -> " | LLM⏸"
            isLlmConnected                -> " | LLM✓"
            else                          -> ""
        }
        return getString(R.string.status_main, mic, llm, connectedClients)
    }
}
