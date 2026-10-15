import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { api } from '../api'
import { can, useSpace, type SpaceDetail } from '../spaces'
import { useAssignable, useComponents, useVersions, type VersionStatus } from '../tickets'
import { Button, ErrorText, Input } from '../ui'
import { t } from '../i18n'

type LabelView = { id: string; name: string; color: string | null; count: number }

const select = 'rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900'
const STATUS: Record<VersionStatus, string> = { UNRELEASED: t('Non publiée'), RELEASED: t('Publiée'), ARCHIVED: t('Archivée') }

function useAct(spaceKey: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['space', spaceKey] }); void qc.invalidateQueries({ queryKey: ['ticket'] }); void qc.invalidateQueries({ queryKey: ['board'] }) },
  })
}

/** Labels, components and versions of a space (spec §4.13). */
export function CatalogPage() {
  const { key } = useParams()
  const space = useSpace(key).data
  if (!space) return null
  return (
    <div className="mx-auto max-w-4xl space-y-10 p-6">
      <h1 className="text-xl font-semibold">{t('Référentiels · {name}', { name: space.name })}</h1>
      {can(space, 'MANAGE_VERSIONS') && <Versions space={space} />}
      {can(space, 'ADMINISTER_SPACE') && <Components space={space} />}
      {can(space, 'ADMINISTER_SPACE') && <Labels space={space} />}
    </div>
  )
}

function Labels({ space }: { space: SpaceDetail }) {
  const list = useQuery({ queryKey: ['space', space.key, 'label-catalog'], queryFn: () => api<LabelView[]>(`/spaces/${space.key}/label-catalog`) })
  const act = useAct(space.key)
  const [merging, setMerging] = useState<{ from: LabelView; to: string } | null>(null)
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Labels')}</h2>
      <ErrorText error={act.error} />
      <ul className="space-y-1">
        {list.data?.map((l) => (
          <li key={l.id} className="flex flex-wrap items-center gap-2 text-sm">
            <input type="color" aria-label={t('Couleur de {name}', { name: l.name })} value={l.color ?? '#94a3b8'} className="h-7 w-8 rounded border border-slate-300 dark:border-slate-700"
              onChange={(e) => act.mutate(() => api(`/labels/${l.id}`, { method: 'PATCH', body: { color: e.target.value } }))} />
            <Input aria-label={t('Nom du label')} defaultValue={l.name} className="w-48"
              onBlur={(e) => { if (e.target.value.trim() && e.target.value !== l.name) act.mutate(() => api(`/labels/${l.id}`, { method: 'PATCH', body: { name: e.target.value } })) }} />
            <span className="text-slate-500">{t('{n} ticket(s)', { n: l.count })}</span>
            {merging?.from.id === l.id ? (
              <>
                <select aria-label={t('Fusionner dans')} className={select} value={merging.to} onChange={(e) => setMerging({ ...merging, to: e.target.value })}>
                  <option value="" />{list.data.filter((o) => o.id !== l.id).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                </select>
                <Button disabled={!merging.to} onClick={() => act.mutate(() => api(`/labels/${l.id}/merge`, { body: { targetId: merging.to } }), { onSuccess: () => setMerging(null) })}>{t('Fusionner')}</Button>
                <Button variant="secondary" onClick={() => setMerging(null)}>{t('Annuler')}</Button>
              </>
            ) : (
              <Button variant="ghost" onClick={() => setMerging({ from: l, to: '' })}>{t('Fusionner…')}</Button>
            )}
            <Button variant="ghost" className="text-red-600" onClick={() => {
              if (window.confirm(t('Supprimer le label « {name} » de {n} ticket(s) ?', { name: l.name, n: l.count }))) act.mutate(() => api(`/labels/${l.id}`, { method: 'DELETE' }))
            }}>{t('Supprimer')}</Button>
          </li>
        ))}
        {list.data?.length === 0 && <li className="text-sm text-slate-500">{t('Aucun label.')}</li>}
      </ul>
    </section>
  )
}

function Components({ space }: { space: SpaceDetail }) {
  const list = useComponents(space.key).data
  const assignable = useAssignable(space.key).data ?? []
  const act = useAct(space.key)
  const [name, setName] = useState('')
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Composants')}</h2>
      <ErrorText error={act.error} />
      <ul className="space-y-1">
        {list?.map((c) => (
          <li key={c.id} className={`flex flex-wrap items-center gap-2 text-sm ${c.archived ? 'opacity-60' : ''}`}>
            <Input aria-label={t('Nom du composant')} defaultValue={c.name} className="w-48"
              onBlur={(e) => { if (e.target.value.trim() && e.target.value !== c.name) act.mutate(() => api(`/components/${c.id}`, { method: 'PATCH', body: { name: e.target.value } })) }} />
            <select aria-label={t('Responsable de {name}', { name: c.name })} className={select} value={c.lead?.id ?? ''}
              onChange={(e) => act.mutate(() => api(`/components/${c.id}`, { method: 'PATCH', body: { leadId: e.target.value } }))}>
              <option value="">{t('Sans responsable')}</option>
              {assignable.map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
            </select>
            <span className="text-slate-500">{t('{n} ticket(s)', { n: c.count })}</span>
            <Button variant="ghost" onClick={() => act.mutate(() => api(`/components/${c.id}`, { method: 'PATCH', body: { archived: !c.archived } }))}>
              {c.archived ? t('Désarchiver') : t('Archiver')}
            </Button>
            <Button variant="ghost" className="text-red-600" onClick={() => {
              if (window.confirm(t('Supprimer le composant « {name} » ?', { name: c.name }))) act.mutate(() => api(`/components/${c.id}`, { method: 'DELETE' }))
            }}>{t('Supprimer')}</Button>
          </li>
        ))}
      </ul>
      <form className="flex gap-2" onSubmit={(e) => {
        e.preventDefault()
        act.mutate(() => api(`/spaces/${space.key}/components`, { body: { name } }), { onSuccess: () => setName('') })
      }}>
        <Input required aria-label={t('Nouveau composant')} placeholder={t('Nouveau composant')} value={name} className="w-48" onChange={(e) => setName(e.target.value)} />
        <Button type="submit" variant="secondary">{t('Ajouter')}</Button>
      </form>
      <p className="text-xs text-slate-500">{t('Le responsable devient l’assigné par défaut des tickets du composant.')}</p>
    </section>
  )
}

function Versions({ space }: { space: SpaceDetail }) {
  const list = useVersions(space.key).data
  const act = useAct(space.key)
  const [draft, setDraft] = useState({ name: '', startDate: '', releaseDate: '' })
  const [merging, setMerging] = useState<{ id: string; to: string } | null>(null)
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Versions')}</h2>
      <ErrorText error={act.error} />
      <table className="w-full text-sm">
        <thead className="text-left text-xs uppercase text-slate-500">
          <tr><th className="py-1">{t('Nom')}</th><th>{t('Statut')}</th><th>{t('Avancement')}</th><th>{t('Sortie')}</th><th /></tr>
        </thead>
        <tbody>
          {list?.map((v) => (
            <tr key={v.id} className="border-t border-slate-100 dark:border-slate-900">
              <td className="py-1.5 font-medium">{v.name}</td>
              <td>{STATUS[v.status]}</td>
              <td>
                <span title={t('{done} terminé(s) sur {n}', { done: v.done, n: v.count })} className="inline-flex items-center gap-2">
                  <span className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
                    <span className="block h-full bg-green-500" style={{ width: `${v.count ? (100 * v.done) / v.count : 0}%` }} />
                  </span>
                  {v.done}/{v.count}
                </span>
              </td>
              <td>{v.releaseDate ?? '—'}</td>
              <td className="space-x-1 text-right">
                {v.status === 'UNRELEASED' && <Button variant="ghost" onClick={() => act.mutate(() => api(`/versions/${v.id}/release`, { body: {} }))}>{t('Publier')}</Button>}
                {v.status !== 'UNRELEASED' && <Button variant="ghost" onClick={() => act.mutate(() => api(`/versions/${v.id}/unrelease`, { body: {} }))}>{t('Rouvrir')}</Button>}
                {v.status !== 'ARCHIVED' && <Button variant="ghost" onClick={() => act.mutate(() => api(`/versions/${v.id}/archive`, { body: {} }))}>{t('Archiver')}</Button>}
                {merging?.id === v.id ? (
                  <>
                    <select aria-label={t('Fusionner dans')} className={select} value={merging.to} onChange={(e) => setMerging({ ...merging, to: e.target.value })}>
                      <option value="" />{list.filter((o) => o.id !== v.id).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                    </select>
                    <Button disabled={!merging.to} onClick={() => act.mutate(() => api(`/versions/${v.id}/merge`, { body: { targetId: merging.to } }), { onSuccess: () => setMerging(null) })}>{t('Fusionner')}</Button>
                  </>
                ) : (
                  <Button variant="ghost" onClick={() => setMerging({ id: v.id, to: '' })}>{t('Fusionner…')}</Button>
                )}
                <Button variant="ghost" className="text-red-600" onClick={() => {
                  if (window.confirm(t('Supprimer la version « {name} » ?', { name: v.name }))) act.mutate(() => api(`/versions/${v.id}`, { method: 'DELETE' }))
                }}>{t('Supprimer')}</Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => {
        e.preventDefault()
        act.mutate(() => api(`/spaces/${space.key}/versions`, { body: { name: draft.name, startDate: draft.startDate || null, releaseDate: draft.releaseDate || null } }),
          { onSuccess: () => setDraft({ name: '', startDate: '', releaseDate: '' }) })
      }}>
        <Input required aria-label={t('Nouvelle version')} placeholder={t('Nouvelle version')} value={draft.name} className="w-40" onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
        <label className="text-xs text-slate-500">{t('Début')}<input type="date" className={`${select} block`} value={draft.startDate} onChange={(e) => setDraft({ ...draft, startDate: e.target.value })} /></label>
        <label className="text-xs text-slate-500">{t('Sortie prévue')}<input type="date" className={`${select} block`} value={draft.releaseDate} onChange={(e) => setDraft({ ...draft, releaseDate: e.target.value })} /></label>
        <Button type="submit" variant="secondary">{t('Ajouter')}</Button>
      </form>
    </section>
  )
}
