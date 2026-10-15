import { NavLink, Outlet, useParams } from 'react-router'
import { can, useSpace } from '../spaces'
import { ErrorText } from '../ui'
import { t } from '../i18n'

/** Space sub-navigation; children read the space from the shared query cache. */
export function SpaceLayout() {
  const { key } = useParams()
  const space = useSpace(key)
  if (space.error) return <div className="p-6"><ErrorText error={space.error} /></div>
  if (!space.data) return null
  const s = space.data
  const tab = ({ isActive }: { isActive: boolean }) =>
    `border-b-2 px-1 pb-2 text-sm ${isActive ? 'border-indigo-600 font-medium' : 'border-transparent text-slate-500 hover:text-slate-900 dark:hover:text-slate-100'}`
  return (
    <div>
      <div className="flex items-end gap-4 border-b border-slate-200 px-6 pt-4 dark:border-slate-800">
        <div className="pb-2">
          <span className="mr-2 rounded bg-slate-100 px-1.5 font-mono text-xs dark:bg-slate-800">{s.key}</span>
          <span className="font-semibold">{s.name}</span>
          {s.archived && <span className="ml-2 text-xs text-amber-600">{t('archivé')}</span>}
        </div>
        <nav className="flex gap-4">
          <NavLink end to={`/spaces/${s.key}`} className={tab}>{t('Board')}</NavLink>
          {(can(s, 'ADMINISTER_SPACE') || can(s, 'MANAGE_VERSIONS')) && <NavLink to={`/spaces/${s.key}/catalog`} className={tab}>{t('Référentiels')}</NavLink>}
          {can(s, 'ADMINISTER_SPACE') && <NavLink to={`/spaces/${s.key}/workflow`} className={tab}>{t('Workflow')}</NavLink>}
          {can(s, 'ADMINISTER_SPACE') && <NavLink to={`/spaces/${s.key}/settings`} className={tab}>{t('Paramètres')}</NavLink>}
        </nav>
      </div>
      <Outlet />
    </div>
  )
}
