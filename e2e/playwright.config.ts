import { defineConfig } from '@playwright/test'

/** End-to-end journeys of spec §5 against the Docker image (see README). */
export default defineConfig({
  testDir: './tests',
  fullyParallel: false, // journeys share one fresh instance and run in order
  workers: 1,
  timeout: 60_000,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './global-setup.ts',
  use: {
    baseURL: process.env.FLUXO_URL ?? 'http://localhost:18080',
    locale: 'fr-FR',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
