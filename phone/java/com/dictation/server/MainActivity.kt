package com.dictation.server

import android.Manifest
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.dictation.server.service.DictationService

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var service: DictationService? = null
    private var isBound by mutableStateOf(false)
    private var stateVersion by mutableStateOf(0)

    // 在 service 还未绑定时用户点了 LLM 连接，先暂存，绑定后立即执行
    private var pendingLlmHost: String? = null
    private var pendingLlmConvId: String? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            val svc = (b as DictationService.LocalBinder).getService().also {
                it.onStateChanged = { stateVersion++ }
            }
            service = svc
            isBound = true
            stateVersion++
            Log.i(TAG, "onServiceConnected: isStarted=${svc.isStarted} isRelayMode=${svc.isRelayMode}")
            // 应用暂存的 LLM 连接请求
            val host = pendingLlmHost
            if (host != null) {
                pendingLlmHost = null
                svc.startLlmBridge(host, pendingLlmConvId)
                pendingLlmConvId = null
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected")
            service = null; isBound = false
        }
    }

    private fun forceUnbind() {
        if (isBound) { unbindService(conn); isBound = false; service = null }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.i(TAG, "permLauncher results: $results")
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            startDictation()
        } else {
            Log.e(TAG, "RECORD_AUDIO denied, cannot start")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            @Suppress("UNUSED_EXPRESSION") stateVersion
            ServerMonitorScreen(
                isBound = isBound,
                service = service,
                stateVersion = stateVersion,
                serverIp = localIp(),
                onStartStt = { requestPerms() },
                onPauseStt = { service?.pauseStt() },
                onResumeStt = { service?.resumeStt() },
                onConnectLlm = { host, convId ->
                    // 先显式 start service，确保挂后台后 service 不随解绑而销毁
                    ContextCompat.startForegroundService(this, DictationService.llmIntent(this))
                    val svc = service
                    if (svc != null) {
                        svc.startLlmBridge(host, convId)
                    } else {
                        pendingLlmHost = host
                        pendingLlmConvId = convId
                        bindService(Intent(this, DictationService::class.java), conn, BIND_AUTO_CREATE)
                    }
                },
                onPauseLlm = { service?.pauseLlm() },
                onResumeLlm = { service?.resumeLlm() },
                onStop = {
                    Log.i(TAG, "onStop clicked")
                    startService(DictationService.stopIntent(this))
                    forceUnbind()
                },
                onManualSend = { text -> service?.manualBroadcast(text) },
                onStartTypeless = {
                    val i = DictationService.typelessIntent(this)
                    ContextCompat.startForegroundService(this, i)
                    bindService(i, conn, Context.BIND_AUTO_CREATE)
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DictationService::class.java), conn, 0)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) { unbindService(conn); isBound = false }
    }

    private fun requestPerms() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            else
                add(Manifest.permission.BLUETOOTH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        Log.i(TAG, "Requesting permissions: $perms")
        permLauncher.launch(perms.toTypedArray())
    }

    private fun startDictation() {
        Log.i(TAG, "startDictation: launching FGS")
        val i = DictationService.startIntent(this)
        ContextCompat.startForegroundService(this, i)
        bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    private fun localIp(): String {
        val candidates = mutableListOf<String>()
        try {
            for (iface in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr !is java.net.Inet4Address || addr.isLoopbackAddress) continue
                    candidates.add(addr.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) {}
        return candidates.firstOrNull { it.startsWith("192.168.") }
            ?: candidates.firstOrNull { it.startsWith("10.") }
            ?: candidates.firstOrNull()
            ?: getString(R.string.net_unavailable)
    }
}
