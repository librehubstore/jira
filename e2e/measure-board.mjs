// Measures the board's first display (spec §10: 2 000 cards < 1.5 s). Usage: node measure-board.mjs [url] [space]
import { chromium } from '@playwright/test'

const url = process.argv[2] ?? 'http://localhost:5180'
const space = process.argv[3] ?? 'PERF'
const browser = await chromium.launch()
const page = await browser.newPage()
await page.goto(`${url}/login`)
await page.getByLabel('Identifiant').fill(process.env.FLUXO_USER ?? 'admin')
await page.getByLabel('Mot de passe').fill(process.env.FLUXO_PASSWORD ?? 'admin-password')
await page.getByRole('button', { name: 'Se connecter' }).click()
await page.waitForURL((u) => !u.pathname.startsWith('/login'))
for (let run = 1; run <= 3; run++) {
  const t0 = Date.now()
  await page.goto(`${url}/spaces/${space}`)
  await page.waitForFunction(() => document.querySelectorAll('[data-card]').length >= 2000, null, { timeout: 60_000 })
  const ms = Date.now() - t0
  const heap = await page.evaluate(() => (performance.memory?.usedJSHeapSize ?? 0) / 1e6)
  console.log(`run ${run}: ${ms} ms, heap ${heap.toFixed(0)} Mo`)
}
await browser.close()
