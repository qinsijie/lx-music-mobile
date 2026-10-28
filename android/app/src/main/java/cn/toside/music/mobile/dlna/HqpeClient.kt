package cn.toside.music.mobile.dlna

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * HQPE / DLNA AVTransport SOAP 控制客户端
 * 严格按照 UPnP 标准构造 SOAP 请求：
 * - 携带明确包含端口的 Host 头 (例如 10.10.10.120:49152)
 * - Content-Type: text/xml; charset="utf-8"
 * - SOAPAction: "urn:schemas-upnp-org:service:AVTransport:1#ActionName"
 */
object HqpeClient {

    private const val TAG = "HqpeClient"
    private val XML_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun setUri(controlUrl: String, streamUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        setUriSync(controlUrl, streamUrl)
    }

    suspend fun play(controlUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        playSync(controlUrl)
    }

    suspend fun stop(controlUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        stopSync(controlUrl)
    }

    fun setUriSync(controlUrl: String, streamUrl: String): Result<Unit> {
        val didlLite = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
  <item id="1" parentID="0" restricted="1">
    <dc:title>LX Music Live Stream</dc:title>
    <dc:creator>LX Music</dc:creator>
    <upnp:class>object.item.audioItem.musicTrack</upnp:class>
    <res protocolInfo="http-get:*:audio/wav:*" duration="03:00:00.000" sampleFrequency="48000" nrAudioChannels="2" bitsPerSample="16">$streamUrl</res>
  </item>
</DIDL-Lite>"""

        val escapedMetaData = didlLite
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        val payload = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>$streamUrl</CurrentURI>
      <CurrentURIMetaData>$escapedMetaData</CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>""".trimIndent()

        return executeSoap(controlUrl, "SetAVTransportURI", payload)
    }

    fun playSync(controlUrl: String): Result<Unit> {
        val payload = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Speed>1</Speed>
    </u:Play>
  </s:Body>
</s:Envelope>""".trimIndent()

        return executeSoap(controlUrl, "Play", payload)
    }

    fun stopSync(controlUrl: String): Result<Unit> {
        val payload = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:Stop>
  </s:Body>
</s:Envelope>""".trimIndent()

        return executeSoap(controlUrl, "Stop", payload)
    }

    private fun executeSoap(controlUrl: String, action: String, xmlPayload: String): Result<Unit> {
        return try {
            val url = URL(controlUrl)
            val hostHeader = if (url.port != -1) "${url.host}:${url.port}" else url.host
            val soapActionHeader = "\"urn:schemas-upnp-org:service:AVTransport:1#$action\""

            Log.d(TAG, "Executing SOAP $action on $controlUrl (Host: $hostHeader)")

            val request = Request.Builder()
                .url(controlUrl)
                .header("Host", hostHeader)
                .header("SOAPAction", soapActionHeader)
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .post(xmlPayload.toRequestBody(XML_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Log.i(TAG, "SOAP $action succeeded (HTTP ${response.code})")
                    Result.success(Unit)
                } else {
                    Log.e(TAG, "SOAP $action failed (HTTP ${response.code}): $body")
                    Result.failure(IOException("SOAP $action failed: HTTP ${response.code} $body"))
                }
            }
        } catch (e: Exception) {
            val errMsg = if (e is java.net.SocketTimeoutException) {
                "SOAP $action 超时 (15秒未收到响应)"
            } else {
                "SOAP $action 异常: ${e.message}"
            }
            Log.e(TAG, errMsg, e)
            Result.failure(IOException(errMsg, e))
        }
    }
}
