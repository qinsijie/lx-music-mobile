// 修补依赖源码以使构建的依赖恢复正常工作

const fs = require('node:fs')
const path = require('node:path')

const rootPath = path.join(__dirname, './')

const patchs = [
  [
    path.join(rootPath, 'node_modules/react-native-track-player/android/src/main/java/com/guichaguri/trackplayer/service/MusicManager.java'),
    '        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(service);',
    `        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(service) {
            @Override
            protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(
                    Context context,
                    boolean enableFloatOutput,
                    boolean enableAudioTrackPlaybackParams) {
                androidx.media3.common.audio.AudioProcessor customProcessor = null;
                try {
                    Class<?> clazz = Class.forName("cn.toside.music.mobile.dlna.DlnaAudioProcessor");
                    java.lang.reflect.Method method = clazz.getMethod("getInstance");
                    customProcessor = (androidx.media3.common.audio.AudioProcessor) method.invoke(null);
                } catch (Throwable ignored) {}

                androidx.media3.common.audio.AudioProcessor[] processors = customProcessor != null
                        ? new androidx.media3.common.audio.AudioProcessor[]{ customProcessor }
                        : new androidx.media3.common.audio.AudioProcessor[0];

                return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
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
  console.log('\nDependencies patch finished.\n')
})()
