package com.dictation.server.ws

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class DictationWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    init {
        isReuseAddr = true
    }

    data class ClientInfo(
        val id: String,
        val clientType: String,
        var lastAck: String? = null,
        var lastAckSuccess: Boolean? = null,
        var lastAckError: String? = null
    )

    private val clients = ConcurrentHashMap<WebSocket, ClientInfo>()

    var onClientConnected: ((ClientInfo) -> Unit)? = null
    var onClientDisconnected: ((String) -> Unit)? = null
    var onAckReceived: ((clientId: String, text: String, success: Boolean, error: String?) -> Unit)? = null
    /** mode is null for a plain query, "prefill_branches" for multi-reply fan-out. */
    var onQueryReceived: ((text: String, mode: String?, count: Int) -> Unit)? = null
    var onQueueSyncReceived: ((queueJson: org.json.JSONArray) -> Unit)? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val id = conn.remoteSocketAddress.toString()
        val info = ClientInfo(id = id, clientType = "Unknown")
        clients[conn] = info
        onClientConnected?.invoke(info)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        clients.remove(conn)?.let {
            onClientDisconnected?.invoke(it.id)
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            val info = clients[conn] ?: return

            when (type) {
                "hello" -> {
                    val newInfo = info.copy(clientType = json.optString("client", "Generic"))
                    clients[conn] = newInfo
                    onClientConnected?.invoke(newInfo)
                }
                "ack" -> {
                    val text = json.optString("text")
                    val success = json.optBoolean("success")
                    val error = json.optString("error").takeIf { it.isNotEmpty() }

                    info.lastAck = text
                    info.lastAckSuccess = success
                    info.lastAckError = error

                    onAckReceived?.invoke(info.id, text, success, error)
                }
                "user_query" -> {
                    val text = json.optString("text")
                    val mode = json.optString("mode").takeIf { it.isNotEmpty() }
                    val count = json.optInt("count", 0)
                    if (text.isNotEmpty()) onQueryReceived?.invoke(text, mode, count)
                }
                "queue_sync" -> {
                    val queue = json.optJSONArray("queue")
                    if (queue != null) onQueueSyncReceived?.invoke(queue)
                }
            }
        } catch (e: Exception) {
            Log.e("WS", "Error parsing message", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("WS", "Server error", ex)
    }

    override fun onStart() {
        Log.i("WS", "WebSocket Server started on port ${address.port}")
    }

    fun broadcastText(text: String, source: String = "stt") {
        val payload = JSONObject().apply {
            put("type", "text")
            put("text", text)
            put("source", source)
        }.toString()

        clients.keys.forEach { it.send(payload) }
    }

    /** Marks the end of a streamed turn (e.g. one full LLM reply). */
    fun broadcastTurnEnd(source: String) {
        val payload = JSONObject().apply {
            put("type", "turn_end")
            put("source", source)
        }.toString()

        clients.keys.forEach { it.send(payload) }
    }

    fun sendQueueAction(actionPayload: String) {
        clients.keys.forEach { it.send(actionPayload) }
    }

    fun clientSnapshot() = clients.values.toList()

    fun connectedCount() = clients.size
}
