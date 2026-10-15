import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api'
import { t } from '../i18n'

type Meta = { fields: string[]; operators: string[]; functions: string[]; keywords: string[] }

/** What the caret is on: a field name, an operator, or a value of a given field. */
export function fqlContext(text: string, caret: number): { kind: 'field' | 'operator' | 'value'; field?: string; prefix: string; start: number } {
  const before = text.slice(0, caret)
  let from = before.length
  while (from > 0 && !/[\s(),=~!<>"]/.test(before[from - 1])) from--
  const prefix = before.slice(from)
  const start = caret - prefix.length
  const head = before.slice(0, start).trimEnd()
  const tokens = head.match(/"[^"]*"|[^\s(),]+|[(),]/g) ?? []
  const last = tokens.at(-1)?.toLowerCase() ?? ''
  const ops = ['=', '!=', '>', '>=', '<', '<=', '~', '!~', 'in', 'was', 'not', 'from', 'to', 'by', 'after', 'before', ',', '(']
  if (ops.includes(last)) {
    // Walk back to this clause's operator (skipping list items), then take the field before it.
    const real = ['=', '!=', '>', '>=', '<', '<=', '~', '!~', 'in', 'was', 'from', 'to', 'by', 'after', 'before']
    for (let i = tokens.length - 1; i >= 0; i--) {
      if (!real.includes(tokens[i].toLowerCase())) continue
      let j = i - 1
      while (j >= 0 && ['not', 'was', 'changed'].includes(tokens[j].toLowerCase())) j--
      return { kind: 'value', field: tokens[j], prefix, start }
    }
    return { kind: 'value', prefix, start }
  }
  if (!last || ['and', 'or', '(', 'by'].includes(last) || last === 'not') return { kind: 'field', prefix, start }
  return { kind: 'operator', prefix, start }
}

/** FQL editor with autocompletion (spec §4.11); the server reports errors with a position. */
export function FqlInput({ value, onChange, onSubmit, error, errorPosition }: {
  value: string; onChange: (v: string) => void; onSubmit: () => void; error?: string | null; errorPosition?: number | null
}) {
  const input = useRef<HTMLTextAreaElement>(null)
  const [caret, setCaret] = useState(value.length)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const meta = useQuery({ queryKey: ['fql', 'meta'], queryFn: () => api<Meta>('/search/fql/meta'), staleTime: Infinity }).data
  const ctx = useMemo(() => fqlContext(value, caret), [value, caret])
  const values = useQuery({
    queryKey: ['fql', 'values', ctx.field, ctx.prefix],
    queryFn: () => api<string[]>(`/search/fql/values?field=${encodeURIComponent(ctx.field ?? '')}&q=${encodeURIComponent(ctx.prefix.replace(/^"/, ''))}`),
    enabled: ctx.kind === 'value' && !!ctx.field,
    staleTime: 30_000,
  }).data
  const suggestions = useMemo(() => {
    const p = ctx.prefix.toLowerCase()
    const list = ctx.kind === 'field' ? [...(meta?.fields ?? []), 'NOT']
      : ctx.kind === 'operator' ? [...(meta?.operators ?? []), 'AND', 'OR', 'ORDER BY']
      : [...(values ?? []).map((v) => (/[\s,()]/.test(v) ? `"${v}"` : v)), ...(meta?.functions ?? []), 'EMPTY']
    return list.filter((s) => s.toLowerCase().startsWith(p) || (p && s.toLowerCase().includes(p))).slice(0, 12)
  }, [ctx, meta, values])

  useEffect(() => { setActive(0) }, [ctx.kind, ctx.prefix])

  const insert = (s: string) => {
    const next = value.slice(0, ctx.start) + s + ' ' + value.slice(caret)
    onChange(next)
    const pos = ctx.start + s.length + 1
    requestAnimationFrame(() => { input.current?.setSelectionRange(pos, pos); setCaret(pos) })
  }

  return (
    <div className="relative">
      <textarea ref={input} value={value} rows={2} spellCheck={false} aria-label={t('Requête FQL')}
        placeholder={t('ex. assignee = currentUser() AND statusCategory != Done ORDER BY priority DESC')}
        onChange={(e) => { onChange(e.target.value); setCaret(e.target.selectionStart); setOpen(true) }}
        onSelect={(e) => setCaret(e.currentTarget.selectionStart)}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onKeyDown={(e) => {
          if (open && suggestions.length && (e.key === 'Tab' || (e.key === 'Enter' && !e.ctrlKey && !e.metaKey && ctx.prefix))) {
            e.preventDefault()
            insert(suggestions[active])
            return
          }
          if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); setOpen(false); onSubmit() }
          if (e.key === 'ArrowDown' && open) { e.preventDefault(); setActive((a) => Math.min(a + 1, suggestions.length - 1)) }
          if (e.key === 'ArrowUp' && open) { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)) }
          if (e.key === 'Escape') setOpen(false)
        }}
        className={`w-full rounded-md border bg-white px-2 py-1.5 font-mono text-sm dark:bg-slate-900 ${error ? 'border-red-500' : 'border-slate-300 dark:border-slate-700'}`} />
      {open && suggestions.length > 0 && (
        <ul role="listbox" className="absolute left-0 z-30 mt-1 max-h-64 w-72 overflow-y-auto rounded-md border border-slate-200 bg-white py-1 font-mono text-sm shadow-lg dark:border-slate-700 dark:bg-slate-900">
          {suggestions.map((s, i) => (
            <li key={s} role="option" aria-selected={i === active} onMouseDown={(e) => { e.preventDefault(); insert(s) }}
              className={`cursor-pointer px-3 py-1 ${i === active ? 'bg-indigo-50 dark:bg-indigo-950' : ''}`}>{s}</li>
          ))}
        </ul>
      )}
      {error && (
        <div role="alert" className="mt-1 text-sm text-red-600">
          {error}
          {errorPosition != null && (
            <pre className="mt-1 overflow-x-auto font-mono text-xs text-slate-600 dark:text-slate-400">{value}{'\n'}{' '.repeat(errorPosition)}^</pre>
          )}
        </div>
      )}
    </div>
  )
}
