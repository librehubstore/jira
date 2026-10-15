/** Thin fetch wrapper: JSON in/out, CSRF header, RFC 9457 errors surfaced as ApiError. */
import { getLocale, t } from './i18n'
import { API_ERRORS_EN } from './i18n.en'

export class ApiError extends Error {
  status: number
  code?: string
  /** Extra RFC 9457 properties (e.g. `position` of an FQL error). */
  details: Record<string, unknown>
  constructor(status: number, message: string, code?: string, details: Record<string, unknown> = {}) {
    super(message)
    this.status = status
    this.code = code
    this.details = details
  }
}

function csrfToken() {
  return decodeURIComponent(document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/)?.[1] ?? '')
}

export async function api<T = void>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const method = init.method ?? (init.body === undefined ? 'GET' : 'POST')
  const isForm = init.body instanceof FormData
  const res = await fetch(`/api${path}`, {
    method,
    credentials: 'same-origin',
    headers: {
      ...(init.body !== undefined && !isForm ? { 'Content-Type': 'application/json' } : {}),
      ...(method !== 'GET' ? { 'X-XSRF-TOKEN': csrfToken() } : {}),
    },
    body: init.body === undefined ? undefined : isForm ? (init.body as FormData) : JSON.stringify(init.body),
  })
  if (!res.ok) {
    let detail = res.statusText
    let code: string | undefined
    let details: Record<string, unknown> = {}
    try {
      const p = await res.json()
      details = p
      detail = p.detail ?? detail
      code = p.code
      if (getLocale() === 'en' && code && API_ERRORS_EN[code]) detail = API_ERRORS_EN[code]
      if (!p.detail && p.errors) detail = t('Données invalides')
    } catch {
      /* empty body */
    }
    throw new ApiError(res.status, detail, code, details)
  }
  if (res.status === 204 || res.headers.get('content-length') === '0') return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}
