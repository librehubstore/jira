import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api } from '../api'
import { localStorageGet, localStorageSet } from '../storage'
import { can, useSpace, type SpaceSummary } from '../spaces'
import type { TicketDetail } from '../tickets'
import { Button, ErrorText, Field, Input } from '../ui'
import { CustomFieldInput, fieldLabel, useLayout, type LayoutField } from './CustomFields'
import { useAssignable, useComponents, useMeta, useVersions } from '../tickets'
import { t } from '../i18n'

/** Quick create (shortcut C): only the summary is required (spec §4.3). */
export function CreateTicketDialog({ defaultSpaceKey, onClose }: { defaultSpaceKey?: string; onClose: () => void }) {
  const qc = useQueryClient()
  const spaces = useQuery({ queryKey: ['spaces', false], queryFn: () => api<SpaceSummary[]>('/spaces?includeArchived=false') })
  const creatable = spaces.data?.filter((s) => can(s, 'CREATE')) ?? []
  const [spaceKey, setSpaceKey] = useState(defaultSpaceKey ?? localStorageGet('fluxo.lastSpace') ?? '')
  const effectiveKey = creatable.some((s) => s.key === spaceKey) ? spaceKey : creatable[0]?.key
  const space = useSpace(effectiveKey)
  const [typeId, setTypeId] = useState('')
  const [templateId, setTemplateId] = useState('')
  // Fields the space requires at creation (spec §4.14); everything else keeps its default.
  const layout = useLayout(effectiveKey, typeId || undefined).data ?? []
  const required = layout.filter((l) => l.required)
  const [extra, setExtra] = useState<Record<string, unknown>>({})
  const body = () => {
    const custom: Record<string, unknown> = {}
    const system: Record<string, unknown> = {}
    for (const l of required) {
      const v = extra[l.field]
      if (v == null || v === '') continue
      if (l.field.startsWith('cf:')) custom[l.field.slice(3)] = v
      else system[l.field] = l.field === 'labels' ? String(v).split(/[\s,]+/).filter(Boolean)
        : ['componentIds', 'fixVersionIds'].includes(l.field) ? [v] : l.field === 'estimate' ? Number(String(v).replace(',', '.')) : v
    }
    return { summary, ...(typeId ? { typeId } : {}), ...system, ...(Object.keys(custom).length ? { customFields: custom } : {}) }
  }
  const templates = useQuery({
    queryKey: ['space', effectiveKey, 'templates'],
    queryFn: () => api<{ id: string; name: string; typeId: string | null }[]>(`/spaces/${effectiveKey}/templates`),
    enabled: !!effectiveKey,
  }).data ?? []
  const [summary, setSummary] = useState('')
  const [another, setAnother] = useState(false)
  const [created, setCreated] = useState<TicketDetail[]>([])
  const dialog = useRef<HTMLDialogElement>(null)
  const summaryInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    dialog.current?.showModal()
    summaryInput.current?.focus() // showModal() focuses the first control, React's autoFocus is ignored
  }, [])

  const standardTypes = space.data?.ticketTypes.filter((t) => t.hierarchyLevel >= 0) ?? []
  const create = useMutation({
    mutationFn: () => templateId && templates.some((x) => x.id === templateId)
      ? api<TicketDetail>(`/templates/${templateId}/tickets`, { body: body() })
      : api<TicketDetail>(`/spaces/${effectiveKey}/tickets`, { body: body() }),
    onSuccess: (t) => {
      localStorageSet('fluxo.lastSpace', t.spaceKey)
      void qc.invalidateQueries({ queryKey: ['board'] })
      void qc.invalidateQueries({ queryKey: ['search'] })
      setCreated((c) => [t, ...c].slice(0, 5))
      setSummary('')
      if (!another) onClose()
    },
  })

  return (
    <dialog ref={dialog} onClose={onClose} aria-labelledby="create-title"
      className="m-auto w-full max-w-lg rounded-xl border border-slate-200 bg-white p-0 text-slate-900 shadow-xl backdrop:bg-slate-900/40 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100">
      <form method="dialog" className="space-y-4 p-5" onSubmit={(e) => { e.preventDefault(); if (summary.trim()) create.mutate() }}>
        <h2 id="create-title" className="text-lg font-semibold">{t('Créer un ticket')}</h2>
        {creatable.length === 0 && spaces.isSuccess ? (
          <p className="text-sm text-slate-500">{t('Vous ne pouvez créer de ticket dans aucun espace.')}</p>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-3">
              <Field label={t('Espace')}>
                <select value={effectiveKey ?? ''} onChange={(e) => { setSpaceKey(e.target.value); setTypeId(''); setTemplateId('') }}
                  className="w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900">
                  {creatable.map((s) => <option key={s.key} value={s.key}>{s.name} ({s.key})</option>)}
                </select>
              </Field>
              <Field label={t('Type')}>
                <select value={typeId} onChange={(e) => setTypeId(e.target.value)}
                  className="w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900">
                  <option value="">{t('Par défaut')}</option>
                  {standardTypes.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                </select>
              </Field>
            </div>
            {templates.length > 0 && (
              <Field label={t('Modèle')}>
                <select value={templateId} onChange={(e) => setTemplateId(e.target.value)}
                  className="w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900">
                  <option value="">{t('Aucun')}</option>
                  {templates.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                </select>
              </Field>
            )}
            <Field label={t('Résumé')}>
              <Input ref={summaryInput} value={summary} maxLength={255} onChange={(e) => setSummary(e.target.value)}
                placeholder={t('Que faut-il faire ?')} />
            </Field>
            {required.map((l) => (
              <Field key={l.field} label={`${fieldLabel(l)} *`}>
                <RequiredInput field={l} spaceKey={effectiveKey!} value={extra[l.field]} onChange={(v) => setExtra({ ...extra, [l.field]: v })} />
              </Field>
            ))}
            <ErrorText error={create.error} />
            {created.length > 0 && (
              <ul className="text-sm text-green-700 dark:text-green-400">
                {created.map((t) => (
                  <li key={t.id}>✓ <Link to={`/browse/${t.key}`} onClick={onClose} className="underline">{t.key}</Link> {t.summary}</li>
                ))}
              </ul>
            )}
            <div className="flex items-center justify-between">
              <label className="flex items-center gap-1.5 text-sm">
                <input type="checkbox" checked={another} onChange={(e) => setAnother(e.target.checked)} /> {t('Créer un autre')}
              </label>
              <div className="flex gap-2">
                <Button type="button" variant="secondary" onClick={onClose}>{t('Annuler')}</Button>
                <Button type="submit" disabled={create.isPending || !summary.trim() || !effectiveKey}>{t('Créer')}</Button>
              </div>
            </div>
          </>
        )}
      </form>
    </dialog>
  )
}

/** Input for a field required at creation: system fields get a plain control, custom ones their editor. */
function RequiredInput({ field, spaceKey, value, onChange }: { field: LayoutField; spaceKey: string; value: unknown; onChange: (v: unknown) => void }) {
  const meta = useMeta().data
  const assignable = useAssignable(spaceKey).data ?? []
  const components = useComponents(spaceKey).data ?? []
  const versions = useVersions(spaceKey).data ?? []
  const cls = 'w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900'
  const v = value == null ? '' : String(value)
  const options = (list: { id: string; name: string }[]) => (
    <select required aria-label={fieldLabel(field)} className={cls} value={v} onChange={(e) => onChange(e.target.value)}>
      <option value="" />{list.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
    </select>
  )
  if (field.field.startsWith('cf:')) return <CustomFieldInput field={field} value={value} onSave={onChange} />
  switch (field.field) {
    case 'description': return <textarea required aria-label={fieldLabel(field)} rows={3} className={cls} value={v} onChange={(e) => onChange(e.target.value)} />
    case 'assigneeId': return options(assignable.map((u) => ({ id: u.id, name: u.displayName })))
    case 'priorityId': return options(meta?.priorities ?? [])
    case 'componentIds': return options(components.filter((c) => !c.archived))
    case 'fixVersionIds': return options(versions.filter((x) => x.status !== 'ARCHIVED'))
    case 'dueDate': case 'startDate': return <input required type="date" aria-label={fieldLabel(field)} className={cls} value={v} onChange={(e) => onChange(e.target.value)} />
    default: return <Input required aria-label={fieldLabel(field)} value={v} onChange={(e) => onChange(e.target.value)} />
  }
}
