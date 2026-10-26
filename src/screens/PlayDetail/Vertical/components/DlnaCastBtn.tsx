import { memo, useRef } from 'react'
import DlnaModal, { type DlnaModalType } from '@/components/DlnaModal'
import { useTheme } from '@/store/theme/hook'
import { useDlna } from '@/core/dlna'
import Btn from './Btn'

export default memo(() => {
  const theme = useTheme()
  const modalRef = useRef<DlnaModalType>(null)
  const { isStreaming } = useDlna()

  const handleShow = () => {
    modalRef.current?.show()
  }

  return (
    <>
      <Btn
        icon="share"
        color={isStreaming ? theme['c-primary-font-active'] : theme['c-font']}
        onPress={handleShow}
      />
      <DlnaModal ref={modalRef} />
    </>
  )
})
