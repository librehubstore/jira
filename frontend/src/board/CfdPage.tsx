import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import { api } from '../api'
import { ErrorText } from '../ui'
import { t } from '../i18n'
import type { BoardView } from './BoardPage'

type Cfd = { columns: string[]; days: { date: string; cumulative: number[] }[] }

const COLORS = ['#94a3b8', '#3b82f6', '#8b5cf6', '#f59e0b', '#14b8a6', '#ec4899', '#22c55e']

/**
 * Polygons of the stacked areas: band i spans from cumulative[i+1] (or 0) up to cumulative[i]; the last column (done)
 * is at the bottom, the first one on top, as usual for a CFD.
 */
export function cfdBands(days: Cfd['days'], width: number, height: number) {
  const n = days[0]?.cumulative.length ?? 0
  const max = Math.max(1, ...days.map((d) => d.cumulative[0] ?? 0))
  const x = (i: number) => (days.length === 1 ? width / 2 : (i * width) / (days.length - 1))
  const y = (v: number) => height - (v * height) / max
  return Array.from({ length: n }, (_, col) => {
    const top = days.map((d, i) => `${x(i)},${y(d.cumulative[col])}`)
    const bottom = days.map((d, i) => `${x(i)},${y(d.cumulative[col + 1] ?? 0)}`).reverse()
    return [...top, ...bottom].join(' ')
  })
}

/** Cumulative flow diagram of a board (spec §4.15). */
export function CfdPage() {
  const { key, boardId } = useParams()
  const [range, setRange] = useState(30)
  const [fql, setFql] = useState('')
  const board = useQuery({ queryKey: ['board', boardId], queryFn: () => api<BoardView>(`/boards/${boardId}`) }).data
  const to = new Date()
  const from = new Date(to.getTime() - (range - 1) * 86_400_000)
  const day = (d: Date) => d.toLocaleDateString('sv-SE') // yyyy-mm-dd in local time
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
  const data = useQuery({
    queryKey: ['board', boardId, 'cfd', range, fql],
    queryFn: () => api<Cfd>(`/boards/${boardId}/cfd?from=${day(from)}&to=${day(to)}&zone=${encodeURIComponent(zone)}${fql ? `&fql=${encodeURIComponent(fql)}` : ''}`),
  })
  const W = 800, H = 320
  const filters = [
    ...(board?.quickFilters.filter((f) => f.criteria.fql).map((f) => ({ label: f.name, fql: f.criteria.fql! })) ?? []),
    ...(board?.swimlaneQueries.map((q) => ({ label: t('Couloir « {name} »', { name: q.name }), fql: q.fql })) ?? []),
  ]
  const d = data.data
  const polygons = d && d.days.length ? cfdBands(d.days, W, H) : []
  const last = d?.days.at(-1)
  return (
    <div className="mx-auto max-w-5xl space-y-4 p-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold">{t('Diagramme de flux cumulé')}</h1>
        <select aria-label={t('Période')} value={range} onChange={(e) => setRange(Number(e.target.value))}
          className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900">
          <option value={7}>{t('7 jours')}</option><option value={30}>{t('30 jours')}</option><option value={90}>{t('3 mois')}</option>
        </select>
        {filters.length > 0 && (
          <select aria-label={t('Affiner')} value={fql} onChange={(e) => setFql(e.target.value)}
            className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900">
            <option value="">{t('Tous les tickets')}</option>
            {filters.map((f) => <option key={f.label} value={f.fql}>{f.label}</option>)}
          </select>
        )}
        <Link to={`/spaces/${key}/boards/${boardId}`} className="ml-auto text-sm text-indigo-600 hover:underline">{t('← Retour au board')}</Link>
      </div>
      <ErrorText error={data.error} />
      {d && (
        <figure className="space-y-2">
          <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={t('Diagramme de flux cumulé')} className="w-full rounded-md border border-slate-200 dark:border-slate-800">
            {polygons.map((p, i) => <polygon key={i} points={p} fill={COLORS[i % COLORS.length]} fillOpacity={0.85} />)}
          </svg>
          <figcaption className="flex flex-wrap gap-4 text-sm">
            {d.columns.map((c, i) => (
              <span key={c} className="flex items-center gap-1.5">
                <span className="inline-block h-3 w-3 rounded-sm" style={{ backgroundColor: COLORS[i % COLORS.length] }} />
                {c}{last && <span className="text-slate-500">({last.cumulative[i] - (last.cumulative[i + 1] ?? 0)})</span>}
              </span>
            ))}
            <span className="text-slate-500">{d.days[0]?.date} → {last?.date}</span>
          </figcaption>
          {/* Accessible data: the cumulative counts of the last day. */}
          {last && <p className="sr-only">{d.columns.map((c, i) => `${c} : ${last.cumulative[i]}`).join(', ')}</p>}
        </figure>
      )}
    </div>
  )
}
