import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '../api'
import { useMe } from '../auth'
import { fetchTransitions, needsScreen, transitionLabel, type AvailableTransition, type ScreenValues } from '../components/Transitions'
import { t } from '../i18n'
import type { Card } from './model'

/** Right-click menu of a board card (ADD_FUNCTIONALITIES A.2); only transitions allowed to the user are listed. */
export function CardMenu({ card, x, y, boardId, canEdit, canDelete, canCreate, onClose, onOpen, onToast, ask }: {
  card: Card; x: number; y: number; boardId: string; canEdit: boolean; canDelete: boolean; canCreate: boolean
  onClose: () => void; onOpen: (key: string) => void; onToast: (m: string) => void
  ask: (key: string, tr: AvailableTransition) => Promise<ScreenValues | null>
}) {
  const qc = useQueryClient()
  const me = useMe().data!
  const ref = useRef<HTMLDivElement>(null)
  const [transitions, setTransitions] = useState<AvailableTransition[] | null>(null)
  const [statusOpen, setStatusOpen] = useState(false)

  useEffect(() => {
    fetchTransitions(qc, card.key).then(setTransitions, () => setTransitions([]))
    ref.current?.querySelector<HTMLElement>('[role=menuitem]')?.focus()
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) onClose() }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [card.key]) // eslint-disable-line react-hooks/exhaustive-deps

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['board', boardId, 'cards'] })
    void qc.invalidateQueries({ queryKey: ['ticket', card.key] })
  }
  const run = (fn: () => Promise<unknown>, done?: string) => {
    onClose()
    fn().then(() => { refresh(); if (done) onToast(done) }, (e: Error) => onToast(e.message))
  }
  const patch = (body: Record<string, unknown>, done: string) => run(() => api(`/tickets/${card.key}`, { method: 'PATCH', body }), done)
  const transition = async (tr: AvailableTransition) => {
    onClose()
    let values: ScreenValues = {}
    if (needsScreen(tr)) {
      const answered = await ask(card.key, tr)
      if (!answered) return
      values = answered
    }
    api(`/tickets/${card.key}/transition`, { body: { statusId: tr.to.id, ...values } })
      .then(() => { refresh(); onToast(t('{key} → « {status} »', { key: card.key, status: tr.to.name })) }, (e: Error) => onToast(e.message))
  }

  type Item = { label: string; action: () => void; danger?: boolean; hidden?: boolean }
  const items: Item[] = [
    { label: t('Ouvrir'), action: () => { onClose(); onOpen(card.key) } },
    { label: t('Ouvrir dans un nouvel onglet'), action: () => { onClose(); window.open(`/browse/${card.key}`, '_blank', 'noopener') } },
    { label: t('M’assigner'), hidden: !canEdit || card.assignee?.id === me.id, action: () => patch({ assigneeId: me.id }, t('{key} vous est assigné', { key: card.key })) },
    { label: card.flagged ? t('Retirer le signalement') : t('Signaler'), hidden: !canEdit, action: () => patch({ flagged: !card.flagged }, card.flagged ? t('Signalement retiré') : t('{key} signalé', { key: card.key })) },
    { label: t('Envoyer en haut'), hidden: !canEdit, action: () => run(() => api(`/boards/${boardId}/send`, { body: { ticketKey: card.key, top: true } })) },
    { label: t('Envoyer en bas'), hidden: !canEdit, action: () => run(() => api(`/boards/${boardId}/send`, { body: { ticketKey: card.key, top: false } })) },
    { label: t('Cloner'), hidden: !canCreate, action: () => run(() => api(`/tickets/${card.key}/clone`, { body: { subtasks: true, attachments: true } }), t('{key} cloné', { key: card.key })) },
    { label: t('Copier le lien'), action: () => { onClose(); void navigator.clipboard.writeText(`${location.origin}/browse/${card.key}`); onToast(t('Lien copié')) } },
    { label: t('Archiver'), hidden: !canEdit, action: () => patch({ archived: true }, t('{key} archivé', { key: card.key })) },
    {
      label: t('Supprimer…'), danger: true, hidden: !canDelete, action: () => {
        if (!window.confirm(t('Supprimer définitivement {key} ?', { key: card.key }))) return onClose()
        run(() => api(`/tickets/${card.key}?withSubtasks=true`, { method: 'DELETE' }), t('{key} supprimé', { key: card.key }))
      },
    },
  ]
  const visible = items.filter((i) => !i.hidden)
  const left = Math.min(x, window.innerWidth - 240)
  const top = Math.min(y, window.innerHeight - 40 * (visible.length + 2))
  const item = 'block w-full rounded px-3 py-1.5 text-left text-sm hover:bg-slate-100 focus:bg-slate-100 focus:outline-none dark:hover:bg-slate-800 dark:focus:bg-slate-800'
  return (
    <div ref={ref} role="menu" tabIndex={-1} aria-label={t('Actions sur {key}', { key: card.key })} style={{ left, top }}
      className="fixed z-50 w-56 rounded-md border border-slate-200 bg-white p-1 shadow-xl dark:border-slate-700 dark:bg-slate-900"
      onKeyDown={(e) => {
        const all = [...(ref.current?.querySelectorAll<HTMLElement>('[role=menuitem]') ?? [])]
        const i = all.indexOf(document.activeElement as HTMLElement)
        if (e.key === 'Escape') { e.stopPropagation(); onClose() }
        if (e.key === 'ArrowDown') { e.preventDefault(); all[(i + 1) % all.length]?.focus() }
        if (e.key === 'ArrowUp') { e.preventDefault(); all[(i - 1 + all.length) % all.length]?.focus() }
      }}>
      <div className="truncate px-3 py-1 text-xs font-medium text-slate-500">{card.key} · {card.summary}</div>
      {visible.slice(0, 2).map((i) => <button key={i.label} role="menuitem" className={item} onClick={i.action}>{i.label}</button>)}
      <button role="menuitem" aria-haspopup="menu" aria-expanded={statusOpen} className={item} onClick={() => setStatusOpen(!statusOpen)}>
        {t('Changer le statut')} ▸
      </button>
      {statusOpen && (
        <div role="menu" aria-label={t('Changer le statut')} className="ml-3 border-l border-slate-200 pl-1 dark:border-slate-700">
          {transitions === null && <div className="px-3 py-1 text-sm text-slate-500">{t('Chargement…')}</div>}
          {transitions?.length === 0 && <div className="px-3 py-1 text-sm text-slate-500">{t('Aucune transition autorisée')}</div>}
          {transitions?.map((tr) => <button key={`${tr.id}-${tr.to.id}`} role="menuitem" className={item} onClick={() => transition(tr)}>{transitionLabel(tr)}</button>)}
        </div>
      )}
      {visible.slice(2).map((i) => (
        <button key={i.label} role="menuitem" className={`${item} ${i.danger ? 'text-red-600' : ''}`} onClick={i.action}>{i.label}</button>
      ))}
    </div>
  )
}
