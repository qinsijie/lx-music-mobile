// 修补依赖源码以使构建的依赖恢复正常工作

const fs = require('node:fs')
const path = require('node:path')

const rootPath = path.join(__dirname, './')

const patchs = [
  [
    path.join(rootPath, 'node_modules/react-native-track-player/android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java'),
    '        boolean shouldEnableAudioOffload = options.getBoolean("audioOffload", true);',
    '        boolean shouldEnableAudioOffload = false; // DLNA: disable hardware offload so AudioProcessors receive PCM',
  ],
  [
    path.join(rootPath, 'node_modules/react-native-track-player/android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java'),
    '        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(service);',
    `        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(service) {
            @Override
            protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                    Context context,
                    boolean enableFloatOutput,
                    boolean enableAudioTrackPlaybackParams) {
                android.util.Log.i("DlnaAudioProcessor", "buildAudioSink invoked in MusicManager!");
                androidx.media3.common.audio.AudioProcessor customProcessor = null;
                try {
                    Class<?> clazz = Class.forName("cn.toside.music.mobile.dlna.DlnaAudioProcessor");
                    java.lang.reflect.Method method = clazz.getMethod("getInstance");
                    customProcessor = (androidx.media3.common.audio.AudioProcessor) method.invoke(null);
                    android.util.Log.i("DlnaAudioProcessor", "Successfully resolved DlnaAudioProcessor: " + customProcessor);
                } catch (Throwable t) {
                    android.util.Log.e("DlnaAudioProcessor", "FAILED to resolve DlnaAudioProcessor: ", t);
                }

                androidx.media3.common.audio.AudioProcessor[] processors = customProcessor != null
                        ? new androidx.media3.common.audio.AudioProcessor[]{ customProcessor }
                        : new androidx.media3.common.audio.AudioProcessor[0];

                return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(processors)
                        .build();
            }
        };`,
  ],
]

;(async() => {
  for (const [filePath, fromStr, toStr] of patchs) {
    console.log(`Patching ${filePath.replace(rootPath, '')}`)
    try {
      const file = (await fs.promises.readFile(filePath)).toString()
      await fs.promises.writeFile(filePath, file.replace(fromStr, toStr))
    } catch (err) {
      console.error(`Patch ${filePath.replace(rootPath, '')} failed: ${err.message}`)
    }
  }

  // Ensure react-native-track-player has compiled lib directory if missing
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

  console.log('\nDependencies patch finished.\n')
})()
