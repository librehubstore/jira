import { useEffect, useRef, useState } from 'react'
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api'
import type { SpaceDetail, SpaceSummary } from '../spaces'
import { useDirectory } from '../spaces'
import { useComponents, useMeta, useVersions } from '../tickets'
import { Button, ErrorText, Input } from '../ui'
import { t } from '../i18n'

export const BULK_MAX = 1000

/** Click toggles one key; Shift+click selects the range from the last clicked key (spec §4.12). */
export function toggleSelection(selected: Set<string>, ordered: string[], key: string, anchor: string | null, shift: boolean) {
  const next = new Set(selected)
  if (shift && anchor && ordered.includes(anchor)) {
    const [a, b] = [ordered.indexOf(anchor), ordered.indexOf(key)].sort((x, y) => x - y)
    ordered.slice(a, b + 1).forEach((k) => next.add(k))
  } else if (next.has(key)) next.delete(key)
  else next.add(key)
  return next
}

export function useSelection() {
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const anchor = useRef<string | null>(null)
  return {
    selected,
    toggle: (ordered: string[], key: string, shift: boolean) => {
      setSelected((s) => toggleSelection(s, ordered, key, anchor.current, shift))
      anchor.current = key
    },
    set: (keys: string[]) => setSelected(new Set(keys.slice(0, BULK_MAX))),
    clear: () => setSelected(new Set()),
  }
}

/** Bar shown while tickets are selected. */
export function BulkBar({ keys, onClear }: { keys: string[]; onClear: () => void }) {
  const [open, setOpen] = useState(false)
  if (keys.length === 0) return null
  return (
    <div className="flex items-center gap-3 rounded-md bg-indigo-50 px-3 py-1.5 text-sm dark:bg-indigo-950">
      <span className="font-medium">{t('{n} ticket(s) sélectionné(s)', { n: keys.length })}</span>
      <Button onClick={() => setOpen(true)}>{t('Actions en masse…')}</Button>
      <button className="text-slate-600 hover:underline dark:text-slate-300" onClick={onClear}>{t('Désélectionner')}</button>
      {open && <BulkDialog keys={keys} onClose={(done) => { setOpen(false); if (done) onClear() }} />}
    </div>
  )
}

type Job = { id: string; total: number; done: number; failed: number; status: 'RUNNING' | 'DONE'; report: { key: string; reason: string }[] }
type Operation = 'EDIT' | 'TRANSITION' | 'MOVE' | 'DELETE' | 'WATCH' | 'UNWATCH'

const OPERATIONS: Record<Operation, string> = {
  EDIT: t('Modifier des champs'), TRANSITION: t('Changer le statut'), MOVE: t('Déplacer vers un autre espace'),
  DELETE: t('Supprimer'), WATCH: t('Observer'), UNWATCH: t('Ne plus observer'),
}

function BulkDialog({ keys, onClose }: { keys: string[]; onClose: (done: boolean) => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const qc = useQueryClient()
  const meta = useMeta().data
  const users = useDirectory().data ?? []
  const spaceKeys = [...new Set(keys.map((k) => k.slice(0, k.lastIndexOf('-'))))]
  const spaceDetails = useQueries({ queries: spaceKeys.map((k) => ({ queryKey: ['space', k], queryFn: () => api<SpaceDetail>(`/spaces/${k}`) })) })
  const allSpaces = useQuery({ queryKey: ['spaces', false], queryFn: () => api<SpaceSummary[]>('/spaces?includeArchived=false') }).data ?? []
  const single = spaceKeys.length === 1 ? spaceKeys[0] : undefined // components and versions belong to one space
  const components = useComponents(single).data?.filter((c) => !c.archived) ?? []
  const versions = useVersions(single).data?.filter((v) => v.status !== 'ARCHIVED') ?? []
  const statusNames = [...new Set(spaceDetails.flatMap((q) => q.data?.statuses.map((s) => s.name) ?? []))]
  const [op, setOp] = useState<Operation>('EDIT')
  const [p, setP] = useState<Record<string, string>>({ labelsMode: 'ADD' })
  const [notify, setNotify] = useState(keys.length <= 20)
  const [job, setJob] = useState<Job | null>(null)
  const [error, setError] = useState<unknown>(null)
  useEffect(() => { dialog.current?.showModal() }, [])

  // Poll the job until it is done; the browser never waits on one long request (spec §4.12).
  useEffect(() => {
    if (!job || job.status === 'DONE') return
    const timer = setTimeout(async () => {
      const next = await api<Job>(`/bulk/${job.id}`)
      setJob(next)
      if (next.status === 'DONE') ['ticket', 'board', 'search', 'notifications'].forEach((k) => qc.invalidateQueries({ queryKey: [k] }))
    }, 500)
    return () => clearTimeout(timer)
  }, [job, qc])

  const params = (): Record<string, unknown> => {
    switch (op) {
      case 'EDIT': {
        const out: Record<string, unknown> = {}
        if (p.assigneeId) out.assigneeId = p.assigneeId === 'none' ? null : p.assigneeId
        if (p.priorityId) out.priorityId = p.priorityId
        if (p.labels?.trim()) out.labels = { mode: p.labelsMode, values: p.labels.split(/[\s,]+/).filter(Boolean) }
        if (p.componentId) out.componentIds = { mode: p.componentMode ?? 'ADD', values: [p.componentId] }
        if (p.versionId) out.fixVersionIds = { mode: p.versionMode ?? 'ADD', values: [p.versionId] }
        return out
      }
      case 'TRANSITION': return { statusName: p.statusName, resolutionId: p.resolutionId || null, comment: p.comment || null }
      case 'MOVE': return { spaceKey: p.spaceKey }
      case 'DELETE': return { withSubtasks: p.withSubtasks === 'true' }
      default: return {}
    }
  }
  const select = 'w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900'
  const field = (label: string, control: React.ReactNode) => (
    <label className="block text-sm"><span className="mb-1 block font-medium">{label}</span>{control}</label>
  )
  return (
    <dialog ref={dialog} aria-label={t('Actions en masse')} onCancel={(e) => { e.preventDefault(); if (!job || job.status === 'DONE') onClose(!!job) }}
      className="w-full max-w-lg rounded-lg bg-white p-0 text-slate-900 shadow-xl backdrop:bg-slate-900/40 dark:bg-slate-900 dark:text-slate-100">
      <div className="space-y-3 p-5">
        <h2 className="text-lg font-semibold">{t('Actions en masse · {n} ticket(s)', { n: keys.length })}</h2>
        {!job ? (
          <form className="space-y-3" onSubmit={async (e) => {
            e.preventDefault()
            if (op === 'DELETE' && !window.confirm(t('Supprimer définitivement {n} ticket(s) ?', { n: keys.length }))) return
            try {
              setJob(await api<Job>('/bulk', { body: { operation: op, keys, params: params(), sendNotifications: notify } }))
            } catch (err) {
              setError(err)
            }
          }}>
            {field(t('Opération'), (
              <select className={select} value={op} onChange={(e) => setOp(e.target.value as Operation)}>
                {Object.entries(OPERATIONS).map(([k, label]) => <option key={k} value={k}>{label}</option>)}
              </select>
            ))}
            {op === 'EDIT' && (
              <>
                {field(t('Assigné'), (
                  <select className={select} value={p.assigneeId ?? ''} onChange={(e) => setP({ ...p, assigneeId: e.target.value })}>
                    <option value="">{t('(inchangé)')}</option><option value="none">{t('Non assigné')}</option>
                    {users.map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
                  </select>
                ))}
                {field(t('Priorité'), (
                  <select className={select} value={p.priorityId ?? ''} onChange={(e) => setP({ ...p, priorityId: e.target.value })}>
                    <option value="">{t('(inchangé)')}</option>
                    {meta?.priorities.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                  </select>
                ))}
                <div className="flex gap-2">
                  {field(t('Labels'), (
                    <select className={select} value={p.labelsMode} onChange={(e) => setP({ ...p, labelsMode: e.target.value })}>
                      <option value="ADD">{t('Ajouter')}</option><option value="REMOVE">{t('Retirer')}</option><option value="REPLACE">{t('Remplacer par')}</option>
                    </select>
                  ))}
                  <div className="flex-1">{field(t('Valeurs (séparées par des espaces)'), <Input value={p.labels ?? ''} onChange={(e) => setP({ ...p, labels: e.target.value })} />)}</div>
                </div>
                {single && components.length > 0 && <RefOp label={t('Composant')} mode={p.componentMode ?? 'ADD'} value={p.componentId ?? ''} options={components}
                  onMode={(m) => setP({ ...p, componentMode: m })} onValue={(v) => setP({ ...p, componentId: v })} />}
                {single && versions.length > 0 && <RefOp label={t('Version')} mode={p.versionMode ?? 'ADD'} value={p.versionId ?? ''} options={versions}
                  onMode={(m) => setP({ ...p, versionMode: m })} onValue={(v) => setP({ ...p, versionId: v })} />}
              </>
            )}
            {op === 'TRANSITION' && (
              <>
                {field(t('Nouveau statut'), (
                  <select required className={select} value={p.statusName ?? ''} onChange={(e) => setP({ ...p, statusName: e.target.value })}>
                    <option value="" />{statusNames.map((n) => <option key={n} value={n}>{n}</option>)}
                  </select>
                ))}
                {field(t('Résolution (si Terminé)'), (
                  <select className={select} value={p.resolutionId ?? ''} onChange={(e) => setP({ ...p, resolutionId: e.target.value })}>
                    <option value="">{t('Par défaut')}</option>
                    {meta?.resolutions.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                  </select>
                ))}
                {field(t('Commentaire (si demandé par la transition)'), (
                  <textarea rows={2} className={select} value={p.comment ?? ''} onChange={(e) => setP({ ...p, comment: e.target.value })} />
                ))}
              </>
            )}
            {op === 'MOVE' && field(t('Espace cible'), (
              <select required className={select} value={p.spaceKey ?? ''} onChange={(e) => setP({ ...p, spaceKey: e.target.value })}>
                <option value="" />{allSpaces.map((s) => <option key={s.key} value={s.key}>{s.key} · {s.name}</option>)}
              </select>
            ))}
            {op === 'DELETE' && (
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={p.withSubtasks === 'true'} onChange={(e) => setP({ ...p, withSubtasks: String(e.target.checked) })} />
                {t('Supprimer aussi les sous-tâches')}
              </label>
            )}
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={notify} onChange={(e) => setNotify(e.target.checked)} />
              {t('Envoyer les notifications')}
            </label>
            <ErrorText error={error} />
            <div className="flex justify-end gap-2">
              <Button type="button" variant="secondary" onClick={() => onClose(false)}>{t('Annuler')}</Button>
              <Button type="submit" variant={op === 'DELETE' ? 'danger' : 'primary'}>{t('Lancer')}</Button>
            </div>
          </form>
        ) : (
          <div className="space-y-3">
            <div role="progressbar" aria-valuemin={0} aria-valuemax={job.total} aria-valuenow={job.done} aria-label={t('Progression')}
              className="h-2 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
              <div className="h-full bg-indigo-600 transition-all" style={{ width: `${(100 * job.done) / job.total}%` }} />
            </div>
            <p className="text-sm" role="status">
              {job.status === 'DONE'
                ? t('{ok} réussi(s), {failed} refusé(s)', { ok: job.done - job.failed, failed: job.failed })
                : t('{done} / {total} traité(s)…', { done: job.done, total: job.total })}
            </p>
            {job.report.length > 0 && (
              <ul className="max-h-48 overflow-y-auto rounded-md border border-slate-200 p-2 text-sm dark:border-slate-800">
                {job.report.map((f) => <li key={f.key}><span className="font-mono">{f.key}</span> : {f.reason}</li>)}
              </ul>
            )}
            <div className="flex justify-end">
              <Button disabled={job.status !== 'DONE'} onClick={() => onClose(true)}>{t('Fermer')}</Button>
            </div>
          </div>
        )}
      </div>
    </dialog>
  )
}

function RefOp({ label, mode, value, options, onMode, onValue }: {
  label: string; mode: string; value: string; options: { id: string; name: string }[]; onMode: (m: string) => void; onValue: (v: string) => void
}) {
  const select = 'rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900'
  return (
    <div className="flex items-end gap-2 text-sm">
      <label className="block"><span className="mb-1 block font-medium">{label}</span>
        <select aria-label={t('Action sur {field}', { field: label })} className={select} value={mode} onChange={(e) => onMode(e.target.value)}>
          <option value="ADD">{t('Ajouter')}</option><option value="REMOVE">{t('Retirer')}</option><option value="REPLACE">{t('Remplacer par')}</option>
        </select>
      </label>
      <select aria-label={label} className={`${select} flex-1`} value={value} onChange={(e) => onValue(e.target.value)}>
        <option value="">{t('(inchangé)')}</option>
        {options.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
      </select>
    </div>
  )
}
