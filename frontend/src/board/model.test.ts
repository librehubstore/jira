import { describe, expect, it } from 'vitest'
import { textOn } from './BoardCard'
import { cardColor, laneChange, lanes, matches, neighbours, wipCount, wipState, workingDaysSince, type Card, type Column } from './model'

const card = (over: Partial<Card>): Card => ({
  id: over.key ?? 'X-1', key: 'X-1', summary: 'Corriger le login', version: 0,
  type: { id: 'task', name: 'Tâche', icon: 'task', color: '#000', hierarchyLevel: 0 },
  statusId: 'todo', priority: { id: 'mid', name: 'Moyenne', color: '#000', rank: 3 },
  assignee: null, reporter: { id: 'rep', username: 'rep', displayName: 'Rep', active: true }, lane: -1, color: null, statusSince: '2026-10-14T10:00:00Z', components: [], fixVersions: [], collaborators: [], parent: null, labels: [], rank: 'i', flagged: false, dueDate: null, startDate: null,
  estimate: null, updatedAt: '2026-10-14T10:00:00Z', subtasks: null, comments: 0, attachments: 0, blocked: false,
  ...over,
})
const alice = { id: 'alice', username: 'alice', displayName: 'Alice', active: true }
const bob = { id: 'bob', username: 'bob', displayName: 'Bob', active: true }

describe('matches', () => {
  it('ANDs fields and ORs values', () => {
    const c = card({ assignee: alice, labels: ['Front'] })
    expect(matches(c, { assignee: ['me'] }, 'alice')).toBe(true)
    expect(matches(c, { assignee: ['bob', 'alice'] }, 'x')).toBe(true)
    expect(matches(c, { assignee: ['me'], labels: ['back'] }, 'alice')).toBe(false)
    expect(matches(c, { labels: ['front'] }, 'x')).toBe(true)
    expect(matches(card({}), { assignee: ['none'] }, 'x')).toBe(true)
  })

  it('filters recent updates and text', () => {
    const now = Date.parse('2026-10-14T20:00:00Z')
    expect(matches(card({}), { updatedWithinDays: 1 }, 'x', now)).toBe(true)
    expect(matches(card({ updatedAt: '2026-10-12T10:00:00Z' }), { updatedWithinDays: 1 }, 'x', now)).toBe(false)
    expect(matches(card({}), { text: 'LOGIN' }, 'x')).toBe(true)
    expect(matches(card({}), { text: 'x-1' }, 'x')).toBe(true)
  })
})

describe('lanes', () => {
  it('sorts by name and puts unassigned last', () => {
    const l = lanes([card({}), card({ key: 'X-2', assignee: bob }), card({ key: 'X-3', assignee: alice })], 'ASSIGNEE')
    expect(l.map((x) => x.label)).toEqual(['Alice', 'Bob', 'Non assignés'])
  })
})

describe('query lanes and fql filters', () => {
  it('orders query lanes and puts the rest last', () => {
    const l = lanes([card({ lane: -1 }), card({ key: 'X-2', lane: 1 }), card({ key: 'X-3', lane: 0 })], 'QUERIES', ['Expedite', 'Bugs'])
    expect(l.map((x) => x.label)).toEqual(['Expedite', 'Bugs', 'Le reste'])
  })
  it('applies fql ids once known', () => {
    const c = card({ id: 'a' })
    expect(matches(c, { fql: 'type = Bug' }, 'me')).toBe(true)
    expect(matches(c, { fql: 'type = Bug' }, 'me', Date.now(), new Map([['type = Bug', new Set(['b'])]]))).toBe(false)
  })
})

describe('wip', () => {
  const col: Column = { id: 'c', name: 'En cours', wipMin: 1, wipMax: 2, statuses: [{ id: 'doing', name: 'En cours', category: 'IN_PROGRESS' }] }
  const sub = { id: 'sub', name: 'Sous-tâche', icon: 'subtask', color: '#000', hierarchyLevel: -1 }
  const cards = [card({ statusId: 'doing' }), card({ key: 'X-2', statusId: 'doing' }), card({ key: 'X-3', statusId: 'doing', type: sub })]
  it('counts and flags limits', () => {
    expect(wipCount(cards, col, 'COUNT')).toBe(3)
    expect(wipState(3, col, 'COUNT')).toBe('over')
    expect(wipCount(cards, col, 'COUNT_EXCL_SUBTASKS')).toBe(2)
    expect(wipState(0, col, 'COUNT')).toBe('under')
    expect(wipState(9, col, 'OFF')).toBe('ok')
  })
})

describe('neighbours', () => {
  it('ignores the moved card itself', () => {
    expect(neighbours(['A', 'B', 'C'], 'A', 1)).toEqual({ aboveKey: 'B', belowKey: 'C' })
    expect(neighbours(['A', 'B'], 'Z', 0)).toEqual({ aboveKey: null, belowKey: 'A' })
    expect(neighbours(['A', 'B'], 'Z', 2)).toEqual({ aboveKey: 'B', belowKey: null })
  })
})

describe('v1.3 card helpers', () => {
  it('counts working days only', () => {
    // Friday 9 Oct 2026 → Tuesday 13 Oct: Mon + Tue = 2 working days, the weekend is skipped.
    expect(workingDaysSince('2026-10-09T15:00:00', [1, 2, 3, 4, 5], new Date('2026-10-13T09:00:00'))).toBe(2)
    expect(workingDaysSince('2026-10-09T15:00:00', [1, 2, 3, 4, 5, 6, 7], new Date('2026-10-13T09:00:00'))).toBe(4)
    expect(workingDaysSince('2026-10-13T08:00:00', [1, 2, 3, 4, 5], new Date('2026-10-13T09:00:00'))).toBe(0)
  })
  it('colours by mode', () => {
    expect(cardColor(card({}), 'TYPE')).toBe('#000')
    expect(cardColor(card({ color: '#dc2626' }), 'QUERIES')).toBe('#dc2626')
    expect(cardColor(card({}), 'ASSIGNEE')).toBeUndefined()
    expect(cardColor(card({ assignee: alice }), 'ASSIGNEE')).toBe(cardColor(card({ key: 'X-9', assignee: alice }), 'ASSIGNEE'))
  })
  it('maps lane drops to fields', () => {
    const a = { id: 'alice', label: 'Alice' }, none = { id: '__none', label: 'Non assignés' }
    expect(laneChange('ASSIGNEE', a, none)).toEqual({ field: 'ASSIGNEE', value: null })
    expect(laneChange('ASSIGNEE', none, a)).toEqual({ field: 'ASSIGNEE', value: 'alice' })
    expect(laneChange('TYPE', none, a)).toBeNull()
    expect(laneChange('ASSIGNEE', a, a)).toBeNull()
  })
})

describe('label text colour', () => {
  it('keeps contrast on light and dark backgrounds', () => {
    expect(textOn('#fde047')).toBe('#000')
    expect(textOn('#1e3a8a')).toBe('#fff')
  })
})
