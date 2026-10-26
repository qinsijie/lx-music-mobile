package cn.toside.music.mobile.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SSDP / UPnP 局域网音频渲染设备发现器
 * 针对 MediaRenderer 和 AVTransport 进行精准过滤，排除路由器/NAS等无关设备
 */
object SsdpScanner {
    private const val TAG = "DlnaSsdpScanner"
    private const val MULTICAST_ADDRESS = "239.255.255.250"
    private const val MULTICAST_PORT = 1900

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun scan(context: Context, timeoutMs: Int = 3000): List<DlnaDevice> = withContext(Dispatchers.IO) {
        scanSync(context, timeoutMs)
    }

    fun scanSync(context: Context, timeoutMs: Int = 3000): List<DlnaDevice> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("lx_music_dlna_multicast_lock")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        val discoveredDevices = mutableMapOf<String, DlnaDevice>()

        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 800
                socket.broadcast = true

                val group = InetAddress.getByName(MULTICAST_ADDRESS)

                // 针对 MediaRenderer 与 AVTransport 进行定向探测，防止路由器/NAS等无关设备刷屏
                val searchTargets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1"
                )

                for (st in searchTargets) {
                    val mSearch = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: $MULTICAST_ADDRESS:$MULTICAST_PORT\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\n" +
                            "ST: $st\r\n" +
                            "\r\n"
                    val sendData = mSearch.toByteArray(Charsets.UTF_8)
                    val sendPacket = DatagramPacket(sendData, sendData.size, group, MULTICAST_PORT)
                    socket.send(sendPacket)
                }

                val startTime = System.currentTimeMillis()
                val receiveBuffer = ByteArray(4096)

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    try {
                        socket.receive(receivePacket)
                        val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                        val location = extractHeader(response, "LOCATION")
                        if (!location.isNullOrBlank()) {
                            try {
                                val url = URL(location)
                                val ip = url.host
                                val port = if (url.port != -1) url.port else 80
                                val key = "$ip:$port"
                                if (!discoveredDevices.containsKey(key)) {
                                    val device = fetchDeviceDetails(location, ip, port)
                                    if (device != null) {
                                        discoveredDevices[key] = device
                                        Log.i(TAG, "Discovered MediaRenderer: ${device.friendlyName} at $key, controlUrl: ${device.controlUrl}")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed parsing URL or fetching description: $location", e)
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // 单次读取超时，继续循环直到总 timeoutMs 耗尽
                    } catch (e: Exception) {
                        Log.e(TAG, "Error receiving SSDP response", e)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP scan error", e)
        } finally {
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {}
        }

        return discoveredDevices.values.toList()
    }

    private fun extractHeader(response: String, headerName: String): String? {
        val pattern = Pattern.compile("(?i)^$headerName:\\s*(.*)$", Pattern.MULTILINE)
        val matcher = pattern.matcher(response)
        return if (matcher.find()) {
            matcher.group(1)?.trim()
        } else {
            null
        }
    }

    private fun fetchDeviceDetails(locationUrl: String, ip: String, port: Int): DlnaDevice? {
        try {
            val request = Request.Builder().url(locationUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val xml = response.body?.string()
                    if (!xml.isNullOrBlank()) {
                        val isMediaRenderer = xml.contains("MediaRenderer", ignoreCase = true)
                        val parsedControl = parseAvTransportControlUrl(locationUrl, xml)

                        // 严格过滤：过滤掉不支持 AVTransport 且非 MediaRenderer 的节点 (如路由器、群晖/fnOS NAS 等)
                        if (parsedControl == null && !isMediaRenderer) {
                            Log.d(TAG, "Filtered out non-renderer node at $ip:$port")
                            return null
                        }

                        val controlUrl = parsedControl ?: "http://$ip:$port/upnp/control/rendertransport"
                        val friendlyName = parseFriendlyName(xml) ?: "UPnP Renderer ($ip:$port)"
                        val id = "$ip:$port"
                        return DlnaDevice(
                            id = id,
                            friendlyName = friendlyName,
                            ip = ip,
                            port = port,
                            location = locationUrl,
                            controlUrl = controlUrl
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching device description from $locationUrl: ${e.message}")
        }
        return null
    }

    private fun parseFriendlyName(xml: String): String? {
        val pattern = Pattern.compile("<friendlyName>(.*?)</friendlyName>", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(xml)
        return if (matcher.find()) matcher.group(1)?.trim() else null
    }

    private fun parseAvTransportControlUrl(locationUrl: String, xml: String): String? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

            val serviceNodes = doc.getElementsByTagName("service")
            for (i in 0 until serviceNodes.length) {
                val serviceElem = serviceNodes.item(i) as? Element ?: continue
                val serviceType = serviceElem.getElementsByTagName("serviceType").item(0)?.textContent ?: ""
                if (serviceType.contains("AVTransport", ignoreCase = true)) {
                    val controlUrlRel = serviceElem.getElementsByTagName("controlURL").item(0)?.textContent?.trim() ?: ""
                    if (controlUrlRel.isNotEmpty()) {
                        return if (controlUrlRel.startsWith("http://") || controlUrlRel.startsWith("https://")) {
                            controlUrlRel
                        } else {
                            val baseUri = URL(locationUrl)
                            val cleanRel = if (controlUrlRel.startsWith("/")) controlUrlRel else "/$controlUrlRel"
                            "${baseUri.protocol}://${baseUri.host}:${if (baseUri.port != -1) baseUri.port else 80}$cleanRel"
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "DOM parse failed for AVTransport control URL: ${e.message}")
            null
        }
    }
}
