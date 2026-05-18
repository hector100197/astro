import { test, expect } from '@playwright/test';

/**
 * Smoke test — verifies the dev stack is up end-to-end:
 *   simulation-service on :8081 + simulation-mfe on :4200.
 *
 * Fast fail signal for CI: if this breaks, every other spec will too.
 */
test.describe('smoke', () => {
  test('home loads with header, canvas, and batch button visible', async ({ page }) => {
    await page.goto('/');

    // Header brand chip is the most stable anchor.
    await expect(page.locator('text=astro · N-body').first()).toBeVisible({ timeout: 10_000 });

    // WebGL canvas — the actual viewer renders into <canvas>.
    await expect(page.locator('canvas').first()).toBeVisible();

    // Batch jobs button (Turno 3/4 entrypoint).
    await expect(page.getByRole('button', { name: 'Batch jobs' })).toBeVisible();
  });

  test('simulation-service /actuator/health reports UP', async ({ request }) => {
    const res = await request.get('http://localhost:8081/actuator/health');
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body.status).toBe('UP');
  });
});
