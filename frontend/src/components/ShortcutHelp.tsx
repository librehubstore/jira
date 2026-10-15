import { useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '../api'
import { useMe } from '../auth'
import { SHORTCUTS } from '../keyboard'
import { Button } from '../ui'
import { t } from '../i18n'

/** "?" help dialog; shortcuts can be turned off here (spec §4.16). */
export function ShortcutHelp({ onClose }: { onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const me = useMe().data!
  const qc = useQueryClient()
  useEffect(() => { dialog.current?.showModal() }, [])
  const enabled = me.preferences.shortcuts !== false
  return (
    <dialog ref={dialog} onClose={onClose} aria-labelledby="shortcuts-title"
      className="m-auto w-full max-w-xl rounded-xl border border-slate-200 bg-white p-5 text-slate-900 shadow-xl backdrop:bg-slate-900/40 dark:border-slate-800 dark:bg-slate-900 dark:text-slate-100">
      <h2 id="shortcuts-title" className="mb-3 text-lg font-semibold">{t('Raccourcis clavier')}</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        {SHORTCUTS.map((s) => (
          <section key={s.section}>
            <h3 className="mb-1 text-sm font-semibold text-slate-500">{s.section}</h3>
            <dl className="space-y-1 text-sm">
              {s.keys.map(([k, label]) => (
                <div key={k} className="flex justify-between gap-3">
                  <dt>{label}</dt>
                  <dd><kbd className="rounded border border-slate-300 px-1.5 font-mono text-xs dark:border-slate-600">{k}</kbd></dd>
                </div>
              ))}
            </dl>
          </section>
        ))}
      </div>
      <div className="mt-4 flex items-center justify-between">
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={enabled} onChange={async (e) => {
            qc.setQueryData(['me'], await api('/me', { method: 'PATCH', body: { preferences: { shortcuts: e.target.checked } } }))
          }} />
          {t('Raccourcis à une touche activés')}
        </label>
        <Button autoFocus onClick={() => dialog.current?.close()}>{t('Fermer')}</Button>
      </div>
    </dialog>
  )
}
