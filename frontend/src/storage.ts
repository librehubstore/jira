/** localStorage for per-viewer conveniences; it can be unavailable (private mode), so never throw. */
export function localStorageGet(k: string) {
  try { return localStorage.getItem(k) } catch { return null }
}

export function localStorageSet(k: string, v: string) {
  try { localStorage.setItem(k, v) } catch { /* private mode */ }
}
