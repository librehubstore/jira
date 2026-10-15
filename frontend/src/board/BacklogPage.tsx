import { useEffect, useState } from 'react'
import {
  closestCorners, DndContext, KeyboardSensor, MouseSensor, TouchSensor, useDroppable, useSensor, useSensors, type DragEndEvent,
} from '@dnd-kit/core'
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router'
import { api } from '../api'
import { useShortcuts } from '../keyboard'
import { Avatar, TypeIcon } from '../components/Badges'
import { useAssignable, useMeta } from '../tickets'
import { ErrorText } from '../ui'
import { t } from '../i18n'
import { TicketDrawer, type BoardCards, type BoardView } from './BoardPage'
import { BulkBar, useSelection } from '../components/Bulk'
import { fetchTransitions, needsScreen, pickTransition, useTransitionScreen, type ScreenValues } from '../components/Transitions'
import { neighbours, type Card, type Column } from './model'

/**
 * Kanban backlog (spec §4.10): the board's first column (« Backlog ») and second column (« Sélectionné ») as a list.
 * Drag to order, drag to « Sélectionné » to transition, inline creation and editing, epic panel as a filter (E).
 */
export function BacklogPage() {
  const { boardId } = useParams()
  const qc = useQueryClient()
  const [params, setParams] = useSearchParams()
  const [epics, setEpics] = useState(true)
  const [toast, setToast] = useState<string | null>(null)
  const [screen, ask] = useTransitionScreen()
  const selection = useSelection()
  const board = useQuery({ queryKey: ['board', boardId], queryFn: () => api<BoardView>(`/boards/${boardId}`) })
  const cardsKey = ['board', boardId, 'cards', 'backlog']
  const data = useQuery({ queryKey: cardsKey, queryFn: () => api<BoardCards>(`/boards/${boardId}/cards?backlog=true`) })
  const selected = params.get('selected')
  const epicFilter = params.get('e')
  const setParam = (name: string, value: string | null) => setParams((prev) => {
    const p = new URLSearchParams(prev)
    if (value) p.set(name, value)
    else p.delete(name)
    return p
  }, { replace: true })
  useShortcuts({ e: () => setEpics((v) => !v) })
  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(null), 5000)
    return () => clearTimeout(timer)
  }, [toast])

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['board', boardId, 'cards'] })
    void qc.invalidateQueries({ queryKey: ['ticket'] })
  }
  const move = useMutation({
    mutationFn: (body: { ticketKey: string; statusId: string | null; aboveKey: string | null; belowKey: string | null; version: number } & ScreenValues) =>
      api(`/boards/${boardId}/move`, { body }),
    onError: (e) => setToast(t('Déplacement refusé : {reason}', { reason: (e as Error).message })),
    onSettled: refresh,
  })
  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 6 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 250, tolerance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  if (board.error || data.error) return <div className="p-6"><ErrorText error={board.error ?? data.error} /></div>
  if (!board.data || !data.data) return <div className="p-6 text-sm text-slate-500">{t('Chargement du backlog…')}</div>
  const b = board.data
  if (!b.backlogEnabled) return <p className="p-6 text-slate-500">{t('Le backlog n’est pas activé sur ce board.')}</p>
  const sections = b.columns.slice(0, 2)
  const sectionOf = (c: Card) => sections.findIndex((col) => col.statuses.some((s) => s.id === c.statusId))
  const all = data.data.cards
  const shown = epicFilter ? all.filter((c) => c.parent?.key === epicFilter || c.key === epicFilter) : all
  const epicList = [...new Map(all.flatMap((c) => (c.parent && c.parent.type.hierarchyLevel === 1 ? [[c.parent.key, c.parent] as const] : []))).values()]

  const onDragEnd = async (e: DragEndEvent) => {
    const card = all.find((c) => c.key === e.active.id)
    const target = e.over?.data.current?.section as number | undefined
    if (!card || target == null) return
    const keys = shown.filter((c) => sectionOf(c) === target).map((c) => c.key)
    const others = keys.filter((k) => k !== card.key)
    let index = others.length
    if (e.over && !String(e.over.id).startsWith('section|')) {
      index = others.indexOf(String(e.over.id))
      if (keys.includes(card.key) && keys.indexOf(card.key) < keys.indexOf(String(e.over.id))) index += 1
      if (index < 0) index = others.length
    }
    const changes = sectionOf(card) !== target
    if (!changes && keys[index] === card.key) return
    let statusId: string | null = null
    let values: ScreenValues = {}
    if (changes) {
      const way = pickTransition(await fetchTransitions(qc, card.key), sections[target].statuses.map((s) => s.id))
      if (!way) return setToast(t('Aucune transition autorisée vers « {column} »', { column: sections[target].name }))
      if (needsScreen(way)) {
        const answered = await ask(card.key, way)
        if (!answered) return
        values = answered
      }
      statusId = way.to.id
    }
    const { aboveKey, belowKey } = neighbours(keys, card.key, index)
    qc.setQueryData<BoardCards>(cardsKey, (old) => {
      if (!old) return old
      const rest = old.cards.filter((c) => c.key !== card.key)
      const at = aboveKey ? rest.findIndex((c) => c.key === aboveKey) + 1 : belowKey ? rest.findIndex((c) => c.key === belowKey) : rest.length
      rest.splice(at, 0, { ...card, statusId: statusId ?? card.statusId })
      return { ...old, cards: rest }
    })
    move.mutate({ ticketKey: card.key, statusId, aboveKey, belowKey, version: card.version, ...values })
  }

  return (
    <div className="flex h-[calc(100vh-5.75rem)]">
      {epics && (
        <aside className="w-60 shrink-0 overflow-y-auto border-r border-slate-200 p-3 text-sm dark:border-slate-800" aria-label={t('Epics')}>
          <h2 className="mb-2 font-semibold">{t('Epics')} <span className="text-xs font-normal text-slate-500">(E)</span></h2>
          <button onClick={() => setParam('e', null)} aria-pressed={!epicFilter}
            className={`mb-1 block w-full rounded px-2 py-1 text-left ${!epicFilter ? 'bg-indigo-50 font-medium dark:bg-indigo-950' : 'hover:bg-slate-100 dark:hover:bg-slate-800'}`}>{t('Tous les tickets')}</button>
          {epicList.map((ep) => (
            <button key={ep.key} onClick={() => setParam('e', ep.key)} aria-pressed={epicFilter === ep.key}
              className={`mb-1 block w-full truncate rounded px-2 py-1 text-left ${epicFilter === ep.key ? 'bg-indigo-50 font-medium dark:bg-indigo-950' : 'hover:bg-slate-100 dark:hover:bg-slate-800'}`}>
              <span className="font-mono text-xs text-slate-500">{ep.key}</span> {ep.summary}
            </button>
          ))}
          {epicList.length === 0 && <p className="text-slate-500">{t('Aucun epic')}</p>}
        </aside>
      )}
      <div className="min-w-0 flex-1 overflow-y-auto p-4">
        <div className="mb-3 flex items-center gap-3">
          <h1 className="text-lg font-semibold">{t('Backlog · {name}', { name: b.name })}</h1>
          <Link to={`/spaces/${b.spaceKey}/boards/${b.id}`} className="ml-auto text-sm text-indigo-600 hover:underline">{t('← Retour au board')}</Link>
        </div>
        <div className="mb-2"><BulkBar keys={[...selection.selected]} onClear={selection.clear} /></div>
        <DndContext sensors={sensors} collisionDetection={closestCorners} onDragEnd={onDragEnd}>
          {sections.map((col, i) => (
            <Section key={col.id} index={i} column={col} board={b} cards={shown.filter((c) => sectionOf(c) === i)}
              onOpen={(k) => setParam('selected', k)} onChanged={refresh} onError={setToast}
              isSelected={(k) => selection.selected.has(k)}
              onSelect={(k, shift) => selection.toggle(shown.map((c) => c.key), k, shift)} />
          ))}
        </DndContext>
      </div>
      {selected && <TicketDrawer ticketKey={selected} onClose={() => setParam('selected', null)} />}
      {screen}
      {toast && (
        <div role="status" className="fixed bottom-4 left-1/2 z-50 -translate-x-1/2 rounded-md bg-slate-900 px-4 py-2 text-sm text-white shadow-lg">
          {toast} <button className="ml-2 underline" onClick={() => setToast(null)}>{t('Fermer')}</button>
        </div>
      )}
    </div>
  )
}

function Section({ index, column, board, cards, onOpen, onChanged, onError, isSelected, onSelect }: {
  index: number; column: Column; board: BoardView; cards: Card[]; onOpen: (k: string) => void
  onChanged: () => void; onError: (m: string) => void; isSelected: (k: string) => boolean; onSelect: (k: string, shift: boolean) => void
}) {
  const { setNodeRef, isOver } = useDroppable({ id: `section|${index}`, data: { section: index } })
  const [summary, setSummary] = useState('')
  const create = useMutation({
    mutationFn: () => api(`/spaces/${board.spaceKey}/tickets`, { body: { summary: summary.trim() } }),
    onSuccess: () => { setSummary(''); onChanged() }, // the field stays focused: « créer un autre »
    onError: (e) => onError((e as Error).message),
  })
  const canCreate = index === 0 && board.myPermissions.includes('CREATE')
  return (
    <section className="mb-6" aria-label={column.name}>
      <h2 className="mb-1 text-sm font-semibold uppercase tracking-wide text-slate-600 dark:text-slate-300">
        {index === 0 ? t('Backlog') : column.name} <span className="font-normal text-slate-500">({cards.length})</span>
      </h2>
      <SortableContext id={`section|${index}`} items={cards.map((c) => c.key)} strategy={verticalListSortingStrategy}>
        <ul ref={setNodeRef} className={`min-h-12 divide-y divide-slate-100 rounded-md border border-slate-200 dark:divide-slate-800 dark:border-slate-800 ${isOver ? 'bg-indigo-50 dark:bg-indigo-950' : ''}`}>
          {cards.map((c) => <Row key={c.key} card={c} section={index} board={board} onOpen={onOpen} onChanged={onChanged} onError={onError}
            selected={isSelected(c.key)} onSelect={(shift) => onSelect(c.key, shift)} />)}
          {cards.length === 0 && <li className="px-3 py-3 text-sm text-slate-500">{t('Glissez des tickets ici.')}</li>}
        </ul>
      </SortableContext>
      {canCreate && (
        <form className="mt-1" onSubmit={(e) => { e.preventDefault(); if (summary.trim()) create.mutate() }}>
          <input value={summary} onChange={(e) => setSummary(e.target.value)} disabled={create.isPending}
            placeholder={t('+ Créer un ticket (Entrée)')} aria-label={t('Résumé du nouveau ticket')}
            className="w-full rounded-md border border-dashed border-slate-300 bg-transparent px-3 py-1.5 text-sm dark:border-slate-700" />
        </form>
      )}
    </section>
  )
}

function Row({ card, section, board, onOpen, onChanged, onError, selected, onSelect }: {
  card: Card; section: number; board: BoardView; onOpen: (k: string) => void; onChanged: () => void; onError: (m: string) => void
  selected: boolean; onSelect: (shift: boolean) => void
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: card.key, data: { section } })
  const meta = useMeta().data
  const assignable = useAssignable(card.key.split('-')[0]).data ?? []
  const [editing, setEditing] = useState(false)
  const canEdit = board.myPermissions.includes('EDIT')
  const save = useMutation({
    mutationFn: (body: Record<string, unknown>) => api(`/tickets/${card.key}`, { method: 'PATCH', body: { ...body, version: card.version } }),
    onSuccess: onChanged,
    onError: (e) => { onError((e as Error).message); onChanged() },
  })
  const select = 'rounded border border-transparent bg-transparent px-1 py-0.5 text-xs hover:border-slate-300 dark:hover:border-slate-700'
  return (
    <li ref={setNodeRef} style={{ transform: CSS.Translate.toString(transform), transition }}
      className={`flex items-center gap-2 bg-white px-2 py-1.5 text-sm dark:bg-slate-950 ${isDragging ? 'opacity-40' : ''}`}>
      <input type="checkbox" aria-label={t('Sélectionner {key}', { key: card.key })} checked={selected}
        onChange={(e) => onSelect((e.nativeEvent as MouseEvent).shiftKey)} />
      <button {...attributes} {...listeners} aria-label={t('Déplacer {key}', { key: card.key })} className="cursor-grab px-1 text-slate-500 hover:text-slate-800 dark:hover:text-slate-200">⠿</button>
      <TypeIcon type={card.type} />
      <button onClick={() => onOpen(card.key)} className="font-mono text-xs text-slate-500 hover:underline">{card.key}</button>
      {editing ? (
        <input autoFocus defaultValue={card.summary} aria-label={t('Résumé')}
          onBlur={(e) => { setEditing(false); if (e.target.value.trim() && e.target.value !== card.summary) save.mutate({ summary: e.target.value.trim() }) }}
          onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); if (e.key === 'Escape') setEditing(false) }}
          className="min-w-0 flex-1 rounded border border-slate-300 px-1 dark:border-slate-700 dark:bg-slate-900" />
      ) : (
        <button disabled={!canEdit} onClick={() => setEditing(true)} title={canEdit ? t('Modifier le résumé') : undefined}
          className="min-w-0 flex-1 truncate text-left">{card.summary}</button>
      )}
      {card.parent && <span className="hidden truncate rounded bg-violet-100 px-1.5 text-xs text-violet-800 sm:inline dark:bg-violet-950 dark:text-violet-200">{card.parent.key}</span>}
      <input type="number" min={0} step="any" disabled={!canEdit} defaultValue={card.estimate ?? ''} aria-label={t('Estimation')} placeholder="–"
        onBlur={(e) => { const v = e.target.value === '' ? null : Number(e.target.value); if (v !== card.estimate) save.mutate({ estimate: v }) }}
        className="w-14 rounded border border-transparent bg-transparent px-1 text-right text-xs hover:border-slate-300 dark:hover:border-slate-700" />
      <select aria-label={t('Priorité')} disabled={!canEdit} value={card.priority.id} className={select}
        onChange={(e) => save.mutate({ priorityId: e.target.value })}>
        {meta?.priorities.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
      </select>
      <select aria-label={t('Assigné')} disabled={!canEdit} value={card.assignee?.id ?? ''} className={`${select} max-w-36`}
        onChange={(e) => { const v = e.target.value; save.mutate({ assigneeId: v || null }) }}>
        <option value="">{t('Non assigné')}</option>
        {card.assignee && !assignable.some((u) => u.id === card.assignee!.id) && <option value={card.assignee.id}>{card.assignee.displayName}</option>}
        {assignable.map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
      </select>
      <Avatar user={card.assignee} size={5} />
    </li>
  )
}
