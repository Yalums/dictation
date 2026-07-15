package com.dictation.server.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A text chunk pending delivery to the writing plugin. The same model is used
 * on both sides of the relay link (phone UI edits ↔ relay resend queue).
 */
data class QueueItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val sentAt: Long = System.currentTimeMillis(),
    var retryCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("sentAt", sentAt)
        put("retryCount", retryCount)
    }

    companion object {
        fun fromJson(obj: JSONObject) = QueueItem(
            id = obj.optString("id"),
            text = obj.optString("text"),
            sentAt = obj.optLong("sentAt"),
            retryCount = obj.optInt("retryCount"),
        )

        fun listToJson(items: List<QueueItem>): JSONArray =
            JSONArray().also { arr -> items.forEach { arr.put(it.toJson()) } }

        fun listFromJson(arr: JSONArray): List<QueueItem> =
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
    }
}
