import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { NavLink, Outlet } from 'react-router'
import { api } from '../api'
import { Button, ErrorText, Input } from '../ui'
import { t } from '../i18n'

/** Global administration tabs. */
export function AdminLayout() {
  const tab = ({ isActive }: { isActive: boolean }) =>
    `border-b-2 px-1 pb-2 text-sm ${isActive ? 'border-indigo-600 font-medium' : 'border-transparent text-slate-500 hover:text-slate-900 dark:hover:text-slate-100'}`
  return (
    <div>
      <nav className="flex gap-4 border-b border-slate-200 px-6 pt-4 dark:border-slate-800">
        <NavLink to="/admin/users" className={tab}>{t('Utilisateurs')}</NavLink>
        <NavLink to="/admin/fields" className={tab}>{t('Champs personnalisés')}</NavLink>
        <NavLink to="/admin/reference" className={tab}>{t('Référentiels')}</NavLink>
        <NavLink to="/admin/webhooks" className={tab}>{t('Webhooks')}</NavLink>
      </nav>
      <Outlet />
    </div>
  )
}

export type FieldType = 'TEXT' | 'TEXTAREA' | 'NUMBER' | 'DATE' | 'SELECT' | 'MULTISELECT' | 'USER' | 'USERS' | 'CHECKBOX' | 'URL'
export type CustomField = { id: string; name: string; type: FieldType; options: string[] }

export const FIELD_TYPES: Record<FieldType, string> = {
  TEXT: t('Texte court'), TEXTAREA: t('Texte long'), NUMBER: t('Nombre'), DATE: t('Date'), SELECT: t('Liste unique'),
  MULTISELECT: t('Liste multiple'), USER: t('Utilisateur'), USERS: t('Utilisateurs'), CHECKBOX: t('Case à cocher'), URL: t('URL'),
}

export function useCustomFields() {
  return useQuery({ queryKey: ['custom-fields'], queryFn: () => api<CustomField[]>('/custom-fields'), staleTime: 60_000 })
}

/** Custom field definitions (spec §4.14); spaces enable them in their settings. */
export function CustomFieldsAdmin() {
  const qc = useQueryClient()
  const fields = useCustomFields()
  const act = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn(), onSuccess: () => qc.invalidateQueries({ queryKey: ['custom-fields'] }) })
  const [draft, setDraft] = useState<{ name: string; type: FieldType; options: string }>({ name: '', type: 'TEXT', options: '' })
  const select = 'rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900'
  const isList = (ty: FieldType) => ty === 'SELECT' || ty === 'MULTISELECT'
  return (
    <div className="mx-auto max-w-4xl space-y-6 p-6">
      <h1 className="text-xl font-semibold">{t('Champs personnalisés')}</h1>
      <ErrorText error={fields.error ?? act.error} />
      <ul className="space-y-2">
        {fields.data?.map((f) => (
          <li key={f.id} className="flex flex-wrap items-center gap-2 text-sm">
            <Input aria-label={t('Nom du champ')} defaultValue={f.name} className="w-56"
              onBlur={(e) => { if (e.target.value.trim() && e.target.value !== f.name) act.mutate(() => api(`/admin/custom-fields/${f.id}`, { method: 'PATCH', body: { name: e.target.value } })) }} />
            <span className="text-slate-500">{FIELD_TYPES[f.type]}</span>
            {isList(f.type) && (
              <Input aria-label={t('Options de {name}', { name: f.name })} defaultValue={f.options.join(', ')} className="w-64"
                onBlur={(e) => act.mutate(() => api(`/admin/custom-fields/${f.id}`, { method: 'PATCH', body: { options: e.target.value.split(',') } }))} />
            )}
            <Button variant="ghost" className="text-red-600" onClick={() => {
              if (window.confirm(t('Supprimer le champ « {name} » et ses valeurs sur tous les tickets ?', { name: f.name }))) act.mutate(() => api(`/admin/custom-fields/${f.id}`, { method: 'DELETE' }))
            }}>{t('Supprimer')}</Button>
          </li>
        ))}
        {fields.data?.length === 0 && <li className="text-sm text-slate-500">{t('Aucun champ personnalisé.')}</li>}
      </ul>
      <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => {
        e.preventDefault()
        act.mutate(() => api('/admin/custom-fields', { body: { name: draft.name, type: draft.type, options: draft.options.split(',') } }),
          { onSuccess: () => setDraft({ name: '', type: 'TEXT', options: '' }) })
      }}>
        <Input required aria-label={t('Nouveau champ')} placeholder={t('Nouveau champ')} value={draft.name} className="w-56" onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
        <select aria-label={t('Type de champ')} className={select} value={draft.type} onChange={(e) => setDraft({ ...draft, type: e.target.value as FieldType })}>
          {Object.entries(FIELD_TYPES).map(([k, label]) => <option key={k} value={k}>{label}</option>)}
        </select>
        {isList(draft.type) && (
          <Input required aria-label={t('Options (séparées par des virgules)')} placeholder={t('Options (séparées par des virgules)')} value={draft.options} className="w-64"
            onChange={(e) => setDraft({ ...draft, options: e.target.value })} />
        )}
        <Button type="submit">{t('Créer le champ')}</Button>
      </form>
    </div>
  )
}
