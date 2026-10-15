import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { api } from '../api'
import type { Me } from '../auth'
import { Button, CenteredCard, ErrorText, Field, Input } from '../ui'
import { t } from '../i18n'

export function LoginPage() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [form, setForm] = useState({ username: '', password: '', rememberMe: false })
  const login = useMutation({
    mutationFn: () => api<Me>('/auth/login', { body: form }),
    onSuccess: (me) => {
      qc.setQueryData(['me'], me)
      void navigate(me.mustChangePassword ? '/change-password' : '/', { replace: true })
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    login.mutate()
  }
  return (
    <CenteredCard title={t('Connexion')}>
      <form onSubmit={submit} className="space-y-4">
        <Field label={t('Identifiant')}>
          <Input autoFocus autoComplete="username" value={form.username}
            onChange={(e) => setForm({ ...form, username: e.target.value })} />
        </Field>
        <Field label={t('Mot de passe')}>
          <Input type="password" autoComplete="current-password" value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })} />
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={form.rememberMe}
            onChange={(e) => setForm({ ...form, rememberMe: e.target.checked })} />
          {t('Se souvenir de moi (30 jours)')}
        </label>
        <ErrorText error={login.error} />
        <Button type="submit" className="w-full" disabled={login.isPending || !form.username || !form.password}>
          {t('Se connecter')}
        </Button>
      </form>
    </CenteredCard>
  )
}

export function SetupPage() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [form, setForm] = useState({ username: '', displayName: '', password: '', confirm: '' })
  const setup = useMutation({
    mutationFn: async () => {
      if (form.password !== form.confirm) throw new Error(t('Les mots de passe ne correspondent pas'))
      await api('/setup', { body: { username: form.username, displayName: form.displayName, password: form.password } })
      return api<Me>('/auth/login', { body: { username: form.username, password: form.password } })
    },
    onSuccess: (me) => {
      qc.setQueryData(['setup'], { needsSetup: false })
      qc.setQueryData(['me'], me)
      void navigate('/', { replace: true })
    },
  })
  return (
    <CenteredCard title={t('Premier lancement')}>
      <p className="mb-4 text-sm text-slate-600 dark:text-slate-500">{t('Créez le compte administrateur de cette instance.')}</p>
      <form onSubmit={(e) => { e.preventDefault(); setup.mutate() }} className="space-y-4">
        <Field label={t('Identifiant')}>
          <Input autoFocus value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} />
        </Field>
        <Field label={t('Nom affiché')}>
          <Input value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
        </Field>
        <Field label={t('Mot de passe')} hint={t('10 caractères minimum')}>
          <Input type="password" autoComplete="new-password" value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })} />
        </Field>
        <Field label={t('Confirmation')}>
          <Input type="password" autoComplete="new-password" value={form.confirm}
            onChange={(e) => setForm({ ...form, confirm: e.target.value })} />
        </Field>
        <ErrorText error={setup.error} />
        <Button type="submit" className="w-full" disabled={setup.isPending}>{t("Créer l'administrateur")}</Button>
      </form>
    </CenteredCard>
  )
}

export function ChangePasswordPage() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirm: '' })
  const change = useMutation({
    mutationFn: async () => {
      if (form.newPassword !== form.confirm) throw new Error(t('Les mots de passe ne correspondent pas'))
      await api('/me/password', { body: { currentPassword: form.currentPassword, newPassword: form.newPassword } })
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ['me'] })
      void navigate('/', { replace: true })
    },
  })
  return (
    <CenteredCard title={t('Changer le mot de passe')}>
      <form onSubmit={(e) => { e.preventDefault(); change.mutate() }} className="space-y-4">
        <Field label={t('Mot de passe actuel')}>
          <Input type="password" autoFocus autoComplete="current-password" value={form.currentPassword}
            onChange={(e) => setForm({ ...form, currentPassword: e.target.value })} />
        </Field>
        <Field label={t('Nouveau mot de passe')} hint={t('10 caractères minimum')}>
          <Input type="password" autoComplete="new-password" value={form.newPassword}
            onChange={(e) => setForm({ ...form, newPassword: e.target.value })} />
        </Field>
        <Field label={t('Confirmation')}>
          <Input type="password" autoComplete="new-password" value={form.confirm}
            onChange={(e) => setForm({ ...form, confirm: e.target.value })} />
        </Field>
        <ErrorText error={change.error} />
        <Button type="submit" className="w-full" disabled={change.isPending}>{t('Enregistrer')}</Button>
      </form>
    </CenteredCard>
  )
}
