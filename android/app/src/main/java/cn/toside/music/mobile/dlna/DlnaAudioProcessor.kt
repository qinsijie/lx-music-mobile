package cn.toside.music.mobile.dlna

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DLNA 音频拦截处理器
 * 挂入 ExoPlayer 的 AudioSink 管道前置层
 * 拦截解码出的 PCM 音频数据，强制归一化为 48000Hz, 16-bit, 双声道 (Stereo)，
 * 并投递给 DlnaManager / LocalHttpServer 进行实时推流。
 */
@UnstableApi
class DlnaAudioProcessor : BaseAudioProcessor() {

    private val tag = "DlnaAudioProcessor"

    // 目标推流音频规格 (硬性标准)
    private val targetSampleRate = 48000
    private val targetChannels = 2
    private val targetBytesPerSample = 2 // 16-bit = 2 bytes

    // 线性重采样与声道适配缓冲区
    private var resampleRatio = 1.0
    private var resampleFraction = 0.0
    private var lastLeftSample = 0
    private var lastRightSample = 0
    private var queueCount = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        Log.i(tag, "Configuring AudioProcessor with input: " +
                "sampleRate=${inputAudioFormat.sampleRate}, " +
                "channelCount=${inputAudioFormat.channelCount}, " +
                "encoding=${inputAudioFormat.encoding}")

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            Log.w(tag, "Audio format encoding ${inputAudioFormat.encoding} not supported by DlnaAudioProcessor")
            return AudioProcessor.AudioFormat.NOT_SET
        }

        resampleRatio = inputAudioFormat.sampleRate.toDouble() / targetSampleRate.toDouble()
        resampleFraction = 0.0
        lastLeftSample = 0
        lastRightSample = 0

        // 保持输出与输入格式一致以保证向下游 AudioTrack 传递，拦截过程在内部处理
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        queueCount++
        if (queueCount % 100L == 1L) {
            Log.i(tag, "queueInput #$queueCount: $remaining bytes received, isStreaming=${DlnaManager.isStreaming()}")
        }

        // 仅在 DLNA 处于推流状态时捕获并分发音频数据
        if (DlnaManager.isStreaming()) {
            val duplicate = inputBuffer.duplicate()
            duplicate.order(ByteOrder.LITTLE_ENDIAN)
            val inputFormat = inputAudioFormat

            if (inputFormat.encoding == C.ENCODING_PCM_16BIT &&
                inputFormat.sampleRate == targetSampleRate &&
                inputFormat.channelCount == targetChannels) {
                // 完全匹配 48kHz 16-bit 立体声，直接批量提取投递
                val pcmBytes = ByteArray(duplicate.remaining())
                duplicate.get(pcmBytes)
                DlnaManager.writePcmData(pcmBytes, 0, pcmBytes.size)
            } else {
                // 采样率、声道数或编码不一致时，实时转换归一化至 48kHz 16-bit 双声道
                val convertedBytes = convertToTargetPcm(duplicate, inputFormat)
                if (convertedBytes.isNotEmpty()) {
                    DlnaManager.writePcmData(convertedBytes, 0, convertedBytes.size)
                }
            }
        }

        // 下游通道无缝透传，ExoPlayer 本地 AudioTrack 仍能正常运转（当设置 setVolume(0) 时为静音输出）
        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    /**
     * 将任意 16-bit / Float PCM 输入流转换为标准 48000Hz 立体声 16-bit PCM
     */
    private fun convertToTargetPcm(buffer: ByteBuffer, format: AudioProcessor.AudioFormat): ByteArray {
        val inChannels = format.channelCount
        val inSampleRate = format.sampleRate
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val totalInFrames = buffer.remaining() / (inChannels * bytesPerSample)
        if (totalInFrames <= 0) return ByteArray(0)

        // 估算输出帧数
        val estimatedOutFrames = ((totalInFrames / resampleRatio) + 2).toInt()
        val outBytes = ByteArray(estimatedOutFrames * targetChannels * targetBytesPerSample)
        val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

        while (buffer.remaining() >= inChannels * bytesPerSample) {
            val left = if (isFloat) {
                (buffer.float.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt()
            } else {
                buffer.short.toInt()
            }
            val right = if (inChannels >= 2) {
                if (isFloat) {
                    (buffer.float.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt()
                } else {
                    buffer.short.toInt()
                }
            } else left

            // 跳过多余声道
            if (inChannels > 2) {
                for (ch in 2 until inChannels) {
                    if (buffer.remaining() >= bytesPerSample) {
                        if (isFloat) buffer.float else buffer.short
                    }
                }
            }

            if (inSampleRate == targetSampleRate) {
                if (outBuf.remaining() >= 4) {
                    outBuf.putShort(left.toShort())
                    outBuf.putShort(right.toShort())
                }
            } else {
                // 线性插值重采样
                while (resampleFraction < 1.0 && outBuf.remaining() >= 4) {
                    val interpLeft = (lastLeftSample + (left - lastLeftSample) * resampleFraction).toInt().coerceIn(-32768, 32767)
                    val interpRight = (lastRightSample + (right - lastRightSample) * resampleFraction).toInt().coerceIn(-32768, 32767)
                    outBuf.putShort(interpLeft.toShort())
                    outBuf.putShort(interpRight.toShort())
                    resampleFraction += resampleRatio
                }
                resampleFraction -= 1.0
            }

            lastLeftSample = left
            lastRightSample = right
        }

        val outSize = outBuf.position()
        return if (outSize == outBytes.size) {
            outBytes
        } else {
            outBytes.copyOf(outSize)
        }
    }

    override fun onFlush() {
        resampleFraction = 0.0
        lastLeftSample = 0
        lastRightSample = 0
    }

    override fun onReset() {
        onFlush()
    }

    companion object {
        private val INSTANCE = DlnaAudioProcessor()

        @JvmStatic
        fun getInstance(): DlnaAudioProcessor = INSTANCE
    }
}
