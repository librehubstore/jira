import { useEffect } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'

export type Signal = { type: string; spaceKey: string; ticketKey: string; actorId: string }

/** Which cached queries a server signal makes stale. */
export function invalidate(qc: QueryClient, s: Signal) {
  void qc.invalidateQueries({
    predicate: (q) => {
      const [root, id, sub] = q.queryKey as unknown[]
      if (root === 'ticket') return id === s.ticketKey
      if (root === 'board' && sub === 'cards') {
        const board = qc.getQueryData<{ spaceKey: string; spaceKeys?: string[] }>(['board', id])
        return !board || (board.spaceKeys ?? [board.spaceKey]).includes(s.spaceKey)
      }
      return root === 'notifications' || root === 'search'
    },
  })
}

/** Live updates (spec V1): one EventSource per tab; the browser reconnects on its own after a drop. */
export function useRealtime() {
  const qc = useQueryClient()
  useEffect(() => {
    if (typeof EventSource === 'undefined') return
    const source = new EventSource('/api/events')
    source.addEventListener('ticket', (e) => invalidate(qc, JSON.parse((e as MessageEvent).data)))
    return () => source.close()
  }, [qc])
}
