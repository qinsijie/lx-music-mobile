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

/**
 * 线程安全 PCM 环形缓冲区 (PcmRingBuffer)
 * 深度设为 1.5 秒 (48000Hz * 2 channels * 2 bytes * 1.5s = 288,000 字节)
 * 彻底解耦 ExoPlayer / MediaCodec 解码线程突发 (Burst) 与网络物理时钟恒定匀速推送，杜绝缓冲假满丢包
 */
class PcmRingBuffer(val capacity: Int = 288000) {
    private val buffer = ByteArray(capacity)
    private var head = 0
    private var tail = 0
    private var size = 0
    private val lock = java.lang.Object()

    private val totalInboundBytes = java.util.concurrent.atomic.AtomicLong(0L)
    private val inboundBytesInterval = java.util.concurrent.atomic.AtomicLong(0L)
    private val totalDroppedBytes = java.util.concurrent.atomic.AtomicLong(0L)
    private val droppedEventsCount = java.util.concurrent.atomic.AtomicLong(0L)

    fun write(data: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        totalInboundBytes.addAndGet(length.toLong())
        inboundBytesInterval.addAndGet(length.toLong())

        synchronized(lock) {
            var written = 0
            while (written < length) {
                val availableSpace = capacity - size
                if (availableSpace <= 0) {
                    // 缓冲区已满，等待 25ms 让 Pacer 匀速消费，提供平滑背压
                    try {
                        lock.wait(25)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (capacity - size <= 0) {
                        // 若超时仍满则丢弃最老数据，记录丢弃字节数
                        val dropSize = minOf(size, length - written)
                        head = (head + dropSize) % capacity
                        size -= dropSize
                        totalDroppedBytes.addAndGet(dropSize.toLong())
                        droppedEventsCount.incrementAndGet()
                        Log.w("PcmRingBuffer", "Buffer overflow! Dropped $dropSize bytes (total dropped: ${totalDroppedBytes.get()})")
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

    fun getAndResetInboundBytes(): Long = inboundBytesInterval.getAndSet(0L)

    fun getTotalDroppedBytes(): Long = totalDroppedBytes.get()

    fun getDroppedEventsCount(): Long = droppedEventsCount.get()
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
class LocalHttpServer(
    val port: Int = 8088,
    @Volatile var sampleRate: Int = 44100,
    @Volatile var channels: Int = 2,
    @Volatile var bitsPerSample: Int = 16
) {

    private val tag = "LocalHttpServer"
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    private class ClientSubscriber(
        val socket: Socket,
        val outputStream: OutputStream,
        val isChunked: Boolean,
        @Volatile var isClosedManually: Boolean = false
    )

    private val subscribers = CopyOnWriteArrayList<ClientSubscriber>()
    private val crlf = "\r\n".toByteArray(Charsets.US_ASCII)

    // 环形缓冲与高精度时钟匀速发包器 (深度 384,000 字节，容纳 ~2.2s 44.1k 或 ~1s 96k 充足解码缓冲)
    private val ringBuffer = PcmRingBuffer(384000)
    private val pacerLock = java.lang.Object()
    private var pacerThread: Thread? = null

    @Volatile private var streamStartTimeNanos = 0L
    @Volatile private var totalBytesSent = 0L

    /**
     * 客户端断开连接或 Broken pipe 回调通知
     */
    var onClientDisconnected: ((reason: String) -> Unit)? = null

    fun getByteRate(): Long = sampleRate.toLong() * channels * (bitsPerSample / 8)

    fun getChunkBytes(): Int {
        val frameSize = channels * (bitsPerSample / 8)
        val framesIn20ms = (sampleRate * 20) / 1000
        return framesIn20ms * frameSize
    }

    fun getLeadBufferBytes(): Long = (getByteRate() * 400L) / 1000L

    fun getMaxCatchupBytes(): Long = getByteRate() * 1L

    fun updateAudioFormat(newSampleRate: Int, newChannels: Int, newBitsPerSample: Int) {
        val changed = sampleRate != newSampleRate || channels != newChannels || bitsPerSample != newBitsPerSample
        sampleRate = newSampleRate
        channels = newChannels
        bitsPerSample = newBitsPerSample
        if (changed) {
            Log.i(tag, "LocalHttpServer audio format updated to ${newSampleRate}Hz, ${channels}ch, ${bitsPerSample}bit")
            synchronized(pacerLock) {
                pacerLock.notifyAll()
            }
        }
    }

    fun closeClients() {
        for (subscriber in subscribers) {
            subscriber.isClosedManually = true
            try { subscriber.socket.close() } catch (_: Exception) {}
        }
        subscribers.clear()
    }

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
        try {
            // 严格网络底层调优：
            socket.tcpNoDelay = true // 禁用 Nagle 算法，杜绝攒包延迟
            socket.sendBufferSize = 65536 // 64KB SO_SNDBUF，给予网络发送队列足够弹性
            try {
                socket.trafficClass = 0x10 // IPTOS_LOWDELAY，低延迟服务类型
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
                out.flush()
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
                    val curSr = sampleRate
                    val curCh = channels
                    val curBits = bitsPerSample
                    val curByteRate = curSr.toLong() * curCh * (curBits / 8)
                    val leadBytes = ((curByteRate * 400L) / 1000L).toInt()

                    val wavHeader = createWavHeader(curSr, curCh, curBits)
                    val silencePreBuffer = ByteArray(leadBytes)
                    val subscriber = ClientSubscriber(socket, out, isChunked)

                    // 先标记并清理已有旧连接 (主动关闭不触发 onClientDisconnected 级联销毁)
                    closeClients()
                    ringBuffer.clear()

                    // 先写响应头、44 字节实时 WAV 头与 400ms 首包预填充并立即 flush
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
                    streamStartTimeNanos = System.nanoTime()
                    totalBytesSent = leadBytes.toLong() // 初始已有 400ms 写入内核 Socket 形成平滑弹性跑道

                    synchronized(pacerLock) {
                        pacerLock.notifyAll()
                    }
                    Log.i(tag, "Client subscribed to audio stream ($curSr Hz $curCh ch $curBits bit): ${socket.remoteSocketAddress}. 44-byte WAV header and ${leadBytes}B silence lead-buffer sent and flushed. Pipeline is ACTIVE.")
                    return
                }
            }

            // 未知端点
            sendNotFound(out)
            out.flush()
            socket.close()

        } catch (e: Exception) {
            Log.w(tag, "Client handshake exception (${e.javaClass.simpleName}): ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * 高精度单调时钟匀速发包器 (Streaming Pacer)
     * 基于 System.nanoTime() 物理时间轴与高精度纳秒级误差累积补偿 (nanoTime drift compensation)
     * 维持 ~400ms 弹性平滑跑道，平滑所有 Android OS 线程调度与定时器抖动，完全杜绝欠载卡顿与积压延迟。
     * 出队融合：若缓冲水位有真实音频立即送出；若水位不足则补齐全 0 静音，零残留零延迟接力。
     */
    private fun runStreamingPacer() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        } catch (e: Exception) {
            Log.w(tag, "Failed to set THREAD_PRIORITY_AUDIO for StreamingPacer: ${e.message}")
        }
        Log.i(tag, "Streaming Pacer thread started with THREAD_PRIORITY_AUDIO (Bit-Perfect nanoTime drift-compensated pacing with 400ms lead buffer)")
        var sendBuffer = ByteArray(getChunkBytes())

        // 1秒轻量级聚合诊断打点变量
        var diagLastLogNanos = System.nanoTime()
        var diagBytesSent = 0L
        var diagSilenceChunks = 0L
        var diagSilenceBytes = 0L
        var diagRealChunks = 0L

        while (isRunning.get()) {
            try {
                if (subscribers.isEmpty()) {
                    synchronized(pacerLock) {
                        while (subscribers.isEmpty() && isRunning.get()) {
                            try {
                                pacerLock.wait(50)
                            } catch (_: InterruptedException) {
                                return
                            }
                        }
                    }
                    if (!isRunning.get()) break
                }

                // 动态自适应当前音轨规格参数 (通用物理公式)
                val curSr = sampleRate
                val curCh = channels
                val curBits = bitsPerSample
                val frameSize = curCh * (curBits / 8)
                val curByteRate = curSr.toLong() * frameSize
                val curChunkBytes = ((curSr * 20) / 1000) * frameSize
                val curLeadBytes = (curByteRate * 400L) / 1000L
                val curMaxCatchup = curByteRate * 1L

                if (sendBuffer.size != curChunkBytes) {
                    sendBuffer = ByteArray(curChunkBytes)
                }

                val now = System.nanoTime()
                val elapsedNanos = (now - streamStartTimeNanos).coerceAtLeast(0L)
                // 物理时间轴应当达到的水位 = 实际流逝时间对应的字节数 + 400ms 弹性领先跑道
                val targetBytes = (elapsedNanos * curByteRate) / 1_000_000_000L + curLeadBytes

                // 若系统休眠或调度导致落后超过 1000ms，重置时钟锚点，杜绝突发追赶倾泻 (Burst Catch-up)
                if (targetBytes - totalBytesSent > curMaxCatchup) {
                    streamStartTimeNanos = now
                    totalBytesSent = curLeadBytes
                    continue
                }

                // 1. 纳秒级误差累积补偿：若当前发送量落后于 targetBytes，以 20ms 包为粒度连续发包追齐水位
                var sentInThisTick = false
                while (totalBytesSent < targetBytes && isRunning.get() && subscribers.isNotEmpty()) {
                    val hasRealAudio = ringBuffer.read(sendBuffer, 0, curChunkBytes)
                    if (!hasRealAudio) {
                        sendBuffer.fill(0)
                        diagSilenceChunks++
                        diagSilenceBytes += curChunkBytes
                    } else {
                        diagRealChunks++
                    }

                    for (subscriber in subscribers) {
                        try {
                            if (subscriber.isChunked) {
                                sendChunk(subscriber.outputStream, sendBuffer, 0, curChunkBytes)
                            } else {
                                subscriber.outputStream.write(sendBuffer, 0, curChunkBytes)
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "Pacer write exception (${e.javaClass.simpleName}: ${e.message}), removing client ${subscriber.socket.remoteSocketAddress}")
                            subscribers.remove(subscriber)
                            try { subscriber.socket.close() } catch (_: Exception) {}
                            if (!subscriber.isClosedManually && subscribers.isEmpty()) {
                                notifyClientDisconnected("Pacer write failed: ${e.message}")
                            }
                        }
                    }
                    totalBytesSent += curChunkBytes
                    diagBytesSent += curChunkBytes
                    sentInThisTick = true
                }

                // 若本轮有数据写入，统一 flush 一次，确保写入内核网络缓冲区无延迟且无冗余 I/O 锁竞争
                if (sentInThisTick) {
                    for (subscriber in subscribers) {
                        try {
                            subscriber.outputStream.flush()
                        } catch (_: Exception) {}
                    }
                }

                // 2. 高精度休眠节流：计算领先字节量与对应的等待时长
                val curNow = System.nanoTime()
                val curElapsed = (curNow - streamStartTimeNanos).coerceAtLeast(0L)
                val curExpected = (curElapsed * curByteRate) / 1_000_000_000L + curLeadBytes
                val aheadBytes = totalBytesSent - curExpected

                if (aheadBytes > 0) {
                    val waitNanos = (aheadBytes * 1_000_000_000L) / curByteRate
                    val sleepMs = waitNanos / 1_000_000L
                    if (sleepMs > 2L) {
                        // 预留 2ms 冗余以抵抗 Android 线程休眠漂移超标，剩余毫秒以微睡眠等待
                        Thread.sleep((sleepMs - 2L).coerceAtMost(20L))
                    } else {
                        Thread.yield()
                    }
                } else {
                    Thread.yield()
                }

                // 3. 每秒聚合打印一次精准诊断日志 (仅在 DEBUG 环境开启，Release 零开销零 I/O 损耗)
                if (cn.toside.music.mobile.BuildConfig.DEBUG) {
                    val diagNow = System.nanoTime()
                    if (diagNow - diagLastLogNanos >= 1_000_000_000L && subscribers.isNotEmpty()) {
                        val diagElapsedSec = (diagNow - diagLastLogNanos) / 1_000_000_000.0
                        val speedKb = (diagBytesSent / diagElapsedSec) / 1024.0
                        val inBytes = ringBuffer.getAndResetInboundBytes()
                        val inSpeedKb = (inBytes / diagElapsedSec) / 1024.0

                        val ringAvailBytes = ringBuffer.available()
                        val ringAvailMs = (ringAvailBytes * 1000L) / curByteRate

                        val targetNanos = ((totalBytesSent - curLeadBytes) * 1_000_000_000L) / curByteRate
                        val actualElapsedNanos = diagNow - streamStartTimeNanos
                        val driftMs = (actualElapsedNanos - targetNanos) / 1_000_000.0

                        val leadBytes = (totalBytesSent - ((diagNow - streamStartTimeNanos) * curByteRate / 1_000_000_000L)).coerceAtLeast(0L)
                        val leadMs = (leadBytes * 1000L) / curByteRate

                        val droppedBytes = ringBuffer.getTotalDroppedBytes()

                        Log.i("DLNA-PACER-DIAG", String.format(
                            java.util.Locale.US,
                            "[DLNA-PACER-DIAG] [%dHz %dch %dbit] Speed: %.1f KB/s (In: %.1f KB/s) | RingBuffer: %dms (%dB) | Underrun: %d pkts (%dB) | LeadBuffer: %dms | Drift: %+.1fms | Dropped: %dB",
                            curSr, curCh, curBits,
                            speedKb, inSpeedKb, ringAvailMs, ringAvailBytes, diagSilenceChunks, diagSilenceBytes, leadMs, driftMs, droppedBytes
                        ))

                        diagLastLogNanos = diagNow
                        diagBytesSent = 0L
                        diagSilenceChunks = 0L
                        diagSilenceBytes = 0L
                        diagRealChunks = 0L
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
            Log.w(tag, "Active client disconnection notified: $reason")
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
        synchronized(pacerLock) {
            pacerLock.notifyAll()
        }
        pacerThread = null

        ringBuffer.clear()

        for (subscriber in subscribers) {
            subscriber.isClosedManually = true
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
