package cn.toside.music.mobile.dlna

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/**
 * DLNA/UPnP 音频渲染设备数据模型
 */
data class DlnaDevice(
    val id: String,
    val friendlyName: String,
    val ip: String,
    val port: Int,
    val location: String,
    val controlUrl: String
) {
    override fun toString(): String = "$friendlyName ($ip:$port)"

    fun toWritableMap(): WritableMap {
        val map = Arguments.createMap()
        map.putString("id", id)
        map.putString("name", friendlyName)
        map.putString("ip", ip)
        map.putInt("port", port)
        map.putString("location", location)
        map.putString("controlUrl", controlUrl)
        return map
    }
}
