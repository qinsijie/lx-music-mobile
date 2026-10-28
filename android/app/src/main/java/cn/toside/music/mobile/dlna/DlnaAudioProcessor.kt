package cn.toside.music.mobile.dlna

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DLNA 音频拦截处理器 (Bit-Perfect 源码直通版)
 * 挂入 ExoPlayer 的 AudioSink 管道
 * 拦截解码出的 PCM 音频数据，保持原始采样率、通道数与位深，零重采样零插值
 * 并投递给 DlnaManager / LocalHttpServer 进行实时推流。
 */
@UnstableApi
class DlnaAudioProcessor : BaseAudioProcessor() {

    private val tag = "DlnaAudioProcessor"

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        Log.i(tag, "onConfigure (Bit-Perfect): " +
                "sampleRate=${inputAudioFormat.sampleRate}, " +
                "channelCount=${inputAudioFormat.channelCount}, " +
                "encoding=${inputAudioFormat.encoding}")

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            Log.w(tag, "Audio format encoding ${inputAudioFormat.encoding} not supported by DlnaAudioProcessor")
            return AudioProcessor.AudioFormat.NOT_SET
        }

        DlnaManager.setAudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, 16)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (DlnaManager.isStreaming()) {
            val duplicate = inputBuffer.duplicate()
            duplicate.order(ByteOrder.LITTLE_ENDIAN)
            val inputFormat = inputAudioFormat

            if (inputFormat.encoding == C.ENCODING_PCM_16BIT) {
                // Bit-Perfect 源码直通：100% 原始 PCM 旁路透传
                val pcmBytes = ByteArray(duplicate.remaining())
                duplicate.get(pcmBytes)
                DlnaManager.writePcmData(pcmBytes, 0, pcmBytes.size)
            } else if (inputFormat.encoding == C.ENCODING_PCM_FLOAT) {
                // Float PCM 归一化为 16-bit PCM，采样率与通道数 100% 保持原样
                val totalSamples = duplicate.remaining() / 4
                val outBytes = ByteArray(totalSamples * 2)
                val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
                while (duplicate.remaining() >= 4) {
                    val f = duplicate.float.coerceIn(-1.0f, 1.0f)
                    outBuf.putShort((f * 32767.0f).toInt().toShort())
                }
                DlnaManager.writePcmData(outBytes, 0, outBytes.size)
            }
        }

        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
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
