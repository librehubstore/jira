import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router'
import { api } from '../api'
import { useMe } from '../auth'
import { can, PERM_LABELS, useDirectory, useSpace, type Perm, type SpaceDetail, type UserRef } from '../spaces'
import { Button, ErrorText, Field, Input } from '../ui'
import { useMeta } from '../tickets'
import { useCustomFields } from './AdminPages'
import { CsvImport } from './IntegrationPages'
import { SYSTEM_FIELDS } from '../components/CustomFields'
import { t } from '../i18n'

type Role = { id: string; name: string; permissions: Perm[] }
type Member = { id: string; user: UserRef | null; groupId: string | null; groupName: string | null; roleId: string; roleName: string }

export function SpaceSettingsPage() {
  const { key } = useParams()
  const space = useSpace(key)
  if (!space.data) return <div className="p-6"><ErrorText error={space.error} /></div>
  const s = space.data
  return (
    <div className="mx-auto max-w-4xl space-y-10 p-6">
      <h1 className="text-xl font-semibold">{t('Paramètres · {name}', { name: s.name })}</h1>
      {can(s, 'ADMINISTER_SPACE') ? (
        <>
          <GeneralSection space={s} />
          {!s.archived && <MembersSection space={s} />}
          {!s.archived && <RolesSection space={s} />}
          {!s.archived && <CommentsSection space={s} />}
          {!s.archived && <TypesSection space={s} />}
          {!s.archived && <FieldsSection space={s} />}
          {!s.archived && <TemplatesSection space={s} />}
          {!s.archived && <CsvImport spaceKey={s.key} />}
        </>
      ) : (
        <p className="text-slate-500">{t('Seuls les administrateurs de l’espace peuvent modifier ses paramètres.')}</p>
      )}
    </div>
  )
}

function GeneralSection({ space }: { space: SpaceDetail }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const me = useMe().data!
  const [form, setForm] = useState({ name: space.name, key: space.key, description: space.description ?? '' })
  const save = useMutation({
    mutationFn: (body: Record<string, unknown>) => api<SpaceDetail>(`/spaces/${space.key}`, { method: 'PATCH', body }),
    onSuccess: (s) => {
      void qc.invalidateQueries({ queryKey: ['spaces'] })
      qc.setQueryData(['space', s.key], s)
      if (s.key !== space.key) void navigate(`/spaces/${s.key}/settings`, { replace: true })
      else void qc.invalidateQueries({ queryKey: ['space', space.key] })
    },
  })
  const remove = useMutation({
    mutationFn: (confirm: string) => api(`/spaces/${space.key}?confirm=${encodeURIComponent(confirm)}`, { method: 'DELETE' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['spaces'] })
      void navigate('/', { replace: true })
    },
  })
  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">{t('Général')}</h2>
      {space.archived ? (
        <p className="rounded-md bg-amber-50 p-3 text-sm text-amber-800 dark:bg-amber-950 dark:text-amber-200">
          {t('Cet espace est archivé : il est en lecture seule.')}
        </p>
      ) : (
        <form className="grid gap-3 sm:grid-cols-2" onSubmit={(e) => {
          e.preventDefault()
          save.mutate({ name: form.name, description: form.description, key: form.key !== space.key ? form.key : undefined })
        }}>
          <Field label={t('Nom')}><Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
          <Field label={t('Clé')} hint={t('Les anciennes clés restent redirigées')}>
            <Input value={form.key} className="font-mono" onChange={(e) => setForm({ ...form, key: e.target.value.toUpperCase() })} />
          </Field>
          <div className="sm:col-span-2">
            <Field label={t('Description')}><Input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></Field>
          </div>
          <div className="sm:col-span-2 flex justify-end"><Button type="submit" disabled={save.isPending}>{t('Enregistrer')}</Button></div>
        </form>
      )}
      <ErrorText error={save.error ?? remove.error} />
      <div className="flex flex-wrap gap-2 border-t border-slate-200 pt-4 dark:border-slate-800">
        <Button variant="secondary" onClick={() => save.mutate({ archived: !space.archived })}>
          {space.archived ? t('Désarchiver') : t('Archiver')}
        </Button>
        {me.admin && (
          <Button variant="danger" onClick={() => {
            const confirm = window.prompt(t('Suppression définitive de l’espace et de tous ses tickets.\nSaisissez la clé {key} pour confirmer :', { key: space.key }))
            if (confirm) remove.mutate(confirm)
          }}>{t('Supprimer l’espace')}</Button>
        )}
      </div>
    </section>
  )
}

function MembersSection({ space }: { space: SpaceDetail }) {
  const qc = useQueryClient()
  const members = useQuery({ queryKey: ['space', space.key, 'members'], queryFn: () => api<Member[]>(`/spaces/${space.key}/members`) })
  const roles = useQuery({ queryKey: ['space', space.key, 'roles'], queryFn: () => api<Role[]>(`/spaces/${space.key}/roles`) })
  const users = useDirectory()
  const groups = useQuery({ queryKey: ['directory', 'groups'], queryFn: () => api<{ id: string; name: string }[]>('/directory/groups') })
  const [principal, setPrincipal] = useState('')
  const [roleId, setRoleId] = useState('')
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['space', space.key] }),
  })
  const memberRole = roles.data?.find((r) => r.name === 'Membre')?.id ?? ''
  const takenUsers = new Set(members.data?.map((m) => m.user?.id))
  const takenGroups = new Set(members.data?.map((m) => m.groupId))
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Membres')}</h2>
      <form className="flex flex-wrap gap-2" onSubmit={(e) => {
        e.preventDefault()
        const [kind, id] = principal.split(':')
        act.mutate(() => api(`/spaces/${space.key}/members`, {
          body: { [kind === 'u' ? 'userId' : 'groupId']: id, roleId: roleId || memberRole },
        }))
        setPrincipal('')
      }}>
        <select value={principal} onChange={(e) => setPrincipal(e.target.value)}
          className="rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900">
          <option value="">{t('Ajouter un utilisateur ou un groupe…')}</option>
          <optgroup label={t('Utilisateurs')}>
            {users.data?.filter((u) => !takenUsers.has(u.id)).map((u) => <option key={u.id} value={`u:${u.id}`}>{u.displayName}</option>)}
          </optgroup>
          <optgroup label={t('Groupes')}>
            {groups.data?.filter((g) => !takenGroups.has(g.id)).map((g) => <option key={g.id} value={`g:${g.id}`}>{g.name}</option>)}
          </optgroup>
        </select>
        <select value={roleId || memberRole} onChange={(e) => setRoleId(e.target.value)}
          className="rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900">
          {roles.data?.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
        </select>
        <Button type="submit" disabled={!principal || !(roleId || memberRole)}>{t('Ajouter')}</Button>
      </form>
      <ErrorText error={act.error} />
      <ul className="divide-y divide-slate-100 dark:divide-slate-900">
        {members.data?.map((m) => (
          <li key={m.id} className="flex items-center gap-3 py-2 text-sm">
            <span className="flex-1">{m.user ? m.user.displayName : <>{t('Groupe')} <strong>{m.groupName}</strong></>}</span>
            <select value={m.roleId} aria-label={t('Rôle')}
              onChange={(e) => { const roleId = e.target.value; act.mutate(() => api(`/spaces/${space.key}/members/${m.id}`, { method: 'PATCH', body: { roleId } })) }}
              className="rounded-md border border-slate-300 bg-white px-2 py-1 dark:border-slate-700 dark:bg-slate-900">
              {roles.data?.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
            </select>
            <Button variant="ghost" onClick={() => act.mutate(() => api(`/spaces/${space.key}/members/${m.id}`, { method: 'DELETE' }))}>{t('Retirer')}</Button>
          </li>
        ))}
      </ul>
    </section>
  )
}

function RolesSection({ space }: { space: SpaceDetail }) {
  const qc = useQueryClient()
  const roles = useQuery({ queryKey: ['space', space.key, 'roles'], queryFn: () => api<Role[]>(`/spaces/${space.key}/roles`) })
  const update = useMutation({
    mutationFn: ({ role, perms }: { role: Role; perms: Perm[] }) => api(`/spaces/${space.key}/roles/${role.id}`, { method: 'PUT', body: perms }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['space', space.key] }),
  })
  const all = Object.keys(PERM_LABELS) as Perm[]
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Rôles et permissions')}</h2>
      <ErrorText error={update.error} />
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-left text-slate-500 dark:border-slate-800">
              <th className="py-2">{t('Permission')}</th>
              {roles.data?.map((r) => <th key={r.id} className="px-3 text-center">{r.name}</th>)}
            </tr>
          </thead>
          <tbody>
            {all.map((p) => (
              <tr key={p} className="border-b border-slate-100 dark:border-slate-900">
                <td className="py-1.5">{PERM_LABELS[p]}</td>
                {roles.data?.map((r) => {
                  const has = r.permissions.includes(p)
                  return (
                    <td key={r.id} className="text-center">
                      <input type="checkbox" checked={has} aria-label={`${r.name} : ${PERM_LABELS[p]}`}
                        onChange={() => update.mutate({ role: r, perms: has ? r.permissions.filter((x) => x !== p) : [...r.permissions, p] })} />
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

type Template = { id: string; name: string; typeId: string | null; values: Record<string, unknown>; subtasks: string[] }
type TemplateDraft = { id?: string; name: string; typeId: string; priorityId: string; labels: string; description: string; subtasks: string }

const EMPTY_TEMPLATE: TemplateDraft = { name: '', typeId: '', priorityId: '', labels: '', description: '', subtasks: '' }

/** Ticket templates offered at creation (spec §4.3). */
function TemplatesSection({ space }: { space: SpaceDetail }) {
  const qc = useQueryClient()
  const meta = useMeta().data
  const list = useQuery({ queryKey: ['space', space.key, 'templates'], queryFn: () => api<Template[]>(`/spaces/${space.key}/templates`) })
  const [draft, setDraft] = useState<TemplateDraft | null>(null)
  const save = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['space', space.key, 'templates'] }); setDraft(null) },
  })
  const edit = (x: Template) => setDraft({
    id: x.id, name: x.name, typeId: x.typeId ?? '', priorityId: String(x.values.priorityId ?? ''),
    labels: ((x.values.labels as string[] | undefined) ?? []).join(' '), description: String(x.values.description ?? ''), subtasks: x.subtasks.join('\n'),
  })
  const body = (d: TemplateDraft) => ({
    name: d.name, typeId: d.typeId || null, subtasks: d.subtasks.split('\n'),
    values: {
      ...(d.priorityId ? { priorityId: d.priorityId } : {}), ...(d.description.trim() ? { description: d.description } : {}),
      ...(d.labels.trim() ? { labels: d.labels.split(/\s+/).filter(Boolean) } : {}),
    },
  })
  const select = 'w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900'
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Modèles de tickets')}</h2>
      <ul className="space-y-1 text-sm">
        {list.data?.map((x) => (
          <li key={x.id} className="flex items-center gap-2">
            <span className="font-medium">{x.name}</span>
            {x.subtasks.length > 0 && <span className="text-slate-500">{t('{n} sous-tâche(s)', { n: x.subtasks.length })}</span>}
            <Button variant="ghost" onClick={() => edit(x)}>{t('Modifier')}</Button>
            <Button variant="ghost" className="text-red-600" onClick={() => {
              if (window.confirm(t('Supprimer le modèle « {name} » ?', { name: x.name }))) save.mutate(() => api(`/templates/${x.id}`, { method: 'DELETE' }))
            }}>{t('Supprimer')}</Button>
          </li>
        ))}
        {list.data?.length === 0 && <li className="text-slate-500">{t('Aucun modèle.')}</li>}
      </ul>
      {draft ? (
        <form className="grid gap-3 rounded-md border border-slate-200 p-3 sm:grid-cols-2 dark:border-slate-800" onSubmit={(e) => {
          e.preventDefault()
          save.mutate(() => draft.id
            ? api(`/templates/${draft.id}`, { method: 'PUT', body: body(draft) })
            : api(`/spaces/${space.key}/templates`, { body: body(draft) }))
        }}>
          <Field label={t('Nom')}><Input required value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></Field>
          <Field label={t('Type')}>
            <select className={select} value={draft.typeId} onChange={(e) => setDraft({ ...draft, typeId: e.target.value })}>
              <option value="">{t('Par défaut')}</option>
              {space.ticketTypes.filter((x) => x.hierarchyLevel >= 0).map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </Field>
          <Field label={t('Priorité')}>
            <select className={select} value={draft.priorityId} onChange={(e) => setDraft({ ...draft, priorityId: e.target.value })}>
              <option value="">{t('Par défaut')}</option>
              {meta?.priorities.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </Field>
          <Field label={t('Labels (séparés par des espaces)')}><Input value={draft.labels} onChange={(e) => setDraft({ ...draft, labels: e.target.value })} /></Field>
          <div className="sm:col-span-2">
            <Field label={t('Description (markdown)')}>
              <textarea rows={4} className={select} value={draft.description} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
            </Field>
          </div>
          <div className="sm:col-span-2">
            <Field label={t('Sous-tâches (une par ligne)')}>
              <textarea rows={3} className={select} value={draft.subtasks} onChange={(e) => setDraft({ ...draft, subtasks: e.target.value })} />
            </Field>
          </div>
          <div className="flex gap-2 sm:col-span-2">
            <Button type="submit">{t('Enregistrer le modèle')}</Button>
            <Button type="button" variant="secondary" onClick={() => setDraft(null)}>{t('Annuler')}</Button>
          </div>
        </form>
      ) : (
        <Button variant="secondary" onClick={() => setDraft(EMPTY_TEMPLATE)}>{t('+ Nouveau modèle')}</Button>
      )}
      <ErrorText error={save.error} />
    </section>
  )
}

type FieldConfig = { ticketTypeId: string | null; field: string; visible: boolean; required: boolean; position: number; defaultValue: unknown }
type SpaceFields = { enabled: { fieldId: string; typeIds: string[] }[]; configs: FieldConfig[] }

/** Custom fields enabled in the space and field configuration per ticket type (spec §4.14). */
function FieldsSection({ space }: { space: SpaceDetail }) {
  const qc = useQueryClient()
  const defs = useCustomFields().data ?? []
  const data = useQuery({ queryKey: ['space', space.key, 'fields'], queryFn: () => api<SpaceFields>(`/spaces/${space.key}/fields`) })
  const save = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['space', space.key] }),
  })
  const [scope, setScope] = useState('')
  if (!data.data) return null
  const enabled = data.data.enabled
  const setEnabled = (list: SpaceFields['enabled']) => save.mutate(() => api(`/spaces/${space.key}/fields/enabled`, { method: 'PUT', body: list }))
  const types = space.ticketTypes
  const scopeId = scope || null
  const fields = [...Object.keys(SYSTEM_FIELDS), ...enabled.map((e) => `cf:${e.fieldId}`)]
  const conf = (f: string) => data.data!.configs.find((c) => c.field === f && c.ticketTypeId === scopeId)
  const rows = fields.map((f, i) => conf(f) ?? { ticketTypeId: scopeId, field: f, visible: true, required: false, position: 1000 + i, defaultValue: null })
    .sort((a, b) => a.position - b.position)
  const name = (f: string) => SYSTEM_FIELDS[f] ?? defs.find((d) => `cf:${d.id}` === f)?.name ?? f
  const write = (next: FieldConfig[]) => {
    const others = data.data!.configs.filter((c) => c.ticketTypeId !== scopeId)
    save.mutate(() => api(`/spaces/${space.key}/fields/configs`, { method: 'PUT', body: [...others, ...next.map((r, i) => ({ ...r, position: i }))] }))
  }
  const update = (i: number, patch: Partial<FieldConfig>) => write(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  const move = (i: number, d: number) => {
    const next = [...rows]
    ;[next[i], next[i + d]] = [next[i + d], next[i]]
    write(next)
  }
  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">{t('Champs')}</h2>
      <ErrorText error={save.error} />
      <div className="space-y-1 text-sm">
        <h3 className="font-medium">{t('Champs personnalisés actifs')}</h3>
        {defs.length === 0 && <p className="text-slate-500">{t('Aucun champ personnalisé défini par l’administrateur.')}</p>}
        {defs.map((d) => {
          const e = enabled.find((x) => x.fieldId === d.id)
          return (
            <div key={d.id} className="flex flex-wrap items-center gap-3">
              <label className="flex items-center gap-1.5 font-medium">
                <input type="checkbox" checked={!!e} onChange={() => setEnabled(e ? enabled.filter((x) => x.fieldId !== d.id) : [...enabled, { fieldId: d.id, typeIds: [] }])} />
                {d.name}
              </label>
              {e && types.map((ty) => (
                <label key={ty.id} className="flex items-center gap-1 text-slate-600 dark:text-slate-300">
                  <input type="checkbox" checked={e.typeIds.length === 0 || e.typeIds.includes(ty.id)} onChange={() => {
                    const current = e.typeIds.length === 0 ? types.map((x) => x.id) : e.typeIds
                    const next = current.includes(ty.id) ? current.filter((x) => x !== ty.id) : [...current, ty.id]
                    setEnabled(enabled.map((x) => (x.fieldId === d.id ? { ...x, typeIds: next.length === types.length ? [] : next } : x)))
                  }} /> {ty.name}
                </label>
              ))}
            </div>
          )
        })}
      </div>
      <div className="space-y-2">
        <div className="flex items-center gap-2 text-sm">
          <h3 className="font-medium">{t('Configuration des champs')}</h3>
          <select aria-label={t('Type de ticket')} value={scope} onChange={(e) => setScope(e.target.value)}
            className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900">
            <option value="">{t('Tous les types')}</option>
            {types.map((ty) => <option key={ty.id} value={ty.id}>{ty.name}</option>)}
          </select>
        </div>
        <table className="w-full text-sm">
          <thead className="text-left text-xs uppercase text-slate-500">
            <tr><th className="py-1">{t('Champ')}</th><th>{t('Visible')}</th><th>{t('Obligatoire à la création')}</th><th>{t('Ordre')}</th></tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r.field} className="border-t border-slate-100 dark:border-slate-900">
                <td className="py-1">{name(r.field)}</td>
                <td><input type="checkbox" aria-label={t('{field} visible', { field: name(r.field) })} checked={r.visible}
                  onChange={(e) => update(i, { visible: e.target.checked, required: e.target.checked && r.required })} /></td>
                <td><input type="checkbox" aria-label={t('{field} obligatoire', { field: name(r.field) })} checked={r.required}
                  onChange={(e) => update(i, { required: e.target.checked, visible: e.target.checked || r.visible })} /></td>
                <td className="space-x-1">
                  <button aria-label={t('Monter {field}', { field: name(r.field) })} disabled={i === 0} onClick={() => move(i, -1)} className="px-1 disabled:opacity-30">↑</button>
                  <button aria-label={t('Descendre {field}', { field: name(r.field) })} disabled={i === rows.length - 1} onClick={() => move(i, 1)} className="px-1 disabled:opacity-30">↓</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="text-xs text-slate-500">{t('Le résumé est toujours visible et obligatoire. La configuration d’un type remplace celle de « Tous les types ».')}</p>
      </div>
    </section>
  )
}

/** Ticket types offered in the space, among the global ones (V1.6.3). */
function TypesSection({ space }: { space: SpaceDetail }) {
  const qc = useQueryClient()
  const meta = useMeta().data
  const save = useMutation({
    mutationFn: (ids: string[]) => api(`/spaces/${space.key}/ticket-types`, { method: 'PUT', body: ids }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['space', space.key] }),
  })
  const current = space.ticketTypes.map((x) => x.id)
  return (
    <section className="space-y-2">
      <h2 className="text-lg font-semibold">{t('Types de tickets')}</h2>
      <div className="flex flex-wrap gap-4 text-sm">
        {meta?.types.map((ty) => (
          <label key={ty.id} className="flex items-center gap-1.5">
            <input type="checkbox" checked={current.includes(ty.id)}
              onChange={() => save.mutate(current.includes(ty.id) ? current.filter((x) => x !== ty.id) : [...current, ty.id])} />
            {ty.name}
          </label>
        ))}
      </div>
      <ErrorText error={save.error} />
    </section>
  )
}

/** Default visibility of new comments (spec §4.4). */
function CommentsSection({ space }: { space: SpaceDetail }) {
  const qc = useQueryClient()
  const roles = useQuery({ queryKey: ['space', space.key, 'roles'], queryFn: () => api<Role[]>(`/spaces/${space.key}/roles`) })
  const save = useMutation({
    mutationFn: (roleId: string) => api(`/spaces/${space.key}`, { method: 'PATCH', body: { commentVisibilityRoleId: roleId } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['space', space.key] }),
  })
  const unit = useMutation({
    mutationFn: (estimateUnit: string) => api(`/spaces/${space.key}`, { method: 'PATCH', body: { estimateUnit } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['space', space.key] }),
  })
  return (
    <section className="space-y-2">
      <h2 className="text-lg font-semibold">{t('Commentaires et estimations')}</h2>
      <label className="flex items-center gap-2 text-sm">
        {t('Visibilité par défaut des nouveaux commentaires')}
        <select value={space.commentVisibilityRoleId ?? ''} onChange={(e) => save.mutate(e.target.value)}
          className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900">
          <option value="">{t('Tous les membres')}</option>
          {roles.data?.map((r) => <option key={r.id} value={r.id}>{t('Rôle « {role} »', { role: r.name })}</option>)}
        </select>
      </label>
      <label className="flex items-center gap-2 text-sm">
        {t('Unité des estimations')}
        <select value={space.estimateUnit} onChange={(e) => unit.mutate(e.target.value)}
          className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900">
          <option value="POINTS">{t('Points')}</option><option value="HOURS">{t('Heures')}</option>
        </select>
      </label>
      <ErrorText error={save.error ?? unit.error} />
    </section>
  )
}
