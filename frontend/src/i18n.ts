import { localStorageGet, localStorageSet } from './storage'
import { EN } from './i18n.en'

/**
 * Interface in French or English (spec §10). French text is the key; English comes from i18n.en.ts.
 * The locale is fixed for the page's lifetime: changing it saves the preference and reloads.
 */
export type Locale = 'fr' | 'en'

function detect(): Locale {
  const saved = localStorageGet('fluxo.locale')
  if (saved === 'fr' || saved === 'en') return saved
  return navigator.language.toLowerCase().startsWith('fr') ? 'fr' : 'en'
}

let locale: Locale = typeof navigator === 'undefined' ? 'fr' : detect()
if (typeof document !== 'undefined') document.documentElement.lang = locale

export const getLocale = () => locale

/** Applies the user's saved preference; reloads once if it differs from the current page language. */
export function syncLocale(preferred: unknown) {
  if ((preferred === 'fr' || preferred === 'en') && preferred !== locale) {
    localStorageSet('fluxo.locale', preferred)
    location.reload()
  }
}

export function setLocaleForTests(l: Locale) {
  locale = l
}

/** t('Créer {n} tickets', { n: 3 }) */
export function t(fr: string, vars?: Record<string, string | number>): string {
  const text = locale === 'en' ? EN[fr] ?? fr : fr
  return vars ? text.replace(/\{(\w+)\}/g, (_, k: string) => String(vars[k] ?? '')) : text
}
