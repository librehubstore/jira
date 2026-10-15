import { describe, expect, it } from 'vitest'
import { cfdBands } from './CfdPage'

describe('cfdBands', () => {
  it('stacks each column between its count and the next one', () => {
    const [todo, doing, done] = cfdBands([{ date: 'a', cumulative: [4, 2, 0] }, { date: 'b', cumulative: [4, 2, 1] }], 100, 40)
    expect(todo).toBe('0,0 100,0 100,20 0,20') // 4 → 2
    expect(doing).toBe('0,20 100,20 100,30 0,40') // 2 → 0 then 1
    expect(done).toBe('0,40 100,30 100,40 0,40')
  })
})
