import { useEffect, useRef } from 'react'
import { useMe } from './auth'
import { t } from './i18n'

/** True when a keyboard event happens while the user is typing (no single-key shortcut then, spec §4.16). */
export function isTyping(e: KeyboardEvent) {
  const el = e.target as HTMLElement | null
  if (!el) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

/**
 * Single-key shortcuts ("c", "shift+n"…). Ignored while typing, while a modal dialog is open (unless
 * `inDialogs`), with Ctrl/Cmd/Alt held, or when the user turned shortcuts off in their preferences.
 */
export function useShortcuts(map: Record<string, (e: KeyboardEvent) => void>, inDialogs = false) {
  const me = useMe().data
  const enabled = me?.preferences.shortcuts !== false
  const ref = useRef(map)
  ref.current = map
  useEffect(() => {
    if (!enabled) return
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || isTyping(e)) return
      if (!inDialogs && document.querySelector('dialog[open]')) return
      const k = e.key.length === 1 ? e.key.toLowerCase() : e.key
      const handler = ref.current[(e.shiftKey && k !== '?' ? 'shift+' : '') + k]
      if (handler) {
        e.preventDefault()
        handler(e)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [enabled, inDialogs])
}

export const SHORTCUTS: { section: string; keys: [string, string][] }[] = [
  { section: t('Partout'), keys: [['?', t('Afficher cette aide')], ['Ctrl/⌘ + K', t('Palette de commandes')], ['C', t('Créer un ticket')], ['/', t('Rechercher')]] },
  { section: t('Board'), keys: [['J / K', t('Carte suivante / précédente')], ['N / P', t('Colonne suivante / précédente')], [t('Maj + N / Maj + P'), t('Déplacer la carte vers la colonne suivante / précédente')], [t('Entrée / O'), t('Ouvrir la carte')], [t('Espace puis flèches'), t('Glisser la carte au clavier')], [t('Échap'), t('Fermer le panneau')]] },
  { section: t('Ticket'), keys: [['E', t('Modifier le résumé')], ['A', t('Assigner')], ['I', t('M’assigner')], ['L', t('Modifier les labels')], ['M', t('Commenter')], ['W', t('Observer / ne plus observer')]] },
]
