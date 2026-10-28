// 修补依赖源码以使构建的依赖恢复正常工作

const fs = require('node:fs')
const path = require('node:path')

const rootPath = path.join(__dirname, './')

const dlnaAudioProcessorCode = `package com.guichaguri.trackplayer.service;

import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@UnstableApi
public class DlnaAudioProcessor extends BaseAudioProcessor {

    private static final String TAG = "DlnaAudioProcessor";

    public interface AudioDataListener {
        void onAudioFormat(int sampleRate, int channelCount, int bitsPerSample);
        void onAudioData(byte[] buffer, int offset, int length);
        void onFlush();
    }

    private static volatile AudioDataListener dataListener = null;

    public static void setListener(AudioDataListener listener) {
        Log.i(TAG, "AudioDataListener registered: " + listener);
        dataListener = listener;
    }

    public static AudioDataListener getListener() {
        return dataListener;
    }

    private static final DlnaAudioProcessor INSTANCE = new DlnaAudioProcessor();

    public static DlnaAudioProcessor getInstance() {
        return INSTANCE;
    }

    public DlnaAudioProcessor() {
        Log.i(TAG, "DlnaAudioProcessor instance constructed!");
    }

    @Override
    public AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat inputAudioFormat) {
        Log.i(TAG, "onConfigure (Bit-Perfect): sampleRate=" + inputAudioFormat.sampleRate +
                ", channels=" + inputAudioFormat.channelCount +
                ", encoding=" + inputAudioFormat.encoding);

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            Log.w(TAG, "Unsupported encoding: " + inputAudioFormat.encoding);
            return AudioProcessor.AudioFormat.NOT_SET;
        }

        // 通知音频规格变更 (例如 44100Hz, 2声道, 16位深)
        int bitsPerSample = 16;
        AudioDataListener listener = dataListener;
        if (listener != null) {
            listener.onAudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, bitsPerSample);
        }

        return inputAudioFormat;
    }

    @Override
    protected void onFlush() {
        super.onFlush();
        Log.i(TAG, "AudioProcessor onFlush() invoked. Notifying listener to clear buffer.");
        AudioDataListener listener = dataListener;
        if (listener != null) {
            listener.onFlush();
        }
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;

        AudioDataListener listener = dataListener;

        if (listener != null) {
            ByteBuffer duplicate = inputBuffer.duplicate();
            duplicate.order(ByteOrder.LITTLE_ENDIAN);
            AudioFormat format = inputAudioFormat;

            if (format.encoding == C.ENCODING_PCM_16BIT) {
                // Bit-Perfect 源码直通：100% 原始 PCM 旁路透传，零重采样零插值
                byte[] pcmBytes = new byte[duplicate.remaining()];
                duplicate.get(pcmBytes);
                listener.onAudioData(pcmBytes, 0, pcmBytes.length);
            } else if (format.encoding == C.ENCODING_PCM_FLOAT) {
                // Float PCM 归一化为 16-bit PCM，采样率与通道数 100% 保持原样
                int inChannels = format.channelCount;
                int totalSamples = duplicate.remaining() / 4;
                byte[] outBytes = new byte[totalSamples * 2];
                ByteBuffer outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN);
                while (duplicate.remaining() >= 4) {
                    float f = Math.max(-1.0f, Math.min(1.0f, duplicate.getFloat()));
                    outBuf.putShort((short) (f * 32767.0f));
                }
                listener.onAudioData(outBytes, 0, outBytes.length);
            }
        }

        ByteBuffer outputBuffer = replaceOutputBuffer(remaining);
        outputBuffer.put(inputBuffer);
        outputBuffer.flip();
    }

    @Override
    public boolean isActive() {
        return inputAudioFormat != AudioFormat.NOT_SET;
    }
}
`

const patchTrackPlayer = async() => {
  // 1. Write DlnaAudioProcessor.java directly into react-native-track-player
  const dlnaProcessorPath = path.join(rootPath, 'node_modules/react-native-track-player/android/src/main/java/com/guichaguri/trackplayer/service/DlnaAudioProcessor.java')
  const dir = path.dirname(dlnaProcessorPath)
  if (fs.existsSync(dir)) {
    console.log(`Writing DlnaAudioProcessor.java to ${dlnaProcessorPath}`)
    await fs.promises.writeFile(dlnaProcessorPath, dlnaAudioProcessorCode, 'utf8')
    console.log('DlnaAudioProcessor.java successfully written.')
  } else {
    console.warn(`Directory ${dir} not found. Skipping DlnaAudioProcessor.java write.`)
  }

  // 2. Patch MusicManager.java in react-native-track-player to install DlnaAudioProcessor and force software offload=false
  const musicManagerPath = path.join(rootPath, 'node_modules/react-native-track-player/android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java')
  if (fs.existsSync(musicManagerPath)) {
    console.log(`Patching ${musicManagerPath}...`)
    let content = (await fs.promises.readFile(musicManagerPath, 'utf8')).toString()

    // Disable audioOffload
    const offloadPattern = 'boolean shouldEnableAudioOffload = false;'
    const offloadSearch = /boolean shouldEnableAudioOffload\s*=\s*[^;]+;/
    if (offloadSearch.test(content) && !content.includes(offloadPattern)) {
      content = content.replace(offloadSearch, offloadPattern)
      console.log('Patched audioOffload to false.')
    } else if (content.includes(offloadPattern)) {
      console.log('audioOffload already patched.')
    } else {
      console.warn('Could not find audioOffload pattern in MusicManager.java!')
    }

    // Replace DefaultRenderersFactory instantiation with custom AudioSink builder (Bit-Perfect direct pipeline, zero resampling)
    const rfPattern = 'DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(service);'
    const rfReplacement = `DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(service) {
            @Override
            protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                    Context context,
                    boolean enableFloatOutput,
                    boolean enableAudioTrackPlaybackParams) {
                android.util.Log.i("DlnaAudioProcessor", "buildAudioSink invoked in MusicManager (Bit-Perfect direct pipeline)!");
                return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[]{ DlnaAudioProcessor.getInstance() })
                        .build();
            }
        };`

    if (content.includes('buildAudioSink invoked in MusicManager')) {
      content = content.replace(
        /DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory\(service\) \{[\s\S]*?build\(\);\s*\}\s*\};/,
        rfReplacement
      )
      console.log('Updated DefaultRenderersFactory with Bit-Perfect DlnaAudioProcessor in MusicManager.java.')
    } else if (content.includes(rfPattern)) {
      content = content.replace(rfPattern, rfReplacement)
      console.log('Patched DefaultRenderersFactory in MusicManager.java.')
    } else {
      throw new Error('CRITICAL: Could not find DefaultRenderersFactory pattern in MusicManager.java!')
    }

    // Add log to onPlay
    const onPlayPattern = 'Log.d(Utils.LOG, "onPlay");'
    const onPlayReplacement = 'Log.d(Utils.LOG, "onPlay"); android.util.Log.i("DlnaAudioProcessor", "MusicManager.onPlay() called! Audio playback active.");'
    if (content.includes(onPlayPattern) && !content.includes('MusicManager.onPlay() called!')) {
      content = content.replace(onPlayPattern, onPlayReplacement)
      console.log('Patched onPlay log in MusicManager.java.')
    }

    await fs.promises.writeFile(musicManagerPath, content, 'utf8')

    // Assertion check
    const verified = (await fs.promises.readFile(musicManagerPath, 'utf8')).toString()
    if (!verified.includes('DlnaAudioProcessor.getInstance()') || !verified.includes('shouldEnableAudioOffload = false')) {
      throw new Error('CRITICAL ASSERTION FAILED: MusicManager.java patch verification failed!')
    }
    console.log('MusicManager.java patch verified successfully.')
  } else {
    console.warn(`File ${musicManagerPath} not found.`)
  }

  // 3. Ensure react-native-track-player has compiled lib directory if missing
  const rntpDir = path.join(rootPath, 'node_modules/react-native-track-player')
  const rntpLibDir = path.join(rntpDir, 'lib')
  if (fs.existsSync(rntpDir) && !fs.existsSync(rntpLibDir)) {
    console.log('Compiling react-native-track-player TypeScript source...')
    try {
      const { execSync } = require('node:child_process')
      execSync('npm run build', { cwd: rntpDir, stdio: 'inherit' })
      console.log('react-native-track-player built successfully.')
    } catch (err) {
      console.error('Failed to build react-native-track-player:', err.message)
    }
  }
}

patchTrackPlayer().catch((err) => {
  console.error('Error executing patchTrackPlayer:', err)
  process.exit(1)
})
