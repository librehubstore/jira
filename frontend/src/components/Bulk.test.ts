import { describe, expect, it } from 'vitest'
import { toggleSelection } from './Bulk'

describe('toggleSelection', () => {
  const keys = ['A-1', 'A-2', 'A-3', 'A-4']
  it('toggles one and selects ranges with shift', () => {
    let s = toggleSelection(new Set(), keys, 'A-2', null, false)
    expect([...s]).toEqual(['A-2'])
    s = toggleSelection(s, keys, 'A-4', 'A-2', true)
    expect([...s].sort()).toEqual(['A-2', 'A-3', 'A-4'])
    s = toggleSelection(s, keys, 'A-3', 'A-4', false)
    expect([...s].sort()).toEqual(['A-2', 'A-4'])
    expect([...toggleSelection(new Set(), keys, 'A-1', 'A-3', true)].sort()).toEqual(['A-1', 'A-2', 'A-3'])
  })
})
