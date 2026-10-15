import { useEffect, useRef, useState, type ReactNode } from 'react'
import { t } from '../i18n'

/** Click-to-edit single-line text: Enter saves, Escape cancels (spec §4.3 inline editing). */
export function InlineText({ value, onSave, disabled, className = '', inputClassName = '', placeholder, hk }: {
  value: string
  onSave: (v: string) => void
  disabled?: boolean
  className?: string
  inputClassName?: string
  placeholder?: string
  /** Keyboard shortcut target name (data-hk). */
  hk?: string
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(value)
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => { if (editing) ref.current?.select() }, [editing])
  if (!editing) {
    return (
      <button type="button" disabled={disabled} data-hk={hk}
        className={`w-full rounded px-1 -mx-1 text-left enabled:hover:bg-slate-100 dark:enabled:hover:bg-slate-800 ${className}`}
        onClick={() => { setDraft(value); setEditing(true) }}>
        {value || <span className="text-slate-500">{placeholder ?? t('Aucun')}</span>}
      </button>
    )
  }
  const commit = () => {
    setEditing(false)
    if (draft.trim() !== value) onSave(draft.trim())
  }
  return (
    <input ref={ref} value={draft} onChange={(e) => setDraft(e.target.value)} onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') commit()
        if (e.key === 'Escape') setEditing(false)
      }}
      className={`w-full rounded border border-indigo-400 bg-white px-1 outline-none ring-2 ring-indigo-500/30 dark:bg-slate-900 ${inputClassName}`} />
  )
}

/** Label/value row of the ticket side panel. */
export function FieldRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[7.5rem_1fr] items-center gap-2 py-1 text-sm">
      <span className="text-slate-500">{label}</span>
      <div className="min-w-0">{children}</div>
    </div>
  )
}

export const selectClass =
  'w-full rounded border border-transparent bg-transparent px-1 py-0.5 text-sm hover:border-slate-300 focus:border-indigo-500 focus:outline-none disabled:hover:border-transparent dark:hover:border-slate-700'

/** Chips of the chosen items plus a picker for the others (components, versions). */
export function MultiPicker({ label, value, options, disabled, onChange }: {
  label: string; value: { id: string; name: string }[]; options: { id: string; name: string }[]; disabled?: boolean
  onChange: (ids: string[]) => void
}) {
  const ids = value.map((v) => v.id)
  const rest = options.filter((o) => !ids.includes(o.id))
  return (
    <div className="flex flex-wrap items-center gap-1">
      {value.map((v) => (
        <span key={v.id} className="inline-flex items-center gap-1 rounded bg-slate-100 px-1.5 text-xs dark:bg-slate-800">
          {v.name}
          {!disabled && (
            <button aria-label={t('Retirer {name}', { name: v.name })} className="text-slate-500 hover:text-red-600"
              onClick={() => onChange(ids.filter((x) => x !== v.id))}>×</button>
          )}
        </span>
      ))}
      {!disabled && rest.length > 0 && (
        <select aria-label={label} value="" className={selectClass} onChange={(e) => e.target.value && onChange([...ids, e.target.value])}>
          <option value="">{value.length ? '+' : t('Aucun')}</option>
          {rest.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
        </select>
      )}
      {(disabled || rest.length === 0) && value.length === 0 && <span className="text-sm text-slate-500">{t('Aucun')}</span>}
    </div>
  )
}
