import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useParams } from 'react-router'
import { api } from '../api'
import { PERM_LABELS, useSpace, type Perm } from '../spaces'
import type { StatusCategory } from '../spaces'
import { Button, ErrorText, Input } from '../ui'
import { t } from '../i18n'

type Rule = { type: string; field?: string; value?: string }
type Rules = {
  conditionMode: 'ALL' | 'ANY'; conditions: Rule[]; validators: Rule[]; actions: Rule[]
  screen: { resolution?: boolean; comment?: boolean; attachment?: boolean }
}
type TransitionDraft = { name: string; fromStatusIds: string[]; toStatusId: string; rules: Rules }
type StatusView = { id: string; name: string; category: StatusCategory; ticketCount: number }
type WorkflowView = {
  id: string; name: string; shared: boolean; editable: boolean; statuses: StatusView[]
  transitions: (TransitionDraft & { id: string })[]; usedBy: string[]
}
type SpaceWorkflows = { defaultWorkflowId: string; types: { typeId: string; typeName: string; workflowId: string }[]; workflows: WorkflowView[] }

const CATEGORIES: Record<StatusCategory, string> = { TODO: t('À faire'), IN_PROGRESS: t('En cours'), DONE: t('Terminé') }
const CONDITIONS: Record<string, string> = {
  ASSIGNEE_ONLY: t('Seulement l’assigné'), REPORTER_ONLY: t('Seulement le rapporteur'), PERMISSION: t('Permission'),
  ROLE: t('Membre du rôle'), GROUP: t('Membre du groupe'), SUBTASKS_DONE: t('Toutes les sous-tâches terminées'),
  NO_OPEN_BLOCKERS: t('Aucun bloquant ouvert'),
}
const VALIDATORS: Record<string, string> = { FIELD_REQUIRED: t('Champ obligatoire'), COMMENT_REQUIRED: t('Commentaire obligatoire') }
const ACTIONS: Record<string, string> = {
  ASSIGN_ME: t('Assigner à soi'), ASSIGN_REPORTER: t('Assigner au rapporteur'), UNASSIGN: t('Désassigner'),
  CLEAR_FIELD: t('Vider un champ'), SET_FIELD: t('Fixer un champ'), WEBHOOK: t('Envoyer un webhook'),
}
const FIELDS: Record<string, string> = {
  assigneeId: t('Assigné'), priorityId: t('Priorité'), dueDate: t('Échéance'), startDate: t('Date de début'),
  estimate: t('Estimation'), labels: t('Labels'), description: t('Description'), parent: t('Parent'), flagged: t('Signalé'),
}
const NO_RULES: Rules = { conditionMode: 'ALL', conditions: [], validators: [], actions: [], screen: {} }
const select = 'rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900'

/** Workflow editor of a space (spec §4.9): statuses, transitions and their rules, workflow per ticket type. */
export function WorkflowPage() {
  const { key } = useParams()
  const space = useSpace(key).data
  const qc = useQueryClient()
  const data = useQuery({ queryKey: ['space', key, 'workflows'], queryFn: () => api<SpaceWorkflows>(`/spaces/${key}/workflows`) })
  const [selected, setSelected] = useState<string | null>(null)
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['space', key] }); void qc.invalidateQueries({ queryKey: ['ticket'] }); void qc.invalidateQueries({ queryKey: ['board'] }) },
  })
  if (data.error) return <div className="p-6"><ErrorText error={data.error} /></div>
  if (!data.data || !space) return null
  const d = data.data
  const current = d.workflows.find((w) => w.id === (selected ?? d.defaultWorkflowId)) ?? d.workflows[0]
  return (
    <div className="mx-auto max-w-5xl space-y-8 p-6">
      <h1 className="text-xl font-semibold">{t('Workflows')}</h1>
      <section className="space-y-2">
        <h2 className="text-lg font-semibold">{t('Workflow par type de ticket')}</h2>
        <ErrorText error={act.error} />
        <table className="text-sm">
          <tbody>
            {d.types.map((ty) => {
              const own = ty.workflowId !== d.defaultWorkflowId
              return (
                <tr key={ty.typeId}>
                  <td className="py-1 pr-4">{ty.typeName}</td>
                  <td>
                    <select aria-label={t('Workflow du type {type}', { type: ty.typeName })} className={select} value={own ? 'own' : 'space'}
                      onChange={(e) => {
                        const toOwn = e.target.value === 'own'
                        if (!toOwn && !window.confirm(t('Revenir au workflow de l’espace ? Le workflow propre de « {type} » sera supprimé.', { type: ty.typeName }))) return
                        act.mutate(() => api(`/spaces/${key}/workflows/types/${ty.typeId}`, { method: toOwn ? 'POST' : 'DELETE' }))
                      }}>
                      <option value="space">{t('Workflow de l’espace')}</option>
                      <option value="own">{t('Workflow propre (copie)')}</option>
                    </select>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </section>
      {d.workflows.length > 1 && (
        <div role="tablist" className="flex flex-wrap gap-2">
          {d.workflows.map((w) => (
            <button key={w.id} role="tab" aria-selected={w.id === current.id} onClick={() => setSelected(w.id)}
              className={`rounded-md border px-3 py-1 text-sm ${w.id === current.id ? 'border-indigo-600 bg-indigo-600 text-white' : 'border-slate-300 dark:border-slate-700'}`}>
              {w.name}
            </button>
          ))}
        </div>
      )}
      <WorkflowEditor key={current.id + JSON.stringify(current.transitions) + current.statuses.length} workflow={current} spaceKey={key!} />
    </div>
  )
}

function WorkflowEditor({ workflow: w, spaceKey }: { workflow: WorkflowView; spaceKey: string }) {
  const qc = useQueryClient()
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['space', spaceKey] }); void qc.invalidateQueries({ queryKey: ['ticket'] }) },
  })
  const [drafts, setDrafts] = useState<TransitionDraft[]>(w.transitions.map(({ name, fromStatusIds, toStatusId, rules }) => ({ name, fromStatusIds, toStatusId, rules: { ...NO_RULES, ...rules } })))
  const [newStatus, setNewStatus] = useState({ name: '', category: 'IN_PROGRESS' as StatusCategory })
  const [migrating, setMigrating] = useState<{ status: StatusView; to: string } | null>(null)
  const ro = !w.editable
  const update = (i: number, patch: Partial<TransitionDraft>) => setDrafts(drafts.map((x, j) => (j === i ? { ...x, ...patch } : x)))
  return (
    <div className="space-y-8">
      <p className="text-sm text-slate-500">
        {t('Utilisé par : {spaces}', { spaces: w.usedBy.join(', ') })}
        {w.shared && ` · ${t('Workflow partagé : seul l’administrateur le modifie.')}`}
      </p>
      <ErrorText error={act.error} />
      <section className="space-y-2">
        <h2 className="text-lg font-semibold">{t('Statuts')}</h2>
        <ul className="space-y-1">
          {w.statuses.map((s) => (
            <li key={s.id} className="flex flex-wrap items-center gap-2 text-sm">
              <Input aria-label={t('Nom du statut')} defaultValue={s.name} disabled={ro} className="w-48"
                onBlur={(e) => { if (e.target.value.trim() && e.target.value !== s.name) act.mutate(() => api(`/workflows/${w.id}/statuses/${s.id}`, { method: 'PATCH', body: { name: e.target.value } })) }} />
              <select aria-label={t('Catégorie de {status}', { status: s.name })} className={select} value={s.category} disabled={ro}
                onChange={(e) => act.mutate(() => api(`/workflows/${w.id}/statuses/${s.id}`, { method: 'PATCH', body: { category: e.target.value } }))}>
                {Object.entries(CATEGORIES).map(([c, label]) => <option key={c} value={c}>{label}</option>)}
              </select>
              <span className="text-slate-500">{t('{n} ticket(s)', { n: s.ticketCount })}</span>
              {!ro && (
                <Button variant="ghost" aria-label={t('Supprimer le statut {status}', { status: s.name })} onClick={() => {
                  if (s.ticketCount > 0) return setMigrating({ status: s, to: w.statuses.find((o) => o.id !== s.id)!.id })
                  if (window.confirm(t('Supprimer le statut « {status} » ?', { status: s.name }))) act.mutate(() => api(`/workflows/${w.id}/statuses/${s.id}`, { method: 'DELETE' }))
                }}>✕</Button>
              )}
              {migrating?.status.id === s.id && (
                <span className="flex items-center gap-2">
                  {t('Déplacer ses tickets vers')}
                  <select aria-label={t('Statut de remplacement')} className={select} value={migrating.to} onChange={(e) => setMigrating({ ...migrating, to: e.target.value })}>
                    {w.statuses.filter((o) => o.id !== s.id).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                  </select>
                  <Button variant="danger" onClick={() => act.mutate(() => api(`/workflows/${w.id}/statuses/${s.id}?migrateTo=${migrating.to}`, { method: 'DELETE' }), { onSuccess: () => setMigrating(null) })}>
                    {t('Supprimer et déplacer')}
                  </Button>
                  <Button variant="secondary" onClick={() => setMigrating(null)}>{t('Annuler')}</Button>
                </span>
              )}
            </li>
          ))}
        </ul>
        {!ro && (
          <form className="flex items-center gap-2" onSubmit={(e) => {
            e.preventDefault()
            act.mutate(() => api(`/workflows/${w.id}/statuses`, { body: newStatus }), { onSuccess: () => setNewStatus({ ...newStatus, name: '' }) })
          }}>
            <Input required aria-label={t('Nouveau statut')} placeholder={t('Nouveau statut')} value={newStatus.name} className="w-48"
              onChange={(e) => setNewStatus({ ...newStatus, name: e.target.value })} />
            <select aria-label={t('Catégorie')} className={select} value={newStatus.category} onChange={(e) => setNewStatus({ ...newStatus, category: e.target.value as StatusCategory })}>
              {Object.entries(CATEGORIES).map(([c, label]) => <option key={c} value={c}>{label}</option>)}
            </select>
            <Button type="submit" variant="secondary">{t('Ajouter')}</Button>
          </form>
        )}
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">{t('Transitions')}</h2>
        {drafts.length === 0 && (
          <p className="text-sm text-slate-500">{t('Workflow simplifié : tout statut peut passer à tout autre statut.')}</p>
        )}
        {drafts.map((d, i) => (
          <fieldset key={i} disabled={ro} className="space-y-3 rounded-md border border-slate-200 p-3 dark:border-slate-800">
            <legend className="px-1 text-sm font-medium">{d.name || t('Nouvelle transition')}</legend>
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <Input aria-label={t('Nom de la transition')} value={d.name} className="w-48" onChange={(e) => update(i, { name: e.target.value })} />
              <span>{t('de')}</span>
              <label className="flex items-center gap-1">
                <input type="checkbox" checked={d.fromStatusIds.length === 0} onChange={(e) => update(i, { fromStatusIds: e.target.checked ? [] : [w.statuses[0].id] })} />
                {t('tous les statuts')}
              </label>
              {d.fromStatusIds.length > 0 && w.statuses.map((s) => (
                <label key={s.id} className="flex items-center gap-1">
                  <input type="checkbox" checked={d.fromStatusIds.includes(s.id)}
                    onChange={() => update(i, { fromStatusIds: d.fromStatusIds.includes(s.id) ? d.fromStatusIds.filter((x) => x !== s.id) : [...d.fromStatusIds, s.id] })} />
                  {s.name}
                </label>
              ))}
              <span>{t('vers')}</span>
              <select aria-label={t('Statut cible')} className={select} value={d.toStatusId} onChange={(e) => update(i, { toStatusId: e.target.value })}>
                {w.statuses.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
              <Button type="button" variant="ghost" aria-label={t('Supprimer la transition {name}', { name: d.name })} className="ml-auto"
                onClick={() => setDrafts(drafts.filter((_, j) => j !== i))}>✕</Button>
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <div className="space-y-1">
                <div className="flex items-center gap-2 text-sm font-medium">
                  {t('Conditions')}
                  <select aria-label={t('Combinaison des conditions')} className={select} value={d.rules.conditionMode}
                    onChange={(e) => update(i, { rules: { ...d.rules, conditionMode: e.target.value as 'ALL' | 'ANY' } })}>
                    <option value="ALL">{t('toutes')}</option><option value="ANY">{t('au moins une')}</option>
                  </select>
                </div>
                <RuleList kinds={CONDITIONS} rules={d.rules.conditions} spaceKey={spaceKey} onChange={(conditions) => update(i, { rules: { ...d.rules, conditions } })} />
              </div>
              <div className="space-y-1">
                <div className="text-sm font-medium">{t('Validateurs')}</div>
                <RuleList kinds={VALIDATORS} rules={d.rules.validators} spaceKey={spaceKey} onChange={(validators) => update(i, { rules: { ...d.rules, validators } })} />
              </div>
              <div className="space-y-1">
                <div className="text-sm font-medium">{t('Actions après transition')}</div>
                <RuleList kinds={ACTIONS} rules={d.rules.actions} spaceKey={spaceKey} onChange={(actions) => update(i, { rules: { ...d.rules, actions } })} />
              </div>
            </div>
            <div className="flex flex-wrap gap-4 text-sm">
              <span className="font-medium">{t('Écran de transition')}</span>
              {(['resolution', 'comment', 'attachment'] as const).map((f) => (
                <label key={f} className="flex items-center gap-1">
                  <input type="checkbox" checked={!!d.rules.screen[f]} onChange={(e) => update(i, { rules: { ...d.rules, screen: { ...d.rules.screen, [f]: e.target.checked } } })} />
                  {f === 'resolution' ? t('Résolution') : f === 'comment' ? t('Commentaire') : t('Pièces jointes')}
                </label>
              ))}
            </div>
          </fieldset>
        ))}
        {!ro && (
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={() => setDrafts(drafts.length === 0
              // Start from the simplified behaviour: one global transition per status.
              ? w.statuses.map((s) => ({ name: s.name, fromStatusIds: [], toStatusId: s.id, rules: NO_RULES }))
              : [...drafts, { name: '', fromStatusIds: [], toStatusId: w.statuses[0].id, rules: NO_RULES }])}>
              {drafts.length === 0 ? t('Définir des transitions') : t('+ Ajouter une transition')}
            </Button>
            <Button onClick={() => act.mutate(() => api(`/workflows/${w.id}/transitions`, { method: 'PUT', body: drafts }))}>{t('Enregistrer les transitions')}</Button>
            {drafts.length > 0 && (
              <Button variant="ghost" onClick={() => {
                if (window.confirm(t('Revenir au workflow simplifié ? Toutes les transitions seront supprimées.'))) {
                  setDrafts([])
                  act.mutate(() => api(`/workflows/${w.id}/transitions`, { method: 'PUT', body: [] }))
                }
              }}>{t('Revenir au workflow simplifié')}</Button>
            )}
          </div>
        )}
      </section>
    </div>
  )
}

function RuleList({ kinds, rules, onChange, spaceKey }: {
  kinds: Record<string, string>; rules: Rule[]; onChange: (r: Rule[]) => void; spaceKey: string
}) {
  const roles = useQuery({ queryKey: ['space', spaceKey, 'roles'], queryFn: () => api<{ id: string; name: string }[]>(`/spaces/${spaceKey}/roles`) })
  const groups = useQuery({ queryKey: ['directory', 'groups'], queryFn: () => api<{ id: string; name: string }[]>('/directory/groups') })
  const set = (i: number, patch: Partial<Rule>) => onChange(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  return (
    <ul className="space-y-1 text-sm">
      {rules.map((r, i) => (
        <li key={i} className="flex flex-wrap items-center gap-1">
          <span>{kinds[r.type]}</span>
          {r.type === 'PERMISSION' && (
            <select aria-label={t('Permission')} className={select} value={r.field ?? ''} onChange={(e) => set(i, { field: e.target.value })}>
              {Object.entries(PERM_LABELS).map(([p, label]) => <option key={p} value={p}>{label}</option>)}
            </select>
          )}
          {r.type === 'ROLE' && (
            <select aria-label={t('Rôle')} className={select} value={r.value ?? ''} onChange={(e) => set(i, { value: e.target.value })}>
              <option value="" />{roles.data?.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          )}
          {r.type === 'GROUP' && (
            <select aria-label={t('Groupe')} className={select} value={r.value ?? ''} onChange={(e) => set(i, { value: e.target.value })}>
              <option value="" />{groups.data?.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          )}
          {['FIELD_REQUIRED', 'CLEAR_FIELD', 'SET_FIELD'].includes(r.type) && (
            <select aria-label={t('Champ')} className={select} value={r.field ?? ''} onChange={(e) => set(i, { field: e.target.value })}>
              {Object.entries(FIELDS).map(([f, label]) => <option key={f} value={f}>{label}</option>)}
            </select>
          )}
          {r.type === 'SET_FIELD' && (
            <Input aria-label={t('Valeur (JSON ou texte)')} placeholder={t('Valeur')} className="w-32" value={r.value ?? ''} onChange={(e) => set(i, { value: e.target.value })} />
          )}
          {r.type === 'WEBHOOK' && (
            <Input aria-label={t('URL du webhook')} placeholder="https://…" className="w-48" value={r.value ?? ''} onChange={(e) => set(i, { value: e.target.value })} />
          )}
          <button type="button" aria-label={t('Retirer la règle')} className="text-slate-500 hover:text-red-600" onClick={() => onChange(rules.filter((_, j) => j !== i))}>✕</button>
        </li>
      ))}
      <li>
        <select aria-label={t('Ajouter une règle')} className={select} value="" onChange={(e) => {
          const type = e.target.value
          if (!type) return
          const field = type === 'PERMISSION' ? 'EDIT' as Perm : ['FIELD_REQUIRED', 'CLEAR_FIELD', 'SET_FIELD'].includes(type) ? 'assigneeId' : undefined
          onChange([...rules, { type, field }])
        }}>
          <option value="">{t('+ Ajouter…')}</option>
          {Object.entries(kinds).map(([k, label]) => <option key={k} value={k}>{label}</option>)}
        </select>
      </li>
    </ul>
  )
}
