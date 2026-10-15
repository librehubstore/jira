import { useQuery } from '@tanstack/react-query'
import { api } from './api'
import { t } from './i18n'

export type Perm =
  | 'BROWSE' | 'CREATE' | 'EDIT' | 'TRANSITION' | 'ASSIGN' | 'BE_ASSIGNABLE' | 'DELETE' | 'LINK'
  | 'COMMENT_ADD' | 'COMMENT_EDIT_OWN' | 'COMMENT_EDIT_ALL' | 'COMMENT_DELETE_OWN' | 'COMMENT_DELETE_ALL'
  | 'ATTACH_ADD' | 'ATTACH_DELETE_OWN' | 'ATTACH_DELETE_ALL'
  | 'MANAGE_WATCHERS' | 'BULK_CHANGE' | 'MANAGE_BOARD' | 'MANAGE_VERSIONS' | 'ADMINISTER_SPACE'

export type UserRef = { id: string; username: string; displayName: string; active: boolean; avatar?: string | null }
export type StatusCategory = 'TODO' | 'IN_PROGRESS' | 'DONE'
export type StatusDto = { id: string; name: string; category: StatusCategory }
export type TicketTypeDto = { id: string; name: string; icon: string; color: string; hierarchyLevel: number }

export type SpaceSummary = {
  id: string
  key: string
  name: string
  description: string | null
  archived: boolean
  lead: UserRef | null
  myPermissions: Perm[]
}

export type SpaceDetail = SpaceSummary & {
  commentVisibilityRoleId: string | null
  estimateUnit: 'POINTS' | 'HOURS'
  statuses: StatusDto[]
  ticketTypes: TicketTypeDto[]
  boards: { id: string; name: string; isDefault: boolean }[]
}

export function useSpace(key: string | undefined) {
  return useQuery({ queryKey: ['space', key], queryFn: () => api<SpaceDetail>(`/spaces/${key}`), enabled: !!key })
}

export function useDirectory() {
  return useQuery({
    queryKey: ['directory', 'users'],
    queryFn: () => api<{ id: string; username: string; displayName: string }[]>('/directory/users'),
    staleTime: 60_000,
  })
}

export const can = (space: { myPermissions: Perm[] } | undefined, perm: Perm) => !!space?.myPermissions.includes(perm)

export const PERM_LABELS: Record<Perm, string> = {
  BROWSE: t('Voir l’espace'),
  CREATE: t('Créer des tickets'),
  EDIT: t('Modifier des tickets'),
  TRANSITION: t('Changer le statut'),
  ASSIGN: t('Assigner'),
  BE_ASSIGNABLE: t('Être assigné'),
  DELETE: t('Supprimer des tickets'),
  LINK: t('Lier des tickets'),
  COMMENT_ADD: t('Commenter'),
  COMMENT_EDIT_OWN: t('Modifier ses commentaires'),
  COMMENT_EDIT_ALL: t('Modifier tous les commentaires'),
  COMMENT_DELETE_OWN: t('Supprimer ses commentaires'),
  COMMENT_DELETE_ALL: t('Supprimer tous les commentaires'),
  ATTACH_ADD: t('Ajouter des pièces jointes'),
  ATTACH_DELETE_OWN: t('Supprimer ses pièces jointes'),
  ATTACH_DELETE_ALL: t('Supprimer toutes les pièces jointes'),
  MANAGE_WATCHERS: t('Gérer les observateurs'),
  BULK_CHANGE: t('Modifications en masse'),
  MANAGE_BOARD: t('Configurer les boards'),
  MANAGE_VERSIONS: t('Gérer les versions'),
  ADMINISTER_SPACE: t('Administrer l’espace'),
}
