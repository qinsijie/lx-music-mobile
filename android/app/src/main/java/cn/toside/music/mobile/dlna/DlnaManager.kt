package cn.toside.music.mobile.dlna

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 */
object DlnaManager {
    private const val TAG = "DlnaManager"
    private const val DEFAULT_PORT = 8088

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var localHttpServer: LocalHttpServer? = null
    private var currentDevice: DlnaDevice? = null
    private val currentState = AtomicReference(DlnaState.IDLE)
    private var eventListener: DlnaEventListener? = null

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
     */
    fun startStreaming(context: Context, device: DlnaDevice, port: Int = DEFAULT_PORT, callback: ((Boolean, String?) -> Unit)? = null) {
        // 1. 防重复投送：若已经在向同一设备推流中，直接复用连接
        val isAlreadyStreaming = currentState.get() == DlnaState.STREAMING
        val isSameDevice = currentDevice?.id == device.id ||
                (currentDevice?.ip == device.ip && currentDevice?.controlUrl == device.controlUrl)
        val isServerActive = localHttpServer?.isRunning() == true

        if (isAlreadyStreaming && isSameDevice && isServerActive) {
            Log.i(TAG, "Already actively streaming to device ${device.friendlyName} (${device.ip}). Guarding against duplicate startStreaming.")
            callback?.invoke(true, null)
            return
        }

        // 2. 双设备切换规范：若切换到不同设备，必须先向旧设备发送 SOAP Stop，并重置本地连接
        val oldDevice = currentDevice
        if (oldDevice != null && !isSameDevice) {
            Log.i(TAG, "Switching target from ${oldDevice.friendlyName} to ${device.friendlyName}. Sending Stop to previous device first...")
            scope.launch {
                try {
                    HqpeClient.stop(oldDevice.controlUrl)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop previous device: ${e.message}")
                }
            }
            localHttpServer?.stop()
            localHttpServer = null
        }

        updateState(DlnaState.CONNECTING)
        currentDevice = device

        scope.launch {
            try {
                // 3. 启动本地 HTTP 服务及 Pacer 线程
                if (localHttpServer == null || !localHttpServer!!.isRunning()) {
                    val server = LocalHttpServer(port)
                    // 注册远端 Reset / 接管监听
                    server.onClientDisconnected = { reason ->
                        Log.i(TAG, "LocalHttpServer subscriber disconnected ($reason)")
                        if (currentState.get() == DlnaState.STREAMING) {
                            Log.w(TAG, "Remote renderer disconnected (preempted by another controller). Gracefully reverting to IDLE.")
                            stopStreamingSilently()
                        }
                    }
                    if (!server.start()) {
                        throw IllegalStateException("Failed to bind LocalHttpServer on port $port")
                    }
                    localHttpServer = server
                }

                // 清空旧缓冲残留
                localHttpServer?.clearBuffer()

                // 4. 提前激活 STREAMING 状态，解除缓冲饥饿死锁，让当前 ExoPlayer 的 PCM 音频无延迟进入 Pacer 环形缓冲区
                updateState(DlnaState.STREAMING)

                // 5. 解析面向目标设备 IP 的精准本机 IP (规避 VPN/虚拟网卡)
                val localIp = NetworkUtils.getLocalIpAddress(context, device.ip, device.port)
                val streamUrl = "http://$localIp:$port/stream.wav"
                Log.i(TAG, "Target stream URL prepared: $streamUrl for device ${device.friendlyName}")

                // 6. 执行 SOAP SetAVTransportURI
                val setUriResult = HqpeClient.setUri(device.controlUrl, streamUrl)
                if (setUriResult.isFailure) {
                    val err = setUriResult.exceptionOrNull()?.message ?: "未知错误"
                    // 若返回 701 (Transition not available)，表明 HQPE 当前已在播放该流，允许通过
                    if (err.contains("701") && (localHttpServer?.getClientCount() ?: 0) > 0) {
                        Log.w(TAG, "SetAVTransportURI returned 701 but client is already connected, treating as success.")
                    } else {
                        throw IllegalStateException("[SetAVTransportURI 阶段] $err")
                    }
                }

                // 7. 执行 SOAP Play
                val playResult = HqpeClient.play(device.controlUrl)
                if (playResult.isFailure) {
                    val err = playResult.exceptionOrNull()?.message ?: "未知错误"
                    // 若返回 701 (Transition not available)，表明 HQPE 当前已在 PLAYING 状态，允许通过
                    if (err.contains("701") && (localHttpServer?.getClientCount() ?: 0) > 0) {
                        Log.w(TAG, "Play returned 701 but client is already playing, treating as success.")
                    } else {
                        throw IllegalStateException("[Play 阶段] $err")
                    }
                }

                Log.i(TAG, "Successfully started streaming to ${device.friendlyName} via $streamUrl")
                callback?.invoke(true, null)

            } catch (e: Exception) {
                Log.e(TAG, "startStreaming error: ${e.message}", e)
                currentDevice = null
                localHttpServer?.stop()
                localHttpServer = null
                updateState(DlnaState.ERROR, e.message)
                eventListener?.onError(e.message ?: "Streaming initiation error")
                callback?.invoke(false, e.message)
            }
        }
    }

    /**
     * 规范主动停止串流：
     * 必须严格“先向 HQPE 发送 SOAP Stop 指令”，等待确认完成释放声卡，再关闭 LocalHttpServer 和 Pacer 线程
     */
    fun stopStreaming(callback: ((Boolean, String?) -> Unit)? = null) {
        val targetDevice = currentDevice
        scope.launch {
            try {
                // 1. 严格先向 HQPE 发送 SOAP Stop 指令
                if (targetDevice != null) {
                    Log.i(TAG, "Sending SOAP Stop to ${targetDevice.friendlyName} (${targetDevice.controlUrl})...")
                    val stopResult = HqpeClient.stop(targetDevice.controlUrl)
                    if (stopResult.isFailure) {
                        Log.w(TAG, "SOAP Stop completed with warning: ${stopResult.exceptionOrNull()?.message}")
                    } else {
                        Log.i(TAG, "SOAP Stop successfully executed on remote device.")
                    }
                }

                // 2. 确认后再关闭 LocalHttpServer 和 Pacer 线程
                localHttpServer?.stop()
                localHttpServer = null
                currentDevice = null

                updateState(DlnaState.STOPPED)
                updateState(DlnaState.IDLE)
                callback?.invoke(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "stopStreaming error: ${e.message}", e)
                localHttpServer?.stop()
                localHttpServer = null
                currentDevice = null
                updateState(DlnaState.IDLE)
                callback?.invoke(false, e.message)
            }
        }
    }

    /**
     * 被远端抢占或断开时的静默降级停止（不向远端重复发 Stop，防止干扰接管设备）
     */
    private fun stopStreamingSilently() {
        scope.launch {
            try {
                localHttpServer?.stop()
                localHttpServer = null
                currentDevice = null
                updateState(DlnaState.IDLE, "PREEMPTED")
            } catch (e: Exception) {
                Log.e(TAG, "stopStreamingSilently error: ${e.message}", e)
                updateState(DlnaState.IDLE, "PREEMPTED")
            }
        }
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
        currentState.set(newState)
        eventListener?.onStateChanged(newState, message)
    }
}
