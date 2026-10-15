import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './api'
import type { Perm, StatusCategory, UserRef } from './spaces'
import { t } from './i18n'

export type TypeRef = { id: string; name: string; icon: string; color: string; hierarchyLevel: number }
export type StatusRef = { id: string; name: string; category: StatusCategory }
export type PriorityRef = { id: string; name: string; color: string; rank: number }
export type NamedRef = { id: string; name: string }

export type TicketRef = {
  id: string
  key: string
  summary: string
  type: TypeRef
  status: StatusRef
  priority: PriorityRef
  assignee: UserRef | null
}

export type VersionStatus = 'UNRELEASED' | 'RELEASED' | 'ARCHIVED'
export type ComponentView = { id: string; name: string; description: string | null; lead: UserRef | null; archived: boolean; count: number }
export type VersionView = {
  id: string; name: string; description: string | null; startDate: string | null; releaseDate: string | null
  status: VersionStatus; count: number; done: number
}

export function useComponents(spaceKey: string | undefined) {
  return useQuery({ queryKey: ['space', spaceKey, 'components'], queryFn: () => api<ComponentView[]>(`/spaces/${spaceKey}/components`), enabled: !!spaceKey })
}

export function useVersions(spaceKey: string | undefined) {
  return useQuery({ queryKey: ['space', spaceKey, 'versions'], queryFn: () => api<VersionView[]>(`/spaces/${spaceKey}/versions`), enabled: !!spaceKey })
}

export type TicketDetail = {
  id: string
  key: string
  spaceKey: string
  spaceName: string
  version: number
  type: TypeRef
  status: StatusRef
  resolution: NamedRef | null
  priority: PriorityRef
  summary: string
  description: string | null
  assignee: UserRef | null
  reporter: UserRef
  creator: UserRef
  parent: TicketRef | null
  dueDate: string | null
  startDate: string | null
  estimate: number | null
  labels: string[]
  components: NamedRef[]
  fixVersions: { id: string; name: string; status: VersionStatus }[]
  customFields: Record<string, unknown>
  collaborators: UserRef[]
  archived: boolean
  flagged: boolean
  resolvedAt: string | null
  createdAt: string
  updatedAt: string
  children: TicketRef[]
  progress: { done: number; total: number }
  myPermissions: Perm[]
}

export type HistoryView = {
  id: string
  at: string
  actor: UserRef
  field: string
  oldDisplay: string | null
  newDisplay: string | null
}

export type Meta = { types: TypeRef[]; priorities: PriorityRef[]; resolutions: NamedRef[] }

export function useMeta() {
  return useQuery({ queryKey: ['meta'], queryFn: () => api<Meta>('/meta'), staleTime: Infinity })
}

export function useTicket(key: string | undefined) {
  return useQuery({ queryKey: ['ticket', key], queryFn: () => api<TicketDetail>(`/tickets/${key}`), enabled: !!key })
}

export function useAssignable(spaceKey: string | undefined) {
  return useQuery({
    queryKey: ['space', spaceKey, 'assignable'],
    queryFn: () => api<UserRef[]>(`/spaces/${spaceKey}/assignable`),
    enabled: !!spaceKey,
    staleTime: 60_000,
  })
}

/** Ticket mutations: results replace the cached ticket; anything ticket-shaped elsewhere is refetched. */
export function useTicketMutation(key: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (fn: () => Promise<TicketDetail | void>) => fn(),
    onSuccess: (t) => {
      if (t) qc.setQueryData(['ticket', t.key], t)
      void qc.invalidateQueries({ queryKey: ['ticket', key] })
      void qc.invalidateQueries({ queryKey: ['board'] })
      void qc.invalidateQueries({ queryKey: ['search'] })
    },
    onError: (e) => {
      if ((e as { code?: string }).code === 'TICKET_CONFLICT') void qc.invalidateQueries({ queryKey: ['ticket', key] })
    },
  })
}

export const patchTicket = (t: TicketDetail, body: Record<string, unknown>) =>
  api<TicketDetail>(`/tickets/${t.key}`, { method: 'PATCH', body: { ...body, version: t.version } })

export const FIELD_LABELS: Record<string, string> = {
  created: t('a créé le ticket'),
  summary: t('Résumé'),
  description: t('Description'),
  priority: t('Priorité'),
  assignee: t('Assigné'),
  reporter: t('Rapporteur'),
  parent: t('Parent'),
  dueDate: t('Échéance'),
  startDate: t('Date de début'),
  estimate: t('Estimation'),
  flagged: t('Signalement'),
  labels: t('Labels'),
  type: t('Type'),
  status: t('Statut'),
  resolution: t('Résolution'),
  space: t('Espace'),
}
