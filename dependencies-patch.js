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
    private static final int TARGET_SAMPLE_RATE = 48000;
    private static final int TARGET_CHANNELS = 2;
    private static final int TARGET_BYTES_PER_SAMPLE = 2;

    public interface AudioDataListener {
        void onAudioData(byte[] buffer, int offset, int length);
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

    private double resampleRatio = 1.0;
    private double resampleFraction = 0.0;
    private int lastLeftSample = 0;
    private int lastRightSample = 0;

    public DlnaAudioProcessor() {
        Log.i(TAG, "DlnaAudioProcessor instance constructed!");
    }

    @Override
    public AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat inputAudioFormat) {
        Log.i(TAG, "onConfigure: sampleRate=" + inputAudioFormat.sampleRate +
                ", channels=" + inputAudioFormat.channelCount +
                ", encoding=" + inputAudioFormat.encoding);

        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            Log.w(TAG, "Unsupported encoding: " + inputAudioFormat.encoding);
            return AudioProcessor.AudioFormat.NOT_SET;
        }

        resampleRatio = (double) inputAudioFormat.sampleRate / (double) TARGET_SAMPLE_RATE;
        resampleFraction = 0.0;
        lastLeftSample = 0;
        lastRightSample = 0;

        return inputAudioFormat;
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

            if (format.encoding == C.ENCODING_PCM_16BIT &&
                format.sampleRate == TARGET_SAMPLE_RATE &&
                format.channelCount == TARGET_CHANNELS) {
                byte[] pcmBytes = new byte[duplicate.remaining()];
                duplicate.get(pcmBytes);
                listener.onAudioData(pcmBytes, 0, pcmBytes.length);
            } else {
                byte[] converted = convertToTargetPcm(duplicate, format);
                if (converted.length > 0) {
                    listener.onAudioData(converted, 0, converted.length);
                }
            }
        }

        ByteBuffer outputBuffer = replaceOutputBuffer(remaining);
        outputBuffer.put(inputBuffer);
        outputBuffer.flip();
    }

    private byte[] convertToTargetPcm(ByteBuffer buffer, AudioFormat format) {
        int inChannels = format.channelCount;
        int inSampleRate = format.sampleRate;
        boolean isFloat = format.encoding == C.ENCODING_PCM_FLOAT;
        int bytesPerSample = isFloat ? 4 : 2;
        int totalInFrames = buffer.remaining() / (inChannels * bytesPerSample);
        if (totalInFrames <= 0) return new byte[0];

        int estimatedOutFrames = (int) ((totalInFrames / resampleRatio) + 2);
        byte[] outBytes = new byte[estimatedOutFrames * TARGET_CHANNELS * TARGET_BYTES_PER_SAMPLE];
        ByteBuffer outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN);

        while (buffer.remaining() >= inChannels * bytesPerSample) {
            int left;
            if (isFloat) {
                float f = Math.max(-1.0f, Math.min(1.0f, buffer.getFloat()));
                left = (int) (f * 32767.0f);
            } else {
                left = buffer.getShort();
            }

            int right;
            if (inChannels >= 2) {
                if (isFloat) {
                    float f = Math.max(-1.0f, Math.min(1.0f, buffer.getFloat()));
                    right = (int) (f * 32767.0f);
                } else {
                    right = buffer.getShort();
                }
            } else {
                right = left;
            }

            if (inChannels > 2) {
                for (int ch = 2; ch < inChannels; ch++) {
                    if (buffer.remaining() >= bytesPerSample) {
                        if (isFloat) buffer.getFloat(); else buffer.getShort();
                    }
                }
            }

            if (inSampleRate == TARGET_SAMPLE_RATE) {
                if (outBuf.remaining() >= 4) {
                    outBuf.putShort((short) left);
                    outBuf.putShort((short) right);
                }
            } else {
                while (resampleFraction < 1.0 && outBuf.remaining() >= 4) {
                    int interpLeft = (int) (lastLeftSample + (left - lastLeftSample) * resampleFraction);
                    int interpRight = (int) (lastRightSample + (right - lastRightSample) * resampleFraction);
                    interpLeft = Math.max(-32768, Math.min(32767, interpLeft));
                    interpRight = Math.max(-32768, Math.min(32767, interpRight));
                    outBuf.putShort((short) interpLeft);
                    outBuf.putShort((short) interpRight);
                    resampleFraction += resampleRatio;
                }
                resampleFraction -= 1.0;
            }

            lastLeftSample = left;
            lastRightSample = right;
        }

        int outSize = outBuf.position();
        if (outSize == outBytes.length) {
            return outBytes;
        } else {
            byte[] trimmed = new byte[outSize];
            System.arraycopy(outBytes, 0, trimmed, 0, outSize);
            return trimmed;
        }
    }

    @Override
    public void onFlush() {
        resampleFraction = 0.0;
        lastLeftSample = 0;
        lastRightSample = 0;
    }

    @Override
    public void onReset() {
        onFlush();
    }
}
`

const musicManagerPath = path.join(rootPath, 'node_modules/react-native-track-player/android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java')
const dlnaProcessorPath = path.join(rootPath, 'node_modules/react-native-track-player/android/src/main/java/com/guichaguri/trackplayer/service/DlnaAudioProcessor.java')

;(async() => {
  console.log('=== Starting DLNA Dependencies Patching ===')

  // 1. Write DlnaAudioProcessor.java directly into react-native-track-player
  const serviceDir = path.dirname(dlnaProcessorPath)
  if (fs.existsSync(serviceDir)) {
    console.log(`Writing DlnaAudioProcessor.java to ${dlnaProcessorPath}`)
    await fs.promises.writeFile(dlnaProcessorPath, dlnaAudioProcessorCode, 'utf8')
    console.log('DlnaAudioProcessor.java successfully written.')
  } else {
    console.warn(`Directory ${serviceDir} does not exist yet.`)
  }

  // 2. Patch MusicManager.java
  if (fs.existsSync(musicManagerPath)) {
    console.log(`Patching ${musicManagerPath}`)
    let content = (await fs.promises.readFile(musicManagerPath, 'utf8')).toString()

    // Disable audio offload
    const offloadPattern = 'boolean shouldEnableAudioOffload = options.getBoolean("audioOffload", true);'
    const offloadReplacement = 'boolean shouldEnableAudioOffload = false; // DLNA: disable hardware offload so AudioProcessors receive PCM'
    if (content.includes(offloadPattern)) {
      content = content.replace(offloadPattern, offloadReplacement)
      console.log('Patched audioOffload to false.')
    } else if (content.includes(offloadReplacement)) {
      console.log('audioOffload already patched.')
    } else {
      console.warn('Could not find audioOffload pattern in MusicManager.java!')
    }

    // Replace DefaultRenderersFactory instantiation with custom AudioSink builder
    const rfPattern = 'DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(service);'
    const rfReplacement = `DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(service) {
            @Override
            protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                    Context context,
                    boolean enableFloatOutput,
                    boolean enableAudioTrackPlaybackParams) {
                android.util.Log.i("DlnaAudioProcessor", "buildAudioSink invoked in MusicManager!");
                return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[]{ DlnaAudioProcessor.getInstance() })
                        .build();
            }
        };`

    if (content.includes(rfPattern)) {
      content = content.replace(rfPattern, rfReplacement)
      console.log('Patched DefaultRenderersFactory in MusicManager.java.')
    } else if (content.includes('buildAudioSink invoked in MusicManager!')) {
      console.log('DefaultRenderersFactory already patched.')
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

  console.log('\n=== Dependencies patch completed successfully! ===\n')
})().catch(err => {
  console.error('Dependencies patch failed:', err)
  process.exit(1)
})
