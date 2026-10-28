package cn.toside.music.mobile.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * DLNA 状态枚举
 */
enum class DlnaState {
    IDLE,
    SCANNING,
    CONNECTING,
    STREAMING,
    STOPPED,
    ERROR
}

/**
 * DLNA 事件监听器
 */
interface DlnaEventListener {
    fun onStateChanged(state: DlnaState, message: String? = null)
    fun onDevicesDiscovered(devices: List<DlnaDevice>)
    fun onError(errorMessage: String)
}

/**
 * DLNA 核心管理器
 * 统一调度 LocalHttpServer、HqpeClient、SsdpScanner 与 NetworkUtils
 * 具备协程互斥锁 (Mutex) 与严格防竞态序列控制
 */
object DlnaManager {
    private const val TAG = "DlnaManager"
    private const val DEFAULT_PORT = 8088

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private var activeSessionJob: Job? = null

    private var localHttpServer: LocalHttpServer? = null
    private var currentDevice: DlnaDevice? = null
    private val currentState = AtomicReference(DlnaState.IDLE)
    private var eventListener: DlnaEventListener? = null

    // 系统级高性能保活锁 (防止三星等系统后台 Wi-Fi 微休眠与 CPU 降频)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    private fun acquireLocks(context: Context) {
        try {
            if (wifiLock == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LXMusic:DlnaWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                Log.i(TAG, "WifiLock (WIFI_MODE_FULL_HIGH_PERF) acquired for uninterrupted streaming.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
        }

        try {
            if (wakeLock == null) {
                val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LXMusic:DlnaWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
                Log.i(TAG, "WakeLock (PARTIAL_WAKE_LOCK) acquired for uninterrupted CPU pacing.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    @Synchronized
    private fun releaseLocks() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.i(TAG, "WifiLock safely released.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WifiLock: ${e.message}")
        }
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "WakeLock safely released.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }

    fun setEventListener(listener: DlnaEventListener?) {
        this.eventListener = listener
    }

    fun getState(): DlnaState = currentState.get()

    fun getCurrentDevice(): DlnaDevice? = currentDevice

    fun isStreaming(): Boolean = currentState.get() == DlnaState.STREAMING

    /**
     * 扫描局域网中的 DLNA 设备
     * 注意：严格保持推流状态机，若当前处于 STREAMING 或 CONNECTING，严禁被扫描覆写为 SCANNING 或 IDLE
     */
    fun scanDevices(context: Context, timeoutMs: Int = 3000, callback: ((List<DlnaDevice>) -> Unit)? = null) {
        val stateBeforeScan = currentState.get()
        if (stateBeforeScan != DlnaState.STREAMING && stateBeforeScan != DlnaState.CONNECTING) {
            updateState(DlnaState.SCANNING)
        }
        scope.launch {
            try {
                val devices = SsdpScanner.scan(context, timeoutMs)
                if (currentState.get() == DlnaState.SCANNING) {
                    updateState(DlnaState.IDLE)
                }
                eventListener?.onDevicesDiscovered(devices)
                callback?.invoke(devices)
            } catch (e: Exception) {
                Log.e(TAG, "Device scan failed: ${e.message}", e)
                if (currentState.get() == DlnaState.SCANNING) {
                    updateState(DlnaState.IDLE)
                }
                eventListener?.onError("Scan failed: ${e.message}")
                callback?.invoke(emptyList())
            }
        }
    }

    /**
     * 连接至指定的 DLNA 渲染设备并启动串流
     * 遵循纯净标准时序：SetAVTransportURI -> (成功后) -> Play
     * 使用 Mutex 锁定操作，严禁投送前盲目调用 Stop，彻底根除时序竞态与自身误杀
     */
    fun startStreaming(context: Context, device: DlnaDevice, port: Int = DEFAULT_PORT, callback: ((Boolean, String?) -> Unit)? = null) {
        // 1. 防重复投送保护
        val isAlreadyStreaming = currentState.get() == DlnaState.STREAMING
        val isSameDevice = currentDevice?.id == device.id ||
                (currentDevice?.ip == device.ip && currentDevice?.controlUrl == device.controlUrl)
        val isServerActive = localHttpServer?.isRunning() == true && (localHttpServer?.getClientCount() ?: 0) > 0

        if (isAlreadyStreaming && isSameDevice && isServerActive) {
            Log.i(TAG, "Already actively streaming to ${device.friendlyName}. Guarding against duplicate startStreaming.")
            callback?.invoke(true, null)
            return
        }

        // 2. 原子取消先前尚未执行完的旧异步任务，杜绝旧请求飘到新会话中
        activeSessionJob?.cancel()

        updateState(DlnaState.CONNECTING)

        val job = scope.launch {
            operationMutex.withLock {
                try {
                    // 3. 切换目标设备时，才显式向旧设备发送 Stop 释放旧声卡
                    val oldDevice = currentDevice
                    if (oldDevice != null && (oldDevice.id != device.id || oldDevice.ip != device.ip)) {
                        Log.i(TAG, "Switching device: stopping previous device ${oldDevice.friendlyName} first...")
                        try {
                            HqpeClient.stop(oldDevice.controlUrl)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to stop previous device: ${e.message}")
                        }
                    }
                    currentDevice = device

                    // 4. 重启或启动本地 HTTP 推流服务
                    localHttpServer?.stop()
                    localHttpServer = null

                    val server = LocalHttpServer(port)
                    server.onClientDisconnected = { reason ->
                        Log.w(TAG, "LocalHttpServer client disconnected ($reason).")
                        scope.launch {
                            operationMutex.withLock {
                                val s = currentState.get()
                                if (s == DlnaState.STREAMING || s == DlnaState.CONNECTING) {
                                    localHttpServer?.stop()
                                    localHttpServer = null
                                    currentDevice = null
                                    updateState(DlnaState.IDLE, "DISCONNECTED")
                                }
                            }
                        }
                    }

                    if (!server.start()) {
                        throw IllegalStateException("Failed to bind LocalHttpServer on port $port")
                    }
                    localHttpServer = server
                    localHttpServer?.clearBuffer()

                    // 5. 纯净标准时序：解析本机 IP -> SetAVTransportURI -> (成功后) -> Play
                    val localIp = NetworkUtils.getLocalIpAddress(context, device.ip, device.port)
                    val streamUrl = "http://$localIp:$port/stream.wav"
                    Log.i(TAG, "Initiating stream to ${device.friendlyName} via $streamUrl (standard sequence)")

                    // a) 执行 SOAP SetAVTransportURI
                    val setUriResult = HqpeClient.setUri(device.controlUrl, streamUrl)
                    if (setUriResult.isFailure) {
                        val err = setUriResult.exceptionOrNull()?.message ?: "未知错误"
                        throw IllegalStateException("[SetAVTransportURI 阶段] $err")
                    }

                    // b) 执行 SOAP Play
                    val playResult = HqpeClient.play(device.controlUrl)
                    if (playResult.isFailure) {
                        val err = playResult.exceptionOrNull()?.message ?: "未知错误"
                        throw IllegalStateException("[Play 阶段] $err")
                    }

                    // c) 确认收到 Play 成功报文后，第一时间激活高性能锁与 STREAMING 状态并向前端返回成功
                    acquireLocks(context)
                    updateState(DlnaState.STREAMING)
                    Log.i(TAG, "Successfully started streaming to ${device.friendlyName}")
                    callback?.invoke(true, null)

                } catch (e: Exception) {
                    if (e is CancellationException) {
                        Log.i(TAG, "startStreaming job was cancelled by a newer request.")
                        return@withLock
                    }
                    Log.e(TAG, "startStreaming error: ${e.message}", e)
                    currentDevice = null
                    localHttpServer?.stop()
                    localHttpServer = null
                    updateState(DlnaState.IDLE, "ERROR: ${e.message}")
                    eventListener?.onError(e.message ?: "Streaming error")
                    callback?.invoke(false, e.message)
                }
            }
        }
        activeSessionJob = job
    }

    /**
     * 规范主动停止串流：
     * 仅在用户主动点击“断开”时显式调用，受 Mutex 保护
     */
    fun stopStreaming(callback: ((Boolean, String?) -> Unit)? = null) {
        // 原子取消正在排队或执行的启动任务
        activeSessionJob?.cancel()

        val targetDevice = currentDevice
        val job = scope.launch {
            operationMutex.withLock {
                try {
                    // 1. 用户主动点击断开时，显式向目标设备发送 SOAP Stop
                    if (targetDevice != null) {
                        Log.i(TAG, "Explicitly sending SOAP Stop to ${targetDevice.friendlyName} (${targetDevice.controlUrl})...")
                        val stopResult = HqpeClient.stop(targetDevice.controlUrl)
                        if (stopResult.isFailure) {
                            Log.w(TAG, "SOAP Stop completed with warning: ${stopResult.exceptionOrNull()?.message}")
                        } else {
                            Log.i(TAG, "SOAP Stop executed successfully on remote device.")
                        }
                    }

                    // 2. 停止本地 HTTP 服务与 Pacer 线程
                    localHttpServer?.stop()
                    localHttpServer = null
                    currentDevice = null

                    updateState(DlnaState.STOPPED)
                    updateState(DlnaState.IDLE)
                    callback?.invoke(true, null)
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        return@withLock
                    }
                    Log.e(TAG, "stopStreaming error: ${e.message}", e)
                    localHttpServer?.stop()
                    localHttpServer = null
                    currentDevice = null
                    updateState(DlnaState.IDLE)
                    callback?.invoke(false, e.message)
                }
            }
        }
        activeSessionJob = job
    }

    /**
     * 向 HTTP 推流写入 PCM 音频数据 (推入环形缓冲池)
     */
    fun writePcmData(buffer: ByteArray, offset: Int, length: Int) {
        localHttpServer?.writeAudioData(buffer, offset, length)
    }

    /**
     * 原子排空环形缓冲池 (换曲、定位时调用)
     */
    fun clearPcmBuffer() {
        localHttpServer?.clearBuffer()
    }

    private fun updateState(newState: DlnaState, message: String? = null) {
        val previousState = currentState.getAndSet(newState)
        Log.i(TAG, "DLNA State transition: $previousState -> $newState (message=$message)")
        // 只要推流结束、断开或发生异常回退至非活跃态，必须严格释放系统锁以进入低功耗待机
        if (newState == DlnaState.IDLE || newState == DlnaState.STOPPED || newState == DlnaState.ERROR) {
            releaseLocks()
        }
        eventListener?.onStateChanged(newState, message)
    }
}
