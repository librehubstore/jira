import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { api } from '../api'
import type { UserRef } from '../spaces'
import { FIELD_LABELS } from '../tickets'
import { Avatar } from './Badges'
import { t } from '../i18n'

type Item = {
  id: string; event: string; actor: UserRef | null; read: boolean; updatedAt: string
  payload: { ticketKey: string; summary: string; count: number; excerpt?: string; changes: { field: string; from: string | null; to: string | null }[] }
}
type Page = { items: Item[]; unread: number }

export const EVENT_LABELS: Record<string, string> = {
  CREATED: t('a créé'), UPDATED: t('a modifié'), ASSIGNED: t('vous a assigné'), TRANSITIONED: t('a changé le statut de'),
  RESOLVED: t('a résolu'), REOPENED: t('a rouvert'), COMMENTED: t('a commenté'), COMMENT_EDITED: t('a modifié un commentaire sur'),
  MENTIONED: t('vous a mentionné dans'), DELETED: t('a supprimé'), MOVED: t('a déplacé'), BLOCKER_RESOLVED: t('a résolu un bloquant de'),
}

/** In-app notifications (spec §9): unread counter, list, mark as read. */
export function NotificationBell() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const count = useQuery({
    queryKey: ['notifications', 'count'],
    queryFn: () => api<{ unread: number }>('/notifications/unread-count'),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
  })
  const list = useQuery({
    queryKey: ['notifications', 'list', unreadOnly],
    queryFn: () => api<Page>(`/notifications?unreadOnly=${unreadOnly}`),
    enabled: open,
  })
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }),
  })
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  const unread = count.data?.unread ?? 0
  const openItem = (n: Item) => {
    if (!n.read) act.mutate(() => api(`/notifications/${n.id}/read`, { body: {} }))
    setOpen(false)
    if (n.event !== 'DELETED') void navigate(`/browse/${n.payload.ticketKey}`)
  }

  return (
    <div ref={ref} className="relative">
      <button onClick={() => setOpen(!open)} aria-expanded={open} aria-label={`Notifications, ${unread} non lue(s)`}
        className="relative rounded px-1.5 py-1 hover:bg-slate-100 dark:hover:bg-slate-800">
        🔔
        {unread > 0 && (
          <span className="absolute -right-1 -top-1 min-w-4 rounded-full bg-red-600 px-1 text-center text-[10px] font-semibold text-white">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>
      {open && (
        <div className="absolute right-0 z-40 mt-1 w-96 rounded-md border border-slate-200 bg-white shadow-lg dark:border-slate-700 dark:bg-slate-900">
          <div className="flex items-center gap-3 border-b border-slate-100 px-3 py-2 text-sm dark:border-slate-800">
            <span className="font-semibold">{t('Notifications')}</span>
            <label className="flex items-center gap-1 text-xs text-slate-500">
              <input type="checkbox" checked={unreadOnly} onChange={(e) => setUnreadOnly(e.target.checked)} /> {t('Non lues')}
            </label>
            <button className="ml-auto text-xs text-indigo-600 hover:underline disabled:text-slate-500" disabled={unread === 0}
              onClick={() => act.mutate(() => api('/notifications/read-all', { body: {} }))}>{t('Tout marquer comme lu')}</button>
          </div>
          <ul className="max-h-[28rem] overflow-y-auto">
            {list.data?.items.map((n) => (
              <li key={n.id} className={`group flex gap-2 border-b border-slate-50 px-3 py-2 text-sm dark:border-slate-800 ${n.read ? '' : 'bg-indigo-50/60 dark:bg-indigo-950/40'}`}>
                <Avatar user={n.actor} size={6} />
                <button className="min-w-0 flex-1 text-left" onClick={() => openItem(n)}>
                  <div>
                    <span className="font-medium">{n.actor?.displayName ?? t('Quelqu’un')}</span> {EVENT_LABELS[n.event] ?? n.event}{' '}
                    <span className="font-medium">{n.payload.ticketKey}</span>
                    {n.payload.count > 1 && <span className="text-slate-500"> ({n.payload.count} changements)</span>}
                  </div>
                  <div className="truncate text-slate-500">{n.payload.summary}</div>
                  {n.payload.excerpt && <div className="line-clamp-2 text-xs text-slate-500">« {n.payload.excerpt} »</div>}
                  {n.payload.changes.slice(-3).map((c, i) => (
                    <div key={i} className="truncate text-xs text-slate-500">
                      {FIELD_LABELS[c.field] ?? c.field} : {c.from ?? t('aucun')} → {c.to ?? t('aucun')}
                    </div>
                  ))}
                  <div className="text-xs text-slate-500">{new Date(n.updatedAt).toLocaleString()}</div>
                </button>
                <button title={n.read ? t('Marquer comme non lu') : t('Marquer comme lu')} aria-label={n.read ? t('Marquer comme non lu') : t('Marquer comme lu')}
                  onClick={() => act.mutate(() => api(`/notifications/${n.id}/read?read=${!n.read}`, { body: {} }))}
                  className="self-start text-xs text-slate-500 opacity-0 hover:text-slate-700 group-hover:opacity-100">
                  {n.read ? '○' : '●'}
                </button>
              </li>
            ))}
            {list.data?.items.length === 0 && <li className="px-3 py-6 text-center text-sm text-slate-500">{t('Rien de neuf.')}</li>}
          </ul>
          <div className="px-3 py-2 text-right text-xs">
            <Link to="/preferences" onClick={() => setOpen(false)} className="text-slate-500 hover:underline">{t('Préférences de notification')}</Link>
          </div>
        </div>
      )}
    </div>
  )
}
