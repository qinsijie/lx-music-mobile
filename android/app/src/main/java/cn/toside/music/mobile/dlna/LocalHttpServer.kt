package cn.toside.music.mobile.dlna

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 线程安全 PCM 环形缓冲区 (PcmRingBuffer)
 * 深度设为 250ms (48000Hz * 2 channels * 2 bytes * 0.25s = 48,000 字节)
 * 彻底解耦 ExoPlayer 解码线程突发 (Burst) 与网络物理时钟恒定匀速推送
 */
class PcmRingBuffer(val capacity: Int = 48000) {
    private val buffer = ByteArray(capacity)
    private var head = 0
    private var tail = 0
    private var size = 0
    private val lock = java.lang.Object()

    fun write(data: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        synchronized(lock) {
            var written = 0
            while (written < length) {
                val availableSpace = capacity - size
                if (availableSpace <= 0) {
                    // 缓冲区已满，微等待 15ms 让 Pacer 匀速消费，提供平滑背压
                    try {
                        lock.wait(15)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (capacity - size <= 0) {
                        // 若超时仍满则丢弃最老数据，防止彻底阻塞 ExoPlayer 播放器内部线程
                        val dropSize = minOf(size, length - written)
                        head = (head + dropSize) % capacity
                        size -= dropSize
                    }
                }
                val toWrite = minOf(length - written, capacity - size)
                val firstChunk = minOf(toWrite, capacity - tail)
                System.arraycopy(data, offset + written, buffer, tail, firstChunk)
                tail = (tail + firstChunk) % capacity
                val secondChunk = toWrite - firstChunk
                if (secondChunk > 0) {
                    System.arraycopy(data, offset + written + firstChunk, buffer, 0, secondChunk)
                    tail = secondChunk
                }
                size += toWrite
                written += toWrite
                lock.notifyAll()
            }
            return written
        }
    }

    /**
     * 读取指定字节数，若可用真实数据充足则返回 true 并填充 dest；
     * 若数据不足则返回 false (由外部 Pacer 补齐静音)，保证绝对零静音残留
     */
    fun read(dest: ByteArray, offset: Int, length: Int): Boolean {
        synchronized(lock) {
            if (size < length) return false
            val firstChunk = minOf(length, capacity - head)
            System.arraycopy(buffer, head, dest, offset, firstChunk)
            head = (head + firstChunk) % capacity
            val secondChunk = length - firstChunk
            if (secondChunk > 0) {
                System.arraycopy(buffer, 0, dest, offset + firstChunk, secondChunk)
                head = secondChunk
            }
            size -= length
            lock.notifyAll()
            return true
        }
    }

    /**
     * 原子排空所有残留数据 (换曲、定位或起播时调用)
     */
    fun clear() {
        synchronized(lock) {
            head = 0
            tail = 0
            size = 0
            lock.notifyAll()
        }
    }

    fun available(): Int = synchronized(lock) { size }
}

/**
 * 本地实时音频推流 HTTP 服务器
 * 遵循 HQPE DLNA 音频链路规范：
 * - 监听端口默认 8088，向 HQPE 暴露 /stream.wav
 * - 48000 Hz, 16-bit, 双声道 (Stereo) 线性 PCM
 * - 44 字节实时 WAV 头构造 (Little-Endian ChunkSize/Subchunk2Size 固定为 0x7FFFFFF7)
 * - 内置高精度单调时钟匀速发包器 (Streaming Pacer)，每 10ms 恒定推送 1920 字节 (严格 192,000 字节/秒)
 * - 静音垫片出队即时融合，真实音乐无延迟零残留输出
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

    // 环形缓冲与高精度时钟匀速发包器
    private val ringBuffer = PcmRingBuffer(48000) // 250ms 缓冲池
    private val CHUNK_MS = 10L // 每 10ms 严格匀速推送一次
    private val CHUNK_BYTES = 1920 // 48000 * 2 * 2 * 0.01 = 1920 字节
    private val TARGET_NANOS_PER_CHUNK = CHUNK_MS * 1_000_000L
    private var pacerThread: Thread? = null

    /**
     * 客户端异常断开或被远端 Reset 回调（被另一台手机接管或渲染器主动中断）
     */
    var onClientDisconnected: ((reason: String) -> Unit)? = null

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

            pacerThread = Thread({ runStreamingPacer() }, "HttpServer-StreamingPacer").apply { start() }

            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start HTTP server on port $port", e)
            return false
        }
    }

    private fun handleClient(socket: Socket) {
        var registeredSubscriber: ClientSubscriber? = null
        try {
            // 严格网络底层调优：
            socket.tcpNoDelay = true // 禁用 Nagle 算法，杜绝攒包延迟
            socket.sendBufferSize = 16384 // 缩减 SO_SNDBUF 至 16KB，配合 10ms 小包推送，杜绝系统内核缓冲区积压
            try {
                socket.trafficClass = 0x10 // IPTOS_LOWDELAY，低延迟服务类型
            } catch (_: Exception) {}

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

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
                    val silencePreBuffer = ByteArray(38400) // 200ms 48000Hz 16-bit 双声道 PCM 静音数据首包预填充
                    val subscriber = ClientSubscriber(socket, out, isChunked)

                    // 如果有已有旧连接，先主动释放旧连接，防止多设备/重复拉流冲突
                    for (oldSub in subscribers) {
                        try { oldSub.socket.close() } catch (_: Exception) {}
                    }
                    subscribers.clear()
                    ringBuffer.clear()

                    // 先写响应头、44 字节实时 WAV 头与 200ms 首包预填充
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

                    subscribers.add(subscriber)
                    registeredSubscriber = subscriber
                    Log.i(tag, "Client subscribed to audio stream: ${socket.remoteSocketAddress}. 44-byte WAV header and 200ms silence pre-buffer sent and flushed.")

                    // 持续监听客户端 Socket 连接状态 (阻塞等待客户端关闭或对端 Reset)
                    val input = socket.getInputStream()
                    val dummy = ByteArray(128)
                    while (isRunning.get() && !socket.isClosed) {
                        val read = input.read(dummy)
                        if (read == -1) {
                            break
                        }
                    }

                    Log.i(tag, "Client socket read reached EOF: ${socket.remoteSocketAddress}")
                    subscribers.remove(subscriber)
                    try { socket.close() } catch (_: Exception) {}
                    notifyClientDisconnected("Client closed connection (EOF)")
                    return
                }
            }

            // 未知端点
            sendNotFound(out)
            out.flush()
            socket.close()

        } catch (e: Exception) {
            Log.d(tag, "Client handling exception (${e.javaClass.simpleName}): ${e.message}")
            registeredSubscriber?.let { subscribers.remove(it) }
            try { socket.close() } catch (_: Exception) {}
            if (e is SocketException || e.message?.contains("reset", ignoreCase = true) == true) {
                notifyClientDisconnected("SocketException: ${e.message}")
            }
        }
    }

    /**
     * 高精度单调时钟匀速发包器 (Streaming Pacer)
     * 基于 System.nanoTime() 锚定时间轴，以严格的 48kHz 16-bit 双声道节奏 (192,000 字节/秒，每 10ms 一包 1920 字节)
     * 匀速推流，平滑所有解码突发 (Burst)。
     * 出队融合：有真实数据送出真实数据；无真实数据动态送出静音；新曲目到达时零残留立即接续。
     */
    private fun runStreamingPacer() {
        Log.i(tag, "Streaming Pacer thread started (10ms / 1920 bytes pacing, strictly 192,000 B/s)")
        val sendBuffer = ByteArray(CHUNK_BYTES)
        var nextSendTimeNanos = System.nanoTime()

        while (isRunning.get()) {
            try {
                if (subscribers.isEmpty()) {
                    Thread.sleep(20)
                    nextSendTimeNanos = System.nanoTime()
                    continue
                }

                // 1. 高精度时钟对齐
                val now = System.nanoTime()
                val waitNanos = nextSendTimeNanos - now
                if (waitNanos > 1_500_000L) { // 剩余时间 > 1.5ms，进入睡眠
                    Thread.sleep(waitNanos / 1_000_000L)
                } else if (waitNanos > 0) {
                    Thread.yield()
                }

                val curTime = System.nanoTime()
                // 若由于系统线程调度或休眠导致落后超过 50ms，重置时钟锚点，杜绝突发追赶倾泻 (Burst Catch-up)
                if (curTime - nextSendTimeNanos > 50_000_000L) {
                    nextSendTimeNanos = curTime
                }
                nextSendTimeNanos += TARGET_NANOS_PER_CHUNK

                // 2. 出队融合：若缓冲水位有真实音频立即送出；若水位不足则补齐全 0 静音，保证绝对零静音残留
                val hasRealAudio = ringBuffer.read(sendBuffer, 0, CHUNK_BYTES)
                if (!hasRealAudio) {
                    sendBuffer.fill(0)
                }

                // 3. 严格匀速写入活跃客户端 Socket
                for (subscriber in subscribers) {
                    try {
                        if (subscriber.isChunked) {
                            sendChunk(subscriber.outputStream, sendBuffer, 0, CHUNK_BYTES)
                        } else {
                            subscriber.outputStream.write(sendBuffer, 0, CHUNK_BYTES)
                            subscriber.outputStream.flush()
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Pacer write exception (${e.javaClass.simpleName}: ${e.message}), removing client ${subscriber.socket.remoteSocketAddress}")
                        subscribers.remove(subscriber)
                        try { subscriber.socket.close() } catch (_: Exception) {}
                        notifyClientDisconnected("Pacer write failed: ${e.message}")
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(tag, "Error in streaming pacer loop", e)
                }
            }
        }
        Log.i(tag, "Streaming Pacer thread stopped")
    }

    private fun notifyClientDisconnected(reason: String) {
        if (isRunning.get()) {
            Log.i(tag, "Client disconnection notified: $reason")
            onClientDisconnected?.invoke(reason)
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
     * 将 PCM 音频数据压入环形缓冲池，由 Pacer 线程进行时钟匀速调度
     */
    fun writeAudioData(buffer: ByteArray, offset: Int, length: Int) {
        if (!isRunning.get() || length <= 0) return
        ringBuffer.write(buffer, offset, length)
    }

    /**
     * 原子排空环形缓冲池（切歌、跳转定位时调用）
     */
    fun clearBuffer() {
        ringBuffer.clear()
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

        pacerThread?.interrupt()
        pacerThread = null

        ringBuffer.clear()

        for (subscriber in subscribers) {
            try {
                if (subscriber.isChunked) {
                    subscriber.outputStream.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                }
                subscriber.outputStream.flush()
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
