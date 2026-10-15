// Apply the theme before first paint (copy of the user's preference, see App.tsx).
// A separate file rather than an inline script, so the Content-Security-Policy can forbid inline scripts.
try {
  var t = localStorage.getItem('fluxo.theme') || 'system'
  if (t === 'dark' || (t === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)) document.documentElement.classList.add('dark')
} catch { /* storage unavailable: keep the default theme */ }
