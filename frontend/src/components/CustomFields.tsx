import { useQuery } from '@tanstack/react-query'
import { api } from '../api'
import { useDirectory } from '../spaces'
import { InlineText, selectClass } from './Inline'
import type { FieldType } from '../pages/AdminPages'
import { t } from '../i18n'

export type LayoutField = {
  field: string; name: string | null; kind: 'SYSTEM' | FieldType; visible: boolean; required: boolean
  defaultValue: unknown; options: string[]
}

export const SYSTEM_FIELDS: Record<string, string> = {
  description: t('Description'), assigneeId: t('Assigné'), priorityId: t('Priorité'), labels: t('Labels'), componentIds: t('Composants'),
  fixVersionIds: t('Versions'), parent: t('Parent'), dueDate: t('Échéance'), startDate: t('Date de début'), estimate: t('Estimation'),
}

/** Form layout of a space and ticket type (spec §4.14). */
export function useLayout(spaceKey: string | undefined, typeId?: string) {
  return useQuery({
    queryKey: ['space', spaceKey, 'layout', typeId ?? ''],
    queryFn: () => api<LayoutField[]>(`/spaces/${spaceKey}/layout${typeId ? `?typeId=${typeId}` : ''}`),
    enabled: !!spaceKey,
  })
}

export function fieldLabel(l: LayoutField) {
  return l.name ?? SYSTEM_FIELDS[l.field] ?? l.field
}

/** Editor of one custom field value; saves on change (lists, dates, checkbox) or on blur (texts). */
export function CustomFieldInput({ field, value, disabled, onSave }: {
  field: LayoutField; value: unknown; disabled?: boolean; onSave: (v: unknown) => void
}) {
  const users = useDirectory().data ?? []
  const label = fieldLabel(field)
  switch (field.kind) {
    case 'TEXT': case 'URL': case 'NUMBER':
      return <InlineText value={value == null ? '' : String(value)} disabled={disabled} placeholder="—" onSave={(v) => onSave(v.trim() || null)} />
    case 'TEXTAREA':
      return (
        <textarea aria-label={label} defaultValue={value == null ? '' : String(value)} disabled={disabled} rows={3}
          onBlur={(e) => { if (e.target.value !== (value ?? '')) onSave(e.target.value || null) }}
          className="w-full rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900" />
      )
    case 'DATE':
      return <input type="date" aria-label={label} value={value == null ? '' : String(value)} disabled={disabled} className={selectClass}
        onChange={(e) => onSave(e.target.value || null)} />
    case 'CHECKBOX':
      return <input type="checkbox" aria-label={label} checked={value === true} disabled={disabled} onChange={(e) => onSave(e.target.checked)} />
    case 'SELECT':
      return (
        <select aria-label={label} value={value == null ? '' : String(value)} disabled={disabled} className={selectClass} onChange={(e) => onSave(e.target.value || null)}>
          <option value="">{t('Aucun')}</option>
          {field.options.map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      )
    case 'USER':
      return (
        <select aria-label={label} value={value == null ? '' : String(value)} disabled={disabled} className={selectClass} onChange={(e) => onSave(e.target.value || null)}>
          <option value="">{t('Aucun')}</option>
          {users.map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
        </select>
      )
    case 'MULTISELECT': case 'USERS': {
      const chosen = Array.isArray(value) ? value.map(String) : []
      const options = field.kind === 'USERS' ? users.map((u) => ({ id: u.id, name: u.displayName })) : field.options.map((o) => ({ id: o, name: o }))
      return (
        <div className="flex flex-wrap items-center gap-1">
          {chosen.map((c) => (
            <span key={c} className="inline-flex items-center gap-1 rounded bg-slate-100 px-1.5 text-xs dark:bg-slate-800">
              {options.find((o) => o.id === c)?.name ?? c}
              {!disabled && <button aria-label={t('Retirer {name}', { name: c })} className="text-slate-500 hover:text-red-600" onClick={() => onSave(chosen.filter((x) => x !== c))}>×</button>}
            </span>
          ))}
          {!disabled && (
            <select aria-label={label} value="" className={selectClass} onChange={(e) => e.target.value && onSave([...chosen, e.target.value])}>
              <option value="">{chosen.length ? '+' : t('Aucun')}</option>
              {options.filter((o) => !chosen.includes(o.id)).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
            </select>
          )}
        </div>
      )
    }
    default:
      return null
  }
}
