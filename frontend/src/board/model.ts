import type { UserRef } from '../spaces'
import type { PriorityRef, StatusRef, TicketRef, TypeRef } from '../tickets'
import { t } from '../i18n'

export type Card = {
  id: string
  key: string
  summary: string
  version: number
  type: TypeRef
  statusId: string
  priority: PriorityRef
  assignee: UserRef | null
  reporter: UserRef
  parent: TicketRef | null
  labels: string[]
  rank: string
  flagged: boolean
  dueDate: string | null
  startDate: string | null
  estimate: number | null
  updatedAt: string
  subtasks: { done: number; total: number } | null
  comments: number
  attachments: number
  blocked: boolean
  /** Index of the first matching swimlane query (QUERIES mode), -1 otherwise. */
  lane: number
  color: string | null
  statusSince: string
  components: string[]
  fixVersions: string[]
  collaborators: UserRef[]
}

export type Column = { id: string; name: string; wipMin: number | null; wipMax: number | null; statuses: StatusRef[] }
export type SwimlaneMode = 'NONE' | 'ASSIGNEE' | 'PARENT' | 'PRIORITY' | 'TYPE' | 'QUERIES' | 'REPORTER' | 'LABEL'
export type WipMode = 'OFF' | 'COUNT' | 'COUNT_EXCL_SUBTASKS'

/** Structured filter (spec §4.10 quick filters). Within a field: OR; across fields: AND. */
export type Criteria = {
  assignee?: string[] // user ids, 'me', 'none'
  types?: string[]
  priorities?: string[]
  labels?: string[]
  parents?: string[] // parent ticket keys
  updatedWithinDays?: number
  flagged?: boolean
  text?: string
  /** FQL quick filter, evaluated by the server (ids of matching tickets). */
  fql?: string
}

export const PREDEFINED: { id: string; name: string; criteria: Criteria }[] = [
  { id: 'mine', name: t('Mes tickets'), criteria: { assignee: ['me'] } },
  { id: 'recent', name: t('Mis à jour récemment'), criteria: { updatedWithinDays: 1 } },
  { id: 'unassigned', name: t('Non assignés'), criteria: { assignee: ['none'] } },
]

const some = (list: string[] | undefined, test: (v: string) => boolean) => !list?.length || list.some(test)

export function matches(card: Card, c: Criteria, meId: string, now = Date.now(), fqlMatches?: Map<string, Set<string>>): boolean {
  if (c.fql) {
    const ids = fqlMatches?.get(c.fql)
    if (ids && !ids.has(card.id)) return false // until the server answers, nothing is hidden
  }
  if (!some(c.assignee, (a) => a === 'none' ? !card.assignee : card.assignee?.id === (a === 'me' ? meId : a))) return false
  if (!some(c.types, (t) => card.type.id === t)) return false
  if (!some(c.priorities, (p) => card.priority.id === p)) return false
  if (!some(c.labels, (l) => card.labels.some((x) => x.toLowerCase() === l.toLowerCase()))) return false
  if (!some(c.parents, (p) => card.parent?.key === p)) return false
  if (c.updatedWithinDays && now - Date.parse(card.updatedAt) > c.updatedWithinDays * 86_400_000) return false
  if (c.flagged && !card.flagged) return false
  if (c.text) {
    const q = c.text.toLowerCase()
    if (!card.summary.toLowerCase().includes(q) && !card.key.toLowerCase().includes(q)) return false
  }
  return true
}

export type Lane = { id: string; label: string }

const NO_LANE = '__none'

export function laneOf(card: Card, mode: SwimlaneMode, queries: string[] = []): Lane {
  switch (mode) {
    case 'QUERIES': return card.lane >= 0 && card.lane < queries.length ? { id: `q${card.lane}`, label: queries[card.lane] } : { id: NO_LANE, label: t('Le reste') }
    case 'REPORTER': return { id: card.reporter.id, label: card.reporter.displayName }
    case 'LABEL': {
      const first = [...card.labels].sort((a, b) => a.localeCompare(b))[0]
      return first ? { id: `l:${first.toLowerCase()}`, label: first } : { id: NO_LANE, label: t('Sans label') }
    }
    case 'ASSIGNEE': return card.assignee ? { id: card.assignee.id, label: card.assignee.displayName } : { id: NO_LANE, label: t('Non assignés') }
    case 'PARENT': return card.parent ? { id: card.parent.key, label: `${card.parent.key} ${card.parent.summary}` } : { id: NO_LANE, label: t('Sans parent') }
    case 'PRIORITY': return { id: card.priority.id, label: card.priority.name }
    case 'TYPE': return { id: card.type.id, label: card.type.name }
    default: return { id: 'all', label: '' }
  }
}

/** Lanes in display order; the "none" lane always last. */
export function lanes(cards: Card[], mode: SwimlaneMode, queries: string[] = []): Lane[] {
  if (mode === 'NONE') return [{ id: 'all', label: '' }]
  const seen = new Map<string, { lane: Lane; sort: string | number }>()
  for (const c of cards) {
    const lane = laneOf(c, mode, queries)
    if (seen.has(lane.id)) continue
    const sort = mode === 'PRIORITY' ? c.priority.rank : mode === 'QUERIES' ? c.lane : mode === 'PARENT' ? seen.size : lane.label.toLowerCase()
    seen.set(lane.id, { lane, sort })
  }
  return [...seen.values()]
    .sort((a, b) => (a.lane.id === NO_LANE ? 1 : b.lane.id === NO_LANE ? -1 : a.sort < b.sort ? -1 : a.sort > b.sort ? 1 : 0))
    .map((v) => v.lane)
}

/** WIP count of a column: all its cards, quick filters ignored (spec §4.10). */
export function wipCount(cards: Card[], column: Column, mode: WipMode) {
  const statusIds = new Set(column.statuses.map((s) => s.id))
  return cards.filter((c) => statusIds.has(c.statusId) && (mode !== 'COUNT_EXCL_SUBTASKS' || c.type.hierarchyLevel !== -1)).length
}

export function wipState(count: number, column: Column, mode: WipMode): 'over' | 'under' | 'ok' {
  if (mode === 'OFF') return 'ok'
  if (column.wipMax != null && count > column.wipMax) return 'over'
  if (column.wipMin != null && count < column.wipMin) return 'under'
  return 'ok'
}

/** Neighbours of a dropped card in its target cell, which the server turns into a rank. */
export function neighbours(cellKeys: string[], movedKey: string, index: number) {
  const others = cellKeys.filter((k) => k !== movedKey)
  return { aboveKey: others[index - 1] ?? null, belowKey: others[index] ?? null }
}

/** Whole working days elapsed since `since` (spec §4.10 « jours dans la colonne »); days are ISO (1 = Monday). */
export function workingDaysSince(since: string, workingDays: number[], now = new Date()): number {
  const day = new Date(since)
  day.setHours(0, 0, 0, 0)
  const end = new Date(now)
  end.setHours(0, 0, 0, 0)
  let n = 0
  // ponytail: day-by-day loop, fine for the few hundred days a card stays in a column.
  for (day.setDate(day.getDate() + 1); day <= end; day.setDate(day.getDate() + 1)) {
    if (workingDays.includes(day.getDay() || 7)) n++
  }
  return n
}

export type ColorMode = 'NONE' | 'TYPE' | 'PRIORITY' | 'ASSIGNEE' | 'QUERIES'

/** Left band colour of a card (spec §4.10), or undefined for none. */
export function cardColor(card: Card, mode: ColorMode): string | undefined {
  switch (mode) {
    case 'TYPE': return card.type.color
    case 'PRIORITY': return card.priority.color
    case 'ASSIGNEE': {
      if (!card.assignee) return undefined
      let h = 0
      for (const ch of card.assignee.id) h = (h * 31 + ch.charCodeAt(0)) % 360
      return `hsl(${h} 65% 50%)`
    }
    case 'QUERIES': return card.color ?? undefined
    default: return undefined
  }
}

/** Field update implied by dropping a card in another swimlane (spec §4.10), or null when the lane carries no field. */
export function laneChange(mode: SwimlaneMode, from: Lane, to: Lane): { field: string; value: string | null } | null {
  if (from.id === to.id || !['ASSIGNEE', 'PRIORITY', 'PARENT'].includes(mode)) return null
  if (mode === 'PRIORITY' && to.id === NO_LANE) return null
  return { field: mode, value: to.id === NO_LANE ? null : to.id }
}
