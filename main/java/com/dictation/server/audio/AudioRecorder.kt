package com.dictation.server.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*

interface TranscriptionEngine {
    fun process(pcmData: ShortArray)
}

class AudioRecorder(private val transcriptionEngine: TranscriptionEngine) {
    private var isRecording = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sampleRate    = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat   = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize    = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) { Log.w(TAG, "startRecording: 已在录音，跳过"); return }
        Log.i(TAG, "startRecording: 开始初始化 AudioRecord")
        isRecording = true
        job = scope.launch {
            try {
                val recorder = AudioRecord(
                    // MIC 而非 VOICE_COMMUNICATION：
                    // VOICE_COMMUNICATION 激活系统 DSP 噪声消除，
                    // 处理后音频送给 Paraformer 会严重降低识别率。
                    // SCO 连上后 AudioManager 自动把 MIC 路由到蓝牙麦，无需额外处理。
                    MediaRecorder.AudioSource.MIC,
                    sampleRate, channelConfig, audioFormat, bufferSize
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord 初始化失败")
                    isRecording = false
                    return@launch
                }
                recorder.startRecording()
                Log.i(TAG, "开始录音 sampleRate=$sampleRate")
                val buffer = ShortArray(1600) // 100ms @ 16kHz
                while (isRecording && isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) transcriptionEngine.process(buffer.copyOf(read))
                }
                recorder.stop()
                recorder.release()
                Log.i(TAG, "录音已停止")
            } catch (e: Exception) {
                Log.e(TAG, "录音异常", e)
            } finally {
                isRecording = false
            }
        }
    }

    fun stopRecording() {
        Log.i(TAG, "stopRecording called, isRecording=$isRecording")
        isRecording = false
        job?.cancel()
        job = null
    }

    companion object { private const val TAG = "AudioRecorder" }
}
