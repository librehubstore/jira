import { execSync } from 'node:child_process'

/** Starts a fresh stack (unless FLUXO_URL points at a running one) and waits for health. */
export default async function globalSetup() {
  if (process.env.FLUXO_URL) return
  execSync('docker compose -f docker-compose.e2e.yml down -v', { stdio: 'inherit', cwd: import.meta.dirname })
  execSync('docker compose -f docker-compose.e2e.yml up -d', { stdio: 'inherit', cwd: import.meta.dirname })
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    try {
      if ((await fetch('http://localhost:18080/actuator/health')).ok) return
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 1000))
  }
  throw new Error('Fluxo did not become healthy on :18080')
}
