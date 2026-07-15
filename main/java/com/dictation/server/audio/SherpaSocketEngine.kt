package com.dictation.server.audio

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * VAD + 批次 STT 引擎，配合 FireRedASR（离线模式）使用。
 *
 * 协议：
 *   检测到静音后，发送 4字节(uint32 LE)音频总字节数 + PCM →
 *   服务端解码 → 返回 "FINAL:文本\n"
 *
 * VAD 逻辑：
 *   每 100ms 计算 RMS 能量，连续 VAD_SILENCE_CHUNKS 块低于阈值 → 判定句子结束 → 发送
 */
class SherpaSocketEngine(
    private val host: String = "127.0.0.1",
    private val port: Int = 9527,
    private val logDir: File? = null,
) : TranscriptionEngine {

    companion object {
        private const val TAG = "SherpaEngine"

        // VAD 参数
        private const val VAD_SILENCE_CHUNKS  = 12   // 连续静音块数 → 12×100ms = 1.2秒
        private const val VAD_ENERGY_THRESHOLD = 200f // RMS 阈值（0~32768），低于此为静音
        private const val MIN_SPEECH_CHUNKS   = 5    // 最少说话块数，避免噪音误触发（5×100ms=0.5秒）
        private const val MAX_SPEECH_CHUNKS   = 300  // 最多积累块数 → 300×100ms = 30秒强制发送
    }

    // ── 回调 ──────────────────────────────────────────────────────────────────
    var onResult:       ((text: String, isFinal: Boolean) -> Unit)? = null
    var onConnected:    (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    // ── 状态 ──────────────────────────────────────────────────────────────────
    @Volatile private var shouldRun = false

    // ── VAD 状态 ──────────────────────────────────────────────────────────────
    private val bufferLock    = Any()
    private val speechBuffer  = mutableListOf<ShortArray>() // 当前句子积累的音频
    private var silenceChunks = 0   // 当前连续静音块计数
    private var speechChunks  = 0   // 当前句子说话块计数
    private var isSpeaking    = false

    // ── 统计 ──────────────────────────────────────────────────────────────────
    private var processCalls = 0L
    private var sentBatches  = 0

    // ── 文件日志 ──────────────────────────────────────────────────────────────
    private val logFile: File? = logDir?.let { File(it, "stt_app.log") }

    private fun fileLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "$ts [SherpaEngine] $msg"
        Log.i(TAG, msg)
        try { logFile?.let { FileWriter(it, true).use { w -> w.appendLine(line) } } }
        catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────

    fun connect() {
        if (shouldRun) { fileLog("connect() 重复调用，跳过"); return }
        shouldRun = true
        logFile?.writeText("")
        fileLog("引擎启动，VAD+批次模式 (阈值=$VAD_ENERGY_THRESHOLD, 静音=${VAD_SILENCE_CHUNKS * 100}ms)")
        onConnected?.invoke()
    }

    fun disconnect() {
        fileLog("disconnect() 调用")
        shouldRun = false
        // 发送残留音频
        synchronized(bufferLock) {
            if (speechBuffer.isNotEmpty() && speechChunks >= MIN_SPEECH_CHUNKS) {
                flushBuffer("disconnect")
            } else {
                speechBuffer.clear()
            }
        }
        onDisconnected?.invoke()
    }

    /** AudioRecorder 每 100ms 调用一次 */
    override fun process(pcmData: ShortArray) {
        if (!shouldRun) return
        processCalls++

        val rms = calcRms(pcmData)
        val isSilent = rms < VAD_ENERGY_THRESHOLD

        synchronized(bufferLock) {
            if (!isSilent) {
                // 有声音
                silenceChunks = 0
                if (!isSpeaking) {
                    isSpeaking = true
                    fileLog("检测到语音 (RMS=${"%.0f".format(rms)})")
                }
                speechBuffer.add(pcmData)
                speechChunks++

                // 超过最大长度强制发送
                if (speechChunks >= MAX_SPEECH_CHUNKS) {
                    fileLog("达到最大长度(${MAX_SPEECH_CHUNKS * 100}ms)，强制发送")
                    flushBuffer("max_len")
                }
            } else {
                // 静音
                if (isSpeaking) {
                    speechBuffer.add(pcmData)  // 把静音帧也加入，避免截断尾音
                    silenceChunks++

                    if (silenceChunks >= VAD_SILENCE_CHUNKS) {
                        if (speechChunks >= MIN_SPEECH_CHUNKS) {
                            flushBuffer("silence")
                        } else {
                            fileLog("丢弃短片段 (${speechChunks * 100}ms < 最小${MIN_SPEECH_CHUNKS * 100}ms)")
                            speechBuffer.clear()
                        }
                        isSpeaking = false
                        silenceChunks = 0
                        speechChunks = 0
                    }
                }
                // 完全静音期间不积累
            }
        }

        if (processCalls % 300 == 0L) {
            fileLog("状态: ${processCalls}次调用, 已发${sentBatches}批, " +
                    "缓冲${speechChunks * 100}ms, RMS=${"%.0f".format(rms)}")
        }
    }

    // ── 私有：发送缓冲区 ──────────────────────────────────────────────────────

    /** 必须在 bufferLock 内调用 */
    private fun flushBuffer(trigger: String) {
        val chunks = speechBuffer.toList()
        speechBuffer.clear()
        speechChunks = 0
        silenceChunks = 0
        isSpeaking = false

        val totalSamples = chunks.sumOf { it.size }
        val audioSecs = totalSamples / 16000.0
        sentBatches++
        fileLog("━━ 批次#$sentBatches ($trigger) ${String.format("%.1f", audioSecs)}秒 ━━")

        thread(name = "sherpa-send-$sentBatches") {
            val batchId = sentBatches
            val startMs = System.currentTimeMillis()
            try {
                val sock = Socket(host, port).apply {
                    soTimeout = 60_000
                    tcpNoDelay = true
                }

                val out = sock.getOutputStream()

                // 1. 发 4字节长度前缀
                val totalBytes = totalSamples * 2
                val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(totalBytes).array()
                out.write(lenBuf)

                // 2. 发 PCM
                for (chunk in chunks) {
                    val bytes = ByteBuffer.allocate(chunk.size * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .also { buf -> chunk.forEach(buf::putShort) }
                        .array()
                    out.write(bytes)
                }
                out.flush()
                fileLog("  [$batchId] 已发送 ${totalBytes / 1024}KB，等待识别...")

                // 3. 读结果
                val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = reader.readLine() ?: break
                    val text = when {
                        line.startsWith("FINAL:")   -> line.removePrefix("FINAL:").trim()
                        line.startsWith("PARTIAL:") -> line.removePrefix("PARTIAL:").trim()
                        else -> line.trim()
                    }
                    if (text.isNotEmpty()) {
                        val elapsed = System.currentTimeMillis() - startMs
                        fileLog("  [$batchId] 结果(${elapsed}ms): $text")
                        onResult?.invoke(text + "\n", true)
                    }
                }
                sock.close()
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startMs
                fileLog("  [$batchId] 错误(${elapsed}ms): ${e.message}")
                Log.e(TAG, "批次#$batchId 失败", e)
            }
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun calcRms(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return sqrt(sum / pcm.size).toFloat()
    }
}
