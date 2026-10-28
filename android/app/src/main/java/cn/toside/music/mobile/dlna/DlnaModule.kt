package cn.toside.music.mobile.dlna

import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * React Native 桥接模块: LxDlnaModule
 * 暴露扫描、投送、断开、状态查询等 API 给前端 JavaScript/TypeScript
 */
class DlnaModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), DlnaEventListener {

    init {
        DlnaManager.setEventListener(this)
    }

    override fun getName(): String = "LxDlnaModule"

    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        DlnaManager.stopStreaming()
        DlnaManager.setEventListener(null)
    }

    @ReactMethod
    fun scanDevices(timeoutMs: Int, promise: Promise) {
        try {
            val timeout = if (timeoutMs > 0) timeoutMs else 3000
            DlnaManager.scanDevices(reactContext, timeout) { devices ->
                val array = Arguments.createArray()
                for (device in devices) {
                    array.pushMap(device.toWritableMap())
                }
                promise.resolve(array)
            }
        } catch (e: Exception) {
            promise.reject("SCAN_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun startStreaming(deviceMap: ReadableMap, port: Int, promise: Promise) {
        try {
            val id = if (deviceMap.hasKey("id")) deviceMap.getString("id") ?: "" else ""
            val name = if (deviceMap.hasKey("name")) deviceMap.getString("name") ?: "" else ""
            val ip = if (deviceMap.hasKey("ip")) deviceMap.getString("ip") ?: "" else ""
            val targetPort = if (deviceMap.hasKey("port")) deviceMap.getInt("port") else 80
            val location = if (deviceMap.hasKey("location")) deviceMap.getString("location") ?: "" else ""
            val controlUrl = if (deviceMap.hasKey("controlUrl")) deviceMap.getString("controlUrl") ?: "" else ""

            if (ip.isEmpty() || controlUrl.isEmpty()) {
                promise.reject("INVALID_PARAMS", "Device IP or controlUrl cannot be empty")
                return
            }

            val device = DlnaDevice(
                id = id.ifEmpty { "$ip:$targetPort" },
                friendlyName = name.ifEmpty { "DLNA Player ($ip)" },
                ip = ip,
                port = targetPort,
                location = location,
                controlUrl = controlUrl
            )

            val serverPort = if (port in 1024..65535) port else 8088
            DlnaManager.startStreaming(reactContext, device, serverPort) { success, error ->
                if (success) {
                    promise.resolve(true)
                } else {
                    promise.reject("STREAM_START_FAILED", error ?: "Failed to start streaming")
                }
            }
        } catch (e: Exception) {
            promise.reject("START_STREAM_EXCEPTION", e.message, e)
        }
    }

    @ReactMethod
    fun stopStreaming(promise: Promise) {
        try {
            DlnaManager.stopStreaming { success, error ->
                if (success) {
                    promise.resolve(true)
                } else {
                    promise.reject("STREAM_STOP_FAILED", error ?: "Failed to stop streaming")
                }
            }
        } catch (e: Exception) {
            promise.reject("STOP_STREAM_EXCEPTION", e.message, e)
        }
    }

    @ReactMethod
    fun getState(promise: Promise) {
        try {
            val map = Arguments.createMap()
            map.putString("state", DlnaManager.getState().name)
            val current = DlnaManager.getCurrentDevice()
            if (current != null) {
                map.putMap("device", current.toWritableMap())
            } else {
                map.putNull("device")
            }
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("GET_STATE_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getLocalIp(targetIp: String?, promise: Promise) {
        try {
            val ip = NetworkUtils.getLocalIpAddress(reactContext, targetIp)
            promise.resolve(ip)
        } catch (e: Exception) {
            promise.reject("GET_LOCAL_IP_ERROR", e.message, e)
        }
    }

    // --- DlnaEventListener 回调派发至 JS 端 ---

    override fun onStateChanged(state: DlnaState, message: String?) {
        val params = Arguments.createMap()
        params.putString("state", state.name)
        if (message != null) {
            params.putString("message", message)
        }
        sendEvent("onDlnaStateChanged", params)
    }

    override fun onDevicesDiscovered(devices: List<DlnaDevice>) {
        val array = Arguments.createArray()
        for (device in devices) {
            array.pushMap(device.toWritableMap())
        }
        val params = Arguments.createMap()
        params.putArray("devices", array)
        sendEvent("onDlnaDevicesDiscovered", params)
    }

    override fun onError(errorMessage: String) {
        val params = Arguments.createMap()
        params.putString("error", errorMessage)
        sendEvent("onDlnaError", params)
    }

    private fun sendEvent(eventName: String, params: Any?) {
        if (reactContext.hasActiveCatalystInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        }
    }
}
