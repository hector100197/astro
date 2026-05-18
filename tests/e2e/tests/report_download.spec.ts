import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * Report bundle download — smoke-tests the three artifact endpoints we expose
 * for every completed job:
 *
 *   GET /api/jobs/{id}/report.pdf   → application/pdf, starts with %PDF
 *   GET /api/jobs/{id}/report.tex   → text/plain,      starts with \documentclass
 *   GET /api/jobs/{id}/report.json  → application/json, parseable, has expected keys
 *
 * Also exercises the GET /api/jobs/{id}/validation endpoint.
 */

const API = 'http://localhost:8081/api/jobs';

async function findOrCreateCompletedJob(req: APIRequestContext): Promise<string> {
  // Reuse the most recent passing job if one exists — saves ~30s in CI loops.
  const list = await (await req.get(`${API}?limit=20`)).json();
  const existing = list.find(
    (j: any) => j.status === 'completed' && j.reportAvailable && j.validationAvailable
  );
  if (existing) return existing.id;

  const created = await req.post(API, {
    data: {
      scenarioName: 'pleiades',
      nSteps: 5000,
      dt: 0.005,
      softening: 0.01,
      seed: 42,
      snapshotEvery: 50,
    },
  });
  const job = await created.json();
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    const r = await (await req.get(`${API}/${job.id}`)).json();
    if (r.status === 'completed' && r.reportAvailable) return job.id;
    await new Promise((r) => setTimeout(r, 1500));
  }
  throw new Error('did not get a completed job in 120s');
}

test.describe('report downloads', () => {
  let jobId: string;

  test.beforeAll(async ({ request }) => {
    jobId = await findOrCreateCompletedJob(request);
  });

  test('GET /report.pdf returns a valid PDF', async ({ request }) => {
    const res = await request.get(`${API}/${jobId}/report.pdf`);
    expect(res.ok()).toBeTruthy();
    expect(res.headers()['content-type']).toContain('application/pdf');
    const body = await res.body();
    expect(body.length).toBeGreaterThan(800);
    expect(body.subarray(0, 5).toString()).toBe('%PDF-');
  });

  test('GET /report.tex returns LaTeX source', async ({ request }) => {
    const res = await request.get(`${API}/${jobId}/report.tex`);
    expect(res.ok()).toBeTruthy();
    const text = await res.text();
    expect(text).toMatch(/^\\documentclass/);
    expect(text).toContain('\\begin{document}');
    expect(text).toContain('\\section*{Conservation diagnostics}');
    expect(text).toContain('\\section*{Binary catalog}');
  });

  test('GET /report.json returns the analysis payload', async ({ request }) => {
    const res = await request.get(`${API}/${jobId}/report.json`);
    expect(res.ok()).toBeTruthy();
    const data = await res.json();
    expect(data.scenario).toBeTruthy();
    expect(data.nBodies).toBeGreaterThan(0);
    expect(data.timeline.length).toBeGreaterThan(0);
    expect(data.conservation).toHaveProperty('dE_over_E_initial');
    expect(data.conservation).toHaveProperty('dL_over_L_initial');
  });

  test('GET /validation returns the 6-check report', async ({ request }) => {
    const res = await request.get(`${API}/${jobId}/validation`);
    expect(res.ok()).toBeTruthy();
    const v = await res.json();
    expect(['pass', 'warn', 'fail']).toContain(v.verdict);
    expect(v.summary).toBeTruthy();
    expect(v.checks).toHaveLength(6);
    const ids = v.checks.map((c: any) => c.id);
    expect(ids).toContain('energy_final');
    expect(ids).toContain('energy_worst');
    expect(ids).toContain('angular_momentum');
    expect(ids).toContain('virial');
    expect(ids).toContain('r50_stability');
    expect(ids).toContain('escaper_fraction');
  });

  test('unknown job → 404 on /report.pdf', async ({ request }) => {
    const fakeId = '00000000-0000-0000-0000-000000000000';
    const res = await request.get(`${API}/${fakeId}/report.pdf`);
    expect(res.status()).toBe(404);
  });
});
