package com.dictation.server.core

/**
 * Sentence-aware text chunking, shared by the phone broadcast path and the
 * relay→plugin path (they only differ in max chunk size).
 */
object TextChunker {
    val SENTENCE_END_CHARS = setOf('。', '！', '？', '.', '!', '?', '\n')

    /** Characters that must not start a chunk (would render at line start). */
    private const val LEADING_PUNCT = "，。、；：！？）》」』”’,.;:!?)]}"

    /**
     * Whether text[index] terminates a sentence. A '.' inside a number
     * sequence or list marker ("1.2", "3. ") does not count.
     */
    fun isSentenceEnd(text: CharSequence, index: Int): Boolean {
        val ch = text[index]
        if (ch !in SENTENCE_END_CHARS) return false
        if (ch == '.') {
            val before = if (index > 0) text[index - 1] else ' '
            val after = if (index + 1 < text.length) text[index + 1] else ' '
            if (before.isDigit() && (after.isDigit() || after.isWhitespace())) return false
        }
        return true
    }

    /** Index of the last sentence boundary in [text], or -1. */
    fun lastSentenceEnd(text: CharSequence): Int {
        for (i in text.indices.reversed()) {
            if (isSentenceEnd(text, i)) return i
        }
        return -1
    }

    /**
     * Split [text] into chunks of at most [maxChars], preferring sentence
     * boundaries; sentences longer than [maxChars] are hard-split.
     */
    fun splitAtBoundaries(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)

        val sentences = mutableListOf<String>()
        var start = 0
        for (i in text.indices) {
            if (isSentenceEnd(text, i)) {
                sentences.add(text.substring(start, i + 1))
                start = i + 1
            }
        }
        if (start < text.length) sentences.add(text.substring(start))

        val result = mutableListOf<String>()
        var chunk = ""
        for (sentence in sentences) {
            if (chunk.length + sentence.length <= maxChars) {
                chunk += sentence
            } else {
                if (chunk.isNotEmpty()) result.add(chunk)
                if (sentence.length <= maxChars) {
                    chunk = sentence
                } else {
                    var s = 0
                    while (s < sentence.length) {
                        var e = minOf(s + maxChars, sentence.length)
                        // 硬拆分不让下一块以标点开头：向后多带上紧跟的标点，
                        // 避免"，""。"被甩到下一个文本框的行首。
                        while (e < sentence.length && sentence[e] in LEADING_PUNCT) e++
                        result.add(sentence.substring(s, e))
                        s = e
                    }
                    chunk = ""
                }
            }
        }
        if (chunk.isNotEmpty()) result.add(chunk)
        return result
    }
}
