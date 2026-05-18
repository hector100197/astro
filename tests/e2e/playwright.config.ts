import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 5_000 },
  retries: process.env.CI ? 2 : 0,
  reporter: [['html', { open: 'never' }], ['list']],
  use: {
    // simulation-mfe served standalone (its own ng serve). The shell-app
    // federation composition at :4200 is exercised manually; the e2e value
    // here is the real simulation UI + backend + kernel + reports, which
    // simulation-mfe renders in full when served directly.
    baseURL: 'http://localhost:4201',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure'
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } }
  ]
});
