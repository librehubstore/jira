import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api'
import type { SpaceSummary } from '../spaces'
import { Button, ErrorText, Input } from '../ui'
import { t } from '../i18n'

type Token = { id: string; name: string; prefix: string; scope: 'READ' | 'WRITE'; expiresAt: string | null; lastUsedAt: string | null; createdAt: string }

const select = 'rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900'

/** Personal access tokens for the API (spec §8); the secret is shown once. */
export function ApiTokens() {
  const qc = useQueryClient()
  const tokens = useQuery({ queryKey: ['me', 'tokens'], queryFn: () => api<Token[]>('/me/tokens') })
  const [draft, setDraft] = useState({ name: '', scope: 'READ', expiresAt: '' })
  const [secret, setSecret] = useState<string | null>(null)
  const act = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn(), onSuccess: () => qc.invalidateQueries({ queryKey: ['me', 'tokens'] }) })
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Jetons d’accès à l’API')}</h2>
      <p className="text-sm text-slate-500">
        {t('En-tête « Authorization: Bearer <jeton> ».')} <a className="text-indigo-600 hover:underline" href="/api/docs" target="_blank" rel="noreferrer">{t('Documentation de l’API')}</a>
      </p>
      <ErrorText error={act.error} />
      {secret && (
        <div role="status" className="rounded-md bg-amber-50 p-3 text-sm dark:bg-amber-950">
          {t('Copiez ce jeton maintenant, il ne sera plus affiché :')}
          <code className="mt-1 block break-all font-mono">{secret}</code>
          <button className="mt-1 text-indigo-600 hover:underline" onClick={() => navigator.clipboard.writeText(secret)}>{t('Copier')}</button>
        </div>
      )}
      <ul className="space-y-1 text-sm">
        {tokens.data?.map((tk) => (
          <li key={tk.id} className="flex flex-wrap items-center gap-2">
            <span className="font-medium">{tk.name}</span>
            <code className="text-xs text-slate-500">{tk.prefix}…</code>
            <span>{tk.scope === 'READ' ? t('lecture seule') : t('lecture et écriture')}</span>
            <span className="text-slate-500">
              {tk.expiresAt ? t('expire le {date}', { date: new Date(tk.expiresAt).toLocaleDateString() }) : t('sans expiration')}
              {' · '}{tk.lastUsedAt ? t('utilisé le {date}', { date: new Date(tk.lastUsedAt).toLocaleString() }) : t('jamais utilisé')}
            </span>
            <Button variant="ghost" className="text-red-600" onClick={() => {
              if (window.confirm(t('Révoquer le jeton « {name} » ?', { name: tk.name }))) act.mutate(() => api(`/me/tokens/${tk.id}`, { method: 'DELETE' }))
            }}>{t('Révoquer')}</Button>
          </li>
        ))}
      </ul>
      <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => {
        e.preventDefault()
        act.mutate(async () => {
          const r = await api<{ secret: string }>('/me/tokens', { body: { name: draft.name, scope: draft.scope, expiresAt: draft.expiresAt ? new Date(draft.expiresAt).toISOString() : null } })
          setSecret(r.secret)
          setDraft({ name: '', scope: 'READ', expiresAt: '' })
        })
      }}>
        <Input required aria-label={t('Nom du jeton')} placeholder={t('Nom du jeton')} className="w-44" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
        <select aria-label={t('Portée')} className={select} value={draft.scope} onChange={(e) => setDraft({ ...draft, scope: e.target.value })}>
          <option value="READ">{t('Lecture seule')}</option><option value="WRITE">{t('Lecture et écriture')}</option>
        </select>
        <label className="text-xs text-slate-500">{t('Expiration (facultative)')}
          <input type="date" className={`${select} block`} value={draft.expiresAt} onChange={(e) => setDraft({ ...draft, expiresAt: e.target.value })} />
        </label>
        <Button type="submit" variant="secondary">{t('Créer le jeton')}</Button>
      </form>
    </section>
  )
}

type Hook = { id: string; url: string; secret: string; events: string[]; spaceKey: string | null; active: boolean }
type Delivery = { id: string; event: string; statusCode: number | null; error: string | null; attempts: number; durationMs: number | null; at: string }

const EVENTS = ['ticket.created', 'ticket.updated', 'ticket.transitioned', 'ticket.deleted', 'comment.created', 'comment.updated', 'comment.deleted']

/** Signed webhooks and their delivery log (spec §8). */
export function WebhooksAdmin() {
  const qc = useQueryClient()
  const hooks = useQuery({ queryKey: ['admin', 'webhooks'], queryFn: () => api<Hook[]>('/admin/webhooks') })
  const spaces = useQuery({ queryKey: ['spaces', true], queryFn: () => api<SpaceSummary[]>('/spaces?includeArchived=true') }).data ?? []
  const act = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn(), onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'webhooks'] }) })
  const [draft, setDraft] = useState<{ url: string; events: string[]; spaceKey: string }>({ url: '', events: [], spaceKey: '' })
  const [open, setOpen] = useState<string | null>(null)
  return (
    <div className="mx-auto max-w-4xl space-y-6 p-6">
      <h1 className="text-xl font-semibold">{t('Webhooks')}</h1>
      <p className="text-sm text-slate-500">{t('Charge utile JSON signée HMAC-SHA256 dans l’en-tête X-Fluxo-Signature ; 3 tentatives.')}</p>
      <ErrorText error={hooks.error ?? act.error} />
      <ul className="space-y-3">
        {hooks.data?.map((h) => (
          <li key={h.id} className="space-y-1 rounded-md border border-slate-200 p-3 text-sm dark:border-slate-800">
            <div className="flex flex-wrap items-center gap-2">
              <code className="break-all font-mono">{h.url}</code>
              <span className="text-slate-500">{h.spaceKey ?? t('tous les espaces')} · {h.events.length ? h.events.join(', ') : t('tous les événements')}</span>
              <label className="ml-auto flex items-center gap-1">
                <input type="checkbox" checked={h.active} onChange={(e) => act.mutate(() => api(`/admin/webhooks/${h.id}`, { method: 'PATCH', body: { active: e.target.checked } }))} />
                {t('Actif')}
              </label>
              <Button variant="ghost" onClick={() => setOpen(open === h.id ? null : h.id)}>{t('Livraisons')}</Button>
              <Button variant="ghost" className="text-red-600" onClick={() => {
                if (window.confirm(t('Supprimer ce webhook ?'))) act.mutate(() => api(`/admin/webhooks/${h.id}`, { method: 'DELETE' }))
              }}>{t('Supprimer')}</Button>
            </div>
            <div className="text-xs text-slate-500">{t('Secret de signature :')} <code className="font-mono">{h.secret}</code></div>
            {open === h.id && <Deliveries id={h.id} />}
          </li>
        ))}
      </ul>
      <form className="space-y-2 rounded-md border border-slate-200 p-3 dark:border-slate-800" onSubmit={(e) => {
        e.preventDefault()
        act.mutate(() => api('/admin/webhooks', { body: { url: draft.url, events: draft.events, spaceKey: draft.spaceKey || null } }),
          { onSuccess: () => setDraft({ url: '', events: [], spaceKey: '' }) })
      }}>
        <div className="flex flex-wrap gap-2">
          <Input required type="url" aria-label={t('URL du webhook')} placeholder="https://…" className="w-96" value={draft.url} onChange={(e) => setDraft({ ...draft, url: e.target.value })} />
          <select aria-label={t('Espace')} className={select} value={draft.spaceKey} onChange={(e) => setDraft({ ...draft, spaceKey: e.target.value })}>
            <option value="">{t('Tous les espaces')}</option>
            {spaces.map((s) => <option key={s.key} value={s.key}>{s.key} · {s.name}</option>)}
          </select>
        </div>
        <div className="flex flex-wrap gap-3 text-sm">
          {EVENTS.map((ev) => (
            <label key={ev} className="flex items-center gap-1">
              <input type="checkbox" checked={draft.events.includes(ev)}
                onChange={() => setDraft({ ...draft, events: draft.events.includes(ev) ? draft.events.filter((x) => x !== ev) : [...draft.events, ev] })} />
              <code>{ev}</code>
            </label>
          ))}
        </div>
        <p className="text-xs text-slate-500">{t('Aucun événement coché = tous les événements.')}</p>
        <Button type="submit">{t('Créer le webhook')}</Button>
      </form>
    </div>
  )
}

function Deliveries({ id }: { id: string }) {
  const list = useQuery({ queryKey: ['admin', 'webhooks', id, 'deliveries'], queryFn: () => api<Delivery[]>(`/admin/webhooks/${id}/deliveries`) })
  if (!list.data?.length) return <p className="text-xs text-slate-500">{t('Aucune livraison.')}</p>
  return (
    <table className="w-full text-xs">
      <thead className="text-left text-slate-500"><tr><th>{t('Date')}</th><th>{t('Événement')}</th><th>{t('Résultat')}</th><th>{t('Tentatives')}</th><th>{t('Durée')}</th></tr></thead>
      <tbody>
        {list.data.map((d) => (
          <tr key={d.id} className={d.error ? 'text-red-700 dark:text-red-400' : ''}>
            <td>{new Date(d.at).toLocaleString()}</td><td>{d.event}</td><td>{d.error ?? d.statusCode}</td><td>{d.attempts}</td><td>{d.durationMs} ms</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

type Preview = { headers: string[]; rows: string[][]; total: number }
type Report = { dryRun: boolean; total: number; created: string[]; errors: { line: number; message: string }[] }

const IMPORT_FIELDS: Record<string, string> = {
  '': t('(ignorer)'), id: t('Identifiant de ligne'), summary: t('Résumé'), description: t('Description'), type: t('Type'), priority: t('Priorité'),
  assignee: t('Assigné (identifiant)'), reporter: t('Rapporteur (identifiant)'), labels: t('Labels'), components: t('Composants'),
  fixVersions: t('Versions'), dueDate: t('Échéance'), startDate: t('Date de début'), estimate: t('Estimation'), parent: t('Parent (clé ou identifiant de ligne)'),
  status: t('Statut'),
}

/** Best guess of the field of a CSV column from its header. */
export function guessField(header: string) {
  const h = header.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
  const table: [RegExp, string][] = [[/^(id|ref)/, 'id'], [/resume|summary|titre|title/, 'summary'], [/desc/, 'description'], [/type/, 'type'],
    [/priori/, 'priority'], [/assign/, 'assignee'], [/rapport|reporter/, 'reporter'], [/label|etiquette/, 'labels'], [/compos/, 'components'],
    [/version/, 'fixVersions'], [/echeance|due/, 'dueDate'], [/debut|start/, 'startDate'], [/estim/, 'estimate'], [/parent|epic/, 'parent'], [/statu/, 'status']]
  return table.find(([re]) => re.test(h))?.[1] ?? ''
}

/** CSV import into a space (spec §8): preview, column mapping, explicit estimate unit, simulation, line report. */
export function CsvImport({ spaceKey }: { spaceKey: string }) {
  const qc = useQueryClient()
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [mapping, setMapping] = useState<string[]>([])
  const [unit, setUnit] = useState('h')
  const [report, setReport] = useState<Report | null>(null)
  const act = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn() })
  const upload = <T,>(url: string, extra?: Record<string, string>) => {
    const form = new FormData()
    form.append('file', file!)
    Object.entries(extra ?? {}).forEach(([k, v]) => form.append(k, v))
    return api<T>(url, { body: form })
  }
  const run = (dryRun: boolean) => act.mutate(async () => {
    const options = { mapping: Object.fromEntries(mapping.map((f, i) => [i, f]).filter(([, f]) => f)), estimateUnit: unit, dryRun }
    setReport(await upload<Report>(`/spaces/${spaceKey}/import`, { options: JSON.stringify(options) }))
    if (!dryRun) { void qc.invalidateQueries({ queryKey: ['board'] }); void qc.invalidateQueries({ queryKey: ['search'] }) }
  })
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Import CSV')}</h2>
      <input type="file" accept=".csv,text/csv" aria-label={t('Fichier CSV')} onChange={(e) => {
        const f = e.target.files?.[0] ?? null
        setFile(f); setPreview(null); setReport(null)
        if (f) act.mutate(async () => {
          const form = new FormData()
          form.append('file', f)
          const p = await api<Preview>(`/spaces/${spaceKey}/import/preview`, { body: form })
          setPreview(p)
          setMapping(p.headers.map(guessField))
        })
      }} />
      <ErrorText error={act.error} />
      {preview && (
        <>
          <div className="overflow-x-auto">
            <table className="text-xs">
              <thead>
                <tr>{preview.headers.map((h, i) => (
                  <th key={i} className="px-2 py-1 text-left align-bottom">
                    <div className="font-medium">{h}</div>
                    <select aria-label={t('Champ de la colonne {column}', { column: h })} className={select} value={mapping[i] ?? ''}
                      onChange={(e) => setMapping(mapping.map((m, j) => (j === i ? e.target.value : m)))}>
                      {Object.entries(IMPORT_FIELDS).map(([k, label]) => <option key={k} value={k}>{label}</option>)}
                    </select>
                  </th>
                ))}</tr>
              </thead>
              <tbody>{preview.rows.map((r, i) => <tr key={i} className="border-t border-slate-100 dark:border-slate-900">{preview.headers.map((_, j) => <td key={j} className="px-2 py-0.5">{r[j]}</td>)}</tr>)}</tbody>
            </table>
          </div>
          <p className="text-sm text-slate-500">{t('{n} ligne(s) dans le fichier ; aperçu des 10 premières.', { n: preview.total })}</p>
          <label className="flex items-center gap-2 text-sm">{t('Unité des estimations sans unité')}
            <select className={select} value={unit} onChange={(e) => setUnit(e.target.value)}>
              <option value="h">{t('heures (h)')}</option><option value="m">{t('minutes (m)')}</option><option value="pts">{t('points (pts)')}</option>
            </select>
          </label>
          <div className="flex gap-2">
            <Button variant="secondary" disabled={act.isPending || !mapping.includes('summary')} onClick={() => run(true)}>{t('Simuler')}</Button>
            <Button disabled={act.isPending || !mapping.includes('summary')} onClick={() => run(false)}>{t('Importer')}</Button>
          </div>
        </>
      )}
      {report && (
        <div role="status" className="space-y-1 text-sm">
          <p className="font-medium">
            {report.dryRun
              ? t('Simulation : {ok} ligne(s) valide(s), {ko} en erreur.', { ok: report.total - report.errors.length, ko: report.errors.length })
              : t('{ok} ticket(s) créé(s), {ko} ligne(s) en erreur.', { ok: report.created.length, ko: report.errors.length })}
          </p>
          <ul className="max-h-48 overflow-y-auto text-red-700 dark:text-red-400">
            {report.errors.map((e) => <li key={e.line}>{t('Ligne {line} : {message}', { line: e.line, message: e.message })}</li>)}
          </ul>
        </div>
      )}
    </section>
  )
}
