package com.dictation.server.core

import android.content.Context
import android.content.SharedPreferences

/** Typed accessors for the app's single SharedPreferences file. */
object AppPrefs {
    private const val FILE = "prefs"

    const val DEFAULT_PHONE_IP = "192.168.1.100"
    const val DEFAULT_STT_HOST = "127.0.0.1"
    const val DEFAULT_STT_PORT = 9527

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun phoneIp(ctx: Context): String =
        prefs(ctx).getString("phone_ip", DEFAULT_PHONE_IP) ?: DEFAULT_PHONE_IP

    fun setPhoneIp(ctx: Context, ip: String) =
        prefs(ctx).edit().putString("phone_ip", ip).apply()

    fun llmHost(ctx: Context): String =
        prefs(ctx).getString("llm_host", "") ?: ""

    fun setLlmHost(ctx: Context, host: String) =
        prefs(ctx).edit().putString("llm_host", host).apply()

    fun llmToken(ctx: Context): String =
        prefs(ctx).getString("llm_token", "") ?: ""

    fun setLlmToken(ctx: Context, token: String) =
        prefs(ctx).edit().putString("llm_token", token).apply()

    /** Multi-reply mode: a plugin query fans out into N prefill-continued branches. */
    fun multiReply(ctx: Context): Boolean =
        prefs(ctx).getBoolean("multi_reply", false)

    fun setMultiReply(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean("multi_reply", enabled).apply()

    fun multiReplyCount(ctx: Context): Int =
        prefs(ctx).getInt("multi_reply_count", 3)

    fun setMultiReplyCount(ctx: Context, count: Int) =
        prefs(ctx).edit().putInt("multi_reply_count", count).apply()

    /**
     * Prefill template for multi-reply. "{text}" is replaced with the query
     * text (lasso selection); a template without the placeholder is used as a
     * prefix. The result seeds each assistant branch before continuation.
     */
    fun prefillTemplate(ctx: Context): String =
        prefs(ctx).getString("prefill_template", "{text}") ?: "{text}"

    fun setPrefillTemplate(ctx: Context, template: String) =
        prefs(ctx).edit().putString("prefill_template", template).apply()

    /**
     * The LLM bridge was running when the service last stopped — used to
     * bring it back automatically after the system kills and (START_STICKY)
     * restarts the service, so a background kill doesn't silently sever the
     * phone ↔ AI-client link.
     */
    fun llmAutoStart(ctx: Context): Boolean =
        prefs(ctx).getBoolean("llm_auto_start", false)

    fun setLlmAutoStart(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean("llm_auto_start", enabled).apply()

    fun sttHost(ctx: Context): String =
        prefs(ctx).getString("stt_host", DEFAULT_STT_HOST) ?: DEFAULT_STT_HOST

    fun sttPort(ctx: Context): Int =
        prefs(ctx).getInt("stt_port", DEFAULT_STT_PORT)

    fun setSttEndpoint(ctx: Context, host: String, port: Int) =
        prefs(ctx).edit().putString("stt_host", host).putInt("stt_port", port).apply()
}
