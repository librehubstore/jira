import { describe, expect, it } from 'vitest'
import { EN } from './i18n.en'
import { setLocaleForTests, t } from './i18n'

/** Every t('…') / tr('…') key used in the sources must have an English translation. */
const SOURCES = import.meta.glob(['./**/*.{ts,tsx}', '!./**/*.test.{ts,tsx}', '!./i18n*.ts'], {
  query: '?raw', import: 'default', eager: true,
}) as Record<string, string>

function usedKeys(): Set<string> {
  const keys = new Set<string>()
  const call = /(?<![\w.$])tr?\(\s*(['"])((?:\\.|(?!\1)[^\\\n])*)\1/g
  for (const src of Object.values(SOURCES)) {
    for (const m of src.matchAll(call)) keys.add(m[2].replace(/\\'/g, "'").replace(/\\n/g, '\n'))
  }
  return keys
}

describe('i18n', () => {
  it('scans the sources', () => {
    expect(Object.keys(SOURCES).length).toBeGreaterThan(20)
  })

  it('has an English translation for every key', () => {
    expect([...usedKeys()].filter((k) => !(k in EN)).sort()).toEqual([])
  })

  it('has no stale translations', () => {
    const used = usedKeys()
    expect(Object.keys(EN).filter((k) => !used.has(k)).sort()).toEqual([])
  })

  it('interpolates variables', () => {
    setLocaleForTests('fr')
    expect(t('{n} ticket(s)', { n: 3 })).toBe('3 ticket(s)')
  })
})
