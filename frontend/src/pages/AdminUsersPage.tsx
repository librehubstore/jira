import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api'
import { useMe } from '../auth'
import { Button, ErrorText, Field, Input } from '../ui'
import { t } from '../i18n'

type AdminUser = {
  id: string
  username: string
  displayName: string
  email: string | null
  admin: boolean
  active: boolean
  mustChangePassword: boolean
  locked: boolean
}

type GroupDto = { id: string; name: string; memberIds: string[] }

export function AdminUsersPage() {
  const qc = useQueryClient()
  const me = useMe().data
  const users = useQuery({ queryKey: ['admin', 'users'], queryFn: () => api<AdminUser[]>('/admin/users') })
  const refresh = () => qc.invalidateQueries({ queryKey: ['admin'] })
  const act = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn(), onSuccess: refresh })
  const [creating, setCreating] = useState(false)

  return (
    <div className="mx-auto max-w-5xl space-y-8 p-6">
      <section>
        <div className="mb-3 flex items-center justify-between">
          <h1 className="text-xl font-semibold">{t('Utilisateurs')}</h1>
          <Button onClick={() => setCreating(true)}>{t('Nouvel utilisateur')}</Button>
        </div>
        {creating && <CreateUserForm onDone={() => { setCreating(false); void refresh() }} />}
        <ErrorText error={users.error ?? act.error} />
        <table className="w-full text-left text-sm">
          <thead className="border-b border-slate-200 text-slate-500 dark:border-slate-800">
            <tr><th className="py-2">{t('Nom')}</th><th>{t('Identifiant')}</th><th>{t('Statut')}</th><th className="text-right">{t('Actions')}</th></tr>
          </thead>
          <tbody>
            {users.data?.map((u) => (
              <tr key={u.id} className="border-b border-slate-100 dark:border-slate-900">
                <td className="py-2">
                  {u.displayName} {u.admin && <span className="ml-1 rounded bg-indigo-100 px-1.5 text-xs text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">{t('admin')}</span>}
                </td>
                <td className="text-slate-500">{u.username}</td>
                <td>
                  {!u.active ? t('Désactivé') : u.locked ? t('Verrouillé') : u.mustChangePassword ? t('Mot de passe temporaire') : t('Actif')}
                </td>
                <td className="space-x-1 text-right">
                  {u.locked && <Button variant="ghost" onClick={() => act.mutate(() => api(`/admin/users/${u.id}/unlock`, { body: {} }))}>{t('Déverrouiller')}</Button>}
                  <Button variant="ghost" onClick={() => {
                    const pw = window.prompt(t('Mot de passe temporaire pour {name} (10 caractères min.)', { name: u.displayName }))
                    if (pw) act.mutate(() => api(`/admin/users/${u.id}/reset-password`, { body: { temporaryPassword: pw } }))
                  }}>{t('Réinitialiser')}</Button>
                  {u.id !== me?.id && (
                    <>
                      <Button variant="ghost" onClick={() => act.mutate(() => api(`/admin/users/${u.id}`, { method: 'PATCH', body: { admin: !u.admin } }))}>
                        {u.admin ? t('Retirer admin') : t('Rendre admin')}
                      </Button>
                      <Button variant="ghost" onClick={() => act.mutate(() => api(`/admin/users/${u.id}`, { method: 'PATCH', body: { active: !u.active } }))}>
                        {u.active ? t('Désactiver') : t('Réactiver')}
                      </Button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <GroupsSection users={users.data ?? []} />
    </div>
  )
}

function CreateUserForm({ onDone }: { onDone: () => void }) {
  const [form, setForm] = useState({ username: '', displayName: '', email: '', temporaryPassword: '', admin: false })
  const create = useMutation({
    mutationFn: () => api('/admin/users', { body: { ...form, email: form.email || null } }),
    onSuccess: onDone,
  })
  return (
    <form onSubmit={(e) => { e.preventDefault(); create.mutate() }}
      className="mb-4 grid grid-cols-2 gap-3 rounded-lg border border-slate-200 p-4 dark:border-slate-800">
      <Field label={t('Identifiant')}><Input autoFocus value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} /></Field>
      <Field label={t('Nom affiché')}><Input value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} /></Field>
      <Field label={t('Email (facultatif)')}><Input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></Field>
      <Field label={t('Mot de passe temporaire')} hint={t('À changer à la première connexion')}>
        <Input value={form.temporaryPassword} onChange={(e) => setForm({ ...form, temporaryPassword: e.target.value })} />
      </Field>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={form.admin} onChange={(e) => setForm({ ...form, admin: e.target.checked })} /> {t('Administrateur')}
      </label>
      <div className="col-span-2 flex items-center justify-end gap-2">
        <ErrorText error={create.error} />
        <Button type="button" variant="secondary" onClick={onDone}>{t('Annuler')}</Button>
        <Button type="submit" disabled={create.isPending}>{t('Créer')}</Button>
      </div>
    </form>
  )
}

function GroupsSection({ users }: { users: AdminUser[] }) {
  const qc = useQueryClient()
  const groups = useQuery({ queryKey: ['admin', 'groups'], queryFn: () => api<GroupDto[]>('/admin/groups') })
  const [name, setName] = useState('')
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'groups'] }),
  })
  return (
    <section>
      <h2 className="mb-3 text-xl font-semibold">{t('Groupes')}</h2>
      <form className="mb-3 flex gap-2" onSubmit={(e) => {
        e.preventDefault()
        act.mutate(() => api('/admin/groups', { body: { name } }))
        setName('')
      }}>
        <Input placeholder={t('Nom du groupe')} value={name} onChange={(e) => setName(e.target.value)} className="max-w-xs" />
        <Button type="submit" disabled={!name.trim()}>{t('Ajouter')}</Button>
      </form>
      <ErrorText error={act.error} />
      <ul className="space-y-3">
        {groups.data?.map((g) => (
          <li key={g.id} className="rounded-lg border border-slate-200 p-3 dark:border-slate-800">
            <div className="mb-2 flex items-center justify-between">
              <span className="font-medium">{g.name}</span>
              <Button variant="ghost" onClick={() => {
                if (window.confirm(t('Supprimer le groupe {name} ?', { name: g.name }))) act.mutate(() => api(`/admin/groups/${g.id}`, { method: 'DELETE' }))
              }}>{t('Supprimer')}</Button>
            </div>
            <div className="flex flex-wrap gap-2">
              {users.filter((u) => u.active).map((u) => {
                const member = g.memberIds.includes(u.id)
                return (
                  <label key={u.id} className="flex items-center gap-1 text-sm">
                    <input type="checkbox" checked={member} onChange={() => {
                      const ids = member ? g.memberIds.filter((id) => id !== u.id) : [...g.memberIds, u.id]
                      act.mutate(() => api(`/admin/groups/${g.id}/members`, { method: 'PUT', body: ids }))
                    }} />
                    {u.displayName}
                  </label>
                )
              })}
            </div>
          </li>
        ))}
      </ul>
    </section>
  )
}
