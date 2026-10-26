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
        // 防重复投送：若当前推流长连接处于活跃状态，拦截对同一设备的重复 SetAVTransportURI 和 Play 调用
        val isAlreadyStreaming = currentState.get() == DlnaState.STREAMING
        val isSameDevice = currentDevice?.id == device.id ||
                (currentDevice?.ip == device.ip && currentDevice?.controlUrl == device.controlUrl)
        val isServerActive = localHttpServer?.isRunning() == true

        if (isAlreadyStreaming && isSameDevice && isServerActive) {
            Log.i(TAG, "Already actively streaming to device ${device.friendlyName} (${device.ip}). Guarding against duplicate startStreaming.")
            callback?.invoke(true, null)
            return
        }

        updateState(DlnaState.CONNECTING)
        currentDevice = device

        scope.launch {
            try {
                // 1. 启动或复用本地 HTTP 服务
                if (localHttpServer == null || !localHttpServer!!.isRunning()) {
                    val server = LocalHttpServer(port)
                    if (!server.start()) {
                        throw IllegalStateException("Failed to bind LocalHttpServer on port $port")
                    }
                    localHttpServer = server
                }

                // 2. 提前激活 STREAMING 状态，解除缓冲饥饿死锁，让当前 ExoPlayer 的 PCM 音频无延迟进入缓冲区等待拉取
                updateState(DlnaState.STREAMING)

                // 3. 解析面向目标设备 IP 的精准本机 IP (规避 VPN/虚拟网卡)
                val localIp = NetworkUtils.getLocalIpAddress(context, device.ip, device.port)
                val streamUrl = "http://$localIp:$port/stream.wav"
                Log.i(TAG, "Target stream URL prepared: $streamUrl for device ${device.friendlyName}")

                // 4. 执行 SOAP SetAVTransportURI
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

                // 5. 执行 SOAP Play
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
     * 停止串流并释放远端设备
     */
    fun stopStreaming(callback: ((Boolean, String?) -> Unit)? = null) {
        val targetDevice = currentDevice
        scope.launch {
            try {
                // 1. 通知远端 DLNA 播放器停止，释放声卡占用
                if (targetDevice != null) {
                    HqpeClient.stop(targetDevice.controlUrl)
                }

                // 2. 停止本地 HTTP 服务
                localHttpServer?.stop()
                localHttpServer = null
                currentDevice = null

                updateState(DlnaState.STOPPED)
                updateState(DlnaState.IDLE)
                callback?.invoke(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "stopStreaming error: ${e.message}", e)
                currentDevice = null
                localHttpServer?.stop()
                localHttpServer = null
                updateState(DlnaState.IDLE)
                callback?.invoke(false, e.message)
            }
        }
    }

    /**
     * 向 HTTP 推流写入 PCM 音频数据
     */
    fun writePcmData(buffer: ByteArray, offset: Int, length: Int) {
        localHttpServer?.writeAudioData(buffer, offset, length)
    }

    private fun updateState(newState: DlnaState, message: String? = null) {
        currentState.set(newState)
        eventListener?.onStateChanged(newState, message)
    }
}
