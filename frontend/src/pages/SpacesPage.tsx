import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { api } from '../api'
import { useMe } from '../auth'
import type { SpaceDetail, SpaceSummary } from '../spaces'
import { Button, ErrorText, Field, Input } from '../ui'
import { t } from '../i18n'

export function SpacesPage() {
  const me = useMe().data!
  const [showArchived, setShowArchived] = useState(false)
  const [creating, setCreating] = useState(false)
  const spaces = useQuery({
    queryKey: ['spaces', showArchived],
    queryFn: () => api<SpaceSummary[]>(`/spaces?includeArchived=${showArchived}`),
  })
  return (
    <div className="mx-auto max-w-5xl p-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold">{t('Espaces')}</h1>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-1.5 text-sm text-slate-600 dark:text-slate-500">
            <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
            {t('Inclure les archives')}
          </label>
          {me.admin && <Button onClick={() => setCreating(true)}>{t('Nouvel espace')}</Button>}
        </div>
      </div>
      {creating && <CreateSpaceForm onCancel={() => setCreating(false)} />}
      <ErrorText error={spaces.error} />
      {spaces.data?.length === 0 && (
        <p className="text-slate-500">
          {me.admin ? t('Aucun espace. Créez le premier.') : t('Vous n’êtes membre d’aucun espace. Demandez à un administrateur de vous y ajouter.')}
        </p>
      )}
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {spaces.data?.map((s) => (
          <li key={s.id}>
            <Link to={`/spaces/${s.key}`}
              className="block rounded-lg border border-slate-200 p-4 hover:border-indigo-400 dark:border-slate-800">
              <div className="flex items-center gap-2">
                <span className="rounded bg-slate-100 px-1.5 font-mono text-xs dark:bg-slate-800">{s.key}</span>
                <span className="font-medium">{s.name}</span>
                {s.archived && <span className="ml-auto text-xs text-amber-600">{t('archivé')}</span>}
              </div>
              {s.description && <p className="mt-1 line-clamp-2 text-sm text-slate-500">{s.description}</p>}
              {s.lead && <p className="mt-2 text-xs text-slate-500">{t('Responsable : {name}', { name: s.lead.displayName })}</p>}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}

function CreateSpaceForm({ onCancel }: { onCancel: () => void }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [form, setForm] = useState({ name: '', key: '', description: '' })
  const [keyEdited, setKeyEdited] = useState(false)
  const suggestKey = (name: string) =>
    name.normalize('NFD').replace(/[^A-Za-z0-9 ]/g, '').split(/\s+/).filter(Boolean)
      .map((w) => w[0]).join('').toUpperCase().replace(/^[0-9]+/, '').slice(0, 4) || ''
  const create = useMutation({
    mutationFn: () => api<SpaceDetail>('/spaces', { body: { ...form, description: form.description || null } }),
    onSuccess: (s) => {
      void qc.invalidateQueries({ queryKey: ['spaces'] })
      void navigate(`/spaces/${s.key}`)
    },
  })
  return (
    <form onSubmit={(e) => { e.preventDefault(); create.mutate() }}
      className="mb-6 grid gap-3 rounded-lg border border-slate-200 p-4 sm:grid-cols-2 dark:border-slate-800">
      <Field label={t('Nom')}>
        <Input autoFocus value={form.name} onChange={(e) => {
          const name = e.target.value
          setForm({ ...form, name, key: keyEdited ? form.key : suggestKey(name).padEnd(2, 'X') })
        }} />
      </Field>
      <Field label={t('Clé')} hint={t('Préfixe des tickets, ex. PAY → PAY-1')}>
        <Input value={form.key} maxLength={10} className="font-mono uppercase"
          onChange={(e) => { setKeyEdited(true); setForm({ ...form, key: e.target.value.toUpperCase() }) }} />
      </Field>
      <Field label={t('Description (facultative)')}>
        <Input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
      </Field>
      <div className="flex items-end justify-end gap-2">
        <Button type="button" variant="secondary" onClick={onCancel}>{t('Annuler')}</Button>
        <Button type="submit" disabled={create.isPending || !form.name || !form.key}>{t('Créer l’espace')}</Button>
      </div>
      <div className="sm:col-span-2"><ErrorText error={create.error} /></div>
    </form>
  )
}
