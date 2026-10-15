import { describe, expect, it } from 'vitest'
import { needsScreen, pickTransition, transitionLabel, type AvailableTransition } from './Transitions'

const tr = (name: string, to: string, over: Partial<AvailableTransition> = {}): AvailableTransition => ({
  id: name, name, to: { id: to, name: to, category: to === 'done' ? 'DONE' : 'TODO' }, screen: {}, commentRequired: false, ...over,
})

describe('transitions', () => {
  it('picks the preferred status, else any of the column', () => {
    const list = [tr('Démarrer', 'doing'), tr('Valider', 'done')]
    expect(pickTransition(list, ['doing', 'review'])?.name).toBe('Démarrer')
    expect(pickTransition(list, ['doing', 'done'], 'done')?.name).toBe('Valider')
    expect(pickTransition(list, ['review'])).toBeNull()
  })
  it('opens the screen only when it asks something', () => {
    expect(needsScreen(tr('a', 'doing'))).toBe(false)
    expect(needsScreen(tr('a', 'doing', { screen: { resolution: true } }))).toBe(false) // not going to Done
    expect(needsScreen(tr('a', 'done', { screen: { resolution: true } }))).toBe(true)
    expect(needsScreen(tr('a', 'doing', { commentRequired: true }))).toBe(true)
    expect(transitionLabel(tr('doing', 'doing'))).toBe('doing')
    expect(transitionLabel(tr('Démarrer', 'doing'))).toBe('Démarrer → doing')
  })
})
