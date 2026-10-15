import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api } from '../api'
import { useMe, type Me } from '../auth'
import { EVENT_LABELS } from '../components/NotificationBell'
import { ApiTokens } from './IntegrationPages'
import { Avatar } from '../components/Badges'
import { Button, ErrorText, Field, Input } from '../ui'
import { getLocale, syncLocale, t } from '../i18n'

const EVENTS: Record<string, string> = {
  CREATED: t('Ticket créé'), ASSIGNED: t('Ticket assigné'), UPDATED: t('Champ modifié'), TRANSITIONED: t('Statut changé'),
  RESOLVED: t('Ticket résolu'), REOPENED: t('Ticket rouvert'), COMMENTED: t('Nouveau commentaire'), COMMENT_EDITED: t('Commentaire modifié'),
  MENTIONED: t('Mention'), DELETED: t('Ticket supprimé'), MOVED: t('Ticket déplacé'), BLOCKER_RESOLVED: t('Bloquant résolu'),
}

/** Profile and preferences (spec §4.1, §9). */
export function PreferencesPage() {
  const me = useMe().data!
  const qc = useQueryClient()
  const [profile, setProfile] = useState({ displayName: me.displayName, email: me.email ?? '' })
  const save = useMutation({
    mutationFn: (body: Record<string, unknown>) => api<Me>('/me', { method: 'PATCH', body }),
    onSuccess: (m) => qc.setQueryData(['me'], m),
  })
  const prefs = me.preferences
  const muted = (prefs.notifications ?? {}) as Record<string, boolean>
  const mutedEmail = (prefs.emailNotifications ?? {}) as Record<string, boolean>
  const setPref = (k: string, v: unknown) => save.mutate({ preferences: { [k]: v } })

  return (
    <div className="mx-auto max-w-2xl space-y-8 p-6">
      <h1 className="text-xl font-semibold">{t('Préférences')}</h1>
      <ErrorText error={save.error} />
      <section className="space-y-3">
        <h2 className="text-lg font-semibold">{t('Profil')}</h2>
        <div className="flex items-center gap-3">
          <Avatar user={{ id: me.id, username: me.username, displayName: me.displayName, active: true, avatar: me.avatar }} size={8} zoom={false} />
          <label className="cursor-pointer text-sm text-indigo-600 hover:underline">
            {t('Changer la photo')}
            <input type="file" accept="image/png,image/jpeg,image/gif" className="sr-only" onChange={async (e) => {
              const file = e.target.files?.[0]
              if (!file) return
              const form = new FormData()
              form.append('file', file)
              await api('/me/avatar', { body: form })
              void qc.invalidateQueries()
            }} />
          </label>
          {me.avatar && <button className="text-sm text-slate-500 hover:underline" onClick={async () => { await api('/me/avatar', { method: 'DELETE' }); void qc.invalidateQueries() }}>{t('Retirer la photo')}</button>}
        </div>
        <form className="grid gap-3 sm:grid-cols-2" onSubmit={(e) => { e.preventDefault(); save.mutate(profile) }}>
          <Field label={t('Nom affiché')}><Input value={profile.displayName} onChange={(e) => setProfile({ ...profile, displayName: e.target.value })} /></Field>
          <Field label={t('Email (facultatif)')}><Input type="email" value={profile.email} onChange={(e) => setProfile({ ...profile, email: e.target.value })} /></Field>
          <div className="flex items-center gap-3 sm:col-span-2">
            <Button type="submit">{t('Enregistrer')}</Button>
            <Link to="/change-password" className="text-sm text-indigo-600 hover:underline">{t('Changer de mot de passe')}</Link>
          </div>
        </form>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-semibold">{t('Apparence')}</h2>
        <div className="flex gap-4 text-sm" role="radiogroup" aria-label={t('Langue')}>
          {[['fr', 'Français'], ['en', 'English']].map(([v, label]) => (
            <label key={v} className="flex items-center gap-1.5">
              <input type="radio" name="locale" checked={getLocale() === v}
                onChange={async () => { qc.setQueryData(['me'], await api<Me>('/me', { method: 'PATCH', body: { preferences: { locale: v } } })); syncLocale(v) }} /> {label}
            </label>
          ))}
        </div>
        <div className="flex gap-4 text-sm" role="radiogroup" aria-label={t('Thème')}>
          {[['system', t('Comme le système')], ['light', t('Clair')], ['dark', t('Sombre')]].map(([v, label]) => (
            <label key={v} className="flex items-center gap-1.5">
              <input type="radio" name="theme" checked={(prefs.theme ?? 'system') === v} onChange={() => setPref('theme', v)} /> {label}
            </label>
          ))}
        </div>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-semibold">{t('Travail')}</h2>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={prefs.autoWatch !== false} onChange={(e) => setPref('autoWatch', e.target.checked)} />
          {t('Observer automatiquement les tickets que je commente ou modifie')}
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={prefs.editor === 'markdown'} onChange={(e) => setPref('editor', e.target.checked ? 'markdown' : 'rich')} />
          {t('Éditer en markdown brut plutôt qu’en éditeur riche')}
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={prefs.commentOrder === 'newest'} onChange={(e) => setPref('commentOrder', e.target.checked ? 'newest' : 'oldest')} />
          {t('Afficher l’activité la plus récente en premier')}
        </label>
      </section>

      <ApiTokens />

      <section className="space-y-2">
        <h2 className="text-lg font-semibold">{t('Notifications')}</h2>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={prefs.notifyOnlyDirect === true} onChange={(e) => setPref('notifyOnlyDirect', e.target.checked)} />
          {t('Seulement quand je suis mentionné, assigné, ou sur mes tickets assignés')}
        </label>
        <p className="text-sm text-slate-500">{t('Les emails partent si l’administrateur a configuré l’envoi et que votre email est renseigné ; les changements d’un ticket sur 2 minutes sont regroupés.')}</p>
        <table className="text-sm">
          <thead className="text-left text-xs uppercase text-slate-500">
            <tr><th className="py-1 pr-6">{t('Événement')}</th><th className="pr-4">{t('Application')}</th><th>{t('Email')}</th></tr>
          </thead>
          <tbody>
            {Object.entries(EVENTS).map(([ev, label]) => (
              <tr key={ev} title={t('Quelqu’un {action} un ticket', { action: EVENT_LABELS[ev] })}>
                <td className="py-0.5 pr-6">{label}</td>
                <td><input type="checkbox" aria-label={t('{event} dans l’application', { event: label })} checked={muted[ev] !== false}
                  onChange={(e) => setPref('notifications', { ...muted, [ev]: e.target.checked })} /></td>
                <td><input type="checkbox" aria-label={t('{event} par email', { event: label })} checked={mutedEmail[ev] !== false}
                  onChange={(e) => setPref('emailNotifications', { ...mutedEmail, [ev]: e.target.checked })} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  )
}
