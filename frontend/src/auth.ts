import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from './api'

export type Me = {
  id: string
  username: string
  displayName: string
  email: string | null
  avatar: string | null
  admin: boolean
  mustChangePassword: boolean
  preferences: Record<string, unknown>
}

export function useMe() {
  return useQuery({
    queryKey: ['me'],
    queryFn: async () => {
      try {
        return await api<Me>('/me')
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null
        throw e
      }
    },
    staleTime: 60_000,
  })
}

export function useSetupStatus() {
  return useQuery({ queryKey: ['setup'], queryFn: () => api<{ needsSetup: boolean }>('/setup'), staleTime: Infinity })
}
