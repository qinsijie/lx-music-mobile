import { forwardRef, useImperativeHandle, useRef, useEffect } from 'react'
import { View, StyleSheet, ScrollView, TouchableOpacity, ActivityIndicator } from 'react-native'
import Dialog, { type DialogType } from '@/components/common/Dialog'
import Text from '@/components/common/Text'
import { Icon } from '@/components/common/Icon'
import { useTheme } from '@/store/theme/hook'
import {
  useDlna,
  scanDlnaDevices,
  connectDlnaDevice,
  disconnectDlnaDevice,
} from '@/core/dlna'
import { scaleSizeH, scaleSizeW } from '@/utils/pixelRatio'
import { type DlnaDevice } from '@/utils/nativeModules/dlna'

export interface DlnaModalType {
  show: () => void
  hide: () => void
}

export default forwardRef<DlnaModalType, {}>((props, ref) => {
  const dialogRef = useRef<DialogType>(null)
  const theme = useTheme()
  const { state, currentDevice, devices, isScanning, isStreaming } = useDlna()

  useImperativeHandle(ref, () => ({
    show() {
      dialogRef.current?.setVisible(true)
      void scanDlnaDevices()
    },
    hide() {
      dialogRef.current?.setVisible(false)
    },
  }))

  const handleRefresh = () => {
    void scanDlnaDevices()
  }

  const handleDevicePress = (device: DlnaDevice) => {
    if (isStreaming && currentDevice?.id === device.id) {
      void disconnectDlnaDevice()
    } else {
      void connectDlnaDevice(device)
    }
  }

  const handleDisconnect = () => {
    void disconnectDlnaDevice()
  }

  // 保证已连接设备始终显示在列表最前，即使局域网扫描响应延迟
  const displayDevices = [...devices]
  if (isStreaming && currentDevice && !displayDevices.some(d => d.id === currentDevice.id)) {
    displayDevices.unshift(currentDevice)
  }

  return (
    <Dialog ref={dialogRef} title="DLNA 音频投送 (HQPE-DLNA)" closeBtn>
      <View style={styles.container}>
        {/* 当前串流状态指示条 */}
        <View style={{
          ...styles.statusBar,
          backgroundColor: isStreaming
            ? theme['c-primary-light-100-alpha-200']
            : theme['c-content-background'],
          borderColor: isStreaming ? theme['c-primary'] : theme['c-border-background'],
        }}>
          <View style={styles.statusLeft}>
            <View style={{
              ...styles.statusDot,
              backgroundColor: isStreaming ? '#52c41a' : (state === 'CONNECTING' ? '#faad14' : '#d9d9d9'),
            }} />
            <View style={styles.statusTextWrap}>
              <Text size={13} style={{ fontWeight: 'bold' }}>
                {isStreaming
                  ? `串流中: ${currentDevice?.name ?? 'HQPlayer'}`
                  : (state === 'CONNECTING' ? '正在连接设备...' : '手机扬声器发声 (未投送)')}
              </Text>
              <Text size={11} color={theme['c-font-label']}>
                {isStreaming
                  ? `手机端已单应用静音 | 远端: ${currentDevice?.ip}:${currentDevice?.port}`
                  : '点击下方设备即可定向推流至 HQPlayer 音响'}
              </Text>
            </View>
          </View>
          {isStreaming && (
            <TouchableOpacity style={{ ...styles.disconnectBtn, backgroundColor: theme['c-button-background'] }} onPress={handleDisconnect}>
              <Text size={12} color={theme['c-primary']}>断开</Text>
            </TouchableOpacity>
          )}
        </View>

        {/* 局域网设备列表 */}
        <View style={styles.listHeader}>
          <Text size={12} color={theme['c-font-label']}>局域网渲染器 ({displayDevices.length})</Text>
          <TouchableOpacity style={styles.refreshBtn} onPress={handleRefresh} disabled={isScanning}>
            {isScanning ? (
              <ActivityIndicator size="small" color={theme['c-primary']} />
            ) : (
              <Text size={12} color={theme['c-primary']}>重新扫描</Text>
            )}
          </TouchableOpacity>
        </View>

        <ScrollView style={styles.listScrollView} contentContainerStyle={styles.listContent}>
          {displayDevices.length === 0 ? (
            <View style={styles.emptyWrap}>
              {isScanning ? (
                <>
                  <ActivityIndicator size="small" color={theme['c-primary']} style={{ marginBottom: 10 }} />
                  <Text size={13} color={theme['c-font-label']}>正在通过 SSDP 扫描局域网 UPnP 设备...</Text>
                </>
              ) : (
                <>
                  <Text size={13} color={theme['c-font-label']} style={{ textAlign: 'center' }}>
                    未发现局域网 DLNA 设备
                  </Text>
                  <Text size={11} color={theme['c-font-label']} style={{ textAlign: 'center', marginTop: 6, lineHeight: 16 }}>
                    请确保手机已连接局域网 WiFi，且 HQPlayer Embedded (HQPE) 已开启 UPnP 渲染器服务。
                  </Text>
                </>
              )}
            </View>
          ) : (
            displayDevices.map(device => {
              const isCurrent = isStreaming && currentDevice?.id === device.id
              return (
                <TouchableOpacity
                  key={device.id}
                  style={{
                    ...styles.deviceItem,
                    backgroundColor: isCurrent ? theme['c-primary-light-100-alpha-200'] : theme['c-main-background'],
                    borderColor: isCurrent ? theme['c-primary'] : theme['c-border-background'],
                  }}
                  onPress={() => handleDevicePress(device)}
                >
                  <View style={styles.deviceIcon}>
                    <Icon name="album" size={20} color={isCurrent ? theme['c-primary'] : theme['c-font']} />
                  </View>
                  <View style={styles.deviceInfo}>
                    <Text size={14} numberOfLines={1} style={{ fontWeight: isCurrent ? 'bold' : 'normal' }}>
                      {device.name}
                    </Text>
                    <Text size={11} color={theme['c-font-label']} numberOfLines={1}>
                      {device.ip}:{device.port}
                    </Text>
                  </View>
                  <View style={styles.deviceAction}>
                    {isCurrent ? (
                      <Text size={12} color={theme['c-primary']}>已连接</Text>
                    ) : (
                      <View style={{ ...styles.castBadge, backgroundColor: theme['c-primary'] }}>
                        <Text size={11} color="#ffffff">投送</Text>
                      </View>
                    )}
                  </View>
                </TouchableOpacity>
              )
            })
          )}
        </ScrollView>
      </View>
    </Dialog>
  )
})

const styles = StyleSheet.create({
  container: {
    padding: scaleSizeW(12),
    minWidth: scaleSizeW(280),
    maxHeight: scaleSizeH(400),
  },
  statusBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: scaleSizeW(10),
    borderRadius: 6,
    borderWidth: 1,
    marginBottom: scaleSizeH(10),
  },
  statusLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    flex: 1,
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 8,
  },
  statusTextWrap: {
    flex: 1,
  },
  disconnectBtn: {
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 4,
    marginLeft: 8,
  },
  listHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: scaleSizeH(6),
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e8e8e8',
  },
  refreshBtn: {
    padding: 4,
  },
  listScrollView: {
    maxHeight: scaleSizeH(220),
    marginTop: scaleSizeH(6),
  },
  listContent: {
    paddingVertical: scaleSizeH(4),
  },
  emptyWrap: {
    paddingVertical: scaleSizeH(24),
    alignItems: 'center',
    justifyContent: 'center',
  },
  deviceItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: scaleSizeW(10),
    borderRadius: 6,
    borderWidth: 1,
    marginBottom: scaleSizeH(8),
  },
  deviceIcon: {
    marginRight: 10,
  },
  deviceInfo: {
    flex: 1,
  },
  deviceAction: {
    marginLeft: 8,
  },
  castBadge: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 3,
  },
})
