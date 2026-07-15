package com.dictation.server.ws

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class DictationWebSocketClient(serverUri: String) : WebSocketClient(URI(serverUri)) {

    companion object { private const val TAG = "WSClient" }

    /** (text, source) — source is "stt" / "llm" / "manual" (older phones omit it → "stt"). */
    var onTextReceived: ((String, String) -> Unit)? = null
    /** End of a streamed turn for [source]. */
    var onTurnEnd: ((String) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onQueueActionReceived: ((String) -> Unit)? = null

    init { Log.i(TAG, "创建 WebSocketClient uri=$serverUri") }

    override fun onOpen(handshake: ServerHandshake) {
        Log.i(TAG, "onOpen: 已连接服务器 status=${handshake.httpStatus}")
        send(JSONObject().apply {
            put("type", "hello")
            put("client", "Supernote-Relay")
        }.toString())
        onConnectionChanged?.invoke(true)
    }

    override fun onMessage(message: String) {
        try {
            val json = JSONObject(message)
            if (json.optString("type") == "text") {
                val text = json.optString("text")
                val source = json.optString("source").ifEmpty { "stt" }
                Log.i(TAG, "onMessage text[$source]: ${text.take(50)}")
                onTextReceived?.invoke(text, source)
            } else if (json.optString("type") == "turn_end") {
                val source = json.optString("source").ifEmpty { "llm" }
                Log.i(TAG, "onMessage turn_end[$source]")
                onTurnEnd?.invoke(source)
            } else if (json.optString("type") == "queue_action") {
                Log.i(TAG, "onMessage queue_action")
                onQueueActionReceived?.invoke(message)
            } else {
                Log.d(TAG, "onMessage other type=${json.optString("type")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onMessage parse error: ${e.message}")
        }
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        Log.w(TAG, "onClose: code=$code reason=$reason remote=$remote")
        onConnectionChanged?.invoke(false)
    }

    override fun onError(ex: Exception) {
        Log.e(TAG, "onError: ${ex.javaClass.simpleName}: ${ex.message}")
    }

    fun sendAck(text: String, success: Boolean, error: String?) {
        Log.d(TAG, "sendAck success=$success text=${text.take(30)}")
        try {
            send(JSONObject().apply {
                put("type", "ack")
                put("text", text)
                put("success", success)
                error?.let { put("error", it) }
            }.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendAck failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * [mode] "prefill_branches" asks the phone to fan the query out into
     * [count] prefill-continued assistant branches; null is a plain query.
     */
    fun sendQuery(text: String, mode: String? = null, count: Int = 0) {
        Log.i(TAG, "sendQuery[mode=$mode count=$count]: ${text.take(50)}")
        send(JSONObject().apply {
            put("type", "user_query")
            put("text", text)
            if (mode != null) {
                put("mode", mode)
                put("count", count)
            }
        }.toString())
    }

    fun sendQueueSync(queueJson: org.json.JSONArray) {
        try {
            send(JSONObject().apply {
                put("type", "queue_sync")
                put("queue", queueJson)
            }.toString())
        } catch (e: Exception) {
            Log.e(TAG, "sendQueueSync failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
