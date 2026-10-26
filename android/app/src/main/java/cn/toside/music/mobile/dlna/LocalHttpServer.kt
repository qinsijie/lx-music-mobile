package cn.toside.music.mobile.dlna

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地实时音频推流 HTTP 服务器
 * 遵循 HQPE DLNA 音频链路规范：
 * - 监听端口默认 8088，向 HQPE 暴露 /stream.wav
 * - 48000 Hz, 16-bit, 双声道 (Stereo) 线性 PCM
 * - 44 字节实时 WAV 头构造 (Little-Endian ChunkSize/Subchunk2Size 固定为 0x7FFFFFF7)
 * - HTTP 响应头严格符合规范
 */
class LocalHttpServer(val port: Int = 8088) {

    private val tag = "LocalHttpServer"
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    private class ClientSubscriber(
        val socket: Socket,
        val outputStream: OutputStream,
        val isChunked: Boolean
    )

    private val subscribers = CopyOnWriteArrayList<ClientSubscriber>()
    private val crlf = "\r\n".toByteArray(Charsets.US_ASCII)

    private var silenceThread: Thread? = null
    private val lastRealAudioTime = AtomicLong(0L)
    private val SILENCE_THRESHOLD_MS = 80L // 80ms 无真实 PCM 判定为缓冲/切歌空档
    private val SILENCE_CHUNK_BYTES = 3840 // 20ms 的 48000Hz 16-bit 双声道 PCM (48000 * 2 * 2 * 0.02)
    private val silenceChunk = ByteArray(SILENCE_CHUNK_BYTES)

    @Synchronized
    fun start(): Boolean {
        if (isRunning.get()) return true

        try {
            val server = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            server.reuseAddress = true
            serverSocket = server
            isRunning.set(true)

            acceptThread = Thread({
                Log.i(tag, "HTTP Server started on 0.0.0.0:$port")
                while (isRunning.get() && !server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        Thread({ handleClient(clientSocket) }, "Http-Client-${clientSocket.port}").start()
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(tag, "Error accepting client connection", e)
                        }
                    }
                }
            }, "HttpServer-Accept").apply { start() }

            silenceThread = Thread({ runSilenceFeeder() }, "HttpServer-SilenceFeeder").apply { start() }

            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start HTTP server on port $port", e)
            return false
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 65536
            try {
                socket.trafficClass = 0x10 // IPTOS_LOWDELAY
            } catch (_: Exception) {}

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val rawOut = socket.getOutputStream()
            val out = BufferedOutputStream(rawOut, 16384)

            val requestLine = reader.readLine() ?: run {
                socket.close()
                return
            }

            Log.i(tag, "Incoming request: $requestLine from ${socket.remoteSocketAddress}")

            var hasChunkedHeader = false
            var line: String? = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val lower = line.lowercase()
                if (lower.contains("te:") && lower.contains("chunked")) {
                    hasChunkedHeader = true
                }
                line = reader.readLine()
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendNotFound(out)
                socket.close()
                return
            }

            val method = parts[0].uppercase()
            val path = parts[1]

            if (path == "/stream.wav" || path.startsWith("/stream.wav?")) {
                if (method == "HEAD") {
                    sendHeadResponse(out)
                    out.flush()
                    socket.close()
                    return
                }

                if (method == "GET") {
                    val isChunked = path.contains("chunked=1")
                    val wavHeader = createWavHeader(48000, 2, 16)
                    val silencePreBuffer = ByteArray(38400) // 200ms 48000Hz 16-bit 双声道 PCM 静音数据预填充
                    val subscriber = ClientSubscriber(socket, out, isChunked)

                    synchronized(subscriber.outputStream) {
                        if (isChunked) {
                            sendChunkedHeaders(out)
                            sendChunk(out, wavHeader, 0, wavHeader.size)
                            sendChunk(out, silencePreBuffer, 0, silencePreBuffer.size)
                        } else {
                            sendRawContinuousHeaders(out)
                            out.write(wavHeader)
                            out.write(silencePreBuffer)
                        }
                        out.flush()
                    }

                    subscribers.add(subscriber)
                    Log.i(tag, "Client subscribed to audio stream (chunked=$isChunked): ${socket.remoteSocketAddress}. 44-byte WAV header and 200ms silence pre-buffer sent and flushed immediately. Total clients: ${subscribers.size}")

                    // Keep socket open until client disconnects
                    val input = socket.getInputStream()
                    val dummy = ByteArray(128)
                    while (isRunning.get() && !socket.isClosed) {
                        val read = input.read(dummy)
                        if (read == -1) {
                            break
                        }
                    }

                    subscribers.remove(subscriber)
                    try { socket.close() } catch (_: Exception) {}
                    Log.i(tag, "Client disconnected: ${socket.remoteSocketAddress}. Remaining clients: ${subscribers.size}")
                    return
                }
            }

            // Unknown endpoint
            sendNotFound(out)
            out.flush()
            socket.close()

        } catch (e: Exception) {
            Log.d(tag, "Client handling finished or aborted: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendRawContinuousHeaders(out: OutputStream) {
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/wav\r\n" +
                "Server: lx-music-hqpe/1.0\r\n" +
                "Connection: close\r\n" +
                "Accept-Ranges: none\r\n" +
                "\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
    }

    private fun sendChunkedHeaders(out: OutputStream) {
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/wav\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Server: lx-music-hqpe/1.0\r\n" +
                "Connection: close\r\n" +
                "Accept-Ranges: none\r\n" +
                "\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
    }

    private fun sendHeadResponse(out: OutputStream) {
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: audio/wav\r\n" +
                "Server: lx-music-hqpe/1.0\r\n" +
                "Connection: close\r\n" +
                "Accept-Ranges: none\r\n" +
                "\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
    }

    private fun sendNotFound(out: OutputStream) {
        val response = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
    }

    private fun sendChunk(out: OutputStream, data: ByteArray, offset: Int, length: Int) {
        val hexSize = (Integer.toHexString(length) + "\r\n").toByteArray(Charsets.US_ASCII)
        out.write(hexSize)
        out.write(data, offset, length)
        out.write(crlf)
        out.flush()
    }

    /**
     * 将 PCM 音频数据分发写入所有活跃的客户端连接
     */
    fun writeAudioData(buffer: ByteArray, offset: Int, length: Int) {
        if (!isRunning.get() || subscribers.isEmpty() || length <= 0) return

        lastRealAudioTime.set(System.currentTimeMillis())

        for (subscriber in subscribers) {
            try {
                synchronized(subscriber.outputStream) {
                    if (subscriber.isChunked) {
                        sendChunk(subscriber.outputStream, buffer, offset, length)
                    } else {
                        subscriber.outputStream.write(buffer, offset, length)
                        subscriber.outputStream.flush()
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "Failed to write audio data to ${subscriber.socket.remoteSocketAddress}, closing socket: ${e.message}")
                subscribers.remove(subscriber)
                try { subscriber.socket.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 后台静音垫片轮询器：
     * 当切歌、网络缓冲或暂停导致 ExoPlayer 短暂停止流入 PCM 数据时，
     * 以严格 48000Hz 16-bit 双声道实时节拍 (192,000 bytes/s) 持续输出静音 PCM，
     * 确保 HQPE 永远不会遇到数据饥饿、不会触发 EOS / idle_req，实现换曲完全无缝不中断。
     */
    private fun runSilenceFeeder() {
        Log.i(tag, "Silence feeder thread started")
        var inSilenceMode = false
        var silenceStartTime = 0L
        var silenceBytesSent = 0L

        while (isRunning.get()) {
            try {
                if (subscribers.isEmpty()) {
                    if (inSilenceMode) {
                        inSilenceMode = false
                        silenceBytesSent = 0L
                    }
                    Thread.sleep(30)
                    continue
                }

                val now = System.currentTimeMillis()
                val lastReal = lastRealAudioTime.get()
                val elapsedSinceReal = now - lastReal

                // 若距离上一包真实 PCM 超过 SILENCE_THRESHOLD_MS (80ms)
                if (elapsedSinceReal >= SILENCE_THRESHOLD_MS) {
                    if (!inSilenceMode) {
                        inSilenceMode = true
                        silenceStartTime = System.nanoTime()
                        silenceBytesSent = 0L
                        Log.i(tag, "Silence feeder activated (gap: ${elapsedSinceReal}ms) - pumping steady silence PCM to HQPE")
                    }

                    // 速率对齐：严格按照 192,000 bytes/sec 计算应推字节数
                    val elapsedNanos = System.nanoTime() - silenceStartTime
                    val expectedBytes = (elapsedNanos * 192000L) / 1_000_000_000L

                    // 避免突发积压过多导致瞬间刷屏，最多允许预充 200ms
                    if (expectedBytes - silenceBytesSent > 38400L) {
                        silenceBytesSent = expectedBytes - 38400L
                    }

                    while (silenceBytesSent < expectedBytes && isRunning.get() && subscribers.isNotEmpty()) {
                        // 如果中途真实音频到达，立刻退出静音发送
                        if (System.currentTimeMillis() - lastRealAudioTime.get() < SILENCE_THRESHOLD_MS) {
                            break
                        }

                        for (subscriber in subscribers) {
                            try {
                                synchronized(subscriber.outputStream) {
                                    if (subscriber.isChunked) {
                                        sendChunk(subscriber.outputStream, silenceChunk, 0, SILENCE_CHUNK_BYTES)
                                    } else {
                                        subscriber.outputStream.write(silenceChunk, 0, SILENCE_CHUNK_BYTES)
                                        subscriber.outputStream.flush()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d(tag, "Client disconnected during silence send: ${e.message}")
                                subscribers.remove(subscriber)
                                try { subscriber.socket.close() } catch (_: Exception) {}
                            }
                        }
                        silenceBytesSent += SILENCE_CHUNK_BYTES
                    }

                    Thread.sleep(10)
                } else {
                    if (inSilenceMode) {
                        inSilenceMode = false
                        silenceBytesSent = 0L
                        Log.i(tag, "Real audio resumed; silence feeder on standby")
                    }
                    val sleepMs = (SILENCE_THRESHOLD_MS - elapsedSinceReal).coerceIn(10L, 30L)
                    Thread.sleep(sleepMs)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(tag, "Error in silence feeder loop", e)
                }
            }
        }
        Log.i(tag, "Silence feeder thread stopped")
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket", e)
        }
        serverSocket = null

        silenceThread?.interrupt()
        silenceThread = null

        for (subscriber in subscribers) {
            try {
                synchronized(subscriber.outputStream) {
                    if (subscriber.isChunked) {
                        subscriber.outputStream.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    }
                    subscriber.outputStream.flush()
                }
            } catch (_: Exception) {}
            try {
                subscriber.socket.close()
            } catch (_: Exception) {}
        }
        subscribers.clear()

        acceptThread?.interrupt()
        acceptThread = null
        Log.i(tag, "HTTP Server stopped")
    }

    fun isRunning(): Boolean = isRunning.get()

    fun getClientCount(): Int = subscribers.size

    companion object {
        /**
         * 44 字节实时 WAV 头构造 (Little-Endian，禁止违规)
         * ChunkSize 与 Subchunk2Size 固定设为小端整数 0x7FFFFFF7 (约 2GB)
         */
        fun createWavHeader(sampleRate: Int = 48000, channels: Int = 2, bitsPerSample: Int = 16): ByteArray {
            val byteRate = sampleRate * channels * (bitsPerSample / 8) // 192000
            val blockAlign = channels * (bitsPerSample / 8) // 4
            val header = ByteArray(44)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // 0-3: "RIFF" (ASCII Big-Endian)
            bb.put('R'.code.toByte())
            bb.put('I'.code.toByte())
            bb.put('F'.code.toByte())
            bb.put('F'.code.toByte())

            // 4-7: ChunkSize = 0x7FFFFFF7 (Little-Endian)
            bb.putInt(0x7FFFFFF7)

            // 8-11: "WAVE" (ASCII Big-Endian)
            bb.put('W'.code.toByte())
            bb.put('A'.code.toByte())
            bb.put('V'.code.toByte())
            bb.put('E'.code.toByte())

            // 12-15: "fmt " (ASCII Big-Endian)
            bb.put('f'.code.toByte())
            bb.put('m'.code.toByte())
            bb.put('t'.code.toByte())
            bb.put(' '.code.toByte())

            // 16-19: Subchunk1Size = 16 (for linear PCM, Little-Endian int)
            bb.putInt(16)

            // 20-21: AudioFormat = 1 (Linear PCM uncompressed, Little-Endian short)
            bb.putShort(1.toShort())

            // 22-23: NumChannels = 2 (Stereo, Little-Endian short)
            bb.putShort(channels.toShort())

            // 24-27: SampleRate = 48000 (Little-Endian int)
            bb.putInt(sampleRate)

            // 28-31: ByteRate = 192000 (Little-Endian int)
            bb.putInt(byteRate)

            // 32-33: BlockAlign = 4 (Little-Endian short)
            bb.putShort(blockAlign.toShort())

            // 34-35: BitsPerSample = 16 (Little-Endian short)
            bb.putShort(bitsPerSample.toShort())

            // 36-39: "data" (ASCII Big-Endian)
            bb.put('d'.code.toByte())
            bb.put('a'.code.toByte())
            bb.put('t'.code.toByte())
            bb.put('a'.code.toByte())

            // 40-43: Subchunk2Size = 0x7FFFFFF7 (Little-Endian int)
            bb.putInt(0x7FFFFFF7)

            return header
        }
    }
}
