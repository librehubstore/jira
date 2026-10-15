import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useQuery, type QueryClient } from '@tanstack/react-query'
import { api } from '../api'
import { useMeta, type StatusRef } from '../tickets'
import { Button, ErrorText } from '../ui'
import { uploadAttachment } from './TicketCollab'
import { ScreenCaptureButton } from './ScreenCapture'
import { t } from '../i18n'

export type AvailableTransition = {
  id: string | null; name: string; to: StatusRef
  screen: { resolution?: boolean; comment?: boolean; attachment?: boolean }; commentRequired: boolean
}

export type ScreenValues = { resolutionId?: string; comment?: string }

const transitionsQuery = (key: string) => ({
  queryKey: ['ticket', key, 'transitions'],
  queryFn: () => api<AvailableTransition[]>(`/tickets/${key}/transitions`),
  staleTime: 10_000,
})

/** Transitions the current user may take on the ticket (spec §4.9). */
export function useTransitions(key: string | undefined) {
  return useQuery({ ...transitionsQuery(key ?? ''), enabled: !!key })
}

export function fetchTransitions(qc: QueryClient, key: string) {
  return qc.fetchQuery(transitionsQuery(key))
}

/** Picks the transition reaching one of {@code statusIds}, preferring {@code preferred}; null when none is allowed. */
export function pickTransition(list: AvailableTransition[], statusIds: string[], preferred?: string) {
  return list.find((x) => x.to.id === preferred) ?? list.find((x) => statusIds.includes(x.to.id)) ?? null
}

export function needsScreen(tr: AvailableTransition) {
  return tr.commentRequired || !!tr.screen.comment || !!tr.screen.attachment || (!!tr.screen.resolution && tr.to.category === 'DONE')
}

export function transitionLabel(tr: AvailableTransition) {
  return tr.name.toLowerCase() === tr.to.name.toLowerCase() ? tr.to.name : `${tr.name} → ${tr.to.name}`
}

/**
 * Transition screen as a promise: `ask(key, transition)` opens the dialog and resolves with the values, or null when
 * cancelled (the card then stays where it was). Render `screen` somewhere in the page.
 */
export function useTransitionScreen(): [ReactNode, (ticketKey: string, tr: AvailableTransition) => Promise<ScreenValues | null>] {
  const [pending, setPending] = useState<{ key: string; tr: AvailableTransition; resolve: (v: ScreenValues | null) => void } | null>(null)
  const ask = (key: string, tr: AvailableTransition) => new Promise<ScreenValues | null>((resolve) => setPending({ key, tr, resolve }))
  const close = (v: ScreenValues | null) => { pending?.resolve(v); setPending(null) }
  const screen = pending ? <TransitionScreen ticketKey={pending.key} transition={pending.tr} onClose={close} /> : null
  return [screen, ask]
}

function TransitionScreen({ ticketKey, transition: tr, onClose }: {
  ticketKey: string; transition: AvailableTransition; onClose: (v: ScreenValues | null) => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const meta = useMeta().data
  const [resolutionId, setResolutionId] = useState('')
  const [comment, setComment] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => { dialog.current?.showModal() }, [])
  const askResolution = !!tr.screen.resolution && tr.to.category === 'DONE'
  const askComment = tr.commentRequired || !!tr.screen.comment
  return (
    <dialog ref={dialog} aria-label={transitionLabel(tr)} onCancel={(e) => { e.preventDefault(); onClose(null) }}
      className="w-full max-w-lg rounded-lg bg-white p-0 text-slate-900 shadow-xl backdrop:bg-slate-900/40 dark:bg-slate-900 dark:text-slate-100">
      <form className="space-y-3 p-5" onSubmit={async (e) => {
        e.preventDefault()
        setBusy(true)
        try {
          for (const f of files) await uploadAttachment(ticketKey, f)
          onClose({ resolutionId: resolutionId || undefined, comment: comment.trim() || undefined })
        } catch (err) {
          setError(err)
          setBusy(false)
        }
      }}>
        <h2 className="text-lg font-semibold">{ticketKey} · {transitionLabel(tr)}</h2>
        {askResolution && (
          <label className="block text-sm">
            <span className="mb-1 block font-medium">{t('Résolution')}</span>
            <select value={resolutionId} onChange={(e) => setResolutionId(e.target.value)}
              className="w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900">
              <option value="">{t('Par défaut')}</option>
              {meta?.resolutions.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
            </select>
          </label>
        )}
        {askComment && (
          <label className="block text-sm">
            <span className="mb-1 block font-medium">{t('Commentaire')}{tr.commentRequired ? ' *' : ''}</span>
            <textarea autoFocus required={tr.commentRequired} rows={4} value={comment} onChange={(e) => setComment(e.target.value)}
              className="w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 dark:border-slate-700 dark:bg-slate-900" />
          </label>
        )}
        {tr.screen.attachment && (
          <label className="block text-sm">
            <span className="mb-1 block font-medium">{t('Pièces jointes')}</span>
            <input type="file" multiple onChange={(e) => setFiles([...files, ...(e.target.files ?? [])])} />
          </label>
        )}
        {tr.screen.attachment && (
          <div className="space-y-1 text-sm">
            <ScreenCaptureButton onCapture={async (file) => setFiles((list) => [...list, file])} />
            {files.length > 0 && <p className="text-xs text-slate-500">{files.map((x) => x.name).join(', ')}</p>}
          </div>
        )}
        <ErrorText error={error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="secondary" onClick={() => onClose(null)}>{t('Annuler')}</Button>
          <Button type="submit" disabled={busy}>{transitionLabel(tr)}</Button>
        </div>
      </form>
    </dialog>
  )
}
