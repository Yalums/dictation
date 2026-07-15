package com.dictation.server.net

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HttpURLConnection helpers for the JSON REST endpoints we talk to. */
object HttpJson {
    private const val TAG = "HttpJson"

    /**
     * Bearer token attached to every request when set (JWT-protected servers).
     * SSE connections opened outside this object should use [sseUrl] instead,
     * since the server also accepts the token as an access_token query param.
     */
    @Volatile var authToken: String? = null

    /** Append the auth token as a query parameter (for SSE, where headers are awkward to retry). */
    fun sseUrl(url: String): String {
        val token = authToken ?: return url
        val sep = if ("?" in url) "&" else "?"
        return "$url${sep}access_token=$token"
    }

    /** POST /api/auth/token with [password]; returns the JWT or null. */
    fun fetchToken(baseUrl: String, password: String): String? =
        try {
            val resp = postJson("$baseUrl/api/auth/token", JSONObject().put("password", password))
            JSONObject(resp).optString("token", "").ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "fetchToken failed: ${e.message}")
            null
        }

    /** "host" → "http://host:defaultPort"; full URLs pass through unchanged. */
    fun normalizeBaseUrl(host: String, defaultPort: Int): String {
        val h = host.trim()
        return if (h.startsWith("http")) h else "http://$h:$defaultPort"
    }

    /** GET [url]; returns the body on 2xx, null otherwise. Throws on I/O errors. */
    fun get(url: String, timeoutMs: Int = 5_000): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Log.w(TAG, "GET $url → ${conn.responseCode}")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    /** GET [url] and parse as JSON object; null on non-2xx, parse error, or I/O error. */
    fun getJsonOrNull(url: String, timeoutMs: Int = 5_000): JSONObject? =
        try {
            get(url, timeoutMs)?.let { JSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        }

    /** POST [body] as JSON; returns response body (2xx or error stream). Throws on I/O errors. */
    fun postJson(url: String, body: JSONObject, timeoutMs: Int = 30_000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) Log.w(TAG, "POST $url → $code: ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
