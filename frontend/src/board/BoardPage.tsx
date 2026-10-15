import { useEffect, useMemo, useRef, useState } from 'react'
import {
  closestCorners, DndContext, DragOverlay, KeyboardSensor, MouseSensor, pointerWithin, TouchSensor, useDroppable, useSensor, useSensors,
  type CollisionDetection, type DragEndEvent, type DragStartEvent,
} from '@dnd-kit/core'
import { SortableContext, sortableKeyboardCoordinates, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { api } from '../api'
import { localStorageGet, localStorageSet } from '../storage'
import { useShortcuts } from '../keyboard'
import { useMe } from '../auth'
import { TicketView } from '../components/TicketView'
import { fetchTransitions, needsScreen, pickTransition, useTransitionScreen, type ScreenValues } from '../components/Transitions'
import type { Perm } from '../spaces'
import { useSpace } from '../spaces'
import { useVersions, type StatusRef } from '../tickets'
import { ErrorText } from '../ui'
import { CardBody, SortableCard, type CardLook } from './BoardCard'
import { CardMenu } from './CardMenu'
import { cardColor, laneChange, laneOf, lanes as computeLanes, matches, neighbours, PREDEFINED, wipCount, wipState, workingDaysSince, type Card, type ColorMode, type Column, type Criteria, type Lane, type SwimlaneMode, type WipMode } from './model'
import { t } from '../i18n'

export type BoardView = {
  id: string; name: string; spaceKey: string; spaceName: string; isDefault: boolean; spaceKeys: string[]; hideDoneDays: number | null
  workingDays: number[]; backlogEnabled: boolean; cardColorMode: ColorMode; cardColorQueries: { fql: string; color: string }[]; wipBlocking: boolean
  swimlaneMode: SwimlaneMode; wipMode: WipMode; cardFields: string[]; showSubtasks: boolean
  baseFql: string | null; swimlaneQueries: { name: string; fql: string }[]
  columns: Column[]; unmappedStatuses: StatusRef[]
  quickFilters: { id: string; name: string; criteria: Criteria }[]
  myPermissions: Perm[]
}

export type BoardCards = { cards: Card[]; unmappedCount: number; labelColors: Record<string, string> }

/** Status drop zones win over cards when the pointer is on one (spec §4.10 « dépôt par statut »). */
const collisions: CollisionDetection = (args) => {
  const zones = pointerWithin(args).filter((c) => String(c.id).startsWith('zone|'))
  return zones.length ? zones : closestCorners(args)
}

/** Space home: the space's default board. */
export function SpaceBoardPage() {
  const { key, boardId } = useParams()
  const space = useSpace(key).data
  const id = boardId ?? space?.boards.find((b) => b.isDefault)?.id ?? space?.boards[0]?.id
  if (!space) return null
  if (!id) return <p className="p-6 text-slate-500">{t('Cet espace n’a pas de board.')}</p>
  return <BoardPage boardId={id} />
}

const LIST_PARAMS = { assignee: 'a', types: 't', priorities: 'p', labels: 'l', parents: 'e' } as const

export function BoardPage({ boardId }: { boardId: string }) {
  const me = useMe().data!
  const qc = useQueryClient()
  const [params, setParams] = useSearchParams()
  const board = useQuery({ queryKey: ['board', boardId], queryFn: () => api<BoardView>(`/boards/${boardId}`) })
  const data = useQuery({ queryKey: ['board', boardId, 'cards'], queryFn: () => api<BoardCards>(`/boards/${boardId}/cards`), refetchOnWindowFocus: true })
  const [toast, setToast] = useState<string | null>(null)
  const [dragKey, setDragKey] = useState<string | null>(null)
  // Statuses the dragged card may go to (spec §4.9: a forbidden transition is not a drop target).
  const [reachable, setReachable] = useState<Set<string> | null>(null)
  const [screen, ask] = useTransitionScreen()
  const [menu, setMenu] = useState<{ card: Card; x: number; y: number } | null>(null)
  // Collapsed columns, remembered per user and board (spec §4.10).
  const [collapsed, setCollapsed] = useState<string[]>(() => {
    try { return JSON.parse(localStorageGet(`fluxo.collapsed.${boardId}`) ?? '[]') } catch { return [] }
  })
  const selected = params.get('selected')

  const setParam = (name: string, value: string | null) => setParams((prev) => {
    const p = new URLSearchParams(prev) // new identity, so memos depending on params recompute
    if (value) p.set(name, value)
    else p.delete(name)
    return p
  }, { replace: true })

  // Active quick filters + value filters + text, all read from the URL so the view is shareable.
  const activeFilterIds = params.get('qf')?.split(',').filter(Boolean) ?? []
  const criteria: Criteria[] = useMemo(() => {
    const all = [...PREDEFINED, ...(board.data?.quickFilters ?? [])]
    const list = activeFilterIds.map((id) => all.find((f) => f.id === id)?.criteria).filter((c): c is Criteria => !!c)
    const values: Criteria = {}
    for (const [field, p] of Object.entries(LIST_PARAMS)) {
      const v = params.get(p)?.split(',').filter(Boolean)
      if (v?.length) (values as Record<string, string[]>)[field] = v
    }
    const text = params.get('q')
    if (text) values.text = text
    return [...list, values]
  }, [params, board.data]) // eslint-disable-line react-hooks/exhaustive-deps

  // FQL quick filters are evaluated by the server: ids of the matching board tickets.
  const fqls = [...new Set(criteria.map((c) => c.fql).filter((f): f is string => !!f))]
  const fqlResults = useQueries({
    queries: fqls.map((fql) => ({
      queryKey: ['board', boardId, 'match', fql],
      queryFn: () => api<string[]>(`/boards/${boardId}/match?fql=${encodeURIComponent(fql)}`),
    })),
  })
  const fqlMatches = new Map(fqls.flatMap((f, i) => (fqlResults[i]?.data ? [[f, new Set(fqlResults[i].data)] as const] : [])))

  const move = useMutation({
    mutationFn: (body: { ticketKey: string; statusId: string | null; aboveKey: string | null; belowKey: string | null; version: number
      lane?: { field: string; value: string | null } | null } & ScreenValues) =>
      api(`/boards/${boardId}/move`, { body }),
    onError: (e) => setToast(t('Déplacement refusé : {reason}', { reason: (e as Error).message })),
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ['board', boardId, 'cards'] })
      void qc.invalidateQueries({ queryKey: ['ticket'] })
    },
  })

  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 6 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 250, tolerance: 8 } }),
    // Space picks a card up (Enter is kept for opening it, spec §4.16).
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
      keyboardCodes: { start: ['Space'], cancel: ['Escape'], end: ['Space', 'Enter'] },
    }),
  )

  // Keyboard navigation (spec §4.16) and keyboard alternative to drag and drop (spec §10).
  const kb = useRef<{ moveToColumn: (key: string, delta: number) => Promise<void> } | null>(null)
  useShortcuts({
    j: () => focusSibling(1),
    k: () => focusSibling(-1),
    n: () => focusColumn(1),
    p: () => focusColumn(-1),
    o: () => { const k = focusedCardKey(); if (k) setParam('selected', k) },
    'shift+n': () => { const k = focusedCardKey(); if (k) void kb.current?.moveToColumn(k, 1) },
    'shift+p': () => { const k = focusedCardKey(); if (k) void kb.current?.moveToColumn(k, -1) },
  })

  /** Version of the card as last loaded; the keyboard path first waits for a refresh in flight. */
  const latestVersion = async (key: string, fallback: number, waitRefresh: boolean) => {
    const cardsKey = ['board', boardId, 'cards']
    if (waitRefresh && qc.isFetching({ queryKey: cardsKey })) await qc.refetchQueries({ queryKey: cardsKey, exact: true })
    return qc.getQueryData<BoardCards>(cardsKey)?.cards.find((c) => c.key === key)?.version ?? fallback
  }

  // A card being dragged is never moved by a remote update (spec §4.10): the drag works on a frozen copy.
  const [frozen, setFrozen] = useState<BoardCards | null>(null)

  useEffect(() => {
    if (!toast) return
    const t = setTimeout(() => setToast(null), 5000)
    return () => clearTimeout(t)
  }, [toast])

  if (board.error || data.error) return <div className="p-6"><ErrorText error={board.error ?? data.error} /></div>
  if (!board.data || !(frozen ?? data.data)) return <div className="p-6 text-sm text-slate-500">{t('Chargement du board…')}</div>

  const b = board.data
  const cardsData = (frozen ?? data.data)!
  const allCards = cardsData.cards
  const visible = allCards.filter((c) => criteria.every((cr) => matches(c, cr, me.id, Date.now(), fqlMatches)))
  const laneNames = b.swimlaneQueries.map((q) => q.name)
  const lanes = computeLanes(visible, b.swimlaneMode, laneNames)
  const columnOf = new Map<string, Column>()
  b.columns.forEach((col) => col.statuses.forEach((s) => columnOf.set(s.id, col)))
  const perms = new Set(b.myPermissions)
  const canDrag = perms.has('TRANSITION') || perms.has('EDIT')
  const look = (c: Card): CardLook => ({
    accent: cardColor(c, b.cardColorMode),
    days: b.cardFields.includes('daysInColumn') ? workingDaysSince(c.statusSince, b.workingDays) : undefined,
    labelColors: cardsData.labelColors,
  })
  const cellId = (col: Column, lane: Lane) => `${col.id}|${lane.id}`
  const cellCards = (col: Column, lane: Lane) =>
    visible.filter((c) => columnOf.get(c.statusId)?.id === col.id && (b.swimlaneMode === 'NONE' || laneOf(c, b.swimlaneMode, laneNames).id === lane.id))

  kb.current = {
    moveToColumn: async (key, delta) => {
      const card = allCards.find((c) => c.key === key)
      const from = card ? b.columns.findIndex((c) => c.id === columnOf.get(card.statusId)?.id) : -1
      const target = b.columns[from + delta]
      if (!card || from < 0 || !target?.statuses[0]) return
      if (!perms.has('TRANSITION')) return setToast(t('Vous ne pouvez pas changer le statut dans cet espace'))
      const way = pickTransition(await fetchTransitions(qc, key), target.statuses.map((s) => s.id))
      if (!way) return setToast(t('Aucune transition autorisée vers « {column} »', { column: target.name }))
      let values: ScreenValues = {}
      if (needsScreen(way)) {
        const answered = await ask(key, way)
        if (!answered) return
        values = answered
      }
      const statusId = way.to.id
      const version = await latestVersion(key, card.version, true)
      qc.setQueryData<BoardCards>(['board', boardId, 'cards'], (old) => old && {
        ...old, cards: old.cards.map((c) => (c.key === key ? { ...c, statusId } : c)),
      })
      move.mutate({ ticketKey: key, statusId, aboveKey: null, belowKey: null, version, ...values })
      setToast(t('{key} déplacé dans « {column} »', { key, column: target.name }))
      setTimeout(() => document.querySelector<HTMLElement>(`[data-card="${key}"]`)?.focus(), 50)
    },
  }

  const onDragStart = (e: DragStartEvent) => {
    const key = String(e.active.id)
    setDragKey(key)
    setFrozen(data.data ?? null)
    setReachable(null)
    fetchTransitions(qc, key).then((list) => setReachable(new Set(list.map((x) => x.to.id))), () => {})
  }
  const onDragEnd = async (e: DragEndEvent) => {
    setDragKey(null)
    setFrozen(null)
    setReachable(null)
    const card = allCards.find((c) => c.key === e.active.id)
    const targetCell = e.over?.data.current?.cell as string | undefined
    if (!card || !targetCell) return
    const [colId, laneId] = targetCell.split('|')
    const col = b.columns.find((c) => c.id === colId)!
    const lane = lanes.find((l) => l.id === laneId) ?? lanes[0]
    const pickedStatus = e.over?.data.current?.statusId as string | undefined
    const lc = laneChange(b.swimlaneMode, laneOf(card, b.swimlaneMode, laneNames), lane)
    if (lc && !perms.has('EDIT')) return setToast(t('Vous ne pouvez pas modifier les tickets de cet espace'))
    const keys = cellCards(col, lane).map((c) => c.key)
    const others = keys.filter((k) => k !== card.key)
    let index = others.length
    if (e.over && e.over.id !== targetCell && !pickedStatus) {
      index = others.indexOf(String(e.over.id))
      const sameCell = keys.includes(card.key)
      if (sameCell && keys.indexOf(card.key) < keys.indexOf(String(e.over.id))) index += 1 // dragging down
      if (index < 0) index = others.length
    }
    const { aboveKey, belowKey } = neighbours(keys, card.key, index)
    const statusChanges = columnOf.get(card.statusId)?.id !== col.id
    if (statusChanges && !perms.has('TRANSITION')) return setToast(t('Vous ne pouvez pas changer le statut dans cet espace'))
    if (!statusChanges && !lc && keys[index] === card.key) return
    if (statusChanges && b.wipBlocking && b.wipMode !== 'OFF' && col.wipMax != null && wipCount(allCards, col, b.wipMode) >= col.wipMax) {
      return setToast(t('Limite WIP atteinte dans « {column} » ({max} max.)', { column: col.name, max: col.wipMax }))
    }
    let statusId: string | null = null
    let values: ScreenValues = {}
    if (statusChanges) {
      const way = pickTransition(await fetchTransitions(qc, card.key), col.statuses.map((s) => s.id), pickedStatus)
      if (!way) return setToast(t('Aucune transition autorisée vers « {column} »', { column: col.name }))
      if (needsScreen(way)) {
        const answered = await ask(card.key, way) // cancelling leaves the card where it was
        if (!answered) return
        values = answered
      }
      statusId = way.to.id
    }
    // Optimistic: move the card in the list right away; the server answer refreshes it.
    qc.setQueryData<BoardCards>(['board', boardId, 'cards'], (old) => {
      if (!old) return old
      const rest = old.cards.filter((c) => c.key !== card.key)
      const moved = { ...card, statusId: statusId ?? card.statusId }
      const at = aboveKey ? rest.findIndex((c) => c.key === aboveKey) + 1 : belowKey ? rest.findIndex((c) => c.key === belowKey) : rest.length
      rest.splice(at, 0, moved)
      return { ...old, cards: rest }
    })
    move.mutate({ ticketKey: card.key, statusId, aboveKey, belowKey, version: await latestVersion(card.key, card.version, false), lane: lc, ...values })
  }

  /**
   * « + » under a cell's cards (ADD_FUNCTIONALITIES A.3): the ticket is created with the swimlane's field, then brought
   * to the cell's column through an allowed transition.
   */
  const quickAdd = (col: Column, lane: Lane) => async (summary: string) => {
    const body: Record<string, unknown> = { summary }
    const lc = laneChange(b.swimlaneMode, { id: '', label: '' }, lane)
    if (lc?.value) body[{ ASSIGNEE: 'assigneeId', PRIORITY: 'priorityId', PARENT: 'parent' }[lc.field]!] = lc.value
    const created = await api<{ key: string; version: number; status: { id: string } }>(`/spaces/${b.spaceKey}/tickets`, { body })
    if (!col.statuses.some((s) => s.id === created.status.id)) {
      const way = pickTransition(await fetchTransitions(qc, created.key), col.statuses.map((s) => s.id))
      if (!way) setToast(t('{key} créé, mais aucune transition autorisée vers « {column} »', { key: created.key, column: col.name }))
      else {
        let values: ScreenValues = {}
        if (needsScreen(way)) values = (await ask(created.key, way)) ?? {}
        await api(`/tickets/${created.key}/transition`, { body: { statusId: way.to.id, ...values } }).catch((e: Error) => setToast(e.message))
      }
    }
    void qc.invalidateQueries({ queryKey: ['board', boardId, 'cards'] })
  }

  const dragged = dragKey ? allCards.find((c) => c.key === dragKey) : null
  const draggedColumn = dragged ? columnOf.get(dragged.statusId)?.id : undefined
  const toggleColumn = (id: string) => {
    const next = collapsed.includes(id) ? collapsed.filter((x) => x !== id) : [...collapsed, id]
    setCollapsed(next)
    localStorageSet(`fluxo.collapsed.${boardId}`, JSON.stringify(next))
  }
  const gridCols = { gridTemplateColumns: b.columns.map((c) => (collapsed.includes(c.id) ? '2.75rem' : 'minmax(16rem, 1fr)')).join(' ') }

  return (
    <div className="flex h-[calc(100vh-5.75rem)]">
      <div className="flex min-w-0 flex-1 flex-col">
        <Toolbar board={b} cards={allCards} params={params} setParam={setParam} activeFilterIds={activeFilterIds} />
        {cardsData.unmappedCount > 0 && perms.has('MANAGE_BOARD') && (
          <div className="mx-4 mb-2 rounded-md bg-amber-50 px-3 py-1.5 text-sm text-amber-900 dark:bg-amber-950 dark:text-amber-100">
            {t('{n} ticket(s) masqué(s) : statut non mappé ({statuses}).', { n: cardsData.unmappedCount, statuses: b.unmappedStatuses.map((s) => s.name).join(', ') })}{' '}
            <Link className="underline" to={`/spaces/${b.spaceKey}/boards/${b.id}/settings`}>{t('Configurer les colonnes')}</Link>
          </div>
        )}
        <DndContext sensors={sensors} collisionDetection={collisions} onDragStart={onDragStart} onDragEnd={onDragEnd}
          onDragCancel={() => { setDragKey(null); setFrozen(null) }}
          accessibility={{ announcements: {
            onDragStart: ({ active }) => t('{key} saisi.', { key: String(active.id) }),
            onDragOver: ({ over }) => over ? t('Au-dessus de la colonne {column}.', { column: columnName(b, over.data.current?.cell) }) : '',
            onDragEnd: ({ over }) => over ? t('Déposé dans {column}.', { column: columnName(b, over.data.current?.cell) }) : t('Déposé hors du board.'),
            onDragCancel: () => t('Déplacement annulé.'),
          } }}>
          <div className="flex-1 overflow-auto px-4 pb-4">
            <div className="grid min-w-max gap-3" style={gridCols}>
              {b.columns.map((col) => {
                const count = wipCount(allCards, col, b.wipMode)
                const state = wipState(count, col, b.wipMode)
                if (collapsed.includes(col.id)) {
                  return (
                    <button key={col.id} onClick={() => toggleColumn(col.id)} aria-expanded={false} title={t('Déplier « {column} »', { column: col.name })}
                      className={`sticky top-0 z-10 rounded-t-md border-t-4 bg-slate-100 py-1.5 text-xs font-semibold text-slate-600 dark:bg-slate-900 dark:text-slate-300 ${state === 'over' ? 'border-red-500' : categoryBorder(col)}`}>
                      ▸ {count}
                    </button>
                  )
                }
                return (
                  <div key={col.id} className={`sticky top-0 z-10 flex items-center gap-2 rounded-t-md border-t-4 bg-slate-100 px-2 py-1.5 text-xs font-semibold uppercase tracking-wide text-slate-600 dark:bg-slate-900 dark:text-slate-300 ${
                    state === 'over' ? 'border-red-500 bg-red-100 dark:bg-red-950' : state === 'under' ? 'border-amber-400 bg-amber-50 dark:bg-amber-950' : categoryBorder(col)}`}>
                    <button onClick={() => toggleColumn(col.id)} aria-expanded title={t('Replier la colonne')} className="text-slate-500 hover:text-slate-900 dark:hover:text-slate-100">▾</button>
                    <span className="truncate">{col.name}</span>
                    <span className={`ml-auto font-normal ${state === 'over' ? 'text-red-700 dark:text-red-300' : ''}`}
                      title={col.wipMax != null || col.wipMin != null ? `Min ${col.wipMin ?? '–'} / Max ${col.wipMax ?? '–'}` : undefined}>
                      {count}{col.wipMax != null ? `/${col.wipMax}` : ''}{col.wipMin != null ? ` (min ${col.wipMin})` : ''}
                    </span>
                  </div>
                )
              })}
              {lanes.map((lane) => (
                <LaneRows key={lane.id} lane={lane} board={b} cellCards={cellCards} cellId={cellId} collapsed={collapsed}
                  canDrag={canDrag} selected={selected} onOpen={(k) => setParam('selected', k)} look={look}
                  onMenu={(card, x, y) => setMenu({ card, x, y })} onAdd={perms.has('CREATE') ? (col, ln) => quickAdd(col, ln) : undefined}
                  zonesFor={dragged ? (col) => {
                    const ok = col.statuses.filter((s) => !reachable || reachable.has(s.id))
                    return col.statuses.length > 1 && ok.length > 0 && col.id !== draggedColumn ? ok : []
                  } : undefined}
                  unreachable={(col) => !!dragged && !!reachable && col.id !== draggedColumn && !col.statuses.some((s) => reachable.has(s.id))} />
              ))}
            </div>
            {visible.length === 0 && allCards.length > 0 && (
              <p className="mt-6 text-center text-sm text-slate-500">{t('Aucun ticket ne correspond aux filtres.')}</p>
            )}
            {allCards.length === 0 && (
              <p className="mt-6 text-center text-sm text-slate-500">{t('Board vide. Créez un ticket avec')} <kbd className="rounded border px-1">C</kbd>.</p>
            )}
          </div>
          <DragOverlay>{dragged ? <CardBody card={dragged} fields={b.cardFields} dragging {...look(dragged)} /> : null}</DragOverlay>
        </DndContext>
      </div>
      {selected && <TicketDrawer ticketKey={selected} onClose={() => setParam('selected', null)} />}
      {screen}
      {menu && (
        <CardMenu card={menu.card} x={menu.x} y={menu.y} boardId={boardId} canEdit={perms.has('EDIT')} canDelete={perms.has('DELETE')}
          canCreate={perms.has('CREATE')} onClose={() => setMenu(null)} onOpen={(k) => setParam('selected', k)} onToast={setToast} ask={ask} />
      )}
      {toast && (
        <div role="status" className="fixed bottom-4 left-1/2 z-50 -translate-x-1/2 rounded-md bg-slate-900 px-4 py-2 text-sm text-white shadow-lg">
          {toast}
        </div>
      )}
    </div>
  )
}

function columnName(b: BoardView, cell: unknown) {
  return b.columns.find((c) => c.id === String(cell ?? '').split('|')[0])?.name ?? ''
}

function categoryBorder(col: Column) {
  const cat = col.statuses[0]?.category
  return cat === 'DONE' ? 'border-green-500' : cat === 'IN_PROGRESS' ? 'border-blue-500' : 'border-slate-400'
}

function LaneRows({ lane, board, cellCards, cellId, canDrag, selected, onOpen, look, collapsed: collapsedColumns, zonesFor, unreachable, onMenu, onAdd }: {
  lane: Lane; board: BoardView; cellCards: (c: Column, l: Lane) => Card[]; cellId: (c: Column, l: Lane) => string
  canDrag: boolean; selected: string | null; onOpen: (k: string) => void; look: (c: Card) => CardLook
  collapsed: string[]; zonesFor?: (c: Column) => StatusRef[]; unreachable: (c: Column) => boolean
  onMenu: (card: Card, x: number, y: number) => void; onAdd?: (c: Column, l: Lane) => (summary: string) => Promise<void>
}) {
  const [collapsed, setCollapsed] = useState(false)
  const total = board.columns.reduce((n, col) => n + cellCards(col, lane).length, 0)
  return (
    <>
      {board.swimlaneMode !== 'NONE' && (
        <button onClick={() => setCollapsed(!collapsed)} aria-expanded={!collapsed}
          className="col-span-full mt-2 flex items-center gap-2 text-left text-sm font-medium" style={{ gridColumn: '1 / -1' }}>
          <span className="text-slate-500">{collapsed ? '▸' : '▾'}</span> {lane.label}
          <span className="text-xs font-normal text-slate-500">{t('{n} ticket(s)', { n: total })}</span>
        </button>
      )}
      {!collapsed && board.columns.map((col) => (
        <Cell key={col.id} id={cellId(col, lane)} cards={cellCards(col, lane)} fields={board.cardFields} look={look}
          canDrag={canDrag} selected={selected} onOpen={onOpen} narrow={collapsedColumns.includes(col.id)} zones={zonesFor?.(col) ?? []}
          unreachable={unreachable(col)} onMenu={onMenu} onAdd={onAdd?.(col, lane)} />
      ))}
    </>
  )
}

function Cell({ id, cards, fields, canDrag, selected, onOpen, look, narrow, zones, unreachable, onMenu, onAdd }: {
  id: string; cards: Card[]; fields: string[]; canDrag: boolean; selected: string | null; onOpen: (k: string) => void
  look: (c: Card) => CardLook; narrow: boolean; zones: StatusRef[]; unreachable: boolean
  onMenu: (card: Card, x: number, y: number) => void; onAdd?: (summary: string) => Promise<void>
}) {
  const { setNodeRef, isOver } = useDroppable({ id, data: { cell: id } })
  if (narrow) {
    // Collapsed column: no cards, but still a drop target.
    return <div ref={setNodeRef} data-cell={id} title={t('{n} ticket(s)', { n: cards.length })}
      className={`min-h-16 rounded-b-md bg-slate-50 dark:bg-slate-900/50 ${isOver ? 'bg-indigo-50 dark:bg-indigo-950' : ''}`} />
  }
  return (
    <SortableContext id={id} items={cards.map((c) => c.key)} strategy={verticalListSortingStrategy}>
      <div ref={setNodeRef} data-cell={id} aria-disabled={unreachable || undefined}
        className={`relative min-h-16 space-y-2 rounded-b-md bg-slate-50 p-1.5 transition-opacity dark:bg-slate-900/50 ${unreachable ? 'opacity-40' : isOver ? 'bg-indigo-50 dark:bg-indigo-950' : ''}`}>
        {cards.map((c) => (
          <SortableCard key={c.key} card={c} cell={id} fields={fields} disabled={!canDrag} selected={selected === c.key} onOpen={onOpen} look={look(c)} onMenu={onMenu} />
        ))}
        {onAdd && <QuickAdd onAdd={onAdd} />}
        {zones.length > 0 && (
          <div className="absolute inset-0 z-20 flex flex-col gap-1 p-1">
            {zones.map((s) => <StatusZone key={s.id} cell={id} status={s} />)}
          </div>
        )}
      </div>
    </SortableContext>
  )
}

/** One drop zone per status when a column groups several statuses. */
function StatusZone({ cell, status }: { cell: string; status: StatusRef }) {
  const { setNodeRef, isOver } = useDroppable({ id: `zone|${cell}|${status.id}`, data: { cell, statusId: status.id } })
  return (
    <div ref={setNodeRef} className={`flex flex-1 items-center justify-center rounded-md border-2 border-dashed text-sm font-medium ${
      isOver ? 'border-indigo-500 bg-indigo-100 text-indigo-800 dark:bg-indigo-950 dark:text-indigo-200' : 'border-slate-300 bg-white/90 text-slate-600 dark:border-slate-600 dark:bg-slate-900/90 dark:text-slate-300'}`}>
      {status.name}
    </div>
  )
}

export function TicketDrawer({ ticketKey, onClose }: { ticketKey: string; onClose: () => void }) {
  const [width, setWidth] = useState(() => Number(localStorageGet('fluxo.drawerWidth')) || 640)
  const dragging = useRef(false)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !document.querySelector('dialog[open]')) onClose() }
    const onMove = (e: MouseEvent) => {
      if (!dragging.current) return
      setWidth(Math.min(Math.max(window.innerWidth - e.clientX, 380), window.innerWidth - 200))
    }
    const onUp = () => { if (dragging.current) { dragging.current = false; localStorageSet('fluxo.drawerWidth', String(width)) } }
    window.addEventListener('keydown', onKey)
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
  }, [onClose, width])
  return (
    <aside style={{ width }} className="relative shrink-0 border-l border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-950" aria-label={`Ticket ${ticketKey}`}>
      <div role="separator" aria-orientation="vertical" title={t('Redimensionner')}
        onMouseDown={() => { dragging.current = true }}
        className="absolute left-0 top-0 h-full w-1.5 -translate-x-1/2 cursor-col-resize hover:bg-indigo-300" />
      <TicketView key={ticketKey} ticketKey={ticketKey} onClose={onClose} />
    </aside>
  )
}

function Toolbar({ board, cards, params, setParam, activeFilterIds }: {
  board: BoardView; cards: Card[]; params: URLSearchParams; setParam: (n: string, v: string | null) => void; activeFilterIds: string[]
}) {
  const [text, setText] = useState(params.get('q') ?? '')
  useEffect(() => {
    // Only when the text really changed: a no-op write right after mount would navigate back here if a link was just clicked.
    if ((text.trim() || null) === params.get('q')) return
    const t = setTimeout(() => setParam('q', text.trim() || null), 150)
    return () => clearTimeout(t)
  }, [text]) // eslint-disable-line react-hooks/exhaustive-deps

  const toggle = (id: string) => {
    const next = activeFilterIds.includes(id) ? activeFilterIds.filter((x) => x !== id) : [...activeFilterIds, id]
    setParam('qf', next.join(',') || null)
  }
  const options = useMemo(() => {
    const uniq = <T,>(list: T[], key: (x: T) => string) => [...new Map(list.map((x) => [key(x), x])).values()]
    return {
      assignee: [{ value: 'none', label: t('Non assigné') },
        ...uniq(cards.flatMap((c) => c.assignee ? [c.assignee] : []), (u) => u.id).map((u) => ({ value: u.id, label: u.displayName }))],
      types: uniq(cards.map((c) => c.type), (t) => t.id).map((t) => ({ value: t.id, label: t.name })),
      priorities: uniq(cards.map((c) => c.priority), (p) => p.id).sort((a, b) => a.rank - b.rank).map((p) => ({ value: p.id, label: p.name })),
      labels: uniq(cards.flatMap((c) => c.labels), (l) => l.toLowerCase()).sort((a, b) => a.localeCompare(b)).map((l) => ({ value: l, label: l })),
      parents: uniq(cards.flatMap((c) => c.parent && c.parent.type.hierarchyLevel === 1 ? [c.parent] : []), (p) => p.key)
        .map((p) => ({ value: p.key, label: `${p.key} ${p.summary}` })),
    }
  }, [cards])
  const anyActive = activeFilterIds.length > 0 || Object.values(LIST_PARAMS).some((p) => params.get(p)) || !!params.get('q')

  return (
    <div className="flex flex-wrap items-center gap-2 px-4 py-2">
      <input value={text} onChange={(e) => setText(e.target.value)} placeholder={t('Filtrer les cartes…')} aria-label={t('Filtrer les cartes')}
        className="w-48 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900" />
      {[...PREDEFINED, ...board.quickFilters].map((f) => (
        <button key={f.id} onClick={() => toggle(f.id)} aria-pressed={activeFilterIds.includes(f.id)}
          className={`rounded-full border px-2.5 py-0.5 text-sm ${activeFilterIds.includes(f.id) ? 'border-indigo-600 bg-indigo-600 text-white' : 'border-slate-300 hover:bg-slate-100 dark:border-slate-700 dark:hover:bg-slate-800'}`}>
          {f.name}
        </button>
      ))}
      <ValueFilter label={t('Assigné')} param={LIST_PARAMS.assignee} options={options.assignee} params={params} setParam={setParam} />
      <ValueFilter label={t('Type')} param={LIST_PARAMS.types} options={options.types} params={params} setParam={setParam} />
      <ValueFilter label={t('Priorité')} param={LIST_PARAMS.priorities} options={options.priorities} params={params} setParam={setParam} />
      {options.labels.length > 0 && <ValueFilter label={t('Label')} param={LIST_PARAMS.labels} options={options.labels} params={params} setParam={setParam} />}
      {options.parents.length > 0 && <ValueFilter label={t('Epic')} param={LIST_PARAMS.parents} options={options.parents} params={params} setParam={setParam} />}
      {anyActive && (
        <button className="text-sm text-slate-500 hover:underline" onClick={() => {
          setText('')
          ;['qf', 'q', ...Object.values(LIST_PARAMS)].forEach((p) => setParam(p, null))
        }}>{t('Effacer les filtres')}</button>
      )}
      <span className="ml-auto" />
      <BoardSwitcher board={board} />
      {board.myPermissions.includes('MANAGE_VERSIONS') && <ReleaseButton board={board} />}
      <Link to={`/spaces/${board.spaceKey}/boards/${board.id}/cfd`} className="text-sm text-slate-600 hover:underline dark:text-slate-300">{t('Flux cumulé')}</Link>
      {board.backlogEnabled && (
        <Link to={`/spaces/${board.spaceKey}/boards/${board.id}/backlog`} className="text-sm text-indigo-600 hover:underline">{t('Backlog')}</Link>
      )}
      {board.myPermissions.includes('MANAGE_BOARD') && (
        <Link to={`/spaces/${board.spaceKey}/boards/${board.id}/settings`} className="text-sm text-slate-500 hover:underline">{t('⚙ Configurer le board')}</Link>
      )}
    </div>
  )
}

function ValueFilter({ label, param, options, params, setParam }: {
  label: string; param: string; options: { value: string; label: string }[]; params: URLSearchParams; setParam: (n: string, v: string | null) => void
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const selected = params.get(param)?.split(',').filter(Boolean) ?? []
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])
  return (
    <div ref={ref} className="relative">
      <button onClick={() => setOpen(!open)} aria-expanded={open}
        className={`rounded-md border px-2 py-0.5 text-sm ${selected.length ? 'border-indigo-600 text-indigo-700 dark:text-indigo-300' : 'border-slate-300 dark:border-slate-700'}`}>
        {label}{selected.length ? ` (${selected.length})` : ''} ▾
      </button>
      {open && (
        <ul className="absolute z-30 mt-1 max-h-72 w-60 overflow-y-auto rounded-md border border-slate-200 bg-white p-1 text-sm shadow-lg dark:border-slate-700 dark:bg-slate-900">
          {options.map((o) => (
            <li key={o.value}>
              <label className="flex cursor-pointer items-center gap-2 rounded px-2 py-1 hover:bg-slate-100 dark:hover:bg-slate-800">
                <input type="checkbox" checked={selected.includes(o.value)} onChange={() => {
                  const next = selected.includes(o.value) ? selected.filter((v) => v !== o.value) : [...selected, o.value]
                  setParam(param, next.join(',') || null)
                }} />
                <span className="truncate">{o.label}</span>
              </label>
            </li>
          ))}
          {options.length === 0 && <li className="px-2 py-1 text-slate-500">{t('Aucune valeur')}</li>}
        </ul>
      )}
    </div>
  )
}

// --- Keyboard helpers: they work on the rendered cards, so filters and swimlanes are respected. ---

function focusedCard() {
  return (document.activeElement?.closest('[data-card]') ?? document.querySelector('[data-card][aria-current="true"]')) as HTMLElement | null
}

function focusedCardKey() {
  return focusedCard()?.dataset.card ?? null
}

function focusSibling(delta: number) {
  const current = focusedCard()
  if (!current) return document.querySelector<HTMLElement>('[data-card]')?.focus()
  const cards = [...(current.closest('[data-cell]')?.querySelectorAll<HTMLElement>('[data-card]') ?? [])]
  cards[cards.indexOf(current) + delta]?.focus()
}

function focusColumn(delta: number) {
  const current = focusedCard()
  if (!current) return document.querySelector<HTMLElement>('[data-card]')?.focus()
  const cell = current.closest<HTMLElement>('[data-cell]')!
  const lane = cell.dataset.cell!.split('|')[1]
  const cells = [...document.querySelectorAll<HTMLElement>('[data-cell]')].filter((c) => c.dataset.cell!.endsWith('|' + lane))
  const index = [...cell.querySelectorAll('[data-card]')].indexOf(current)
  for (let i = cells.indexOf(cell) + delta; i >= 0 && i < cells.length; i += delta) {
    const cards = cells[i].querySelectorAll<HTMLElement>('[data-card]')
    if (cards.length) return cards[Math.min(index, cards.length - 1)].focus()
  }
}

/** Boards of the space (several per space, spec §4.10). */
function BoardSwitcher({ board }: { board: BoardView }) {
  const { key } = useParams()
  const space = useSpace(key ?? board.spaceKey).data
  const navigate = useNavigate()
  if (!space || space.boards.length < 2) return null
  return (
    <select aria-label={t('Board')} value={board.id} onChange={(e) => navigate(`/spaces/${space.key}/boards/${e.target.value}`)}
      className="rounded-md border border-slate-300 bg-white px-2 py-0.5 text-sm dark:border-slate-700 dark:bg-slate-900">
      {space.boards.map((x) => <option key={x.id} value={x.id}>{x.name}{x.isDefault ? ` ${t('(par défaut)')}` : ''}</option>)}
    </select>
  )
}

/** « Publier une version » (spec §4.10): the done column's tickets get the version, which is released. */
function ReleaseButton({ board }: { board: BoardView }) {
  const qc = useQueryClient()
  const dialog = useRef<HTMLDialogElement>(null)
  const versions = useVersions(board.spaceKey).data?.filter((v) => v.status === 'UNRELEASED') ?? []
  const [choice, setChoice] = useState('')
  const [name, setName] = useState('')
  const [result, setResult] = useState<number | null>(null)
  const release = useMutation({
    mutationFn: () => api<{ tickets: number }>(`/boards/${board.id}/release`, { body: choice ? { versionId: choice } : { name } }),
    onSuccess: (r) => {
      setResult(r.tickets)
      void qc.invalidateQueries({ queryKey: ['board', board.id] })
      void qc.invalidateQueries({ queryKey: ['space', board.spaceKey, 'versions'] })
    },
  })
  const done = board.columns.at(-1)
  return (
    <>
      <button className="text-sm text-slate-600 hover:underline dark:text-slate-300" onClick={() => { setResult(null); dialog.current?.showModal() }}>{t('Publier une version')}</button>
      <dialog ref={dialog} aria-label={t('Publier une version')}
        className="w-full max-w-md rounded-lg bg-white p-0 text-slate-900 shadow-xl backdrop:bg-slate-900/40 dark:bg-slate-900 dark:text-slate-100">
        <form className="space-y-3 p-5" onSubmit={(e) => { e.preventDefault(); release.mutate() }}>
          <h2 className="text-lg font-semibold">{t('Publier une version')}</h2>
          {result != null ? (
            <p role="status">{t('{n} ticket(s) publié(s) et retiré(s) du board.', { n: result })}</p>
          ) : (
            <>
              <p className="text-sm text-slate-500">{t('Les tickets de la colonne « {column} » reçoivent la version, qui est publiée ; ils quittent le board.', { column: done?.name ?? '' })}</p>
              <select aria-label={t('Version')} value={choice} onChange={(e) => setChoice(e.target.value)}
                className="w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900">
                <option value="">{t('Nouvelle version…')}</option>
                {versions.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
              </select>
              {!choice && <input required aria-label={t('Nom de la nouvelle version')} placeholder={t('ex. 1.4.0')} value={name} onChange={(e) => setName(e.target.value)}
                className="w-full rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900" />}
              <ErrorText error={release.error} />
            </>
          )}
          <div className="flex justify-end gap-2">
            <button type="button" className="rounded-md px-3 py-1.5 text-sm hover:bg-slate-100 dark:hover:bg-slate-800" onClick={() => dialog.current?.close()}>{result != null ? t('Fermer') : t('Annuler')}</button>
            {result == null && <button type="submit" disabled={release.isPending} className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-500">{t('Publier')}</button>}
          </div>
        </form>
      </dialog>
    </>
  )
}

function QuickAdd({ onAdd }: { onAdd: (summary: string) => Promise<void> }) {
  const [open, setOpen] = useState(false)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  if (!open) {
    return (
      <button onClick={() => setOpen(true)} title={t('Ajouter une carte ici')} aria-label={t('Ajouter une carte ici')}
        className="w-full rounded-md border border-dashed border-transparent py-0.5 text-sm text-slate-500 hover:border-slate-300 hover:text-slate-800 dark:hover:border-slate-600 dark:hover:text-slate-200">+</button>
    )
  }
  return (
    <form onSubmit={async (e) => {
      e.preventDefault()
      if (!text.trim() || busy) return
      setBusy(true)
      try { await onAdd(text.trim()); setText('') } finally { setBusy(false) } // stays open for the next card
    }}>
      <textarea autoFocus rows={2} value={text} disabled={busy} aria-label={t('Résumé de la nouvelle carte')} placeholder={t('Résumé… (Entrée pour créer, Échap pour fermer)')}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') { e.stopPropagation(); setOpen(false) }
          if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.form?.requestSubmit() }
        }}
        onBlur={() => { if (!text.trim()) setOpen(false) }}
        className="w-full rounded-md border border-indigo-400 bg-white p-2 text-sm dark:bg-slate-900" />
    </form>
  )
}
