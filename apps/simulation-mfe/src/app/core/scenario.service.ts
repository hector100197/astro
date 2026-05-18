import { Injectable, inject, signal } from '@angular/core';

/**
 * Lightweight scenario summary returned by {@code GET /api/scenarios}.
 */
export interface ScenarioSummary {
  readonly name: string;
  readonly displayName: string;
  readonly description?: string;
  readonly nBodies: number;
  readonly icType: string;
  readonly supported: boolean;
}

/**
 * Persisted simulation run summary returned by {@code GET /api/runs}.
 */
export interface RunSummary {
  readonly id: string;
  readonly createdAt: string;
  readonly finishedAt: string | null;
  readonly status: string;
  readonly mode: string;
  readonly scenarioName: string | null;
  readonly hdf5Path: string | null;
  readonly manifest: Record<string, any>;
}

/**
 * REST clients for the scenario catalog and the run history.
 *
 * <p>Both fetched on demand via the standard {@code fetch} API — no need to
 * pull in HttpClient for these one-shot reads.
 */
@Injectable({ providedIn: 'root' })
export class ScenarioService {

  private static readonly API = 'http://localhost:8081/api';

  readonly scenarios = signal<readonly ScenarioSummary[]>([]);
  readonly runs = signal<readonly RunSummary[]>([]);

  async loadScenarios(): Promise<readonly ScenarioSummary[]> {
    try {
      const res = await fetch(`${ScenarioService.API}/scenarios`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data: ScenarioSummary[] = await res.json();
      this.scenarios.set(data);
      return data;
    } catch (e) {
      console.error('[ScenarioService] failed to load scenarios:', e);
      return [];
    }
  }

  async loadRuns(limit = 30): Promise<readonly RunSummary[]> {
    try {
      const res = await fetch(`${ScenarioService.API}/runs?limit=${limit}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data: RunSummary[] = await res.json();
      this.runs.set(data);
      return data;
    } catch (e) {
      console.error('[ScenarioService] failed to load runs:', e);
      return [];
    }
  }

  /**
   * Returns the URL used by an `<a download>` to fetch the HDF5 file for a run.
   * The server sets Content-Disposition so the browser will save with the
   * canonical name {runId}.h5.
   */
  hdf5Url(runId: string): string {
    return `${ScenarioService.API}/runs/${runId}/hdf5`;
  }

  /**
   * Upload a custom scenario YAML. Backend registers it ephemerally and
   * returns the assigned name (use it with sendScenarioCommand to load).
   */
  async uploadCustomScenario(yamlBody: string): Promise<{ name: string; nBodies?: number; icType?: string } | { error: string }> {
    try {
      const res = await fetch(`${ScenarioService.API}/scenarios/custom`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain' },
        body: yamlBody,
      });
      const data = await res.json();
      if (!res.ok) return { error: data?.error ?? `HTTP ${res.status}` };
      // Refresh catalog so the new scenario shows in dropdowns immediately.
      this.loadScenarios();
      return data;
    } catch (e) {
      return { error: String(e) };
    }
  }

  /**
   * Fetch a real cluster from Gaia DR3 by name (`pleiades`, `hyades`, `m67`).
   * Wall time is dominated by the Gaia archive round-trip (~10–30 s), so the
   * caller should show a loading state.
   */
  async importGaiaCluster(cluster: string):
      Promise<{ name: string; displayName?: string; nBodies?: number; icType?: string } | { error: string }> {
    try {
      const res = await fetch(
        `${ScenarioService.API}/scenarios/import-gaia?cluster=${encodeURIComponent(cluster)}`,
        { method: 'POST' }
      );
      const data = await res.json();
      if (!res.ok) return { error: data?.error ?? `HTTP ${res.status}` };
      // The new scenario is now on disk → reload the catalog so dropdowns see it.
      await this.loadScenarios();
      return data;
    } catch (e) {
      return { error: String(e) };
    }
  }
}
