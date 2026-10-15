import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api } from '../api'
import { useMe } from '../auth'
import { can, useDirectory, useSpace, type UserRef } from '../spaces'
import { FIELD_LABELS, useAssignable, type HistoryView, type TicketDetail, type TicketRef } from '../tickets'
import { Button, ErrorText } from '../ui'
import { Avatar, StatusBadge, TypeIcon } from './Badges'
import { Editor } from './Editor'
import { Markdown } from './Markdown'
import { ScreenCaptureButton } from './ScreenCapture'
import { t as tr } from '../i18n'

type Reaction = { emoji: string; count: number; mine: boolean }
type CommentView = {
  visibilityRoleId: string | null
  id: string; author: UserRef; body: string; createdAt: string; editedAt: string | null
  reactions: Reaction[]; canEdit: boolean; canDelete: boolean
}
type AttachmentView = {
  id: string; filename: string; mime: string; size: number; hasThumbnail: boolean; inline: boolean
  uploader: UserRef; createdAt: string; canDelete: boolean
}
type LinkView = { id: string; linkTypeId: string; label: string; restricted: boolean; ticket: TicketRef | null }
type LinksView = { links: LinkView[]; webLinks: { id: string; url: string; title: string }[] }
type LinkType = { id: string; name: string; outward: string; inward: string }

const EMOJIS = ['👍', '👎', '❤️', '🎉', '😄', '😕', '👀', '🚀']

export async function uploadAttachment(ticketKey: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  return api<AttachmentView>(`/tickets/${ticketKey}/attachments`, { body: form })
}

function useInvalidate(t: TicketDetail) {
  const qc = useQueryClient()
  return () => qc.invalidateQueries({ queryKey: ['ticket', t.key] })
}

// --- Attachments -----------------------------------------------------------------

export function Attachments({ t }: { t: TicketDetail }) {
  const invalidate = useInvalidate(t)
  const input = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const list = useQuery({ queryKey: ['ticket', t.key, 'attachments'], queryFn: () => api<AttachmentView[]>(`/tickets/${t.key}/attachments`) })
  const act = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn(), onSuccess: invalidate })
  const upload = (files: FileList | null) => {
    if (!files) return
    act.mutate(() => Promise.all([...files].map((f) => uploadAttachment(t.key, f))))
  }
  const canAdd = can(t, 'ATTACH_ADD')
  if (!canAdd && !list.data?.length) return null
  return (
    <section onDragOver={(e) => { if (canAdd) { e.preventDefault(); setDragging(true) } }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => { e.preventDefault(); setDragging(false); if (canAdd) upload(e.dataTransfer.files) }}
      className={`rounded-md ${dragging ? 'outline-2 outline-dashed outline-indigo-400' : ''}`}>
      <div className="mb-1 flex items-center gap-2">
        <h3 className="text-sm font-semibold">{tr('Pièces jointes')} {list.data?.length ? `(${list.data.length})` : ''}</h3>
        {canAdd && (
          <>
            <button className="text-xs text-indigo-600 hover:underline" onClick={() => input.current?.click()}>{tr('+ Ajouter')}</button>
            <input ref={input} type="file" multiple hidden onChange={(e) => { upload(e.target.files); e.target.value = '' }} />
            <span className="text-xs text-slate-500">{tr('ou glissez des fichiers ici')}</span>
            <span className="ml-auto"><ScreenCaptureButton onCapture={(file) => act.mutateAsync(() => uploadAttachment(t.key, file))} /></span>
          </>
        )}
      </div>
      <ErrorText error={act.error} />
      <ul className="flex flex-wrap gap-2">
        {list.data?.map((a) => (
          <li key={a.id} className="group relative w-36 overflow-hidden rounded border border-slate-200 text-xs dark:border-slate-800">
            <a href={`/api/attachments/${a.id}`} target={a.inline ? '_blank' : undefined} rel="noreferrer" className="block">
              {a.hasThumbnail
                ? <img src={`/api/attachments/${a.id}?thumbnail=true`} alt="" className="h-20 w-full object-cover" />
                : <div className="flex h-20 items-center justify-center bg-slate-50 text-2xl text-slate-500 dark:bg-slate-900">📄</div>}
              <div className="truncate px-1.5 pt-1 font-medium" title={a.filename}>{a.filename}</div>
              <div className="px-1.5 pb-1 text-slate-500">{formatSize(a.size)}</div>
            </a>
            {a.canDelete && (
              <button aria-label={tr('Renommer {name}', { name: a.filename })} className="text-slate-500 hover:text-slate-800 dark:hover:text-slate-200"
                onClick={() => {
                  const name = window.prompt(tr('Nouveau nom du fichier :'), a.filename)?.trim()
                  if (name && name !== a.filename) act.mutate(() => api(`/attachments/${a.id}`, { method: 'PATCH', body: { filename: name } }))
                }}>✎</button>
            )}
            {a.canDelete && (
              <button aria-label={tr('Supprimer {name}', { name: a.filename })}
                onClick={() => { if (window.confirm(tr('Supprimer {name} ?', { name: a.filename }))) act.mutate(() => api(`/attachments/${a.id}`, { method: 'DELETE' })) }}
                className="absolute right-1 top-1 hidden rounded bg-white/90 px-1 text-red-600 group-hover:block dark:bg-slate-900/90">✕</button>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}

function formatSize(n: number) {
  return n < 1024 ? `${n} o` : n < 1024 * 1024 ? `${(n / 1024).toFixed(0)} Ko` : `${(n / 1024 / 1024).toFixed(1)} Mo`
}

// --- Links -----------------------------------------------------------------------

export function TicketLinks({ t }: { t: TicketDetail }) {
  const invalidate = useInvalidate(t)
  const qc = useQueryClient()
  const links = useQuery({ queryKey: ['ticket', t.key, 'links'], queryFn: () => api<LinksView>(`/tickets/${t.key}/links`) })
  const types = useQuery({ queryKey: ['link-types'], queryFn: () => api<LinkType[]>('/link-types'), staleTime: Infinity })
  const [adding, setAdding] = useState<null | 'ticket' | 'web'>(null)
  const [form, setForm] = useState({ choice: '', key: '', url: '', title: '' })
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => { void invalidate(); void qc.invalidateQueries({ queryKey: ['board'] }); setAdding(null) },
  })
  const canLink = can(t, 'LINK')
  const choices = types.data?.flatMap((ty) => ty.outward === ty.inward
    ? [{ value: `${ty.id}:out`, label: ty.outward }]
    : [{ value: `${ty.id}:out`, label: ty.outward }, { value: `${ty.id}:in`, label: ty.inward }]) ?? []
  const empty = !links.data?.links.length && !links.data?.webLinks.length
  if (!canLink && empty) return null
  return (
    <section>
      <div className="mb-1 flex items-center gap-2">
        <h3 className="text-sm font-semibold">{tr('Liens')}</h3>
        {canLink && (
          <>
            <button className="text-xs text-indigo-600 hover:underline" onClick={() => setAdding('ticket')}>{tr('+ Lier un ticket')}</button>
            <button className="text-xs text-indigo-600 hover:underline" onClick={() => setAdding('web')}>{tr('+ Lien web')}</button>
          </>
        )}
      </div>
      {adding === 'ticket' && (
        <form className="mb-2 flex flex-wrap gap-2" onSubmit={(e) => {
          e.preventDefault()
          const [linkTypeId, dir] = (form.choice || choices[0]?.value || '').split(':')
          act.mutate(() => api(`/tickets/${t.key}/links`, { body: { linkTypeId, ticketKey: form.key.trim().toUpperCase(), inward: dir === 'in' } }))
        }}>
          <select value={form.choice} onChange={(e) => setForm({ ...form, choice: e.target.value })}
            className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900">
            {choices.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
          </select>
          <input autoFocus placeholder={tr('Clé, ex. PAY-12')} value={form.key} onChange={(e) => setForm({ ...form, key: e.target.value })}
            className="w-36 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900" />
          <Button type="submit" disabled={!form.key.trim()}>{tr('Lier')}</Button>
          <Button type="button" variant="secondary" onClick={() => setAdding(null)}>{tr('Annuler')}</Button>
        </form>
      )}
      {adding === 'web' && (
        <form className="mb-2 flex flex-wrap gap-2" onSubmit={(e) => {
          e.preventDefault()
          act.mutate(() => api(`/tickets/${t.key}/web-links`, { body: { url: form.url, title: form.title || null } }))
        }}>
          <input autoFocus type="url" placeholder={tr('https://…')} value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })}
            className="w-64 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900" />
          <input placeholder={tr('Titre (facultatif)')} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })}
            className="w-48 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900" />
          <Button type="submit" disabled={!form.url}>{tr('Ajouter')}</Button>
          <Button type="button" variant="secondary" onClick={() => setAdding(null)}>{tr('Annuler')}</Button>
        </form>
      )}
      <ErrorText error={act.error} />
      <table className="w-full text-sm">
        <tbody>
          {links.data?.links.map((l) => (
            <tr key={l.id} className="group border-b border-slate-100 dark:border-slate-900">
              <td className="w-32 py-1 text-slate-500">{l.label}</td>
              {l.ticket ? (
                <>
                  <td className="w-24"><Link to={`/browse/${l.ticket.key}`} className="flex items-center gap-1 hover:underline"><TypeIcon type={l.ticket.type} />{l.ticket.key}</Link></td>
                  <td className={`truncate ${l.ticket.status.category === 'DONE' ? 'text-slate-500 line-through' : ''}`}>{l.ticket.summary}</td>
                  <td className="w-8"><Avatar user={l.ticket.assignee} size={5} /></td>
                  <td className="w-28 text-right"><StatusBadge status={l.ticket.status} /></td>
                </>
              ) : (
                <td colSpan={4} className="italic text-slate-500">{tr('Ticket restreint')}</td>
              )}
              <td className="w-8 text-right">
                {canLink && (
                  <button aria-label={tr('Délier')} className="invisible text-slate-500 hover:text-red-600 group-hover:visible"
                    onClick={() => { if (window.confirm(tr('Supprimer ce lien ?'))) act.mutate(() => api(`/links/${l.id}`, { method: 'DELETE' })) }}>✕</button>
                )}
              </td>
            </tr>
          ))}
          {links.data?.webLinks.map((w) => (
            <tr key={w.id} className="group border-b border-slate-100 dark:border-slate-900">
              <td className="py-1 text-slate-500">{tr('lien web')}</td>
              <td colSpan={4}><a href={w.url} target="_blank" rel="noreferrer noopener" className="text-indigo-600 hover:underline">{w.title}</a></td>
              <td className="text-right">
                {canLink && (
                  <button aria-label={tr('Supprimer le lien')} className="invisible text-slate-500 hover:text-red-600 group-hover:visible"
                    onClick={() => act.mutate(() => api(`/web-links/${w.id}`, { method: 'DELETE' }))}>✕</button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

// --- Watchers -----------------------------------------------------------------------

type WatchersView = { watchers: UserRef[]; watching: boolean; canManage: boolean }

export function useWatchers(t: TicketDetail) {
  const qc = useQueryClient()
  const key = ['ticket', t.key, 'watchers']
  const query = useQuery({ queryKey: key, queryFn: () => api<WatchersView>(`/tickets/${t.key}/watchers`) })
  const toggle = useMutation({
    mutationFn: () => api(`/tickets/${t.key}/watch`, { method: query.data?.watching ? 'DELETE' : 'PUT' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: key }),
  })
  return { query, toggle }
}

export function WatchersButton({ t }: { t: TicketDetail }) {
  const { query, toggle } = useWatchers(t)
  const qc = useQueryClient()
  const directory = useDirectory().data ?? []
  const [open, setOpen] = useState(false)
  const act = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ticket', t.key, 'watchers'] }),
  })
  const w = query.data
  return (
    <div className="relative">
      <Button variant="ghost" onClick={() => setOpen(!open)} title={tr('Observateurs (W pour observer)')} aria-expanded={open}>
        👁 {w?.watchers.length ?? ''}
      </Button>
      {open && w && (
        <div className="absolute right-0 z-20 mt-1 w-64 rounded-md border border-slate-200 bg-white p-2 text-sm shadow-lg dark:border-slate-700 dark:bg-slate-900">
          <Button variant="secondary" className="mb-2 w-full" onClick={() => toggle.mutate()}>
            {w.watching ? tr('Ne plus observer') : tr('Observer ce ticket')}
          </Button>
          <ul className="max-h-48 space-y-1 overflow-y-auto">
            {w.watchers.map((u) => (
              <li key={u.id} className="flex items-center gap-2">
                <Avatar user={u} size={5} /> <span className="flex-1">{u.displayName}</span>
                {w.canManage && (
                  <button aria-label={tr('Retirer {name}', { name: u.displayName })} className="text-slate-500 hover:text-red-600"
                    onClick={() => act.mutate(() => api(`/tickets/${t.key}/watchers/${u.id}`, { method: 'DELETE' }))}>✕</button>
                )}
              </li>
            ))}
          </ul>
          {w.canManage && (
            <select value="" aria-label={tr('Ajouter un observateur')}
              onChange={(e) => { const userId = e.target.value; if (userId) act.mutate(() => api(`/tickets/${t.key}/watchers`, { body: { userId } })) }}
              className="mt-2 w-full rounded border border-slate-300 bg-white px-1 py-1 dark:border-slate-700 dark:bg-slate-900">
              <option value="">{tr('+ Ajouter un observateur…')}</option>
              {directory.filter((u) => !w.watchers.some((x) => x.id === u.id))
                .map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
            </select>
          )}
          <ErrorText error={act.error} />
        </div>
      )}
    </div>
  )
}

// --- Activity: comments + history -------------------------------------------------

type Tab = 'all' | 'comments' | 'history'

export function Activity({ t }: { t: TicketDetail }) {
  const qc = useQueryClient()
  const me = useMe().data!
  const [tab, setTab] = useState<Tab>('all')
  const newestFirst = me.preferences.commentOrder === 'newest'
  const comments = useQuery({ queryKey: ['ticket', t.key, 'comments'], queryFn: () => api<CommentView[]>(`/tickets/${t.key}/comments`) })
  const history = useQuery({ queryKey: ['ticket', t.key, 'history'], queryFn: () => api<HistoryView[]>(`/tickets/${t.key}/history`) })
  const mentionables = useAssignable(t.spaceKey).data ?? []
  const invalidate = () => qc.invalidateQueries({ queryKey: ['ticket', t.key] })
  const act = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn(), onSuccess: invalidate })
  const [composerKey, setComposerKey] = useState(0)
  const space = useSpace(t.spaceKey).data
  const roles = useQuery({ queryKey: ['space', t.spaceKey, 'roles'], queryFn: () => api<{ id: string; name: string }[]>(`/spaces/${t.spaceKey}/roles`) }).data ?? []
  // Comment visibility (spec §4.4): the space default, or everyone / one role.
  const [visibility, setVisibility] = useState<string | null>(null)
  const chosenVisibility = visibility ?? space?.commentVisibilityRoleId ?? ''
  const uploadImage = async (f: File) => `/api/attachments/${(await uploadAttachment(t.key, f)).id}`

  type Entry = { at: string; kind: 'comment'; c: CommentView } | { at: string; kind: 'history'; h: HistoryView }
  let entries: Entry[] = [
    ...(tab !== 'history' ? comments.data?.map((c) => ({ at: c.createdAt, kind: 'comment' as const, c })) ?? [] : []),
    ...(tab !== 'comments' ? history.data?.filter((h) => h.field !== 'comment' || tab === 'history')
      .map((h) => ({ at: h.at, kind: 'history' as const, h })) ?? [] : []),
  ].sort((a, b) => a.at.localeCompare(b.at))
  if (newestFirst) entries = entries.reverse()

  const toggleOrder = async () => {
    const updated = await api('/me', { method: 'PATCH', body: { preferences: { commentOrder: newestFirst ? 'oldest' : 'newest' } } })
    qc.setQueryData(['me'], updated)
  }

  const composer = can(t, 'COMMENT_ADD') && (
    <div id="comment-composer" className="flex gap-2">
      <Avatar user={{ id: me.id, username: me.username, displayName: me.displayName, active: true }} size={6} />
      <div className="flex-1">
        <Editor key={composerKey} initial="" submitLabel={tr('Commenter')} placeholder={tr('Ajouter un commentaire… (@ pour mentionner, Ctrl+Entrée pour envoyer)')}
          mentionables={mentionables} uploadImage={uploadImage}
          onSubmit={(body) => {
            if (!body.trim()) return
            act.mutate(() => api(`/tickets/${t.key}/comments`, { body: { body, visibility: chosenVisibility } }), { onSuccess: () => setComposerKey((k) => k + 1) })
          }} />
        <label className="mt-1 flex items-center gap-1.5 text-xs text-slate-500">
          {tr('Visible par')}
          <select aria-label={tr('Visibilité du commentaire')} value={chosenVisibility} onChange={(e) => setVisibility(e.target.value)}
            className="rounded border border-slate-300 bg-white px-1 py-0.5 dark:border-slate-700 dark:bg-slate-900">
            <option value="">{tr('Tous les membres')}</option>
            {roles.map((r) => <option key={r.id} value={r.id}>{tr('Rôle « {role} »', { role: r.name })}</option>)}
          </select>
        </label>
      </div>
    </div>
  )

  return (
    <section>
      <div className="mb-3 flex items-center gap-3 border-b border-slate-200 dark:border-slate-800">
        <h3 className="pb-1.5 text-sm font-semibold">{tr('Activité')}</h3>
        {(['all', 'comments', 'history'] as Tab[]).map((k) => (
          <button key={k} onClick={() => setTab(k)}
            className={`border-b-2 pb-1.5 text-sm ${tab === k ? 'border-indigo-600 font-medium' : 'border-transparent text-slate-500'}`}>
            {{ all: tr('Tout'), comments: tr('Commentaires'), history: tr('Historique') }[k]}
          </button>
        ))}
        <button onClick={toggleOrder} className="ml-auto pb-1.5 text-xs text-slate-500 hover:text-slate-800 dark:hover:text-slate-200">
          {newestFirst ? tr('↓ Plus récents d’abord') : tr('↑ Plus anciens d’abord')}
        </button>
      </div>
      <ErrorText error={act.error} />
      {newestFirst && tab !== 'history' && <div className="mb-4">{composer}</div>}
      <ol className="space-y-3">
        {entries.map((e) => e.kind === 'comment'
          ? <CommentItem key={e.c.id} c={e.c} t={t} mentionables={mentionables} uploadImage={uploadImage} onChanged={invalidate}
              roleName={roles.find((r) => r.id === e.c.visibilityRoleId)?.name} />
          : <HistoryItem key={e.h.id} h={e.h} />)}
      </ol>
      {!newestFirst && tab !== 'history' && <div className="mt-4">{composer}</div>}
    </section>
  )
}

function CommentItem({ c, t, mentionables, uploadImage, onChanged, roleName }: {
  c: CommentView; t: TicketDetail; mentionables: UserRef[]; uploadImage: (f: File) => Promise<string>; onChanged: () => void; roleName?: string
}) {
  const [editing, setEditing] = useState(false)
  const [picker, setPicker] = useState(false)
  const act = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn(), onSuccess: onChanged })
  const highlighted = location.hash === `#comment-${c.id}`
  return (
    <li id={`comment-${c.id}`} className={`flex gap-2 rounded ${highlighted ? 'bg-amber-50 ring-2 ring-amber-300 dark:bg-amber-950' : ''}`}>
      <Avatar user={c.author} size={6} />
      <div className="min-w-0 flex-1">
        <div className="text-sm">
          <span className="font-medium">{c.author.displayName}</span>{' '}
          <a href={`#comment-${c.id}`} className="text-xs text-slate-500 hover:underline">{new Date(c.createdAt).toLocaleString()}</a>
          {c.editedAt && <span className="text-xs text-slate-500" title={new Date(c.editedAt).toLocaleString()}> {tr('· modifié')}</span>}
          {c.visibilityRoleId && <span className="ml-1 rounded bg-amber-100 px-1 text-xs text-amber-900 dark:bg-amber-950 dark:text-amber-200">🔒 {roleName ?? tr('restreint')}</span>}
        </div>
        {editing ? (
          <Editor initial={c.body} mentionables={mentionables} uploadImage={uploadImage} autoFocus
            onCancel={() => setEditing(false)}
            onSubmit={(body) => act.mutate(() => api(`/comments/${c.id}`, { method: 'PATCH', body: { body } }), { onSuccess: () => setEditing(false) })} />
        ) : (
          <Markdown text={c.body} />
        )}
        <div className="mt-1 flex flex-wrap items-center gap-1 text-xs">
          {c.reactions.map((r) => (
            <button key={r.emoji} onClick={() => act.mutate(() => api(`/comments/${c.id}/reactions`, { body: { emoji: r.emoji } }))}
              className={`rounded-full border px-1.5 ${r.mine ? 'border-indigo-400 bg-indigo-50 dark:bg-indigo-950' : 'border-slate-200 dark:border-slate-700'}`}>
              {r.emoji} {r.count}
            </button>
          ))}
          <span className="relative">
            <button onClick={() => setPicker(!picker)} className="rounded px-1 text-slate-500 hover:text-slate-700" aria-label={tr('Ajouter une réaction')}>☺+</button>
            {picker && (
              <span className="absolute left-0 z-10 flex gap-1 rounded-md border border-slate-200 bg-white p-1 shadow dark:border-slate-700 dark:bg-slate-900">
                {EMOJIS.map((em) => (
                  <button key={em} onClick={() => { setPicker(false); act.mutate(() => api(`/comments/${c.id}/reactions`, { body: { emoji: em } })) }}>{em}</button>
                ))}
              </span>
            )}
          </span>
          <button className="text-slate-500 hover:underline" onClick={() => navigator.clipboard.writeText(`${location.origin}/browse/${t.key}#comment-${c.id}`)}>{tr('Copier le lien')}</button>
          {c.canEdit && !editing && <button className="text-slate-500 hover:underline" onClick={() => setEditing(true)}>{tr('Modifier')}</button>}
          {c.canDelete && (
            <button className="text-red-600 hover:underline"
              onClick={() => { if (window.confirm(tr('Supprimer ce commentaire ?'))) act.mutate(() => api(`/comments/${c.id}`, { method: 'DELETE' })) }}>
              {tr('Supprimer')}
            </button>
          )}
        </div>
        <ErrorText error={act.error} />
      </div>
    </li>
  )
}

function HistoryItem({ h }: { h: HistoryView }) {
  return (
    <li className="flex gap-2 text-sm text-slate-600 dark:text-slate-500">
      <Avatar user={h.actor} size={6} />
      <div>
        <span className="font-medium text-slate-800 dark:text-slate-200">{h.actor.displayName}</span>{' '}
        {h.field === 'created' ? FIELD_LABELS.created
          : h.field === 'comment' ? <>{tr('a supprimé un {what}', { what: h.oldDisplay ?? '' })}</>
          : h.field === 'link' || h.field === 'attachment' ? (
            h.newDisplay ? <>{h.field === 'attachment' ? tr('a ajouté la pièce jointe') : tr('a ajouté')} <strong>{h.newDisplay}</strong></>
              : <>{h.field === 'attachment' ? tr('a retiré la pièce jointe') : tr('a retiré')} <strong>{h.oldDisplay}</strong></>
          ) : (
            <>
              {tr('a modifié')} <span className="font-medium">{FIELD_LABELS[h.field] ?? h.field}</span>
              {h.field !== 'description' && (
                <> : <span className="line-through">{h.oldDisplay ?? tr('aucun')}</span> → {h.newDisplay ?? tr('aucun')}</>
              )}
            </>
          )}
        <div className="text-xs text-slate-500">{new Date(h.at).toLocaleString()}</div>
      </div>
    </li>
  )
}
