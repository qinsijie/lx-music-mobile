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

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        Log.i(tag, "Configuring AudioProcessor with input: " +
                "sampleRate=${inputAudioFormat.sampleRate}, " +
                "channelCount=${inputAudioFormat.channelCount}, " +
                "encoding=${inputAudioFormat.encoding}")

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            Log.w(tag, "Audio format encoding ${inputAudioFormat.encoding} not supported by DlnaAudioProcessor")
            return AudioProcessor.AudioFormat.NOT_SET
        }

        // 保持输出与输入格式一致以保证向下游 AudioTrack 传递，拦截过程在内部处理
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

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
                // 委托规范声道适配与位深归一化至 48kHz 16-bit 双声道
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
     * 将 16-bit / Float PCM 输入流转换为标准 48000Hz 立体声 16-bit PCM (采样率由 SonicAudioProcessor 统一处理)
     */
    private fun convertToTargetPcm(buffer: ByteBuffer, format: AudioProcessor.AudioFormat): ByteArray {
        val inChannels = format.channelCount
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val totalInFrames = buffer.remaining() / (inChannels * bytesPerSample)
        if (totalInFrames <= 0) return ByteArray(0)

        val outBytes = ByteArray(totalInFrames * targetChannels * targetBytesPerSample)
        val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

        while (buffer.remaining() >= inChannels * bytesPerSample) {
            val left: Short = if (isFloat) {
                (buffer.float.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            } else {
                buffer.short
            }
            val right: Short = if (inChannels >= 2) {
                if (isFloat) {
                    (buffer.float.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
                } else {
                    buffer.short
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

            outBuf.putShort(left)
            outBuf.putShort(right)
        }

        return outBytes
    }

    override fun onFlush() {
        super.onFlush()
        Log.i(tag, "AudioProcessor onFlush() invoked (seek / track change / pipeline flush). Clearing RingBuffer.")
        DlnaManager.clearPcmBuffer()
    }

    override fun onReset() {
        super.onReset()
        onFlush()
    }

    companion object {
        private val INSTANCE = DlnaAudioProcessor()

        @JvmStatic
        fun getInstance(): DlnaAudioProcessor = INSTANCE
    }
}
