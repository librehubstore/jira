import { describe, expect, it } from 'vitest'
import { fqlContext } from './FqlInput'

describe('fqlContext', () => {
  it('detects fields, operators and values', () => {
    expect(fqlContext('', 0).kind).toBe('field')
    expect(fqlContext('assi', 4)).toMatchObject({ kind: 'field', prefix: 'assi' })
    expect(fqlContext('assignee ', 9).kind).toBe('operator')
    expect(fqlContext('assignee = ', 11)).toMatchObject({ kind: 'value', field: 'assignee' })
    expect(fqlContext('status in (todo, Do', 19)).toMatchObject({ kind: 'value', field: 'status', prefix: 'Do' })
    expect(fqlContext('status = Done AND ', 18).kind).toBe('field')
    expect(fqlContext('status = Done AND pri', 21)).toMatchObject({ kind: 'field', prefix: 'pri' })
  })
})
