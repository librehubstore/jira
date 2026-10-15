import { useEffect, useRef, useState } from 'react'
import { localStorageGet, localStorageSet } from '../storage'
import { Button } from '../ui'
import { t } from '../i18n'

/** File name of a capture: capture-2026-10-15-14h32m05.png (local time). */
export function captureName(d = new Date()) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `capture-${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}-${p(d.getHours())}h${p(d.getMinutes())}m${p(d.getSeconds())}.png`
}

/** One frame of a screen stream, as a PNG file. */
async function grab(stream: MediaStream): Promise<File> {
  const video = document.createElement('video')
  video.srcObject = stream
  video.muted = true
  await video.play()
  await new Promise((r) => requestAnimationFrame(() => r(null))) // let the first frame paint
  const canvas = document.createElement('canvas')
  canvas.width = video.videoWidth
  canvas.height = video.videoHeight
  canvas.getContext('2d')!.drawImage(video, 0, 0)
  video.pause()
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('capture'))), 'image/png'))
  return new File([blob], captureName(), { type: 'image/png' })
}

/**
 * Screenshot as an attachment (ADD_FUNCTIONALITIES A.5). The browser asks which screen, window or tab to capture,
 * so multi-screen setups pick the right one. A page cannot minimise its own window: an optional 3-second countdown
 * leaves time to bring the right content to front (or minimise the browser) before the frame is taken.
 */
export function ScreenCaptureButton({ onCapture }: { onCapture: (file: File) => Promise<unknown> }) {
  const [countdown, setCountdown] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [delay, setDelay] = useState(() => localStorageGet('fluxo.captureDelay') !== 'off')
  const stream = useRef<MediaStream | null>(null)
  const supported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getDisplayMedia

  useEffect(() => () => stream.current?.getTracks().forEach((tr) => tr.stop()), [])

  const finish = async () => {
    const s = stream.current
    if (!s) return
    try {
      await onCapture(await grab(s))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      s.getTracks().forEach((tr) => tr.stop())
      stream.current = null
      setCountdown(null)
    }
  }

  const start = async () => {
    setError(null)
    try {
      stream.current = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
    } catch {
      return // the user cancelled the screen picker
    }
    if (!delay) return finish()
    setCountdown(3)
  }

  useEffect(() => {
    if (countdown === null) return
    if (countdown === 0) { void finish(); return }
    const timer = setTimeout(() => setCountdown(countdown - 1), 1000)
    return () => clearTimeout(timer)
  }, [countdown]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!supported) return null
  return (
    <span className="inline-flex items-center gap-2 text-sm">
      <Button type="button" variant="secondary" onClick={start} disabled={countdown !== null}>
        {countdown !== null ? t('Capture dans {n} s…', { n: countdown }) : t('📷 Capture d’écran')}
      </Button>
      <label className="flex items-center gap-1 text-xs text-slate-500" title={t('Laisse le temps de passer devant la bonne fenêtre ou de réduire le navigateur')}>
        <input type="checkbox" checked={delay} onChange={(e) => { setDelay(e.target.checked); localStorageSet('fluxo.captureDelay', e.target.checked ? 'on' : 'off') }} />
        {t('attendre 3 s')}
      </label>
      {countdown !== null && countdown > 0 && (
        <span role="status" aria-live="assertive" className="fixed bottom-6 right-6 z-50 rounded-full bg-slate-900/90 px-5 py-3 text-2xl font-semibold text-white shadow-xl">
          {countdown}
        </span>
      )}
      {error && <span role="alert" className="text-red-600">{error}</span>}
    </span>
  )
}
