import type { StatusCategory, UserRef } from '../spaces'
import type { PriorityRef, TypeRef } from '../tickets'
import { t } from '../i18n'

const TYPE_GLYPH: Record<string, string> = { epic: '⚡', story: '▲', task: '✓', bug: '●', subtask: '↳' }

export function TypeIcon({ type }: { type: TypeRef }) {
  return (
    <span title={type.name} aria-label={type.name}
      className="inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-sm text-[10px] leading-none text-white"
      style={{ backgroundColor: type.color }}>
      {TYPE_GLYPH[type.icon] ?? '•'}
    </span>
  )
}

export function PriorityIcon({ priority }: { priority: PriorityRef }) {
  const arrows = priority.rank <= 1 ? '⇈' : priority.rank === 2 ? '↑' : priority.rank === 3 ? '=' : priority.rank === 4 ? t('↓') : '⇊'
  return (
    <span title={t('Priorité : {name}', { name: priority.name })} aria-label={t('Priorité : {name}', { name: priority.name })}
      className="inline-block w-4 text-center text-sm font-bold leading-none" style={{ color: priority.color }}>
      {arrows}
    </span>
  )
}

const CATEGORY_STYLE: Record<StatusCategory, string> = {
  TODO: 'bg-slate-200 text-slate-700 dark:bg-slate-700 dark:text-slate-100',
  IN_PROGRESS: 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-100',
  DONE: 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-100',
}

export function StatusBadge({ status }: { status: { name: string; category: StatusCategory } }) {
  return (
    <span className={`inline-block rounded px-1.5 py-0.5 text-xs font-semibold uppercase tracking-wide ${CATEGORY_STYLE[status.category]}`}>
      {status.name}
    </span>
  )
}

/** Uploaded picture or initials; zooms on hover (ADD_FUNCTIONALITIES A.4). */
export function Avatar({ user, size = 6, zoom = true }: { user: UserRef | null; size?: 5 | 6 | 8; zoom?: boolean }) {
  const dim = { 5: 'h-5 w-5 text-[9px]', 6: 'h-6 w-6 text-[10px]', 8: 'h-8 w-8 text-xs' }[size]
  if (!user) {
    return <span title={t('Non assigné')} className={`inline-flex ${dim} items-center justify-center rounded-full border border-dashed border-slate-400 text-slate-500`}>?</span>
  }
  const initials = user.displayName.split(/\s+/).map((w) => w[0]).join('').slice(0, 2).toUpperCase()
  let hash = 0
  for (const c of user.username) hash = (hash * 31 + c.charCodeAt(0)) % 360
  const title = user.displayName + (user.active ? '' : t(' (désactivé)'))
  const zoomClass = zoom ? 'relative transition-transform duration-150 origin-bottom-right hover:z-50 hover:scale-[2.5] hover:shadow-lg motion-reduce:hover:scale-100' : ''
  if (user.avatar) {
    return <img src={user.avatar} alt={title} title={title} loading="lazy"
      className={`inline-block ${dim} shrink-0 rounded-full object-cover ${zoomClass} ${user.active ? '' : 'opacity-50'}`} />
  }
  return (
    <span title={title} role="img" aria-label={title}
      className={`inline-flex ${dim} shrink-0 items-center justify-center rounded-full font-semibold text-white ${zoomClass} ${user.active ? '' : 'opacity-50'}`}
      style={{ backgroundColor: `hsl(${hash} 55% 45%)` }}>
      {initials}
    </span>
  )
}
