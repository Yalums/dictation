package com.dictation.server.llm

/**
 * Bidirectional bridge to an LLM backend.
 *
 * Upstream:   [sendUserMessage] pushes a user turn and triggers a completion.
 * Downstream: replies arrive via [onStreamToken] (incremental) and
 *             [onReplyComplete] (final full text, exactly once per turn).
 *
 * This is the entire contract the service layer consumes; provider-specific
 * concepts (conversations, session following, transport) stay inside the
 * implementation.
 */
interface LlmBridge {
    val isConnected: Boolean

    /** Connect. [conversationId] resumes an existing session when the provider supports it. */
    fun start(conversationId: String? = null)

    fun stop()

    fun sendUserMessage(text: String)

    /**
     * Append [text] as a final assistant-role turn without triggering a
     * completion — e.g. content the relay obtained through the writing
     * plugin. No-op for providers without the concept.
     */
    fun appendAssistantMessage(text: String) {}

    /**
     * Multi-reply: create [count] branches on the last assistant node, each
     * seeded with [prefill], and continue each as an assistant completion.
     * [onResult] receives the finished branch texts (possibly fewer than
     * [count] on partial failure). No-op for providers without the concept.
     */
    fun sendPrefillBranches(count: Int, prefill: String, onResult: (List<String>) -> Unit) {
        onResult(emptyList())
    }

    /** Provider session id, when the concept applies; null otherwise. */
    fun getConversationId(): String? = null

    /** Incremental reply delta while the model is generating. */
    var onStreamToken: ((delta: String) -> Unit)?

    /** Final reply text; fired exactly once per completed turn. */
    var onReplyComplete: ((text: String) -> Unit)?

    var onConnectionChanged: ((connected: Boolean) -> Unit)?
}
