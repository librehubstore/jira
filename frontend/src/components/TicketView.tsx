import { Fragment, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router'
import { api } from '../api'
import { useMe } from '../auth'
import { can, useSpace, type SpaceDetail } from '../spaces'
import { patchTicket, useAssignable, useComponents, useMeta, useTicket, useTicketMutation, useVersions, type TicketDetail } from '../tickets'
import { useShortcuts } from '../keyboard'
import { Button, ErrorText } from '../ui'
import { Avatar, PriorityIcon, StatusBadge, TypeIcon } from './Badges'
import { FieldRow, InlineText, MultiPicker, selectClass } from './Inline'
import { Editor } from './Editor'
import { Markdown } from './Markdown'
import { Activity, Attachments, TicketLinks, uploadAttachment, useWatchers, WatchersButton } from './TicketCollab'
import { needsScreen, transitionLabel, useTransitions, useTransitionScreen } from './Transitions'
import { CustomFieldInput, fieldLabel, useLayout, type LayoutField } from './CustomFields'
import { t as tr } from '../i18n'

/** Full ticket view, used both as a page and as the board side panel. */
export function TicketView({ ticketKey, onClose }: { ticketKey: string; onClose?: () => void }) {
  const ticket = useTicket(ticketKey)
  const space = useSpace(ticket.data?.spaceKey)
  if (ticket.error) return <div className="p-6"><ErrorText error={ticket.error} /></div>
  if (!ticket.data || !space.data) return <div className="p-6 text-sm text-slate-500">{tr('Chargement…')}</div>
  return <TicketBody t={ticket.data} space={space.data} onClose={onClose} />
}

function TicketBody({ t, space, onClose }: { t: TicketDetail; space: SpaceDetail; onClose?: () => void }) {
  const m = useTicketMutation(t.key)
  const meta = useMeta().data
  const assignable = useAssignable(t.spaceKey).data ?? []
  const transitions = useTransitions(t.key).data ?? []
  const components = useComponents(t.spaceKey).data
  const versions = useVersions(t.spaceKey).data
  const layout = useLayout(t.spaceKey, t.type.id).data
  const [screen, ask] = useTransitionScreen()
  const me = useMe().data!
  const navigate = useNavigate()
  const qc = useQueryClient()
  const edit = can(t, 'EDIT')
  const save = (body: Record<string, unknown>) => m.mutate(() => patchTicket(t, body))
  const toggleWatch = useWatchers(t).toggle.mutate // stable across renders

  // Ticket shortcuts (spec §4.16). Inside the board drawer they apply to the open ticket.
  const click = (sel: string) => document.querySelector<HTMLElement>(sel)?.click()
  useShortcuts({
    w: () => toggleWatch(),
    m: () => {
      const box = document.querySelector<HTMLElement>('#comment-composer .ProseMirror, #comment-composer textarea')
      box?.scrollIntoView({ block: 'center' })
      box?.focus()
    },
    e: () => click('[data-hk="summary"]'),
    l: () => click('[data-hk="labels"]'),
    a: () => document.querySelector<HTMLSelectElement>(`select[aria-label="${tr('Assigné')}"]`)?.focus(),
    i: () => { if (edit && assignable.some((u) => u.id === me.id)) save({ assigneeId: me.id }) },
  })

  const remove = async () => {
    const hasSub = t.children.some((c) => c.type.hierarchyLevel === -1)
    const msg = hasSub
      ? tr('Supprimer {key} et ses {n} sous-tâche(s) ? Action définitive.', { key: t.key, n: t.children.filter((c) => c.type.hierarchyLevel === -1).length })
      : tr('Supprimer définitivement {key} ?', { key: t.key })
    if (!window.confirm(msg)) return
    m.mutate(async () => {
      await api(`/tickets/${t.key}?withSubtasks=${hasSub}`, { method: 'DELETE' })
      qc.removeQueries({ queryKey: ['ticket', t.key] })
      if (onClose) onClose()
      else void navigate(`/spaces/${t.spaceKey}`)
    })
  }

  // Optional fields, shown and ordered per the space's field configuration (spec §4.14).
  const optional: Record<string, ReactNode> = {
    labels: (
      <FieldRow label={tr('Labels')}>
            <InlineText value={t.labels.join(' ')} disabled={!edit} placeholder={tr('Aucun')} hk="labels"
              onSave={(v) => save({ labels: v.split(/[\s,]+/).filter(Boolean) })} />
          </FieldRow>
    ),
    componentIds: (
      <FieldRow label={tr('Composants')}>
            <MultiPicker label={tr('Ajouter un composant')} value={t.components} disabled={!edit}
              options={(components ?? []).filter((c) => !c.archived)} onChange={(ids) => save({ componentIds: ids })} />
          </FieldRow>
    ),
    fixVersionIds: (
      <FieldRow label={tr('Versions')}>
            <MultiPicker label={tr('Ajouter une version')} value={t.fixVersions} disabled={!edit}
              options={(versions ?? []).filter((v) => v.status !== 'ARCHIVED')} onChange={(ids) => save({ fixVersionIds: ids })} />
          </FieldRow>
    ),
    parent: (
      <FieldRow label={tr('Parent')}>
            <InlineText value={t.parent?.key ?? ''} disabled={!edit || t.type.hierarchyLevel === 1} placeholder={tr('Aucun')}
              onSave={(v) => save({ parent: v ? v.toUpperCase() : null })} />
          </FieldRow>
    ),
    dueDate: (
      <FieldRow label={tr('Échéance')}>
            <input type="date" value={t.dueDate ?? ''} disabled={!edit} className={selectClass}
              onChange={(e) => save({ dueDate: e.target.value || null })} />
          </FieldRow>
    ),
    startDate: (
      <FieldRow label={tr('Début')}>
            <input type="date" value={t.startDate ?? ''} disabled={!edit} className={selectClass}
              onChange={(e) => save({ startDate: e.target.value || null })} />
          </FieldRow>
    ),
    estimate: (
      <FieldRow label={tr('Estimation')}>
            <InlineText value={t.estimate?.toString() ?? ''} disabled={!edit} placeholder="—"
              onSave={(v) => save({ estimate: v ? Number(v.replace(',', '.')) : null })} />
          </FieldRow>
    ),
  }
  return (
    <article className="@container flex h-full flex-col">
      <header className="flex items-center gap-2 border-b border-slate-200 px-5 py-2 text-sm dark:border-slate-800">
        <Link to={`/spaces/${t.spaceKey}`} className="text-slate-500 hover:underline">{t.spaceName}</Link>
        {t.parent && (
          <>
            <span className="text-slate-500">/</span>
            <Link to={`/browse/${t.parent.key}`} className="flex items-center gap-1 text-slate-500 hover:underline">
              <TypeIcon type={t.parent.type} /> {t.parent.key}
            </Link>
          </>
        )}
        <span className="text-slate-500">/</span>
        <Link to={`/browse/${t.key}`} className="flex items-center gap-1 font-medium hover:underline">
          <TypeIcon type={t.type} /> {t.key}
        </Link>
        <button title={tr('Copier le lien')} className="text-slate-500 hover:text-slate-700"
          onClick={() => navigator.clipboard.writeText(`${location.origin}/browse/${t.key}`)}>⧉</button>
        <div className="ml-auto flex items-center gap-1">
          <WatchersButton t={t} />
          {edit && (
            <Button variant="ghost" onClick={() => save({ flagged: !t.flagged })}>
              {t.flagged ? tr('⚑ Retirer le signalement') : tr('⚐ Signaler')}
            </Button>
          )}
          {edit && <MoveButton t={t} />}
          {edit && <Button variant="ghost" onClick={() => save({ archived: !t.archived })}>{t.archived ? tr('Désarchiver') : tr('Archiver')}</Button>}
          {can(t, 'CREATE') && <CloneButton t={t} />}
          <a href={`/api/tickets/${t.key}/export.json`} className="rounded-md px-2 py-1 text-sm hover:bg-slate-100 dark:hover:bg-slate-800">{tr('Exporter (JSON)')}</a>
          {can(t, 'DELETE') && <Button variant="ghost" className="text-red-600" onClick={remove}>{tr('Supprimer')}</Button>}
          {onClose && <Button variant="ghost" aria-label={tr('Fermer')} onClick={onClose}>✕</Button>}
        </div>
      </header>

      {m.error && (
        <div className="flex items-center gap-2 bg-red-50 px-5 py-2 dark:bg-red-950">
          <ErrorText error={m.error} />
          {(m.error as { code?: string }).code === 'TICKET_CONFLICT' && <span className="text-sm">{tr('La version à jour a été rechargée.')}</span>}
        </div>
      )}
      {t.flagged && <div className="bg-amber-100 px-5 py-1 text-sm text-amber-900 dark:bg-amber-900 dark:text-amber-100">{tr('⚑ Ticket signalé')}</div>}

      <div className="grid flex-1 gap-6 overflow-y-auto p-5 @3xl:grid-cols-[1fr_18rem]">
        <div className="min-w-0 space-y-5">
          <InlineText value={t.summary} disabled={!edit} hk="summary" onSave={(v) => save({ summary: v })}
            className="text-xl font-semibold" inputClassName="text-xl font-semibold" />
          <Description t={t} edit={edit} onSave={(d) => save({ description: d })} />
          <Attachments t={t} />
          <Children t={t} space={space} />
          <TicketLinks t={t} />
          <Activity t={t} />
        </div>

        <aside className="order-first space-y-1 @3xl:order-none">
          <div className="mb-3">
            {/* Only the transitions allowed for this user and ticket (spec §4.9). */}
            <select aria-label={tr('Statut')} value="" disabled={!can(t, 'TRANSITION') || transitions.length === 0}
              onChange={async (e) => {
                // Read now: the controlled select resets before the deferred mutation runs.
                const way = transitions[Number(e.target.value)]
                if (!way) return
                let values = {}
                if (needsScreen(way)) {
                  const answered = await ask(t.key, way)
                  if (!answered) return
                  values = answered
                }
                m.mutate(() => api<TicketDetail>(`/tickets/${t.key}/transition`, { body: { statusId: way.to.id, version: t.version, ...values } }))
              }}
              className="rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm font-medium dark:border-slate-700 dark:bg-slate-900">
              <option value="">{t.status.name}</option>
              {transitions.map((way, i) => <option key={`${way.id}-${way.to.id}`} value={i}>{transitionLabel(way)}</option>)}
            </select>
            {screen}
            {t.resolution && <span className="ml-2 text-sm text-slate-500">{t.resolution.name}</span>}
          </div>
          <FieldRow label={tr('Assigné')}>
            <div className="flex items-center gap-1">
              <Avatar user={t.assignee} size={5} />
              <select aria-label={tr('Assigné')} value={t.assignee?.id ?? ''} disabled={!edit} className={selectClass}
                onChange={(e) => save({ assigneeId: e.target.value || null })}>
                <option value="">{tr('Non assigné')}</option>
                {t.assignee && !assignable.some((u) => u.id === t.assignee!.id) && <option value={t.assignee.id}>{t.assignee.displayName}</option>}
                {assignable.map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
              </select>
            </div>
            {edit && t.assignee?.id !== me.id && assignable.some((u) => u.id === me.id) && (
              <button className="text-xs text-indigo-600 hover:underline" onClick={() => save({ assigneeId: me.id })}>{tr('M’assigner')}</button>
            )}
          </FieldRow>
          <FieldRow label={tr('Rapporteur')}>
            <span className="flex items-center gap-1"><Avatar user={t.reporter} size={5} /> {t.reporter.displayName}</span>
          </FieldRow>
          <FieldRow label={tr('Priorité')}>
            <div className="flex items-center gap-1">
              <PriorityIcon priority={t.priority} />
              <select aria-label={tr('Priorité')} value={t.priority.id} disabled={!edit} className={selectClass}
                onChange={(e) => save({ priorityId: e.target.value })}>
                {meta?.priorities.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </div>
          </FieldRow>
          <FieldRow label={tr('Type')}>
            <select aria-label={tr('Type')} value={t.type.id} disabled={!edit} className={selectClass}
              onChange={(e) => save({ typeId: e.target.value })}>
              {space.ticketTypes.map((ty) => <option key={ty.id} value={ty.id}>{ty.name}</option>)}
            </select>
          </FieldRow>
          <FieldRow label={tr('Collaborateurs')}>
            <MultiPicker label={tr('Ajouter un collaborateur')} value={t.collaborators.map((u) => ({ id: u.id, name: u.displayName }))} disabled={!edit}
              options={assignable.filter((u) => u.id !== t.assignee?.id).map((u) => ({ id: u.id, name: u.displayName }))}
              onChange={(ids) => save({ collaboratorIds: ids })} />
          </FieldRow>
          {(layout ?? DEFAULT_LAYOUT).filter((l) => l.visible && (l.field in optional || l.field.startsWith('cf:'))).map((l) => l.field.startsWith('cf:') ? (
            <FieldRow key={l.field} label={fieldLabel(l)}>
              <CustomFieldInput field={l} value={t.customFields[l.field.slice(3)]} disabled={!edit}
                onSave={(v) => save({ customFields: { [l.field.slice(3)]: v } })} />
            </FieldRow>
          ) : <Fragment key={l.field}>{optional[l.field]}</Fragment>)}
          <div className="space-y-0.5 border-t border-slate-200 pt-3 text-xs text-slate-500 dark:border-slate-800">
            <p>{tr('Créé le {date} par {name}', { date: new Date(t.createdAt).toLocaleString(), name: t.creator.displayName })}</p>
            <p>{tr('Mis à jour le {date}', { date: new Date(t.updatedAt).toLocaleString() })}</p>
            {t.resolvedAt && <p>{tr('Résolu le {date}', { date: new Date(t.resolvedAt).toLocaleString() })}</p>}
          </div>
        </aside>
      </div>
    </article>
  )
}

function Description({ t, edit, onSave }: { t: TicketDetail; edit: boolean; onSave: (d: string | null) => void }) {
  const [editing, setEditing] = useState(false)
  const mentionables = useAssignable(t.spaceKey).data ?? []
  return (
    <section>
      <h3 className="mb-1 text-sm font-semibold">{tr('Description')}</h3>
      {editing ? (
        <Editor initial={t.description ?? ''} autoFocus mentionables={mentionables}
          uploadImage={async (f) => `/api/attachments/${(await uploadAttachment(t.key, f)).id}`}
          onCancel={() => setEditing(false)}
          onSubmit={(d) => { onSave(d.trim() || null); setEditing(false) }} />
      ) : (
        <div role={edit ? 'button' : undefined} tabIndex={edit ? 0 : undefined}
          onClick={(e) => { if (edit && !(e.target as HTMLElement).closest('a')) setEditing(true) }}
          onKeyDown={(e) => { if (edit && e.key === 'Enter') setEditing(true) }}
          className={`min-h-10 rounded p-1 -m-1 ${edit ? 'cursor-text hover:bg-slate-50 dark:hover:bg-slate-900' : ''}`}>
          {t.description ? <Markdown text={t.description} /> : <span className="text-sm text-slate-500">{tr('Ajouter une description…')}</span>}
        </div>
      )}
    </section>
  )
}

function Children({ t, space }: { t: TicketDetail; space: SpaceDetail }) {
  const m = useTicketMutation(t.key)
  const [summary, setSummary] = useState('')
  if (t.type.hierarchyLevel === -1) return null
  const childType = t.type.hierarchyLevel === 1 ? null : space.ticketTypes.find((ty) => ty.hierarchyLevel === -1)
  const pct = t.progress.total ? Math.round((100 * t.progress.done) / t.progress.total) : 0
  return (
    <section>
      <div className="mb-1 flex items-center gap-2">
        <h3 className="text-sm font-semibold">{t.type.hierarchyLevel === 1 ? tr('Tickets de l’epic') : tr('Sous-tâches')}</h3>
        {t.progress.total > 0 && (
          <>
            <div className="h-1.5 w-32 overflow-hidden rounded bg-slate-200 dark:bg-slate-800" aria-hidden>
              <div className="h-full bg-green-500" style={{ width: `${pct}%` }} />
            </div>
            <span className="text-xs text-slate-500">{t.progress.done}/{t.progress.total} – {pct} %</span>
          </>
        )}
      </div>
      <ul className="divide-y divide-slate-100 rounded border border-slate-200 dark:divide-slate-800 dark:border-slate-800">
        {t.children.map((c) => (
          <li key={c.id} className="flex items-center gap-2 px-2 py-1.5 text-sm">
            <TypeIcon type={c.type} />
            <Link to={`/browse/${c.key}`} className="text-slate-500 hover:underline">{c.key}</Link>
            <span className={`flex-1 truncate ${c.status.category === 'DONE' ? 'text-slate-500 line-through' : ''}`}>{c.summary}</span>
            <PriorityIcon priority={c.priority} />
            <Avatar user={c.assignee} size={5} />
            <StatusBadge status={c.status} />
          </li>
        ))}
        {can(space, 'CREATE') && (
          <li className="px-2 py-1">
            <form onSubmit={(e) => {
              e.preventDefault()
              if (!summary.trim()) return
              m.mutate(() => api(`/spaces/${t.spaceKey}/tickets`, {
                body: { summary, parent: t.key, ...(childType ? { typeId: childType.id } : {}) },
              }))
              setSummary('')
            }}>
              <input value={summary} onChange={(e) => setSummary(e.target.value)}
                placeholder={t.type.hierarchyLevel === 1 ? tr('+ Ajouter un ticket à l’epic') : tr('+ Ajouter une sous-tâche')}
                className="w-full bg-transparent py-0.5 text-sm outline-none placeholder:text-slate-500" />
            </form>
          </li>
        )}
      </ul>
    </section>
  )
}

function MoveButton({ t }: { t: TicketDetail }) {
  const navigate = useNavigate()
  const m = useTicketMutation(t.key)
  if (t.type.hierarchyLevel === -1) return null
  const move = async () => {
    const target = window.prompt(tr('Clé de l’espace de destination :'))?.trim().toUpperCase()
    if (!target) return
    m.mutate(async () => {
      const p = await api<{ newKeyPrefix: string; lostData: string[]; subtasksMoved: string[] }>(
        `/tickets/${t.key}/move-preview?spaceKey=${encodeURIComponent(target)}`)
      const details = [
        ...p.lostData,
        ...(p.subtasksMoved.length ? [tr('Sous-tâches déplacées : {keys}', { keys: p.subtasksMoved.join(', ') })] : []),
      ]
      if (!window.confirm(tr('Déplacer {key} vers {space} ?', { key: t.key, space: p.newKeyPrefix }) + '\n' + (details.join('\n') || tr('Aucune perte de données.')))) return
      const moved = await api<TicketDetail>(`/tickets/${t.key}/move`, { body: { spaceKey: target } })
      void navigate(`/browse/${moved.key}`)
      return moved
    })
  }
  return <Button variant="ghost" onClick={move}>{tr('Déplacer')}</Button>
}

/** Clone with options (spec §4.3): attachments, links, subtasks, comments. */
function CloneButton({ t }: { t: TicketDetail }) {
  const navigate = useNavigate()
  const dialog = useRef<HTMLDialogElement>(null)
  const [opts, setOpts] = useState({ attachments: true, links: false, subtasks: true, comments: false })
  const m = useTicketMutation(t.key)
  const labels: Record<keyof typeof opts, string> = {
    attachments: tr('Pièces jointes'), links: tr('Liens'), subtasks: tr('Sous-tâches'), comments: tr('Commentaires'),
  }
  return (
    <>
      <Button variant="ghost" onClick={() => dialog.current?.showModal()}>{tr('Cloner')}</Button>
      <dialog ref={dialog} aria-label={tr('Cloner {key}', { key: t.key })}
        className="w-full max-w-sm rounded-lg bg-white p-0 text-slate-900 shadow-xl backdrop:bg-slate-900/40 dark:bg-slate-900 dark:text-slate-100">
        <form method="dialog" className="space-y-3 p-5" onSubmit={(e) => {
          e.preventDefault()
          m.mutate(async () => {
            const copy = await api<TicketDetail>(`/tickets/${t.key}/clone`, { body: opts })
            dialog.current?.close()
            void navigate(`/browse/${copy.key}`)
            return copy
          })
        }}>
          <h2 className="text-lg font-semibold">{tr('Cloner {key}', { key: t.key })}</h2>
          <p className="text-sm text-slate-500">{tr('La copie s’appelle « COPIE – … » et garde un lien « clone » vers l’original.')}</p>
          {(Object.keys(opts) as (keyof typeof opts)[]).map((k) => (
            <label key={k} className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={opts[k]} onChange={(e) => setOpts({ ...opts, [k]: e.target.checked })} /> {labels[k]}
            </label>
          ))}
          <ErrorText error={m.error} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => dialog.current?.close()}>{tr('Annuler')}</Button>
            <Button type="submit" disabled={m.isPending}>{tr('Cloner')}</Button>
          </div>
        </form>
      </dialog>
    </>
  )
}

const DEFAULT_LAYOUT: LayoutField[] = ['labels', 'componentIds', 'fixVersionIds', 'parent', 'dueDate', 'startDate', 'estimate']
  .map((field) => ({ field, name: null, kind: 'SYSTEM', visible: true, required: false, defaultValue: null, options: [] }))
