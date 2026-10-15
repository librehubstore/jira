import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { api } from '../api'
import { useDirectory, useSpace } from '../spaces'
import { useMeta } from '../tickets'
import { Button, ErrorText, Field, Input } from '../ui'
import type { BoardView } from './BoardPage'
import type { ColorMode, Criteria, SwimlaneMode, WipMode } from './model'
import type { SpaceSummary } from '../spaces'
import { FqlInput } from '../components/FqlInput'
import { ApiError } from '../api'
import { t } from '../i18n'

type ColumnDraft = { id?: string; name: string; wipMin: string; wipMax: string; statusIds: string[]; newStatusName?: string }

/** Board configuration for space admins (MANAGE_BOARD): columns, WIP, swimlanes, cards, quick filters. */
export function BoardSettingsPage() {
  const { key, boardId } = useParams()
  const board = useQuery({ queryKey: ['board', boardId], queryFn: () => api<BoardView>(`/boards/${boardId}`) })
  if (board.error) return <div className="p-6"><ErrorText error={board.error} /></div>
  if (!board.data) return null
  if (!board.data.myPermissions.includes('MANAGE_BOARD')) {
    return <p className="p-6 text-slate-500">{t('Seuls les administrateurs de l’espace configurent les boards.')}</p>
  }
  return (
    <div className="mx-auto max-w-4xl space-y-10 p-6">
      <div className="flex items-center gap-3">
        <h1 className="text-xl font-semibold">{t('Configuration · {name}', { name: board.data.name })}</h1>
        <Link to={`/spaces/${key}/boards/${boardId}`} className="ml-auto text-sm text-indigo-600 hover:underline">{t('← Retour au board')}</Link>
      </div>
      <Boards board={board.data} />
      <General board={board.data} />
      <Scope key={board.data.spaceKeys.join() + board.data.workingDays.join()} board={board.data} />
      <FqlSettings key={board.data.baseFql + JSON.stringify(board.data.swimlaneQueries) + JSON.stringify(board.data.cardColorQueries)} board={board.data} />
      <Columns key={board.data.columns.map((c) => c.id + c.statuses.length).join()} board={board.data} />
      <QuickFilters board={board.data} />
    </div>
  )
}

function useSave(board: BoardView) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['board', board.id] })
      void qc.invalidateQueries({ queryKey: ['space', board.spaceKey] })
    },
  })
}

const CARD_FIELDS: Record<string, string> = { labels: t('Labels'), dueDate: t('Échéance'), startDate: t('Date de début'), estimate: t('Estimation'), parent: t('Parent'), daysInColumn: t('Jours dans la colonne'), components: t('Composants'), fixVersions: t('Versions') }

function General({ board }: { board: BoardView }) {
  const save = useSave(board)
  const patch = (body: Record<string, unknown>) => save.mutate(() => api(`/boards/${board.id}`, { method: 'PATCH', body }))
  const select = 'rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900'
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Général')}</h2>
      <ErrorText error={save.error} />
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label={t('Masquer les tickets terminés depuis')}>
          <select className={select} value={board.hideDoneDays ?? 'never'}
            onChange={(e) => patch(e.target.value === 'never' ? { neverHide: true } : { hideDoneDays: Number(e.target.value) })}>
            <option value="1">{t('1 jour')}</option><option value="7">{t('7 jours')}</option>
            <option value="14">{t('14 jours')}</option><option value="28">{t('28 jours')}</option>
            <option value="never">{t('Ne jamais masquer')}</option>
          </select>
        </Field>
        <Field label={t('Swimlanes')}>
          <select className={select} value={board.swimlaneMode} onChange={(e) => patch({ swimlaneMode: e.target.value as SwimlaneMode })}>
            <option value="NONE">{t('Aucune')}</option><option value="ASSIGNEE">{t('Par assigné')}</option>
            <option value="PARENT">{t('Par parent / epic')}</option><option value="PRIORITY">{t('Par priorité')}</option>
            <option value="TYPE">{t('Par type')}</option><option value="REPORTER">{t('Par rapporteur')}</option>
            <option value="LABEL">{t('Par label')}</option><option value="QUERIES">{t('Par requêtes FQL')}</option>
          </select>
        </Field>
        <Field label={t('Limites WIP comptées sur')}>
          <select className={select} value={board.wipMode} onChange={(e) => patch({ wipMode: e.target.value as WipMode })}>
            <option value="COUNT">{t('Tous les tickets')}</option>
            <option value="COUNT_EXCL_SUBTASKS">{t('Tickets hors sous-tâches')}</option>
            <option value="OFF">{t('Désactivées')}</option>
          </select>
        </Field>
        <label className="flex items-center gap-2 self-end text-sm">
          <input type="checkbox" checked={board.showSubtasks} onChange={(e) => patch({ showSubtasks: e.target.checked })} />
          {t('Afficher les sous-tâches comme cartes')}
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={board.wipBlocking} onChange={(e) => patch({ wipBlocking: e.target.checked })} />
          {t('Limites WIP bloquantes (refuser le dépôt au-delà du max.)')}
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={board.backlogEnabled} disabled={board.columns.length < 3}
            onChange={(e) => patch({ backlogEnabled: e.target.checked })} />
          {t('Backlog Kanban (la 1re colonne devient le backlog, la 2e « Sélectionné »)')}
        </label>
      </div>
      <Field label={t('Champs supplémentaires sur les cartes (3 max.)')}>
        <div className="flex flex-wrap gap-3">
          {Object.entries(CARD_FIELDS).map(([f, label]) => {
            const on = board.cardFields.includes(f)
            return (
              <label key={f} className="flex items-center gap-1.5 text-sm">
                <input type="checkbox" checked={on} disabled={!on && board.cardFields.length >= 3}
                  onChange={() => patch({ cardFields: on ? board.cardFields.filter((x) => x !== f) : [...board.cardFields, f] })} />
                {label}
              </label>
            )
          })}
        </div>
      </Field>
    </section>
  )
}

function Columns({ board }: { board: BoardView }) {
  const save = useSave(board)
  const statuses = useSpace(board.spaceKey).data?.statuses ?? []
  const [cols, setCols] = useState<ColumnDraft[]>(board.columns.map((c) => ({
    id: c.id, name: c.name, wipMin: c.wipMin?.toString() ?? '', wipMax: c.wipMax?.toString() ?? '', statusIds: c.statuses.map((s) => s.id),
  })))
  const mapped = new Set(cols.flatMap((c) => c.statusIds))
  const unmapped = statuses.filter((s) => !mapped.has(s.id))
  const update = (i: number, patch: Partial<ColumnDraft>) => setCols(cols.map((c, j) => (j === i ? { ...c, ...patch } : c)))
  const swap = (i: number, j: number) => {
    const next = [...cols];
    [next[i], next[j]] = [next[j], next[i]]
    setCols(next)
  }
  const submit = () => save.mutate(() => api(`/boards/${board.id}/columns`, {
    method: 'PUT',
    body: cols.map((c) => ({
      id: c.id ?? null, name: c.name, statusIds: c.statusIds, newStatusName: c.newStatusName ?? null,
      wipMin: c.wipMin ? Number(c.wipMin) : null, wipMax: c.wipMax ? Number(c.wipMax) : null,
    })),
  }))
  const name = (id: string) => statuses.find((s) => s.id === id)?.name ?? '?'

  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Colonnes et statuts')}</h2>
      <p className="text-sm text-slate-500">{t('La colonne la plus à droite représente le travail terminé. Un statut non mappé masque ses tickets du board.')}</p>
      {unmapped.length > 0 && (
        <div className="rounded-md bg-amber-50 p-2 text-sm text-amber-900 dark:bg-amber-950 dark:text-amber-100">
          {t('Statuts non mappés : {statuses}', { statuses: unmapped.map((s) => s.name).join(', ') })}
        </div>
      )}
      <ol className="space-y-2">
        {cols.map((c, i) => (
          <li key={c.id ?? `new-${i}`} className="grid grid-cols-[auto_1fr_5rem_5rem_1.5fr_auto] items-center gap-2 rounded-md border border-slate-200 p-2 dark:border-slate-800">
            <span className="flex flex-col">
              <button aria-label={t('Monter')} disabled={i === 0} onClick={() => swap(i, i - 1)} className="text-xs disabled:opacity-30">▲</button>
              <button aria-label={t('Descendre')} disabled={i === cols.length - 1} onClick={() => swap(i, i + 1)} className="text-xs disabled:opacity-30">▼</button>
            </span>
            <Input aria-label={t('Nom de la colonne')} value={c.name} onChange={(e) => update(i, { name: e.target.value })} />
            <Input aria-label={t('WIP min')} type="number" min={0} placeholder={t('Min')} value={c.wipMin} onChange={(e) => update(i, { wipMin: e.target.value })} />
            <Input aria-label={t('WIP max')} type="number" min={0} placeholder={t('Max')} value={c.wipMax} onChange={(e) => update(i, { wipMax: e.target.value })} />
            <div className="flex flex-wrap items-center gap-1 text-xs">
              {c.statusIds.map((sid) => (
                <span key={sid} className="flex items-center gap-1 rounded bg-slate-100 px-1.5 py-0.5 dark:bg-slate-800">
                  {name(sid)}
                  <button aria-label={t('Retirer {name}', { name: name(sid) })} onClick={() => update(i, { statusIds: c.statusIds.filter((x) => x !== sid) })}>✕</button>
                </span>
              ))}
              {c.newStatusName && <span className="rounded bg-indigo-100 px-1.5 py-0.5 dark:bg-indigo-950">{t('+ {name} (nouveau)', { name: c.newStatusName })}</span>}
              {unmapped.length > 0 && (
                <select value="" aria-label={t('Ajouter un statut')} onChange={(e) => e.target.value && update(i, { statusIds: [...c.statusIds, e.target.value] })}
                  className="rounded border border-slate-300 bg-white px-1 dark:border-slate-700 dark:bg-slate-900">
                  <option value="">{t('+ statut')}</option>
                  {unmapped.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              )}
            </div>
            <button aria-label={t('Supprimer la colonne {name}', { name: c.name })} disabled={cols.length === 1}
              onClick={() => setCols(cols.filter((_, j) => j !== i))} className="text-red-600 disabled:opacity-30">✕</button>
          </li>
        ))}
      </ol>
      <div className="flex gap-2">
        <Button variant="secondary" onClick={() => {
          const n = window.prompt(t('Nom de la nouvelle colonne (crée le statut du même nom) :'))?.trim()
          if (n) setCols([...cols.slice(0, -1), { name: n, wipMin: '', wipMax: '', statusIds: [], newStatusName: n }, ...cols.slice(-1)])
        }}>{t('+ Ajouter une colonne')}</Button>
        <Button onClick={submit} disabled={save.isPending}>{t('Enregistrer les colonnes')}</Button>
      </div>
      <ErrorText error={save.error} />
    </section>
  )
}

type FilterDraft = { name: string; criteria: Criteria }

function QuickFilters({ board }: { board: BoardView }) {
  const save = useSave(board)
  const meta = useMeta().data
  const users = useDirectory().data ?? []
  const [filters, setFilters] = useState<FilterDraft[]>(board.quickFilters.map((f) => ({ name: f.name, criteria: f.criteria })))
  const update = (i: number, patch: Partial<FilterDraft>) => setFilters(filters.map((f, j) => (j === i ? { ...f, ...patch } : f)))
  const setCrit = (i: number, field: keyof Criteria, value: unknown) =>
    update(i, { criteria: { ...filters[i].criteria, [field]: Array.isArray(value) && value.length === 0 ? undefined : value } })
  const multi = 'h-20 rounded-md border border-slate-300 bg-white px-1 text-sm dark:border-slate-700 dark:bg-slate-900'
  const picked = (e: React.ChangeEvent<HTMLSelectElement>) => [...e.target.selectedOptions].map((o) => o.value)
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Filtres rapides')}</h2>
      <p className="text-sm text-slate-500">{t('« Mes tickets », « Mis à jour récemment » et « Non assignés » sont toujours proposés. Plusieurs filtres actifs se combinent (ET).')}</p>
      {filters.map((f, i) => (
        <div key={i} className="grid gap-2 rounded-md border border-slate-200 p-3 sm:grid-cols-4 dark:border-slate-800">
          <div className="sm:col-span-4 flex gap-2">
            <Input aria-label={t('Nom du filtre')} value={f.name} placeholder={t('Nom du filtre')} onChange={(e) => update(i, { name: e.target.value })} />
            <button aria-label={t('Monter')} disabled={i === 0} onClick={() => { const n = [...filters]; [n[i - 1], n[i]] = [n[i], n[i - 1]]; setFilters(n) }} className="disabled:opacity-30">▲</button>
            <Button variant="ghost" className="text-red-600" onClick={() => setFilters(filters.filter((_, j) => j !== i))}>{t('Supprimer')}</Button>
          </div>
          <Field label={t('Assigné')}>
            <select multiple className={multi} value={f.criteria.assignee ?? []} onChange={(e) => setCrit(i, 'assignee', picked(e))}>
              <option value="me">{t('Moi')}</option><option value="none">{t('Non assigné')}</option>
              {users.map((u) => <option key={u.id} value={u.id}>{u.displayName}</option>)}
            </select>
          </Field>
          <Field label={t('Type')}>
            <select multiple className={multi} value={f.criteria.types ?? []} onChange={(e) => setCrit(i, 'types', picked(e))}>
              {meta?.types.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          </Field>
          <Field label={t('Priorité')}>
            <select multiple className={multi} value={f.criteria.priorities ?? []} onChange={(e) => setCrit(i, 'priorities', picked(e))}>
              {meta?.priorities.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </Field>
          <div className="space-y-2">
            <Field label={t('Labels (séparés par des espaces)')}>
              <Input value={(f.criteria.labels ?? []).join(' ')} onChange={(e) => setCrit(i, 'labels', e.target.value.split(/\s+/).filter(Boolean))} />
            </Field>
            <label className="flex items-center gap-1.5 text-sm">
              <input type="checkbox" checked={!!f.criteria.flagged} onChange={(e) => setCrit(i, 'flagged', e.target.checked || undefined)} /> {t('Signalés')}
            </label>
          </div>
          <div className="sm:col-span-4">
            <Field label={t('Requête FQL (facultative, combinée avec les critères ci-dessus)')}>
              <FqlInput value={f.criteria.fql ?? ''} onChange={(v) => setCrit(i, 'fql', v.trim() ? v : undefined)} onSubmit={() => {}} />
            </Field>
          </div>
        </div>
      ))}
      <div className="flex gap-2">
        <Button variant="secondary" onClick={() => setFilters([...filters, { name: '', criteria: {} }])}>{t('+ Ajouter un filtre')}</Button>
        <Button disabled={save.isPending} onClick={() => save.mutate(() => api(`/boards/${board.id}/quick-filters`, { method: 'PUT', body: filters }))}>
          {t('Enregistrer les filtres')}
        </Button>
      </div>
      <ErrorText error={save.error} />
    </section>
  )
}

/** Base FQL filter of the board and swimlanes defined by FQL queries (spec §4.10). */
function FqlSettings({ board }: { board: BoardView }) {
  const save = useSave(board)
  const [base, setBase] = useState(board.baseFql ?? '')
  const [queries, setQueries] = useState(board.swimlaneQueries)
  const [colors, setColors] = useState(board.cardColorQueries)
  const err = save.error instanceof ApiError && save.error.code === 'FQL_ERROR' ? save.error : null
  const patch = (body: Record<string, unknown>) => save.mutate(() => api(`/boards/${board.id}`, { method: 'PATCH', body }))
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Filtre et couloirs FQL')}</h2>
      <Field label={t('Filtre de base du board (FQL, facultatif)')} hint={t('Restreint les tickets affichés, ex. type != Epic')}>
        <div className="flex items-start gap-2">
          <div className="flex-1"><FqlInput value={base} onChange={setBase} onSubmit={() => patch({ baseFql: base })}
            error={err?.message} errorPosition={err?.details.position as number | undefined} /></div>
          <Button onClick={() => patch({ baseFql: base })}>{t('Enregistrer')}</Button>
        </div>
      </Field>
      {board.swimlaneMode === 'QUERIES' && (
        <div className="space-y-2">
          <p className="text-sm text-slate-500">{t('Chaque ticket va dans le premier couloir dont la requête correspond ; les autres vont dans « Le reste ».')}</p>
          {queries.map((q, i) => (
            <div key={i} className="flex items-start gap-2">
              <Input aria-label={t('Nom du couloir')} className="w-48" value={q.name} placeholder={t('Nom du couloir')}
                onChange={(e) => setQueries(queries.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
              <div className="flex-1"><FqlInput value={q.fql} onSubmit={() => {}}
                onChange={(v) => setQueries(queries.map((x, j) => (j === i ? { ...x, fql: v } : x)))} /></div>
              <Button variant="ghost" aria-label={t('Supprimer le couloir {name}', { name: q.name })} onClick={() => setQueries(queries.filter((_, j) => j !== i))}>✕</Button>
            </div>
          ))}
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => setQueries([...queries, { name: '', fql: '' }])}>{t('+ Ajouter un couloir')}</Button>
            <Button onClick={() => patch({ swimlaneQueries: queries })}>{t('Enregistrer les couloirs')}</Button>
          </div>
        </div>
      )}
      <Field label={t('Couleur des cartes')}>
        <select className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
          value={board.cardColorMode} onChange={(e) => patch({ cardColorMode: e.target.value as ColorMode })}>
          <option value="NONE">{t('Aucune')}</option><option value="TYPE">{t('Par type')}</option>
          <option value="PRIORITY">{t('Par priorité')}</option><option value="ASSIGNEE">{t('Par assigné')}</option>
          <option value="QUERIES">{t('Par requêtes FQL')}</option>
        </select>
      </Field>
      {board.cardColorMode === 'QUERIES' && (
        <div className="space-y-2">
          {colors.map((q, i) => (
            <div key={i} className="flex items-start gap-2">
              <input type="color" aria-label={t('Couleur')} value={q.color} className="h-8 w-10 rounded border border-slate-300 dark:border-slate-700"
                onChange={(e) => setColors(colors.map((x, j) => (j === i ? { ...x, color: e.target.value } : x)))} />
              <div className="flex-1"><FqlInput value={q.fql} onSubmit={() => {}}
                onChange={(v) => setColors(colors.map((x, j) => (j === i ? { ...x, fql: v } : x)))} /></div>
              <Button variant="ghost" aria-label={t('Supprimer la couleur')} onClick={() => setColors(colors.filter((_, j) => j !== i))}>✕</Button>
            </div>
          ))}
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => setColors([...colors, { fql: '', color: '#4f46e5' }])}>{t('+ Ajouter une couleur')}</Button>
            <Button onClick={() => patch({ cardColorQueries: colors })}>{t('Enregistrer les couleurs')}</Button>
          </div>
        </div>
      )}
      {!err && <ErrorText error={save.error} />}
    </section>
  )
}

/** Several boards per space: rename, make default, delete, create (spec §4.10). */
function Boards({ board }: { board: BoardView }) {
  const save = useSave(board)
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [name, setName] = useState(board.name)
  const [newName, setNewName] = useState('')
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Board')}</h2>
      <div className="flex flex-wrap items-end gap-2">
        <Field label={t('Nom')}>
          <Input value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Button variant="secondary" disabled={!name.trim() || name === board.name}
          onClick={() => save.mutate(() => api(`/boards/${board.id}`, { method: 'PATCH', body: { name } }))}>{t('Renommer')}</Button>
        {board.isDefault ? (
          <span className="pb-1.5 text-sm text-slate-500">{t('Board par défaut de l’espace')}</span>
        ) : (
          <>
            <Button variant="secondary" onClick={() => save.mutate(() => api(`/boards/${board.id}/default`, { body: {} }))}>{t('Définir par défaut')}</Button>
            <Button variant="danger" onClick={() => {
              if (!window.confirm(t('Supprimer le board « {name} » ? Les tickets ne sont pas supprimés.', { name: board.name }))) return
              save.mutate(async () => {
                await api(`/boards/${board.id}`, { method: 'DELETE' })
                void qc.invalidateQueries({ queryKey: ['space', board.spaceKey] })
                void navigate(`/spaces/${board.spaceKey}`)
              })
            }}>{t('Supprimer')}</Button>
          </>
        )}
      </div>
      <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => {
        e.preventDefault()
        save.mutate(async () => {
          const created = await api<BoardView>('/boards', { body: { spaceKey: board.spaceKey, name: newName.trim() } })
          setNewName('')
          void qc.invalidateQueries({ queryKey: ['space', board.spaceKey] })
          void navigate(`/spaces/${board.spaceKey}/boards/${created.id}/settings`)
        })
      }}>
        <Field label={t('Nouveau board dans cet espace')}>
          <Input required value={newName} onChange={(e) => setNewName(e.target.value)} placeholder={t('ex. Support')} />
        </Field>
        <Button type="submit" variant="secondary">{t('Créer le board')}</Button>
      </form>
      <ErrorText error={save.error} />
    </section>
  )
}

const DAYS = [t('Lun'), t('Mar'), t('Mer'), t('Jeu'), t('Ven'), t('Sam'), t('Dim')]

/** Spaces covered by the board (multi-space board) and working days. */
function Scope({ board }: { board: BoardView }) {
  const save = useSave(board)
  const spaces = useQuery({ queryKey: ['spaces', false], queryFn: () => api<SpaceSummary[]>('/spaces?includeArchived=false') })
  const [others, setOthers] = useState(board.spaceKeys.filter((k) => k !== board.spaceKey))
  const [days, setDays] = useState(board.workingDays)
  const toggle = <T,>(list: T[], v: T) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v])
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{t('Périmètre')}</h2>
      <Field label={t('Autres espaces affichés sur ce board')} hint={t('Ajoutez leurs statuts aux colonnes pour voir leurs tickets.')}>
        <div className="flex flex-wrap gap-3">
          {spaces.data?.filter((sp) => sp.key !== board.spaceKey).map((sp) => (
            <label key={sp.key} className="flex items-center gap-1.5 text-sm">
              <input type="checkbox" checked={others.includes(sp.key)} onChange={() => setOthers(toggle(others, sp.key))} />
              <span className="font-mono text-xs">{sp.key}</span> {sp.name}
            </label>
          ))}
        </div>
      </Field>
      <Field label={t('Jours ouvrés (pour « jours dans la colonne »)')}>
        <div className="flex flex-wrap gap-3">
          {DAYS.map((d, i) => (
            <label key={d} className="flex items-center gap-1.5 text-sm">
              <input type="checkbox" checked={days.includes(i + 1)} onChange={() => setDays(toggle(days, i + 1).sort((a, b) => a - b))} /> {d}
            </label>
          ))}
        </div>
      </Field>
      <Button onClick={() => save.mutate(() => api(`/boards/${board.id}`, { method: 'PATCH', body: { otherSpaceKeys: others, workingDays: days } }))}>
        {t('Enregistrer le périmètre')}
      </Button>
      <ErrorText error={save.error} />
    </section>
  )
}
