import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api'
import { Button, ErrorText, Input } from '../ui'
import { t } from '../i18n'

type RefRow = {
  id: string; name: string; count: number; color?: string; icon?: string; hierarchyLevel?: number
  isDefault?: boolean; outward?: string; inward?: string
}
type Reference = { types: RefRow[]; priorities: RefRow[]; resolutions: RefRow[]; linkTypes: RefRow[] }

const LEVELS: [number, string][] = [[1, t('Epic')], [0, t('Standard')], [-1, t('Sous-tâche')]]
const ICONS = ['task', 'bug', 'story', 'epic', 'subtask']
const select = 'rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900'

/** Ticket types, priorities, resolutions and link types (V1.6.3). */
export function ReferenceAdmin() {
  const qc = useQueryClient()
  const ref = useQuery({ queryKey: ['admin', 'reference'], queryFn: () => api<Reference>('/admin/reference') })
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['admin', 'reference'] }); void qc.invalidateQueries({ queryKey: ['meta'] }) },
  })
  const [draft, setDraft] = useState<Record<string, string>>({})
  if (!ref.data) return <div className="p-6"><ErrorText error={ref.error} /></div>
  const r = ref.data
  const d = (k: string) => draft[k] ?? ''
  const set = (k: string, v: string) => setDraft({ ...draft, [k]: v })
  const patch = (path: string, body: object) => act.mutate(() => api(path, { method: 'PATCH', body }))
  const create = (path: string, body: object) => act.mutate(() => api(path, { body }), { onSuccess: () => setDraft({}) })
  // A value in use is replaced by another one before deletion (types and link types must be unused).
  const remove = (path: string, row: RefRow, others: RefRow[]) => {
    if (row.count > 0 && others.length) {
      const target = window.prompt(t('{n} ticket(s) l’utilisent. Remplacer par (nom exact) :', { n: row.count }) + '\n' + others.map((o) => o.name).join(', '))
      const found = others.find((o) => o.name === target?.trim())
      if (found) act.mutate(() => api(`${path}?replaceWith=${found.id}`, { method: 'DELETE' }))
    } else if (window.confirm(t('Supprimer « {name} » ?', { name: row.name }))) {
      act.mutate(() => api(path, { method: 'DELETE' }))
    }
  }
  const nameInput = (row: RefRow, path: string) => (
    <Input aria-label={t('Nom')} defaultValue={row.name} className="w-44"
      onBlur={(e) => { if (e.target.value.trim() && e.target.value !== row.name) patch(path, { name: e.target.value }) }} />
  )
  const color = (row: RefRow, path: string) => (
    <input type="color" aria-label={t('Couleur de {name}', { name: row.name })} value={row.color} className="h-7 w-8 rounded border border-slate-300 dark:border-slate-700"
      onChange={(e) => patch(path, { color: e.target.value })} />
  )
  const usage = (row: RefRow) => <span className="text-slate-500">{t('{n} usage(s)', { n: row.count })}</span>
  const del = (path: string, row: RefRow, others: RefRow[]) => (
    <Button variant="ghost" className="text-red-600" onClick={() => remove(path, row, others)}>{t('Supprimer')}</Button>
  )
  const isDefault = (row: RefRow, group: string, path: string) => (
    <label className="flex items-center gap-1">
      <input type="radio" name={group} checked={!!row.isDefault} onChange={() => patch(path, { isDefault: true })} />{t('par défaut')}
    </label>
  )
  return (
    <div className="mx-auto max-w-4xl space-y-10 p-6">
      <h1 className="text-xl font-semibold">{t('Référentiels')}</h1>
      <ErrorText error={act.error} />

      <section className="space-y-2">
        <h2 className="text-lg font-semibold">{t('Types de tickets')}</h2>
        {r.types.map((row) => {
          const path = `/admin/ticket-types/${row.id}`
          return (
            <div key={row.id} className="flex flex-wrap items-center gap-2 text-sm">
              {color(row, path)}{nameInput(row, path)}
              <select aria-label={t('Icône de {name}', { name: row.name })} className={select} value={row.icon} onChange={(e) => patch(path, { icon: e.target.value })}>
                {ICONS.map((i) => <option key={i} value={i}>{i}</option>)}
              </select>
              <span className="text-slate-500">{LEVELS.find(([l]) => l === row.hierarchyLevel)?.[1]}</span>{usage(row)}{del(path, row, [])}
            </div>
          )
        })}
        <form className="flex flex-wrap gap-2" onSubmit={(e) => {
          e.preventDefault()
          create('/admin/ticket-types', { name: d('type'), icon: 'task', color: '#4f46e5', hierarchyLevel: Number(d('level') || 0) })
        }}>
          <Input required aria-label={t('Nouveau type')} placeholder={t('Nouveau type')} className="w-44" value={d('type')} onChange={(e) => set('type', e.target.value)} />
          <select aria-label={t('Niveau')} className={select} value={d('level') || '0'} onChange={(e) => set('level', e.target.value)}>
            {LEVELS.map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
          <Button type="submit" variant="secondary">{t('Ajouter')}</Button>
        </form>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-semibold">{t('Priorités')} <span className="text-sm font-normal text-slate-500">{t('(de la plus urgente à la moins urgente)')}</span></h2>
        {r.priorities.map((row, i) => {
          const path = `/admin/priorities/${row.id}`
          return (
            <div key={row.id} className="flex flex-wrap items-center gap-2 text-sm">
              {color(row, path)}{nameInput(row, path)}
              <button aria-label={t('Monter {field}', { field: row.name })} disabled={i === 0} className="px-1 disabled:opacity-30" onClick={() => {
                const ids = r.priorities.map((p) => p.id)
                ;[ids[i - 1], ids[i]] = [ids[i], ids[i - 1]]
                act.mutate(() => api('/admin/priorities/order', { method: 'PUT', body: ids }))
              }}>↑</button>
              {isDefault(row, 'priority-default', path)}{usage(row)}{del(path, row, r.priorities.filter((p) => p.id !== row.id))}
            </div>
          )
        })}
        <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); create('/admin/priorities', { name: d('prio'), color: '#64748b' }) }}>
          <Input required aria-label={t('Nouvelle priorité')} placeholder={t('Nouvelle priorité')} className="w-44" value={d('prio')} onChange={(e) => set('prio', e.target.value)} />
          <Button type="submit" variant="secondary">{t('Ajouter')}</Button>
        </form>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-semibold">{t('Résolutions')}</h2>
        {r.resolutions.map((row) => {
          const path = `/admin/resolutions/${row.id}`
          return (
            <div key={row.id} className="flex flex-wrap items-center gap-2 text-sm">
              {nameInput(row, path)}{isDefault(row, 'resolution-default', path)}{usage(row)}{del(path, row, r.resolutions.filter((p) => p.id !== row.id))}
            </div>
          )
        })}
        <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); create('/admin/resolutions', { name: d('res') }) }}>
          <Input required aria-label={t('Nouvelle résolution')} placeholder={t('Nouvelle résolution')} className="w-44" value={d('res')} onChange={(e) => set('res', e.target.value)} />
          <Button type="submit" variant="secondary">{t('Ajouter')}</Button>
        </form>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-semibold">{t('Types de liens')}</h2>
        {r.linkTypes.map((row) => {
          const path = `/admin/link-types/${row.id}`
          return (
            <div key={row.id} className="flex flex-wrap items-center gap-2 text-sm">
              {nameInput(row, path)}
              <Input aria-label={t('Libellé sortant de {name}', { name: row.name })} defaultValue={row.outward} className="w-36"
                onBlur={(e) => { if (e.target.value !== row.outward) patch(path, { outward: e.target.value }) }} />
              <Input aria-label={t('Libellé entrant de {name}', { name: row.name })} defaultValue={row.inward} className="w-36"
                onBlur={(e) => { if (e.target.value !== row.inward) patch(path, { inward: e.target.value }) }} />
              {usage(row)}{del(path, row, [])}
            </div>
          )
        })}
        <form className="flex flex-wrap gap-2" onSubmit={(e) => { e.preventDefault(); create('/admin/link-types', { name: d('link'), outward: d('out'), inward: d('in') }) }}>
          <Input required aria-label={t('Nouveau type de lien')} placeholder={t('Nouveau type de lien')} className="w-44" value={d('link')} onChange={(e) => set('link', e.target.value)} />
          <Input required aria-label={t('Libellé sortant')} placeholder={t('ex. teste')} className="w-36" value={d('out')} onChange={(e) => set('out', e.target.value)} />
          <Input required aria-label={t('Libellé entrant')} placeholder={t('ex. est testé par')} className="w-36" value={d('in')} onChange={(e) => set('in', e.target.value)} />
          <Button type="submit" variant="secondary">{t('Ajouter')}</Button>
        </form>
      </section>
    </div>
  )
}
