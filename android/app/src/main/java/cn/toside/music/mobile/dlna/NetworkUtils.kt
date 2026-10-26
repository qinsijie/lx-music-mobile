package cn.toside.music.mobile.dlna

import android.content.Context
import android.util.Log
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 局域网 IP 与网络接口解析工具
 */
object NetworkUtils {
    private const val TAG = "DlnaNetworkUtils"

    /**
     * 获取指向目标 IP 的本机局域网 IPv4 地址
     * 优先通过 UDP 内核路由探测（不实际发送任何报文）
     * 其次 fallback 至活跃的 WiFi/以太网接口
     */
    fun getLocalIpAddress(context: Context, targetIp: String? = null, targetPort: Int = 80): String {
        // 1. 如果提供了目标 IP，通过内核路由表探测本端绑定的输出 IP
        if (!targetIp.isNullOrBlank() && !targetIp.startsWith("127.")) {
            val routedIp = getIpViaRoutingProbe(targetIp, targetPort)
            if (!routedIp.isNullOrBlank()) {
                Log.i(TAG, "Resolved local IP via kernel routing probe to $targetIp: $routedIp")
                return routedIp
            }
        }

        // 2. 尝试从 WiFi 接口 (wlan0, etc.) 获取
        val wifiIp = getWifiIpAddress()
        if (!wifiIp.isNullOrBlank()) {
            Log.i(TAG, "Resolved local IP via wlan interface: $wifiIp")
            return wifiIp
        }

        // 3. 遍历物理非 VPN 网卡接口
        val physicalIp = getPhysicalInterfaceIp()
        if (!physicalIp.isNullOrBlank()) {
            Log.i(TAG, "Resolved local IP via physical interface: $physicalIp")
            return physicalIp
        }

        return "127.0.0.1"
    }

    private fun getIpViaRoutingProbe(targetIp: String, targetPort: Int): String? {
        return try {
            DatagramSocket().use { socket ->
                val port = if (targetPort in 1..65535) targetPort else 80
                socket.connect(InetAddress.getByName(targetIp), port)
                val localAddr = socket.localAddress
                if (localAddr is Inet4Address && !localAddr.isLoopbackAddress && !localAddr.isAnyLocalAddress) {
                    val host = localAddr.hostAddress
                    if (!host.isNullOrBlank() && !host.startsWith("127.")) {
                        val intf = NetworkInterface.getByInetAddress(localAddr)
                        val name = intf?.name?.lowercase() ?: ""
                        if (!isIgnoredInterface(name)) {
                            return host
                        } else {
                            Log.w(TAG, "Routing probe returned VPN/virtual interface ($name): $host, bypassing")
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Routing probe failed for $targetIp: ${e.message}")
            null
        }
    }

    private fun getWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) {
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank() && !host.startsWith("127.")) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking wlan interface: ${e.message}")
        }
        return null
    }

    private fun getPhysicalInterfaceIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (isIgnoredInterface(name)) continue

                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking physical interfaces: ${e.message}")
        }
        return null
    }

    private fun isIgnoredInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("tun") ||
                lower.startsWith("tap") ||
                lower.startsWith("ppp") ||
                lower.startsWith("p2p") ||
                lower.startsWith("dummy") ||
                lower.startsWith("rmnet") ||
                lower.startsWith("ccmni") ||
                lower.contains("docker") ||
                lower.contains("vbox")
    }
}
