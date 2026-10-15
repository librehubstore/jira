import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router'
import { api } from '../api'
import { useMe } from '../auth'
import type { SpaceSummary } from '../spaces'
import { patchTicket, useTicket, type TicketDetail } from '../tickets'
import { t } from '../i18n'
import { needsScreen, transitionLabel, useTransitions } from './Transitions'

type Command = { id: string; label: string; hint?: string; run: () => void | Promise<unknown> }

/** Ctrl/Cmd+K: go anywhere or act on the current ticket without the mouse (spec §4.16). */
export function CommandPalette({ onCreate }: { onCreate: () => void }) {
  const [open, setOpen] = useState(false)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setOpen((o) => !o)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])
  return open ? <Palette onClose={() => setOpen(false)} onCreate={onCreate} /> : null
}

function Palette({ onClose, onCreate }: { onClose: () => void; onCreate: () => void }) {
  const navigate = useNavigate()
  const location = useLocation()
  const qc = useQueryClient()
  const me = useMe().data!
  const dialog = useRef<HTMLDialogElement>(null)
  const [q, setQ] = useState('')
  const [active, setActive] = useState(0)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { dialog.current?.showModal() }, [])

  // Ticket in context: /browse/KEY or the board drawer (?selected=KEY).
  const ticketKey = location.pathname.match(/^\/browse\/([A-Za-z0-9]+-\d+)/)?.[1]?.toUpperCase()
    ?? new URLSearchParams(location.search).get('selected') ?? undefined
  const ticket = useTicket(ticketKey).data
  const transitionsData = useTransitions(ticket?.key).data
  const spacesData = useQuery({ queryKey: ['spaces', false], queryFn: () => api<SpaceSummary[]>('/spaces?includeArchived=false') }).data
  const text = q.trim()
  const found = useQuery({
    queryKey: ['search', 'quick', text],
    queryFn: () => api<{ exactKey: string | null; results: { key: string; summary: string }[] }>(`/search/quick?q=${encodeURIComponent(text)}`),
    enabled: text.length >= 2,
  }).data

  const commands = useMemo(() => {
    const go = (path: string) => () => navigate(path)
    const list: Command[] = []
    if (ticket) {
      const act = (fn: () => Promise<TicketDetail>) => async () => { await fn(); void qc.invalidateQueries({ queryKey: ['ticket', ticket.key] }); void qc.invalidateQueries({ queryKey: ['board'] }) }
      list.push({ id: 'assign-me', label: t('M’assigner {key}', { key: ticket.key }), run: act(() => patchTicket(ticket, { assigneeId: me.id })) })
      list.push({ id: 'unassign', label: t('Désassigner {key}', { key: ticket.key }), run: act(() => patchTicket(ticket, { assigneeId: null })) })
      list.push({ id: 'flag', label: ticket.flagged ? t('Retirer le signalement de {key}', { key: ticket.key }) : t('Signaler {key}', { key: ticket.key }), run: act(() => patchTicket(ticket, { flagged: !ticket.flagged })) })
      // Transitions with a screen are done from the ticket itself.
      ;(transitionsData ?? []).filter((x) => !needsScreen(x)).forEach((x) => list.push({
        id: `status-${x.to.id}`, label: t('Passer {key} à « {status} »', { key: ticket.key, status: transitionLabel(x) }),
        run: act(() => api<TicketDetail>(`/tickets/${ticket.key}/transition`, { body: { statusId: x.to.id, version: ticket.version } })),
      }))
    }
    found?.results.forEach((r) => list.push({ id: `t-${r.key}`, label: `${r.key} ${r.summary}`, hint: t('Ticket'), run: go(`/browse/${r.key}`) }))
    list.push({ id: 'create', label: t('Créer un ticket'), hint: 'C', run: onCreate })
    ;(spacesData ?? []).forEach((s) => list.push({ id: `s-${s.key}`, label: t('Board {name}', { name: s.name }), hint: s.key, run: go(`/spaces/${s.key}`) }))
    list.push({ id: 'search', label: text ? t('Rechercher « {text} »', { text }) : t('Recherche avancée'), run: go(text ? `/search?q=${encodeURIComponent(text)}` : '/search') })
    list.push({ id: 'spaces', label: t('Tous les espaces'), run: go('/') })
    list.push({ id: 'prefs', label: t('Préférences'), run: go('/preferences') })
    if (me.admin) list.push({ id: 'admin', label: t('Administration des utilisateurs'), run: go('/admin/users') })
    const needle = text.toLowerCase()
    return list.filter((c) => c.id.startsWith('t-') || c.id === 'search' || !needle || c.label.toLowerCase().includes(needle))
  }, [ticket, transitionsData, found, spacesData, text, me, navigate, onCreate, qc])

  const run = async (c: Command | undefined) => {
    if (!c) return
    try {
      await c.run()
      dialog.current?.close()
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <dialog ref={dialog} onClose={onClose} aria-label={t('Palette de commandes')}
      className="mx-auto mt-24 w-full max-w-xl rounded-xl border border-slate-200 bg-white p-0 text-slate-900 shadow-2xl backdrop:bg-slate-900/40 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100">
      <input autoFocus value={q} placeholder={t('Tapez une commande, une clé ou du texte…')} aria-label={t('Commande')}
        onChange={(e) => { setQ(e.target.value); setActive(0); setError(null) }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, commands.length - 1)) }
          if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)) }
          if (e.key === 'Enter') { e.preventDefault(); void run(found?.exactKey ? { id: 'x', label: '', run: () => navigate(`/browse/${found.exactKey}`) } : commands[active]) }
        }}
        className="w-full rounded-t-xl border-b border-slate-200 bg-transparent px-4 py-3 outline-none dark:border-slate-800" />
      {error && <p role="alert" className="px-4 py-2 text-sm text-red-600">{error}</p>}
      <ul role="listbox" className="max-h-80 overflow-y-auto py-1 text-sm">
        {commands.map((c, i) => (
          <li key={c.id} role="option" aria-selected={i === active} onMouseEnter={() => setActive(i)}
            onMouseDown={(e) => { e.preventDefault(); void run(c) }}
            className={`flex cursor-pointer items-center px-4 py-1.5 ${i === active ? 'bg-indigo-50 dark:bg-indigo-950' : ''}`}>
            <span className="flex-1 truncate">{c.label}</span>
            {c.hint && <span className="text-xs text-slate-500">{c.hint}</span>}
          </li>
        ))}
      </ul>
    </dialog>
  )
}
