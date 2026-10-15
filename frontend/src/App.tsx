import { useEffect, useState } from 'react'
import { localStorageSet } from './storage'
import { useQueryClient } from '@tanstack/react-query'
import { Link, Navigate, NavLink, Outlet, Route, Routes, useLocation, useNavigate, useParams } from 'react-router'
import { api } from './api'
import { useMe, useSetupStatus } from './auth'
import { AdminUsersPage } from './pages/AdminUsersPage'
import { ChangePasswordPage, LoginPage, SetupPage } from './pages/AuthPages'
import { SpaceSettingsPage } from './pages/SpaceSettingsPage'
import { SpacesPage } from './pages/SpacesPage'
import { SpaceLayout } from './pages/SpaceLayout'
import { CreateTicketDialog } from './components/CreateTicketDialog'
import { TicketView } from './components/TicketView'
import { useRealtime } from './realtime'
import { BacklogPage } from './board/BacklogPage'
import { WorkflowPage } from './pages/WorkflowPage'
import { CatalogPage } from './pages/CatalogPage'
import { AdminLayout, CustomFieldsAdmin } from './pages/AdminPages'
import { ReferenceAdmin } from './pages/ReferenceAdmin'
import { WebhooksAdmin } from './pages/IntegrationPages'
import { CfdPage } from './board/CfdPage'
import { useShortcuts } from './keyboard'
import { CommandPalette } from './components/CommandPalette'
import { ShortcutHelp } from './components/ShortcutHelp'
import { SpaceBoardPage } from './board/BoardPage'
import { BoardSettingsPage } from './board/BoardSettingsPage'
import { GlobalSearch } from './components/GlobalSearch'
import { SearchPage } from './pages/SearchPage'
import { NotificationBell } from './components/NotificationBell'
import { PreferencesPage } from './pages/PreferencesPage'
import { syncLocale, t } from './i18n'

export default function App() {
  const setup = useSetupStatus()
  const me = useMe()
  useTheme((me.data?.preferences.theme as string | undefined) ?? 'system')
  useEffect(() => { if (me.data) syncLocale(me.data.preferences.locale) }, [me.data])
  if (setup.isPending || me.isPending) return null
  if (setup.data?.needsSetup) {
    return <Routes><Route path="*" element={<SetupPage />} /></Routes>
  }
  if (!me.data) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    )
  }
  if (me.data.mustChangePassword) {
    return <Routes><Route path="*" element={<ChangePasswordPage />} /></Routes>
  }
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route path="/" element={<SpacesPage />} />
        <Route path="/spaces/:key" element={<SpaceLayout />}>
          <Route index element={<SpaceBoardPage />} />
          <Route path="boards/:boardId" element={<SpaceBoardPage />} />
          <Route path="boards/:boardId/settings" element={<BoardSettingsPage />} />
          <Route path="boards/:boardId/backlog" element={<BacklogPage />} />
          <Route path="boards/:boardId/cfd" element={<CfdPage />} />
          <Route path="settings" element={<SpaceSettingsPage />} />
          <Route path="workflow" element={<WorkflowPage />} />
          <Route path="catalog" element={<CatalogPage />} />
        </Route>
        <Route path="/browse/:ticketKey" element={<TicketPage />} />
        <Route path="/search" element={<SearchPage />} />
        <Route path="/preferences" element={<PreferencesPage />} />
        {me.data.admin && (
          <Route path="/admin" element={<AdminLayout />}>
            <Route path="users" element={<AdminUsersPage />} />
            <Route path="fields" element={<CustomFieldsAdmin />} />
            <Route path="reference" element={<ReferenceAdmin />} />
            <Route path="webhooks" element={<WebhooksAdmin />} />
          </Route>
        )}
        <Route path="/change-password" element={<ChangePasswordPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

function Layout() {
  const me = useMe().data!
  const qc = useQueryClient()
  const navigate = useNavigate()
  const logout = async () => {
    await api('/auth/logout', { body: {} })
    qc.clear()
    void navigate('/login', { replace: true })
  }
  const [creating, setCreating] = useState(false)
  const location = useLocation()
  const contextSpace = location.pathname.match(/^\/(?:spaces\/([A-Z0-9]+)|browse\/([A-Z0-9]+)-\d+)/)
  const [help, setHelp] = useState(false)
  useShortcuts({ c: () => setCreating(true), '?': () => setHelp(true) })
  useRealtime()
  const nav = ({ isActive }: { isActive: boolean }) =>
    `rounded px-2 py-1 text-sm ${isActive ? 'bg-slate-100 font-medium dark:bg-slate-800' : 'hover:bg-slate-100 dark:hover:bg-slate-800'}`
  return (
    <div className="min-h-screen">
      <header className="flex h-12 items-center gap-4 border-b border-slate-200 px-4 dark:border-slate-800">
        <Link to="/" className="font-semibold text-indigo-600">{t('Fluxo')}</Link>
        <nav className="flex gap-1">
          <NavLink to="/" end className={nav}>{t('Espaces')}</NavLink>
          <NavLink to="/search" className={nav}>{t('Tickets')}</NavLink>
          {me.admin && <NavLink to="/admin/users" className={nav}>{t('Administration')}</NavLink>}
        </nav>
        <button onClick={() => setCreating(true)} title={t('Créer un ticket (C)')}
          className="rounded-md bg-indigo-600 px-2.5 py-1 text-sm font-medium text-white hover:bg-indigo-500">{t('+ Créer')}</button>
        <div className="ml-auto flex items-center gap-3 text-sm">
          <GlobalSearch />
          <NotificationBell />
          <Link to="/preferences" className="text-slate-600 hover:underline dark:text-slate-500">{me.displayName}</Link>
          <button onClick={logout} className="text-slate-500 hover:text-slate-900 dark:hover:text-slate-100">{t('Déconnexion')}</button>
        </div>
      </header>
      <Outlet />
      <CommandPalette onCreate={() => setCreating(true)} />
      {help && <ShortcutHelp onClose={() => setHelp(false)} />}
      {creating && <CreateTicketDialog defaultSpaceKey={contextSpace?.[1] ?? contextSpace?.[2]} onClose={() => setCreating(false)} />}
    </div>
  )
}

function TicketPage() {
  const { ticketKey } = useParams()
  return <div className="mx-auto h-[calc(100vh-3rem)] max-w-6xl"><TicketView key={ticketKey} ticketKey={ticketKey!.toUpperCase()} /></div>
}

/** Light / dark / system theme (spec §10), kept in the user's preferences. */
function useTheme(theme: string) {
  useEffect(() => {
    localStorageSet('fluxo.theme', theme)
    const media = matchMedia('(prefers-color-scheme: dark)')
    const apply = () => document.documentElement.classList.toggle('dark', theme === 'dark' || (theme === 'system' && media.matches))
    apply()
    media.addEventListener('change', apply)
    return () => media.removeEventListener('change', apply)
  }, [theme])
}
