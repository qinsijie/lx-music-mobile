import { useEffect, useState } from 'react'
import {
  type DlnaDevice,
  type DlnaState,
  scanDevices as nativeScanDevices,
  startStreaming as nativeStartStreaming,
  stopStreaming as nativeStopStreaming,
  getDlnaState as nativeGetDlnaState,
  onDlnaStateChanged,
  onDlnaDevicesDiscovered,
  onDlnaError,
} from '@/utils/nativeModules/dlna'
import { setVolume } from '@/plugins/player'
import settingState from '@/store/setting/state'
import { toast } from '@/utils/tools'

export interface DlnaCoreState {
  state: DlnaState
  currentDevice: DlnaDevice | null
  devices: DlnaDevice[]
  isScanning: boolean
  savedVolume: number
}

const state: DlnaCoreState = {
  state: 'IDLE',
  currentDevice: null,
  devices: [],
  isScanning: false,
  savedVolume: 1,
}

const listeners = new Set<() => void>()
const notifyListeners = () => {
  listeners.forEach(fn => fn())
}

/**
 * 初始化 DLNA 原生事件监听
 */
let isInitialized = false
export const initDlna = () => {
  if (isInitialized) return
  isInitialized = true

  // 监听原生状态流转
  onDlnaStateChanged(({ state: newState, message }) => {
    // 只要原生端转为 IDLE 或 STOPPED，无论出于何种原因（断开、被抢占或用户主动停止），
    // 必须无条件同步更新为 IDLE，恢复本地音量，重置 currentDevice
    if (newState === 'IDLE' || newState === 'STOPPED') {
      state.state = 'IDLE'
      state.currentDevice = null
      void setVolume(state.savedVolume)
      if (message === 'DISCONNECTED') {
        toast('投送连接已中断，手机声音已恢复')
      } else if (message === 'PREEMPTED') {
        toast('投送已被其他设备接管，手机声音已恢复')
      }
      notifyListeners()
      return
    }

    // 处于 STREAMING 时，仅忽略后台的 SCANNING 状态，不可被扫描临时冲掉
    if (state.state === 'STREAMING' && newState === 'SCANNING') {
      return
    }

    state.state = newState
    notifyListeners()
  })

  // 监听发现的设备
  onDlnaDevicesDiscovered((discovered) => {
    state.devices = discovered
    state.isScanning = false
    notifyListeners()
  })

  // 监听异常
  onDlnaError((err) => {
    toast(`DLNA 错误: ${err}`)
    notifyListeners()
  })

  // 同步初始状态
  void nativeGetDlnaState().then(status => {
    state.state = status.state
    state.currentDevice = status.device
    notifyListeners()
  })
}

/**
 * 执行局域网设备扫描 (SSDP M-SEARCH)
 */
export const scanDlnaDevices = async(timeoutMs: number = 3000): Promise<DlnaDevice[]> => {
  initDlna()
  state.isScanning = true
  notifyListeners()

  try {
    const list = await nativeScanDevices(timeoutMs)
    state.devices = list
    state.isScanning = false
    notifyListeners()
    return list
  } catch (err: any) {
    state.isScanning = false
    notifyListeners()
    toast(`扫描失败: ${err?.message ?? err}`)
    return []
  }
}

/**
 * 连接至 DLNA 设备并开始串流投送
 */
export const connectDlnaDevice = async(device: DlnaDevice): Promise<boolean> => {
  initDlna()

  // 防重复投送：若已经在向该设备推流中，直接保持连接并提示
  if (state.state === 'STREAMING' && state.currentDevice?.id === device.id) {
    toast(`当前正在串流至 ${device.name}`)
    return true
  }

  try {
    // 1. 记录当前应用音量 (不影响系统全局媒体音量)
    state.savedVolume = settingState.setting['player.volume'] ?? 1
    state.state = 'CONNECTING'
    notifyListeners()

    toast(`正在连接到 ${device.name}...`)
    const success = await nativeStartStreaming(device, 8088)

    if (success) {
      state.currentDevice = device
      state.state = 'STREAMING'

      // 2. 将本地播放器静音，声音交由远端 HQPE 输出
      await setVolume(0)
      toast(`已投送至 ${device.name}，本地已静音`)
      notifyListeners()
      return true
    } else {
      state.state = 'IDLE'
      state.currentDevice = null
      await setVolume(state.savedVolume)
      notifyListeners()
      toast(`连接至 ${device.name} 失败`)
      return false
    }
  } catch (err: any) {
    state.state = 'IDLE'
    state.currentDevice = null
    await setVolume(state.savedVolume)
    notifyListeners()
    toast(`投送失败: ${err?.message ?? err}`)
    return false
  }
}

/**
 * 断开 DLNA 投送并恢复手机声音
 */
export const disconnectDlnaDevice = async(): Promise<boolean> => {
  try {
    toast('正在断开投送...')
    const success = await nativeStopStreaming()

    // 恢复本地音量
    await setVolume(state.savedVolume)

    state.currentDevice = null
    state.state = 'IDLE'
    toast('已断开 DLNA 串流，手机声音已恢复')
    notifyListeners()
    return success
  } catch (err: any) {
    // 即使原生调用失败，也确保恢复本地音量
    await setVolume(state.savedVolume)
    state.currentDevice = null
    state.state = 'IDLE'
    notifyListeners()
    toast(`断开异常: ${err?.message ?? err}`)
    return false
  }
}

export const getDlnaSnapshot = () => state

/**
 * React Hook: 监听当前 DLNA 串流与设备状态
 */
export const useDlna = () => {
  const [, setTick] = useState(0)

  useEffect(() => {
    initDlna()
    const update = () => setTick(t => t + 1)
    listeners.add(update)
    return () => {
      listeners.delete(update)
    }
  }, [])

  return {
    state: state.state,
    currentDevice: state.currentDevice,
    devices: state.devices,
    isScanning: state.isScanning,
    isStreaming: state.state === 'STREAMING',
  }
}
