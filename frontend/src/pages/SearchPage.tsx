import { useEffect, useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { api, ApiError } from '../api'
import { FqlInput } from '../components/FqlInput'
import { BULK_MAX, BulkBar, useSelection } from '../components/Bulk'
import { useMe } from '../auth'
import { Avatar, PriorityIcon, StatusBadge, TypeIcon } from '../components/Badges'
import type { SpaceSummary, UserRef } from '../spaces'
import { useDirectory } from '../spaces'
import { useMeta, type Meta, type PriorityRef, type StatusRef, type TypeRef } from '../tickets'
import { Button, ErrorText } from '../ui'
import { t } from '../i18n'

type Row = {
  id: string; key: string; summary: string; spaceKey: string; type: TypeRef; status: StatusRef; resolution: string | null
  priority: PriorityRef; assignee: UserRef | null; reporter: UserRef; parentKey: string | null; labels: string[]
  dueDate: string | null; createdAt: string; updatedAt: string; resolvedAt: string | null
  lastComment: { author: string; excerpt: string; at: string } | null
}
type Result = { rows: Row[]; total: number; page: number; size: number }

const COLUMNS: Record<string, { label: string; sort?: string; render: (r: Row) => React.ReactNode }> = {
  type: { label: t('Type'), sort: 'type', render: (r) => <TypeIcon type={r.type} /> },
  key: { label: t('Clé'), sort: 'key', render: (r) => <Link to={`/browse/${r.key}`} className="font-medium text-indigo-600 hover:underline">{r.key}</Link> },
  summary: { label: t('Résumé'), sort: 'summary', render: (r) => <Link to={`/browse/${r.key}`} className="hover:underline">{r.summary}</Link> },
  status: { label: t('Statut'), sort: 'status', render: (r) => <StatusBadge status={r.status} /> },
  priority: { label: t('Priorité'), sort: 'priority', render: (r) => <span className="flex items-center gap-1"><PriorityIcon priority={r.priority} />{r.priority.name}</span> },
  assignee: { label: t('Assigné'), sort: 'assignee', render: (r) => r.assignee ? <span className="flex items-center gap-1"><Avatar user={r.assignee} size={5} />{r.assignee.displayName}</span> : <span className="text-slate-500">{t('Non assigné')}</span> },
  reporter: { label: t('Rapporteur'), sort: 'reporter', render: (r) => r.reporter.displayName },
  resolution: { label: t('Résolution'), render: (r) => r.resolution ?? '' },
  labels: { label: t('Labels'), render: (r) => r.labels.join(', ') },
  parent: { label: t('Parent'), render: (r) => r.parentKey && <Link to={`/browse/${r.parentKey}`} className="hover:underline">{r.parentKey}</Link> },
  due: { label: t('Échéance'), sort: 'due', render: (r) => r.dueDate ?? '' },
  created: { label: t('Créé'), sort: 'created', render: (r) => new Date(r.createdAt).toLocaleDateString() },
  updated: { label: t('Mis à jour'), sort: 'updated', render: (r) => new Date(r.updatedAt).toLocaleDateString() },
  lastComment: { label: t('Dernier commentaire'), render: (r) => r.lastComment && <span className="line-clamp-2 text-xs" title={r.lastComment.excerpt}><strong>{r.lastComment.author}</strong> : {r.lastComment.excerpt}</span> },
}
const DEFAULT_COLUMNS = ['type', 'key', 'summary', 'status', 'priority', 'assignee', 'updated']
const FILTER_PARAMS = ['fql', 'q', 'spaces', 'types', 'statusCategories', 'assignees', 'priorities', 'labels', 'parent', 'resolved', 'updatedFrom', 'updatedTo'] as const

export function SearchPage() {
  const me = useMe().data!
  const qc = useQueryClient()
  const [params, setParams] = useSearchParams()
  const columns = (me.preferences.searchColumns as string[] | undefined)?.filter((c) => COLUMNS[c]) ?? DEFAULT_COLUMNS
  const page = Number(params.get('page') ?? 0)
  const sort = params.get('sort') ?? 'updated,desc'
  const apiQuery = new URLSearchParams()
  FILTER_PARAMS.forEach((p) => { const v = params.get(p); if (v) apiQuery.set(p, v) })
  apiQuery.set('sort', sort)
  apiQuery.set('page', String(page))
  const result = useQuery({
    queryKey: ['search', apiQuery.toString()],
    queryFn: () => api<Result>(`/search?${apiQuery}`),
    placeholderData: keepPreviousData,
  })

  const set = (name: string, value: string | null) => setParams((prev) => {
    const p = new URLSearchParams(prev) // new identity, so memos depending on params recompute
    if (value) p.set(name, value)
    else p.delete(name)
    if (name !== 'page') p.delete('page')
    return p
  }, { replace: true })

  /** Click: sort by this column; Shift+click: add as a secondary sort. */
  const sortBy = (field: string, add: boolean) => {
    const parts = sort.split(';').filter(Boolean).map((s) => s.split(','))
    const existing = parts.find((p) => p[0] === field)
    const dir = existing && existing[1] !== 'desc' ? 'desc' : 'asc'
    const next = add ? [...parts.filter((p) => p[0] !== field), [field, dir]] : [[field, dir]]
    set('sort', next.map((p) => p.join(',')).join(';'))
  }
  const sortMark = (field?: string) => {
    if (!field) return ''
    const i = sort.split(';').findIndex((s) => s.startsWith(field + ','))
    if (i < 0) return ''
    return (sort.split(';')[i].endsWith(',desc') ? t(' ↓') : t(' ↑')) + (sort.includes(';') ? `${i + 1}` : '')
  }

  const saveColumns = async (cols: string[]) => {
    const updated = await api('/me', { method: 'PATCH', body: { preferences: { searchColumns: cols } } })
    qc.setQueryData(['me'], updated)
  }

  const mode = params.get('mode') === 'fql' ? 'fql' : 'basic'
  const [fqlDraft, setFqlDraft] = useState(params.get('fql') ?? '')
  const meta = useMeta().data
  const directory = useDirectory().data
  const fqlError = result.error instanceof ApiError && result.error.code === 'FQL_ERROR' ? result.error : null

  const selection = useSelection()
  const pageKeys = result.data?.rows.map((x) => x.key) ?? []
  const allOnPage = pageKeys.length > 0 && pageKeys.every((k) => selection.selected.has(k))
  const selectAll = async () => {
    const q = new URLSearchParams(apiQuery)
    q.delete('page')
    selection.set(await api<string[]>(`/search/keys?${q}`))
  }

  const r = result.data
  const pages = r ? Math.max(1, Math.ceil(r.total / r.size)) : 1
  return (
    <div className="space-y-3 p-6">
      <div className="flex items-center gap-3">
        <h1 className="text-xl font-semibold">{t('Recherche')}</h1>
        {r && <span className="text-sm text-slate-500">{t('{n} ticket(s)', { n: r.total })}</span>}
        <div className="ml-auto flex items-center gap-2">
          <ColumnPicker columns={columns} onChange={saveColumns} />
          <a className="text-sm text-indigo-600 hover:underline" href={`/api/search/export.csv?${apiQuery}`}>{t('Exporter en CSV')}</a>
        </div>
      </div>
      <div className="flex items-center gap-2">
        <div role="tablist" className="inline-flex rounded-md border border-slate-300 text-sm dark:border-slate-700">
          {(['basic', 'fql'] as const).map((m) => (
            <button key={m} role="tab" aria-selected={mode === m}
              onClick={() => {
                if (m === mode) return
                if (m === 'fql') setFqlDraft(params.get('fql') ?? toFql(params, meta, directory))
                set('mode', m === 'fql' ? 'fql' : null)
              }}
              className={`px-3 py-1 ${mode === m ? 'bg-indigo-600 text-white' : ''}`}>{m === 'fql' ? t('FQL') : t('Basique')}</button>
          ))}
        </div>
        <SavedFilters fql={params.get('fql') ?? ''} onLoad={(f) => { setFqlDraft(f.fql); setParams(new URLSearchParams({ mode: 'fql', fql: f.fql }), { replace: true }) }} />
      </div>
      {mode === 'fql' ? (
        <div className="flex items-start gap-2">
          <div className="flex-1">
            <FqlInput value={fqlDraft} onChange={setFqlDraft} onSubmit={() => set('fql', fqlDraft.trim() || null)}
              error={fqlError?.message} errorPosition={fqlError?.details.position as number | undefined} />
          </div>
          <Button onClick={() => set('fql', fqlDraft.trim() || null)}>{t('Rechercher')}</Button>
        </div>
      ) : (
        <Filters params={params} set={set} />
      )}
      {!fqlError && <ErrorText error={result.error} />}
      <div className="flex min-h-9 flex-wrap items-center gap-3">
        <BulkBar keys={[...selection.selected]} onClear={selection.clear} />
        {r && r.total > pageKeys.length && selection.selected.size < Math.min(r.total, BULK_MAX) && (
          <button className="text-sm text-indigo-600 hover:underline" onClick={selectAll}>
            {t('Tout sélectionner ({n})', { n: Math.min(r.total, BULK_MAX) })}
          </button>
        )}
      </div>
      <div className="overflow-x-auto rounded-md border border-slate-200 dark:border-slate-800">
        <table className="w-full text-left text-sm">
          <thead className="bg-slate-50 text-xs uppercase text-slate-500 dark:bg-slate-900">
            <tr>
              <th className="w-8 px-3 py-2">
                <input type="checkbox" aria-label={t('Sélectionner la page')} checked={allOnPage}
                  onChange={() => (allOnPage ? pageKeys.forEach((k) => selection.selected.has(k) && selection.toggle(pageKeys, k, false))
                    : selection.set([...selection.selected, ...pageKeys]))} />
              </th>
              {columns.map((c) => (
                <th key={c} className="whitespace-nowrap px-3 py-2">
                  {COLUMNS[c].sort ? (
                    <button title={t('Clic : trier · Maj+clic : tri secondaire')} onClick={(e) => sortBy(COLUMNS[c].sort!, e.shiftKey)}
                      className="uppercase hover:text-slate-900 dark:hover:text-slate-100">{COLUMNS[c].label}{sortMark(COLUMNS[c].sort)}</button>
                  ) : COLUMNS[c].label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className={result.isPlaceholderData ? 'opacity-60' : ''}>
            {r?.rows.map((row) => (
              <tr key={row.id} className="border-t border-slate-100 hover:bg-slate-50 dark:border-slate-900 dark:hover:bg-slate-900">
                <td className="px-3 py-1.5 align-top">
                  <input type="checkbox" aria-label={t('Sélectionner {key}', { key: row.key })} checked={selection.selected.has(row.key)}
                    onChange={(e) => selection.toggle(pageKeys, row.key, (e.nativeEvent as MouseEvent).shiftKey)} />
                </td>
                {columns.map((c) => <td key={c} className="px-3 py-1.5 align-top">{COLUMNS[c].render(row)}</td>)}
              </tr>
            ))}
            {r?.rows.length === 0 && <tr><td colSpan={columns.length + 1} className="px-3 py-6 text-center text-slate-500">{t('Aucun ticket ne correspond.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {pages > 1 && (
        <div className="flex items-center justify-center gap-2 text-sm">
          <Button variant="secondary" disabled={page === 0} onClick={() => set('page', String(page - 1))}>{t('Précédent')}</Button>
          <span>{t('Page {page} / {pages}', { page: page + 1, pages })}</span>
          <Button variant="secondary" disabled={page + 1 >= pages} onClick={() => set('page', String(page + 1))}>{t('Suivant')}</Button>
        </div>
      )}
    </div>
  )
}

function Filters({ params, set }: { params: URLSearchParams; set: (n: string, v: string | null) => void }) {
  const meta = useMeta().data
  const users = useDirectory().data ?? []
  const spaces = useQuery({ queryKey: ['spaces', true], queryFn: () => api<SpaceSummary[]>('/spaces?includeArchived=true') }).data ?? []
  const [q, setQ] = useState(params.get('q') ?? '')
  useEffect(() => {
    if ((q.trim() || null) === params.get('q')) return // no-op write would reset the page and could undo a navigation
    const t = setTimeout(() => set('q', q.trim() || null), 250)
    return () => clearTimeout(t)
  }, [q]) // eslint-disable-line react-hooks/exhaustive-deps
  const select = 'rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900'
  const multi = (name: string, label: string, options: { value: string; label: string }[]) => {
    const selected = params.get(name)?.split(',').filter(Boolean) ?? []
    return (
      <select aria-label={label} className={select} value=""
        onChange={(e) => { const v = e.target.value; if (v) set(name, [...new Set([...selected, v])].join(',')) }}>
        <option value="">{label}{selected.length ? ` (${selected.length})` : ''}</option>
        {options.filter((o) => !selected.includes(o.value)).map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    )
  }
  const chips = FILTER_PARAMS.filter((p) => p !== 'q').flatMap((p) => (params.get(p)?.split(',').filter(Boolean) ?? []).map((v) => ({ p, v })))
  const labelOf = (p: string, v: string) =>
    p === 'types' ? meta?.types.find((t) => t.id === v)?.name
      : p === 'priorities' ? meta?.priorities.find((x) => x.id === v)?.name
      : p === 'assignees' ? v === 'me' ? t('Moi') : v === 'none' ? t('Non assigné') : users.find((u) => u.id === v)?.displayName
      : p === 'statusCategories' ? { TODO: t('À faire'), IN_PROGRESS: t('En cours'), DONE: t('Terminé') }[v]
      : p === 'resolved' ? v === 'true' ? t('Résolus') : t('Non résolus')
      : v
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('Texte (résumé, description, commentaires)…')}
          className="w-80 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900" />
        {multi('spaces', t('Espace'), spaces.map((s) => ({ value: s.key, label: `${s.name} (${s.key})` })))}
        {multi('types', t('Type'), meta?.types.map((t) => ({ value: t.id, label: t.name })) ?? [])}
        {multi('statusCategories', t('Statut'), [{ value: 'TODO', label: t('À faire') }, { value: 'IN_PROGRESS', label: t('En cours') }, { value: 'DONE', label: t('Terminé') }])}
        {multi('assignees', t('Assigné'), [{ value: 'me', label: t('Moi') }, { value: 'none', label: t('Non assigné') }, ...users.map((u) => ({ value: u.id, label: u.displayName }))])}
        {multi('priorities', t('Priorité'), meta?.priorities.map((p) => ({ value: p.id, label: p.name })) ?? [])}
        <input placeholder={t('Label')} aria-label={t('Label')} className={`${select} w-28`}
          onKeyDown={(e) => {
            const v = e.currentTarget.value.trim()
            if (e.key === 'Enter' && v) { set('labels', [...new Set([...(params.get('labels')?.split(',').filter(Boolean) ?? []), v])].join(',')); e.currentTarget.value = '' }
          }} />
        <select aria-label={t('Résolution')} className={select} value={params.get('resolved') ?? ''} onChange={(e) => set('resolved', e.target.value || null)}>
          <option value="">{t('Résolus ou non')}</option><option value="false">{t('Non résolus')}</option><option value="true">{t('Résolus')}</option>
        </select>
        <label className="flex items-center gap-1 text-sm text-slate-500">{t('Mis à jour depuis')}
          <input type="date" className={select} value={params.get('updatedFrom') ?? ''} onChange={(e) => set('updatedFrom', e.target.value || null)} />
        </label>
      </div>
      {chips.length > 0 && (
        <div className="flex flex-wrap items-center gap-1">
          {chips.filter((c) => c.p !== 'updatedFrom' && c.p !== 'resolved').map(({ p, v }) => (
            <button key={p + v} onClick={() => set(p, (params.get(p)?.split(',') ?? []).filter((x) => x !== v).join(',') || null)}
              className="rounded-full bg-indigo-50 px-2 py-0.5 text-xs text-indigo-800 hover:bg-indigo-100 dark:bg-indigo-950 dark:text-indigo-200">
              {labelOf(p, v) ?? v} ✕
            </button>
          ))}
          <button className="text-xs text-slate-500 hover:underline" onClick={() => { setQ(''); FILTER_PARAMS.forEach((p) => set(p, null)) }}>{t('Tout effacer')}</button>
        </div>
      )}
    </div>
  )
}

function ColumnPicker({ columns, onChange }: { columns: string[]; onChange: (c: string[]) => void }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="relative">
      <Button variant="secondary" onClick={() => setOpen(!open)} aria-expanded={open}>{t('Colonnes')}</Button>
      {open && (
        <ul className="absolute right-0 z-20 mt-1 w-56 rounded-md border border-slate-200 bg-white p-1 text-sm shadow-lg dark:border-slate-700 dark:bg-slate-900">
          {Object.entries(COLUMNS).map(([k, c]) => (
            <li key={k}>
              <label className="flex items-center gap-2 rounded px-2 py-1 hover:bg-slate-100 dark:hover:bg-slate-800">
                <input type="checkbox" checked={columns.includes(k)} disabled={k === 'key'}
                  onChange={() => onChange(columns.includes(k) ? columns.filter((x) => x !== k) : [...columns, k])} />
                {c.label}
              </label>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Basic filters → equivalent FQL, so switching to FQL keeps the current search (spec §4.11). */
function toFql(params: URLSearchParams, meta: Meta | undefined, users: { id: string; username: string }[] | undefined) {
  const list = (name: string) => params.get(name)?.split(',').filter(Boolean) ?? []
  const quote = (v: string) => (/^[\w-]+$/.test(v) ? v : `"${v.replace(/"/g, '\\"')}"`)
  const inList = (field: string, values: string[]) => values.length === 1 ? `${field} = ${quote(values[0])}` : `${field} in (${values.map(quote).join(', ')})`
  const parts: string[] = []
  const q = params.get('q')
  if (q) parts.push(`text ~ ${quote(q)}`)
  if (list('spaces').length) parts.push(inList('space', list('spaces')))
  const types = list('types').map((id) => meta?.types.find((x) => x.id === id)?.name).filter((x): x is string => !!x)
  if (types.length) parts.push(inList('type', types))
  if (list('statusCategories').length) parts.push(inList('statusCategory', list('statusCategories')))
  const assignees = list('assignees')
  const named = assignees.filter((a) => a !== 'none').map((a) => (a === 'me' ? 'currentUser()' : quote(users?.find((u) => u.id === a)?.username ?? a)))
  if (assignees.includes('none') && named.length) parts.push(`(assignee IS EMPTY OR assignee in (${named.join(', ')}))`)
  else if (assignees.includes('none')) parts.push('assignee IS EMPTY')
  else if (named.length) parts.push(`assignee in (${named.join(', ')})`)
  const priorities = list('priorities').map((id) => meta?.priorities.find((x) => x.id === id)?.name).filter((x): x is string => !!x)
  if (priorities.length) parts.push(inList('priority', priorities))
  if (list('labels').length) parts.push(inList('labels', list('labels')))
  if (params.get('resolved') === 'true') parts.push('resolution IS NOT EMPTY')
  if (params.get('resolved') === 'false') parts.push('resolution IS EMPTY')
  if (params.get('updatedFrom')) parts.push(`updated >= ${params.get('updatedFrom')}`)
  const sort = (params.get('sort') ?? 'updated,desc').split(';').map((x) => x.split(',')).map(([f, d]) => `${f}${d === 'desc' ? ' DESC' : ''}`)
  return `${parts.join(' AND ')} ORDER BY ${sort.join(', ')}`.trim()
}

type FilterView = { id: string; name: string; fql: string; shareScope: string; mine: boolean; favorite: boolean; owner: { displayName: string } }

/** Saved filters: load, save the current FQL (private or shared), favourite, delete (spec §4.11). */
function SavedFilters({ fql, onLoad }: { fql: string; onLoad: (f: FilterView) => void }) {
  const qc = useQueryClient()
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState<null | { name: string; shareScope: string; shareGroupId: string; shareSpaceKey: string }>(null)
  const filters = useQuery({ queryKey: ['filters'], queryFn: () => api<FilterView[]>('/filters') })
  const groups = useQuery({ queryKey: ['directory', 'groups'], queryFn: () => api<{ id: string; name: string }[]>('/directory/groups'), enabled: !!saving })
  const spaces = useQuery({ queryKey: ['spaces', false], queryFn: () => api<SpaceSummary[]>('/spaces?includeArchived=false'), enabled: !!saving })
  const act = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn(), onSuccess: () => qc.invalidateQueries({ queryKey: ['filters'] }) })
  const sorted = [...(filters.data ?? [])].sort((a, b) => Number(b.favorite) - Number(a.favorite))
  const select = 'rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900'
  return (
    <div className="relative flex items-center gap-2">
      <Button variant="secondary" onClick={() => setOpen(!open)} aria-expanded={open}>{t('Filtres sauvegardés')} ▾</Button>
      {fql && <Button variant="ghost" onClick={() => setSaving({ name: '', shareScope: 'PRIVATE', shareGroupId: '', shareSpaceKey: '' })}>{t('Enregistrer le filtre')}</Button>}
      {saving && (
        <form className="flex flex-wrap items-center gap-2" onSubmit={(e) => {
          e.preventDefault()
          act.mutate(() => api('/filters', { body: { name: saving.name, fql, shareScope: saving.shareScope,
            shareGroupId: saving.shareGroupId || null, shareSpaceKey: saving.shareSpaceKey || null } }), { onSuccess: () => setSaving(null) })
        }}>
          <input autoFocus required placeholder={t('Nom du filtre')} value={saving.name} onChange={(e) => setSaving({ ...saving, name: e.target.value })} className={select} />
          <select aria-label={t('Partage')} value={saving.shareScope} onChange={(e) => setSaving({ ...saving, shareScope: e.target.value })} className={select}>
            <option value="PRIVATE">{t('Privé')}</option><option value="GROUP">{t('Un groupe')}</option>
            <option value="SPACE">{t('Les membres d’un espace')}</option><option value="ALL">{t('Tout le monde')}</option>
          </select>
          {saving.shareScope === 'GROUP' && (
            <select aria-label={t('Groupe')} required value={saving.shareGroupId} onChange={(e) => setSaving({ ...saving, shareGroupId: e.target.value })} className={select}>
              <option value="" />{groups.data?.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
            </select>
          )}
          {saving.shareScope === 'SPACE' && (
            <select aria-label={t('Espace')} required value={saving.shareSpaceKey} onChange={(e) => setSaving({ ...saving, shareSpaceKey: e.target.value })} className={select}>
              <option value="" />{spaces.data?.map((sp) => <option key={sp.key} value={sp.key}>{sp.name}</option>)}
            </select>
          )}
          <Button type="submit">{t('Enregistrer')}</Button>
          <Button type="button" variant="secondary" onClick={() => setSaving(null)}>{t('Annuler')}</Button>
          <ErrorText error={act.error} />
        </form>
      )}
      {open && (
        <ul className="absolute left-0 top-full z-30 mt-1 max-h-80 w-96 overflow-y-auto rounded-md border border-slate-200 bg-white py-1 text-sm shadow-lg dark:border-slate-700 dark:bg-slate-900">
          {sorted.map((f) => (
            <li key={f.id} className="group flex items-center gap-2 px-3 py-1.5 hover:bg-slate-50 dark:hover:bg-slate-800">
              <button aria-label={f.favorite ? t('Retirer des favoris') : t('Ajouter aux favoris')} className={f.favorite ? 'text-amber-500' : 'text-slate-400'}
                onClick={() => act.mutate(() => api(`/filters/${f.id}/favorite`, { method: f.favorite ? 'DELETE' : 'PUT' }))}>★</button>
              <button className="min-w-0 flex-1 text-left" onClick={() => { setOpen(false); onLoad(f) }}>
                <div className="truncate font-medium">{f.name}</div>
                <div className="truncate font-mono text-xs text-slate-500">{f.fql}</div>
              </button>
              {!f.mine && <span className="text-xs text-slate-500">{f.owner.displayName}</span>}
              {f.mine && (
                <button aria-label={t('Supprimer {name}', { name: f.name })} className="invisible text-slate-400 hover:text-red-600 group-hover:visible"
                  onClick={() => { if (window.confirm(t('Supprimer {name} ?', { name: f.name }))) act.mutate(() => api(`/filters/${f.id}`, { method: 'DELETE' })) }}>✕</button>
              )}
            </li>
          ))}
          {sorted.length === 0 && <li className="px-3 py-2 text-slate-500">{t('Aucun filtre sauvegardé')}</li>}
        </ul>
      )}
    </div>
  )
}
