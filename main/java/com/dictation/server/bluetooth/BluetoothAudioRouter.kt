package com.dictation.server.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.*
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class BluetoothAudioRouter(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }
    private val mainHandler  = Handler(Looper.getMainLooper())

    @Volatile private var isScoRequested = false
    @Volatile private var scoConnected   = false

    var onDeviceConnected: ((String) -> Unit)? = null
    var onScoConnected:    (() -> Unit)? = null
    var onScoDisconnected: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    val prevState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, -1)
                    Log.i(TAG, "SCO state transition: $prevState -> $state")
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            if (!scoConnected) {
                                scoConnected = true
                                Log.i(TAG, "SCO 已连接回调")
                                onScoConnected?.invoke()
                            }
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            Log.i(TAG, "SCO 已断开")
                            if (scoConnected) {
                                scoConnected = false
                                if (isScoRequested) onScoDisconnected?.invoke()
                            }
                        }
                        AudioManager.SCO_AUDIO_STATE_ERROR -> {
                            Log.e(TAG, "SCO 错误，2s 后重试")
                            mainHandler.postDelayed({ retryRoute() }, 2000)
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val name = device?.let { getDeviceNameSafe(it) } ?: "未知设备"
                    Log.i(TAG, "检测到 BT ACL 连接: $name")
                    onDeviceConnected?.invoke(name)
                    mainHandler.postDelayed({ requestRoute() }, 1000)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(TAG, "BT ACL 断开")
                    isScoRequested = false
                    scoConnected   = false
                    onDeviceConnected?.invoke("") // 清除设备名
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun init() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)
        Log.i(TAG, "Router initialized, registering receiver")
        
        // 初始化时尝试寻找已连接的设备
        findExistingDevice()
    }

    @SuppressLint("MissingPermission")
    private fun findExistingDevice() {
        try {
            val adapter = bluetoothAdapter ?: return
            // 获取已连接的耳机/免提设备
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val devices = proxy.connectedDevices
                    if (devices.isNotEmpty()) {
                        val name = getDeviceNameSafe(devices[0])
                        Log.i(TAG, "初始化发现已连接设备: $name")
                        onDeviceConnected?.invoke(name)
                    }
                    adapter.closeProfileProxy(profile, proxy)
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            Log.e(TAG, "findExistingDevice error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceNameSafe(device: BluetoothDevice): String {
        return try {
            device.name ?: device.address ?: "未知蓝牙设备"
        } catch (e: Exception) {
            "未知蓝牙设备"
        }
    }

    fun requestRoute() {
        if (isScoRequested && scoConnected) {
            Log.d(TAG, "SCO already requested and connected, skipping")
            return
        }
        Log.i(TAG, "requestRoute: 准备开启 Bluetooth SCO")
        isScoRequested = true
        try {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            audioManager.startBluetoothSco()
            Log.i(TAG, "audioManager.startBluetoothSco() 已调用")
        } catch (e: Exception) {
            Log.e(TAG, "startBluetoothSco failed: ${e.message}")
        }
    }

    private fun retryRoute() {
        if (!isScoRequested) return
        Log.i(TAG, "正在重试 SCO 连接...")
        audioManager.stopBluetoothSco()
        mainHandler.postDelayed({ 
            if (isScoRequested) {
                audioManager.startBluetoothSco()
            }
        }, 500)
    }

    fun release() {
        Log.i(TAG, "Router releasing")
        isScoRequested = false
        scoConnected   = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { audioManager.stopBluetoothSco() }
        @Suppress("DEPRECATION")
        runCatching { audioManager.isBluetoothScoOn = false }
        runCatching { context.unregisterReceiver(receiver) }
    }

    companion object { private const val TAG = "BTRouter" }
}
