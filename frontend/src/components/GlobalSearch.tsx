import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { api } from '../api'
import { useShortcuts } from '../keyboard'
import type { StatusRef, TypeRef } from '../tickets'
import { StatusBadge, TypeIcon } from './Badges'
import { t } from '../i18n'

type Quick = { exactKey: string | null; results: { key: string; summary: string; type: TypeRef; status: StatusRef }[] }

/** Header search (shortcut "/"): an exact key opens the ticket, otherwise live full-text results (spec §4.11). */
export function GlobalSearch() {
  const navigate = useNavigate()
  const input = useRef<HTMLInputElement>(null)
  const [q, setQ] = useState('')
  const [debounced, setDebounced] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)

  useEffect(() => {
    const t = setTimeout(() => setDebounced(q.trim()), 150)
    return () => clearTimeout(t)
  }, [q])
  useShortcuts({ '/': () => input.current?.focus() })

  const result = useQuery({
    queryKey: ['search', 'quick', debounced],
    queryFn: () => api<Quick>(`/search/quick?q=${encodeURIComponent(debounced)}`),
    enabled: debounced.length > 0,
  })
  const items = result.data?.results ?? []

  const go = (path: string) => {
    setOpen(false)
    setQ('')
    input.current?.blur()
    void navigate(path)
  }
  const submit = () => {
    if (result.data?.exactKey) return go(`/browse/${result.data.exactKey}`)
    if (items[active]) return go(`/browse/${items[active].key}`)
    if (q.trim()) go(`/search?q=${encodeURIComponent(q.trim())}`)
  }

  return (
    <div className="relative">
      <input ref={input} value={q} placeholder={t('Rechercher ( / )')} aria-label={t('Rechercher un ticket')}
        onChange={(e) => { setQ(e.target.value); setOpen(true); setActive(0) }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') submit()
          if (e.key === 'Escape') { setOpen(false); input.current?.blur() }
          if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, items.length - 1)) }
          if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)) }
        }}
        className="w-64 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900" />
      {open && debounced && (
        <ul role="listbox" className="absolute left-0 z-40 mt-1 w-96 rounded-md border border-slate-200 bg-white py-1 text-sm shadow-lg dark:border-slate-700 dark:bg-slate-900">
          {items.map((r, i) => (
            <li key={r.key} role="option" aria-selected={i === active}
              onMouseDown={(e) => { e.preventDefault(); go(`/browse/${r.key}`) }}
              className={`flex cursor-pointer items-center gap-2 px-3 py-1.5 ${i === active ? 'bg-indigo-50 dark:bg-indigo-950' : ''}`}>
              <TypeIcon type={r.type} /><span className="text-slate-500">{r.key}</span>
              <span className="flex-1 truncate">{r.summary}</span><StatusBadge status={r.status} />
            </li>
          ))}
          {result.isSuccess && items.length === 0 && <li className="px-3 py-1.5 text-slate-500">{t('Aucun résultat')}</li>}
          <li onMouseDown={(e) => { e.preventDefault(); go(`/search?q=${encodeURIComponent(debounced)}`) }}
            className="cursor-pointer border-t border-slate-100 px-3 py-1.5 text-indigo-600 hover:underline dark:border-slate-800">
            {t('Recherche avancée pour « {text} »', { text: debounced })}
          </li>
        </ul>
      )}
    </div>
  )
}
