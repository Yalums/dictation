package com.dictation.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dictation.server.core.AppPrefs
import com.dictation.server.model.QueueItem
import com.dictation.server.net.HttpJson
import com.dictation.server.llm.SseLlmBridge
import com.dictation.server.service.DictationService
import com.dictation.server.ui.*
import com.dictation.server.ws.DictationWebSocketServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Phone-flavor monitor UI, built entirely from the shared e-ink kit (ui/Eink.kt).

@Composable
fun ServerMonitorScreen(
    isBound: Boolean,
    service: DictationService?,
    stateVersion: Int,
    serverIp: String,
    onStartStt: () -> Unit,
    onPauseStt: () -> Unit,
    onResumeStt: () -> Unit,
    onConnectLlm: (host: String, conversationId: String?) -> Unit,
    onPauseLlm: () -> Unit,
    onResumeLlm: () -> Unit,
    onStop: () -> Unit,
    onManualSend: (String) -> Unit,
    onStartTypeless: () -> Unit = {},
) {
    @Suppress("UNUSED_EXPRESSION") stateVersion

    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showLlmDialog by remember { mutableStateOf(false) }
    var showSttDialog by remember { mutableStateOf(false) }
    var llmHostText by remember { mutableStateOf(AppPrefs.llmHost(context).ifEmpty { "localhost" }) }

    val sttRunning = isBound && service?.isRecording == true
    val sttPaused  = service?.isSttPaused == true
    val llmConnected = service?.isLlmConnected == true
    val llmConnecting = service?.isLlmConnecting == true
    val llmError = service?.lastLlmError
    val llmPaused = service?.isLlmPaused == true

    var sttStarting by remember { mutableStateOf(false) }
    LaunchedEffect(sttRunning) { if (sttRunning) sttStarting = false }

    Column(Modifier.fillMaxSize().background(Paper)) {

        ServerStatusBar(
            isBound = isBound,
            isRecording = service?.isRecording ?: false,
            connectedDevice = service?.connectedDevice,
            clients = service?.connectedClients ?: 0,
            serverIp = serverIp,
            port = DictationService.WS_PORT,
            llmConnected = llmConnected,
            onOpenSttSettings = { showSttDialog = true },
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── 转录按钮 ─────────────────────────────────────────────
            when {
                sttStarting -> EinkButton(stringResource(R.string.btn_starting), onClick = {}, enabled = false, modifier = Modifier.weight(1f))
                !isBound || (!sttRunning && !sttPaused) ->
                    EinkButton(stringResource(R.string.btn_start_stt), primary = true, onClick = { sttStarting = true; onStartStt() }, modifier = Modifier.weight(1f))
                sttPaused -> EinkButton(stringResource(R.string.btn_resume_stt), primary = true, onClick = onResumeStt, modifier = Modifier.weight(1f))
                else -> EinkButton(stringResource(R.string.btn_pause_stt), onClick = onPauseStt, modifier = Modifier.weight(1f))
            }

            // ── LLM 按钮 ─────────────────────────────────────────────
            when {
                llmConnecting -> EinkButton(stringResource(R.string.btn_connecting), onClick = {}, enabled = false, modifier = Modifier.weight(1f))
                !llmConnected -> EinkButton(
                    if (llmError != null) stringResource(R.string.btn_reconnect_llm) else stringResource(R.string.btn_connect_llm),
                    onClick = { showLlmDialog = true }, modifier = Modifier.weight(1f),
                )
                llmPaused -> EinkButton(stringResource(R.string.btn_resume_llm), primary = true, onClick = onResumeLlm, modifier = Modifier.weight(1f))
                else -> EinkButton(stringResource(R.string.btn_pause_llm), onClick = onPauseLlm, modifier = Modifier.weight(1f))
            }

            // ── 停止服务 ─────────────────────────────────────────────
            EinkButton(stringResource(R.string.btn_stop_service), onClick = onStop, enabled = isBound, modifier = Modifier.weight(1f))
        }

        // LLM 连接失败提示
        if (llmError != null && !llmConnected && !llmConnecting) {
            Text(
                stringResource(R.string.warn_prefix, llmError),
                fontSize = 12.sp,
                color = Ink,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        if (showLlmDialog) {
            LlmConnectDialog(
                initialHost = llmHostText,
                currentConversationId = service?.getLlmConversationId(),
                onDismiss = { showLlmDialog = false },
                onConnect = { host, convId ->
                    llmHostText = host
                    onConnectLlm(host, convId)
                    showLlmDialog = false
                },
            )
        }

        if (showSttDialog) {
            SttEndpointDialog(onDismiss = { showSttDialog = false })
        }

        if (isBound) {
            SttPanel(
                isRecording = sttRunning,
                lastQuery = service?.lastQueryText ?: "",
            )
        }

        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            EinkSegmented(
                labels = listOf(
                    stringResource(R.string.tab_plugins),
                    stringResource(R.string.tab_typeless),
                    stringResource(R.string.tab_relay_queue),
                ),
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )
        }

        when (selectedTab) {
            0 -> ClientAndLogTab(
                clients = service?.wsServer?.clientSnapshot() ?: emptyList(),
                log = service?.insertionLog?.toList() ?: emptyList(),
            )
            1 -> TypelessInputTab(
                wsReady = isBound && service?.isStarted == true,
                onSend = onManualSend,
                onStartTypeless = onStartTypeless,
            )
            2 -> RelayQueueTab(
                queue = service?.relayQueue?.toList() ?: emptyList(),
                onUpdate = { service?.sendRelayQueueReplace(it) },
                onManualSend = { text -> onManualSend(text) },
            )
        }
    }
}

@Composable
fun ServerStatusBar(
    isBound: Boolean, isRecording: Boolean, connectedDevice: String?,
    clients: Int, serverIp: String, port: Int,
    llmConnected: Boolean = false,
    onOpenSttSettings: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Paper)
            .bottomLine()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.server_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EinkChip(if (isBound) stringResource(R.string.status_running) else stringResource(R.string.status_not_started), filled = isBound)
            EinkChip(if (isRecording) stringResource(R.string.status_recording) else stringResource(R.string.status_stopped), filled = isRecording)
            if (llmConnected) EinkChip(stringResource(R.string.status_llm_ok), filled = true)
            EinkChip(stringResource(R.string.status_plugins, clients), filled = clients > 0)
            if (onOpenSttSettings != null) {
                EinkChip(stringResource(R.string.btn_stt_settings), onClick = onOpenSttSettings)
            }
        }
        Text(
            stringResource(R.string.ws_line, serverIp, port, connectedDevice ?: stringResource(R.string.waiting_bt_mic)),
            fontSize = 13.sp,
            color = Muted,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun ClientAndLogTab(
    clients: List<DictationWebSocketServer.ClientInfo>,
    log: List<DictationService.InsertionRecord>,
) {
    LazyColumn(Modifier.fillMaxSize().background(Paper), contentPadding = PaddingValues(12.dp)) {
        // ── 插件客户端 ──
        if (clients.isEmpty()) {
            item {
                Text(stringResource(R.string.no_plugin_clients), color = Muted, modifier = Modifier.padding(bottom = 8.dp))
            }
        } else {
            items(clients, key = { it.id }) { c ->
                EinkCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(c.id, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
                        EinkChip(c.clientType)
                    }
                    c.lastAck?.let { ack ->
                        val ok = c.lastAckSuccess ?: false
                        Text(
                            stringResource(
                                R.string.ack_line,
                                if (ok) stringResource(R.string.ack_inserted) else stringResource(R.string.ack_failed),
                                ack,
                            ),
                            fontSize = 13.sp, color = Ink,
                        )
                        c.lastAckError?.let { err ->
                            Text(stringResource(R.string.error_prefix, err), fontSize = 12.sp, color = Muted)
                        }
                    } ?: Text(stringResource(R.string.waiting_first_ack), fontSize = 12.sp, color = Muted)
                }
            }
        }

        // ── 插入记录 ──
        if (log.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.insertion_log),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
            }
            items(log, key = { "${it.time}_${it.text.hashCode()}" }) { r ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        .einkBorder().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.text, fontSize = 14.sp, color = Ink)
                        Text(
                            buildString { append(r.clientId); r.error?.let { append(" · $it") } },
                            fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        if (r.success) "✓" else "✗",
                        color = Ink,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun TypelessInputTab(
    wsReady: Boolean,
    onSend: (String) -> Unit,
    onStartTypeless: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(Paper).imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.typeless_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
            if (wsReady) {
                EinkButton(
                    stringResource(R.string.btn_send),
                    primary = true,
                    enabled = text.isNotBlank(),
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend(text.trim())
                            text = ""
                        }
                    },
                )
            }
        }

        if (!wsReady) {
            Text(
                stringResource(R.string.typeless_hint_not_ready),
                fontSize = 12.sp,
                color = Muted,
            )
            EinkButton(stringResource(R.string.btn_start_typeless), primary = true, onClick = onStartTypeless, modifier = Modifier.fillMaxWidth())
        } else {
            Text(
                stringResource(R.string.typeless_hint_ready),
                fontSize = 12.sp,
                color = Muted,
            )
        }

        EinkTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = stringResource(R.string.typeless_field_label),
            singleLine = false,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
fun SttPanel(isRecording: Boolean = false, lastQuery: String = "") {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .einkBorder()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            lastQuery.isNotEmpty() -> Text(
                lastQuery,
                fontSize = 13.sp,
                color = Ink,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            isRecording -> Text(stringResource(R.string.stt_recording), fontSize = 12.sp, color = Muted)
            else -> Text(stringResource(R.string.stt_waiting), fontSize = 12.sp, color = Muted)
        }
    }
}

// ── STT endpoint settings ─────────────────────────────────────────────────

@Composable
fun SttEndpointDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var endpointText by remember {
        mutableStateOf("${AppPrefs.sttHost(context)}:${AppPrefs.sttPort(context)}")
    }
    val parsed = remember(endpointText) {
        val parts = endpointText.trim().split(":")
        val host = parts.getOrNull(0)?.trim().orEmpty()
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (host.isNotEmpty() && (parts.size == 1 || port != null)) {
            host to (port ?: AppPrefs.DEFAULT_STT_PORT)
        } else null
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Paper)
                .einkBorder()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.stt_endpoint_title), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(stringResource(R.string.stt_endpoint_hint), fontSize = 12.sp, color = Muted)
            EinkTextField(
                value = endpointText,
                onValueChange = { endpointText = it },
                placeholder = "${AppPrefs.DEFAULT_STT_HOST}:${AppPrefs.DEFAULT_STT_PORT}",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EinkButton(stringResource(R.string.btn_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                EinkButton(
                    stringResource(R.string.btn_save),
                    primary = true,
                    enabled = parsed != null,
                    onClick = {
                        parsed?.let { (host, port) -> AppPrefs.setSttEndpoint(context, host, port) }
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── LLM connection dialog ─────────────────────────────────────────────────

data class ConversationItem(val id: String, val title: String, val updateAt: Long)

private fun fetchConversations(baseUrl: String): List<ConversationItem>? {
    return try {
        val body = HttpJson.get("$baseUrl/api/conversations") ?: return null
        val arr = org.json.JSONArray(body)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            ConversationItem(
                id = obj.optString("id"),
                title = obj.optString("title").ifBlank { "新对话" },
                updateAt = obj.optLong("updateAt"),
            )
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
fun LlmConnectDialog(
    initialHost: String,
    currentConversationId: String?,
    onDismiss: () -> Unit,
    onConnect: (host: String, conversationId: String?) -> Unit,
) {
    var hostText by remember { mutableStateOf(initialHost) }
    var passwordText by remember { mutableStateOf("") }
    var conversations by remember { mutableStateOf<List<ConversationItem>?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var isFetching by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(currentConversationId) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // JWT-protected server: trade the password for a token once and persist it.
    fun ensureTokenBlocking(baseUrl: String) {
        if (passwordText.isBlank()) {
            // No password entered — reuse a previously stored token, if any.
            HttpJson.authToken = AppPrefs.llmToken(context).ifEmpty { null }
            return
        }
        HttpJson.fetchToken(baseUrl, passwordText)?.let { token ->
            HttpJson.authToken = token
            AppPrefs.setLlmToken(context, token)
        }
    }

    fun doFetch() {
        if (hostText.isBlank()) return
        val baseUrl = HttpJson.normalizeBaseUrl(hostText, SseLlmBridge.DEFAULT_PORT)
        isFetching = true
        fetchError = null
        conversations = null
        scope.launch(Dispatchers.IO) {
            ensureTokenBlocking(baseUrl)
            val result = fetchConversations(baseUrl)
            withContext(Dispatchers.Main) {
                isFetching = false
                if (result == null) {
                    fetchError = context.getString(R.string.fetch_failed)
                } else {
                    conversations = result
                    if (selectedId == null || result.none { it.id == selectedId }) {
                        selectedId = result.firstOrNull()?.id
                    }
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Paper)
                .einkBorder()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.btn_connect_llm), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EinkTextField(
                    value = hostText,
                    onValueChange = {
                        hostText = it
                        conversations = null
                        fetchError = null
                    },
                    placeholder = stringResource(R.string.ip_placeholder),
                    modifier = Modifier.weight(1f),
                )
                EinkButton(
                    if (isFetching) "…" else stringResource(R.string.btn_fetch),
                    enabled = hostText.isNotBlank() && !isFetching,
                    onClick = { doFetch() },
                )
            }

            EinkTextField(
                value = passwordText,
                onValueChange = { passwordText = it },
                placeholder = stringResource(R.string.llm_password_hint),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.connect_to, HttpJson.normalizeBaseUrl(hostText, SseLlmBridge.DEFAULT_PORT)),
                fontSize = 11.sp,
                color = Muted,
            )

            if (fetchError != null) {
                Text(stringResource(R.string.warn_prefix, fetchError!!), fontSize = 12.sp, color = Ink, fontWeight = FontWeight.Bold)
            }

            val convList = conversations
            if (convList != null) {
                Text(stringResource(R.string.select_conversation, convList.size), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(convList, key = { it.id }) { conv ->
                        val isSelected = conv.id == selectedId
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .einkBorder()
                                .background(if (isSelected) Ink else Paper, RectangleShape)
                                .clickable { selectedId = conv.id }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                conv.title,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Paper else Ink,
                                maxLines = 1,
                            )
                            Text(
                                "…${conv.id.takeLast(8)}",
                                fontSize = 10.sp,
                                color = if (isSelected) Paper else Muted,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EinkButton(stringResource(R.string.btn_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                EinkButton(
                    stringResource(R.string.btn_connect),
                    primary = true,
                    enabled = hostText.isNotBlank(),
                    onClick = {
                        val host = hostText.trim()
                        val convId = selectedId
                        scope.launch(Dispatchers.IO) {
                            ensureTokenBlocking(HttpJson.normalizeBaseUrl(host, SseLlmBridge.DEFAULT_PORT))
                            withContext(Dispatchers.Main) { onConnect(host, convId) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── Relay queue tab ───────────────────────────────────────────────────────

@Composable
fun RelayQueueTab(
    queue: List<QueueItem>,
    onUpdate: (List<QueueItem>) -> Unit,
    onManualSend: (String) -> Unit = {},
) {
    var sendText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Paper).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EinkTextField(
                value = sendText,
                onValueChange = { sendText = it },
                placeholder = stringResource(R.string.manual_send_label),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            EinkButton(
                stringResource(R.string.btn_send),
                primary = true,
                enabled = sendText.isNotBlank(),
                onClick = {
                    if (sendText.isNotBlank()) {
                        onManualSend(sendText)
                        sendText = ""
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        if (queue.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.relay_queue_empty), color = Muted)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(count = queue.size, key = { queue[it].id }) { i ->
                    val item = queue[i]
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .einkBorder().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.text, fontSize = 14.sp, color = Ink)
                            val age = (System.currentTimeMillis() - item.sentAt) / 1000
                            Text(stringResource(R.string.queue_item_meta, item.retryCount, age), fontSize = 11.sp, color = Muted)
                        }
                        EinkIconButton("↑", Modifier.padding(start = 6.dp)) {
                            if (i > 0) {
                                val newList = queue.toMutableList()
                                java.util.Collections.swap(newList, i, i - 1)
                                onUpdate(newList)
                            }
                        }
                        EinkIconButton("↓", Modifier.padding(start = 6.dp)) {
                            if (i < queue.size - 1) {
                                val newList = queue.toMutableList()
                                java.util.Collections.swap(newList, i, i + 1)
                                onUpdate(newList)
                            }
                        }
                        EinkIconButton("✗", Modifier.padding(start = 6.dp)) {
                            val newList = queue.toMutableList()
                            newList.removeAt(i)
                            onUpdate(newList)
                        }
                    }
                }
            }
        }
    }
}
