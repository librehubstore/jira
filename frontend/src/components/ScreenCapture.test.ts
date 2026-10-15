import { describe, expect, it } from 'vitest'
import { captureName } from './ScreenCapture'

describe('captureName', () => {
  it('stamps the local date and time', () => {
    expect(captureName(new Date(2026, 9, 5, 9, 3, 7))).toBe('capture-2026-10-05-09h03m07.png')
  })
})
