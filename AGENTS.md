# AGENTS.md - 落雪音乐移动端 (lx-music-mobile) HQPE-DLNA 二次开发指南

## 1. 项目愿景与目标
基于 `lyswhut/lx-music-mobile` (React Native) 进行二次开发，原生内嵌 DLNA 音频投送功能，定向串流至 HQPlayer Embedded (HQPE)。
- **核心体验**：在落雪音乐中点击 DLNA 投送至 HQPE 时，**落雪在手机端静音，声音仅从 HQPE 音响输出**；
- **系统友好**：不修改 Android 系统全局媒体音量，微信、电话、其他 App 正常发声；
- **解耦设计**：所有 DLNA 功能模块化，严格遵循最小侵入原则，确保后续平滑合并上游更新。

---

## 2. 音频链路与技术规范 (踩坑经验与硬性标准)

### 2.1 本地音频推流服务 (LocalHttpServer)
- **协议模式**：内置 HTTP 服务（端口 8088），向 HQPE 暴露 `/stream.wav`。
- **格式规范**：固定为 48000 Hz, 16-bit, 双声道 (Stereo) 线性 PCM。
- **44 字节实时 WAV 头构造 (Little-Endian，禁止违规)**：
  - RIFF (大端)
  - ChunkSize 必须固定设为小端整数 `0x7FFFFFF7`（约 2GB，切勿设为 0 或 0xFFFFFFFF，否则会导致 HQPE / PotPlayer 解码器截断或抛出 cbFormat: 0 错误）。
  - WAVE / fmt 
  - Subchunk1Size = 16, AudioFormat = 1 (PCM), NumChannels = 2, SampleRate = 48000, ByteRate = 192000, BlockAlign = 4, BitsPerSample = 16
  - data
  - Subchunk2Size 必须固定设为小端整数 `0x7FFFFFF7`。
- **HTTP 响应协议头**：
  ```http
  HTTP/1.1 200 OK\r\n
  Content-Type: audio/wav\r\n
  Server: lx-music-hqpe/1.0\r\n
  Connection: close\r\n
  Accept-Ranges: none\r\n
  \r\n