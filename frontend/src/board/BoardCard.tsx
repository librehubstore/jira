import { memo } from 'react'
import { useSortable } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { Avatar, PriorityIcon, TypeIcon } from '../components/Badges'
import type { Card } from './model'
import { t } from '../i18n'

const FIELD_LABEL: Record<string, string> = { dueDate: t('Échéance'), startDate: t('Début'), estimate: t('Estimation'), labels: t('Labels'), parent: t('Parent'), daysInColumn: t('Jours dans la colonne'), components: t('Composants'), fixVersions: t('Versions') }

/** Black or white text, whichever reads best on a #rrggbb background (WCAG relative luminance). */
export function textOn(hex: string) {
  const [r, g, b] = [1, 3, 5].map((i) => Number.parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.179 ? '#000' : '#fff'
}

/** Board-level look of a card: colour band and working days in the column (spec §4.10). */
export type CardLook = { accent?: string; days?: number; labelColors?: Record<string, string> }

export const CardBody = memo(function CardBody({ card, fields, dragging, accent, days, labelColors }: { card: Card; fields: string[]; dragging?: boolean } & CardLook) {
  const today = new Date().toISOString().slice(0, 10)
  const overdue = card.dueDate && card.dueDate < today
  return (
    <div style={accent ? { borderLeft: `4px solid ${accent}` } : undefined} className={`rounded-md border bg-white p-2 text-sm shadow-xs dark:bg-slate-900 ${card.flagged ? 'border-amber-400 bg-amber-50 dark:bg-amber-950' : 'border-slate-200 dark:border-slate-700'} ${dragging ? 'rotate-1 shadow-lg ring-2 ring-indigo-400' : 'hover:border-slate-300 dark:hover:border-slate-600'}`}>
      <p className="mb-1.5 line-clamp-3">{card.summary}</p>
      {fields.includes('labels') && card.labels.length > 0 && (
        <div className="mb-1.5 flex flex-wrap gap-1">
          {card.labels.map((l) => {
            const color = labelColors?.[l.toLowerCase()]
            return color
              ? <span key={l} className="rounded px-1 text-[11px] font-medium" style={{ backgroundColor: color, color: textOn(color) }}>{l}</span>
              : <span key={l} className="rounded bg-slate-100 px-1 text-[11px] text-slate-600 dark:bg-slate-800 dark:text-slate-300">{l}</span>
          })}
        </div>
      )}
      {fields.filter((f) => f !== 'labels' && f !== 'daysInColumn').map((f) => {
        const v = f === 'parent' ? card.parent?.key : f === 'dueDate' ? card.dueDate : f === 'startDate' ? card.startDate
          : f === 'components' ? card.components.join(', ') || null : f === 'fixVersions' ? card.fixVersions.join(', ') || null : card.estimate
        return v == null ? null : <div key={f} className="text-[11px] text-slate-500">{FIELD_LABEL[f]} : {String(v)}</div>
      })}
      <div className="flex items-center gap-1.5 text-xs text-slate-500">
        <TypeIcon type={card.type} />
        <span className="font-medium">{card.key}</span>
        {card.flagged && <span title={t('Signalé')} className="text-amber-600">⚑</span>}
        {card.blocked && <span title={t('Bloqué par un ticket non terminé')} className="text-red-600">⛔</span>}
        {card.subtasks && <span title={t('Sous-tâches terminées')}>☑ {card.subtasks.done}/{card.subtasks.total}</span>}
        {card.comments > 0 && <span title={t('Commentaires')}>💬 {card.comments}</span>}
        {card.attachments > 0 && <span title={t('Pièces jointes')}>📎 {card.attachments}</span>}
        {fields.includes('daysInColumn') && days != null && days > 0 && (
          <span title={t('{n} jour(s) ouvré(s) dans la colonne', { n: days })} aria-label={t('{n} jour(s) ouvré(s) dans la colonne', { n: days })}
            className={days >= 5 ? 'font-semibold text-amber-700 dark:text-amber-400' : ''}>⏱ {days}{t('j')}</span>
        )}
        {card.dueDate && <span title={t('Échéance {date}', { date: card.dueDate })} className={overdue ? 'font-semibold text-red-600' : ''}>📅 {card.dueDate.slice(5)}</span>}
        <span className="ml-auto flex items-center gap-1">
          {card.estimate != null && <span title={t('Estimation')} className="rounded-full bg-slate-200 px-1.5 text-[11px] text-slate-700 dark:bg-slate-700 dark:text-slate-200">{card.estimate}</span>}
          <PriorityIcon priority={card.priority} />
          <span title={t('Rapporteur')}><Avatar user={card.reporter} size={5} /></span>
          <span title={t('Assigné')}><Avatar user={card.assignee} size={5} /></span>
        </span>
      </div>
      {card.collaborators.length > 0 && (
        <div className="mt-1 flex items-center justify-end gap-0.5" aria-label={t('Collaborateurs')}>
          {card.collaborators.map((u) => <Avatar key={u.id} user={u} size={5} />)}
        </div>
      )}
    </div>
  )
})

export function SortableCard({ card, cell, fields, disabled, selected, onOpen, look, onMenu }: {
  card: Card; cell: string; fields: string[]; disabled: boolean; selected: boolean; onOpen: (key: string) => void; look?: CardLook
  onMenu?: (card: Card, x: number, y: number) => void
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: card.key, data: { cell }, disabled })
  return (
    <div ref={setNodeRef} {...attributes} {...listeners} data-card={card.key}
      aria-label={`${card.key} ${card.summary}`} aria-current={selected || undefined}
      onClick={() => onOpen(card.key)}
      onContextMenu={(e) => {
        if (!onMenu) return
        e.preventDefault()
        // The ContextMenu key / Shift+F10 fire this too, at the card's position.
        const r = e.currentTarget.getBoundingClientRect()
        onMenu(card, e.clientX || r.left + 16, e.clientY || r.top + 16)
      }}
      onKeyDown={(e) => {
        listeners?.onKeyDown?.(e)
        if (e.key === 'Enter' && !e.defaultPrevented) onOpen(card.key)
      }}
      style={{ transform: CSS.Translate.toString(transform), transition, contentVisibility: 'auto', containIntrinsicSize: 'auto 88px' }}
      className={`cursor-pointer rounded-md outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 ${isDragging ? 'opacity-30' : ''} ${selected ? 'ring-2 ring-indigo-500' : ''}`}>
      <CardBody card={card} fields={fields} {...look} />
    </div>
  )
}
