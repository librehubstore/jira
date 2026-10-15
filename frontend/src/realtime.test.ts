import { describe, expect, it } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { invalidate } from './realtime'

describe('invalidate', () => {
  it('only marks queries touched by the signal as stale', () => {
    const qc = new QueryClient()
    qc.setQueryData(['board', 'b1'], { spaceKey: 'PAY' })
    qc.setQueryData(['board', 'b2'], { spaceKey: 'OPS' })
    for (const k of [['board', 'b1', 'cards'], ['board', 'b2', 'cards'], ['ticket', 'PAY-1'], ['ticket', 'PAY-2'], ['spaces']]) qc.setQueryData(k, {})
    invalidate(qc, { type: 'UPDATED', spaceKey: 'PAY', ticketKey: 'PAY-1', actorId: 'x' })
    const stale = qc.getQueryCache().getAll().filter((q) => q.isStale() && q.state.isInvalidated).map((q) => q.queryKey.join('/'))
    expect(stale.sort()).toEqual(['board/b1/cards', 'ticket/PAY-1'])
  })
})
