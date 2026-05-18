import { Injectable, signal } from '@angular/core';

export type ValidationVerdict = 'pass' | 'warn' | 'fail';

export interface ValidationCheck {
  readonly id: string;
  readonly label: string;
  readonly severity: ValidationVerdict;
  readonly observed: number;
  readonly passThreshold: number;
  readonly warnThreshold: number;
  readonly unit: string;
  readonly message: string;
}

export interface ValidationReport {
  readonly verdict: ValidationVerdict;
  readonly summary: string;
  readonly checks: readonly ValidationCheck[];
}

export interface BatchJobSummary {
  readonly id: string;
  readonly createdAt: string;
  readonly startedAt: string | null;
  readonly finishedAt: string | null;
  readonly status: 'queued' | 'running' | 'completed' | 'failed' | 'cancelled';
  readonly scenarioName: string;
  readonly nBodies: number;
  readonly nSteps: number;
  readonly dt: number;
  readonly softening: number;
  readonly seed: number;
  readonly snapshotEvery: number;
  readonly progressSteps: number;
  readonly progressPct: number;
  readonly hdf5Available: boolean;
  readonly reportAvailable: boolean;
  readonly validationAvailable: boolean;
  readonly validationVerdict: ValidationVerdict | null;
  readonly errorMessage: string | null;
}

export interface SubmitBatchJob {
  scenarioName: string;
  nSteps: number;
  dt?: number;
  softening?: number;
  seed?: number;
  snapshotEvery?: number;
}

/**
 * REST client for the headless / batch job API on simulation-service.
 *
 * <ul>
 *   <li>{@link submit} → POST /api/jobs</li>
 *   <li>{@link list}   → GET  /api/jobs</li>
 *   <li>{@link get}    → GET  /api/jobs/{id}</li>
 *   <li>{@link hdf5Url}→ download URL for the multi-snapshot HDF5</li>
 * </ul>
 */
@Injectable({ providedIn: 'root' })
export class BatchJobService {

  private static readonly API = 'http://localhost:8081/api/jobs';

  readonly jobs = signal<readonly BatchJobSummary[]>([]);

  async submit(req: SubmitBatchJob): Promise<BatchJobSummary | null> {
    try {
      const res = await fetch(BatchJobService.API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req)
      });
      if (!res.ok) {
        const err = await res.text();
        console.error('[BatchJob] submit failed:', res.status, err);
        return null;
      }
      const job: BatchJobSummary = await res.json();
      // Optimistic: refresh the list
      this.list();
      return job;
    } catch (e) {
      console.error('[BatchJob] submit error:', e);
      return null;
    }
  }

  async list(limit = 30): Promise<readonly BatchJobSummary[]> {
    try {
      const res = await fetch(`${BatchJobService.API}?limit=${limit}`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data: BatchJobSummary[] = await res.json();
      this.jobs.set(data);
      return data;
    } catch (e) {
      console.error('[BatchJob] list error:', e);
      return [];
    }
  }

  async get(id: string): Promise<BatchJobSummary | null> {
    try {
      const res = await fetch(`${BatchJobService.API}/${id}`);
      if (!res.ok) return null;
      return await res.json();
    } catch {
      return null;
    }
  }

  hdf5Url(id: string): string {
    return `${BatchJobService.API}/${id}/hdf5`;
  }

  reportUrl(id: string, kind: 'pdf' | 'tex' | 'json'): string {
    return `${BatchJobService.API}/${id}/report.${kind}`;
  }

  async validation(id: string): Promise<ValidationReport | null> {
    try {
      const res = await fetch(`${BatchJobService.API}/${id}/validation`);
      if (!res.ok) return null;
      return await res.json();
    } catch (e) {
      console.error('[BatchJob] validation error:', e);
      return null;
    }
  }
}
