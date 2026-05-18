import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * Headless flow + validation badge — covers the entire Turno 3/4 surface:
 *
 *   1. Submit a job via REST (faster + more deterministic than driving the form).
 *   2. Poll until completion.
 *   3. Reload the MFE, open the Batch jobs drawer.
 *   4. Locate the new job by its id prefix.
 *   5. Assert the verdict badge renders with the right colour.
 *   6. Click the badge → panel expands with 6 per-check cards.
 *   7. Click again → panel collapses.
 *
 * Two parameterisations: one that should pass (NBODY6-grade) and one engineered
 * to fail (dt=0.05 → energy conservation blows up). Together they validate the
 * green and red badge paths.
 */

const API = 'http://localhost:8081/api/jobs';

interface JobDto {
  id: string;
  status: string;
  validationAvailable: boolean;
  validationVerdict: 'pass' | 'warn' | 'fail' | null;
}

async function submitJob(req: APIRequestContext, dt: number): Promise<string> {
  const res = await req.post(API, {
    data: {
      scenarioName: 'pleiades',
      // 2000 steps is plenty for the verdict assertions (energy/L/virial
      // conservation at dt=0.005 does not need 5000) and keeps the run
      // inside the test budget on a slow CI runner.
      nSteps: dt === 0.05 ? 500 : 2000,
      dt,
      softening: 0.01,
      seed: 42,
      snapshotEvery: dt === 0.05 ? 10 : 50,
    },
  });
  expect(res.ok(), `submit failed: ${res.status()}`).toBeTruthy();
  const job: JobDto = await res.json();
  return job.id;
}

async function waitForCompletion(req: APIRequestContext, id: string): Promise<JobDto> {
  // Pleiades N=3000 + 5000 steps completes in ~30s on the dev box, but a
  // shared CI runner is several times slower. Stay under the 240s test budget.
  const deadline = Date.now() + 210_000;
  while (Date.now() < deadline) {
    const res = await req.get(`${API}/${id}`);
    const job: JobDto = await res.json();
    if (job.status === 'completed' && job.validationAvailable) return job;
    if (job.status === 'failed') throw new Error(`job ${id} failed unexpectedly`);
    await new Promise((r) => setTimeout(r, 1500));
  }
  throw new Error(`job ${id} did not complete within 120s`);
}

test.describe('batch validation badge', () => {
  test('pleiades dt=0.005 → ✓ NBODY6-grade badge + 6 passing checks', async ({ page, request }) => {
    // 1-2. Submit + wait.
    const id = await submitJob(request, 0.005);
    const job = await waitForCompletion(request, id);
    expect(job.validationVerdict).toBe('pass');

    // 3. Open the MFE and the batch drawer.
    await page.goto('/');
    await page.getByRole('button', { name: 'Batch jobs' }).click();
    const drawer = page.locator('aside.drawer');
    await expect(drawer).toBeVisible();

    // 4. Find the job row (id prefix is rendered as "<8chars>…").
    const idPrefix = id.slice(0, 8);
    const row = drawer.locator('li', { hasText: idPrefix });
    await expect(row).toBeVisible({ timeout: 10_000 });

    // 5. The pass badge.
    const badge = row.locator('.verdict.v--pass');
    await expect(badge).toHaveText(/NBODY6-grade/);

    // 6. Expand → 6 checks, all marked pass.
    await badge.click();
    const panel = row.locator('.vpanel');
    await expect(panel).toBeVisible();
    await expect(panel.locator('.vsummary')).toHaveText(/NBODY6-grade/);
    const passChecks = panel.locator('.vcheck.c--pass');
    await expect(passChecks).toHaveCount(6);
    await expect(panel.locator('.vcheck.c--fail')).toHaveCount(0);
    await expect(panel.locator('.vcheck.c--warn')).toHaveCount(0);

    // Check labels — guards against accidental copy renaming.
    await expect(panel).toContainText('Energy conservation');
    await expect(panel).toContainText('Angular momentum');
    await expect(panel).toContainText('Virial equilibrium');
    await expect(panel).toContainText('Half-mass radius');
    await expect(panel).toContainText('Escaper fraction');

    // 7. Toggle off.
    await badge.click();
    await expect(panel).not.toBeVisible();
  });

  test('pleiades dt=0.05 → ✗ Failed badge with 2 fail + 4 pass checks', async ({ page, request }) => {
    const id = await submitJob(request, 0.05);
    const job = await waitForCompletion(request, id);
    expect(job.validationVerdict).toBe('fail');

    await page.goto('/');
    await page.getByRole('button', { name: 'Batch jobs' }).click();

    const drawer = page.locator('aside.drawer');
    const row = drawer.locator('li', { hasText: id.slice(0, 8) });
    await expect(row).toBeVisible({ timeout: 10_000 });

    const badge = row.locator('.verdict.v--fail');
    await expect(badge).toHaveText(/Failed/);

    await badge.click();
    const panel = row.locator('.vpanel');
    await expect(panel.locator('.vsummary')).toHaveText(/Failed/);
    // Two energy checks should fail; the other four should pass.
    await expect(panel.locator('.vcheck.c--fail')).toHaveCount(2);
    await expect(panel.locator('.vcheck.c--pass')).toHaveCount(4);
  });
});
