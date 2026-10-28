import { NativeEventEmitter, NativeModules, Platform } from 'react-native'

const { LxDlnaModule } = NativeModules

export interface DlnaDevice {
  id: string
  name: string
  ip: string
  port: number
  location: string
  controlUrl: string
}

export type DlnaState = 'IDLE' | 'SCANNING' | 'CONNECTING' | 'STREAMING' | 'STOPPED' | 'ERROR'

export interface DlnaStatus {
  state: DlnaState
  device: DlnaDevice | null
  sampleRate?: number
  channels?: number
  bitsPerSample?: number
}

const isAndroid = Platform.OS === 'android' && Boolean(LxDlnaModule)

let eventEmitter: NativeEventEmitter | null = null
const getEventEmitter = () => {
  if (!eventEmitter && isAndroid) {
    eventEmitter = new NativeEventEmitter(LxDlnaModule)
  }
  return eventEmitter
}

/**
 * 扫描局域网中的 DLNA 渲染设备 (MediaRenderer)
 */
export const scanDevices = async(timeoutMs: number = 3000): Promise<DlnaDevice[]> => {
  if (!isAndroid) return []
  return LxDlnaModule.scanDevices(timeoutMs)
}

/**
 * 开始向目标 DLNA 设备串流
 */
export const startStreaming = async(device: DlnaDevice, port: number = 8088): Promise<boolean> => {
  if (!isAndroid) return false
  return LxDlnaModule.startStreaming(device, port)
}

/**
 * 停止 DLNA 串流并释放设备
 */
export const stopStreaming = async(): Promise<boolean> => {
  if (!isAndroid) return false
  return LxDlnaModule.stopStreaming()
}

/**
 * 获取当前 DLNA 连接与推流状态
 */
export const getDlnaState = async(): Promise<DlnaStatus> => {
  if (!isAndroid) return { state: 'IDLE', device: null }
  return LxDlnaModule.getState()
}

/**
 * 获取本地局域网 IP
 */
export const getLocalIp = async(targetIp?: string): Promise<string> => {
  if (!isAndroid) return '127.0.0.1'
  return LxDlnaModule.getLocalIp(targetIp ?? null)
}

/**
 * 监听 DLNA 状态变化
 */
export const onDlnaStateChanged = (handler: (event: { state: DlnaState; message?: string }) => void): (() => void) => {
  const emitter = getEventEmitter()
  if (!emitter) return () => {}
  const subscription = emitter.addListener('onDlnaStateChanged', handler)
  return () => subscription.remove()
}

/**
 * 监听设备扫描结果
 */
export const onDlnaDevicesDiscovered = (handler: (devices: DlnaDevice[]) => void): (() => void) => {
  const emitter = getEventEmitter()
  if (!emitter) return () => {}
  const subscription = emitter.addListener('onDlnaDevicesDiscovered', (data: { devices: DlnaDevice[] }) => {
    handler(data.devices || [])
  })
  return () => subscription.remove()
}

/**
 * 监听 DLNA 错误事件
 */
export const onDlnaError = (handler: (error: string) => void): (() => void) => {
  const emitter = getEventEmitter()
  if (!emitter) return () => {}
  const subscription = emitter.addListener('onDlnaError', (data: { error: string }) => {
    handler(data.error || 'Unknown DLNA error')
  })
  return () => subscription.remove()
}

/**
 * 监听推流音频规格变化 (Bit-Perfect 源码规格)
 */
export const onDlnaAudioFormatChanged = (handler: (format: { sampleRate: number; channels: number; bitsPerSample: number }) => void): (() => void) => {
  const emitter = getEventEmitter()
  if (!emitter) return () => {}
  const subscription = emitter.addListener('onDlnaAudioFormatChanged', handler)
  return () => subscription.remove()
}
