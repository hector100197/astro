import {
  Component,
  EventEmitter,
  OnDestroy,
  OnInit,
  Output,
  inject,
  signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BatchJobService, BatchJobSummary, ValidationReport } from '../core/batch-job.service';
import { ScenarioService } from '../core/scenario.service';

/**
 * Drawer for submitting and tracking long-running batch jobs (headless mode).
 *
 * <p>Form: scenario dropdown (reuses catalog), nSteps, snapshotEvery →
 * POST /api/jobs.
 *
 * <p>List: polls /api/jobs every 2 sec while open. Shows progress bar,
 * status, download button when finished.
 */
@Component({
  selector: 'app-batch-jobs',
  standalone: true,
  imports: [FormsModule],
  template: `
    <aside class="drawer">
      <header>
        <span class="title" i18n>Batch jobs ({{ jobs().length }})</span>
        <button class="ghost" (click)="refresh()" type="button" i18n-title title="Recargar">⟳</button>
        <button class="ghost close" (click)="close.emit()" type="button" aria-label="Cerrar">✕</button>
      </header>

      <section class="form gaia">
        <h4 i18n>Importar cluster real (Gaia DR3)</h4>
        <div class="gaia-row">
          <select [(ngModel)]="gaiaCluster" [disabled]="gaiaImporting()">
            <option value="pleiades">Pleiades (M45)</option>
            <option value="hyades">Hyades</option>
            <option value="m67">M67</option>
          </select>
          <button class="primary small" (click)="onImportGaia()" [disabled]="gaiaImporting()" type="button">
            {{ gaiaImporting() ? 'Importando…' : '↓ Gaia DR3' }}
          </button>
        </div>
        @if (gaiaMessage()) {
          <p class="hint" [class.error]="gaiaError()">{{ gaiaMessage() }}</p>
        }
      </section>

      <hr />

      <section class="form">
        <h4 i18n>Lanzar nuevo job</h4>
        <label>
          <span i18n>Escenario</span>
          <select [(ngModel)]="scenarioName">
            @for (sc of scenarios(); track sc.name) {
              @if (sc.supported) {
                <option [value]="sc.name">{{ sc.displayName }} (N={{ sc.nBodies }})</option>
              }
            }
          </select>
        </label>
        <label>
          <span i18n>Pasos totales</span>
          <input type="number" [(ngModel)]="nSteps" min="100" step="1000" />
        </label>
        <label>
          <span i18n>Snapshot cada N pasos</span>
          <input type="number" [(ngModel)]="snapshotEvery" min="1" step="50" />
        </label>
        <label>
          Δt
          <input type="number" [(ngModel)]="dt" step="0.001" min="0.0001" />
        </label>
        <button class="primary" (click)="onSubmit()" [disabled]="submitting()" i18n>
          {{ submitting() ? 'Enviando…' : '▶ Lanzar job' }}
        </button>
        @if (submitError()) {
          <p class="error">{{ submitError() }}</p>
        }
      </section>

      <hr />

      @if (jobs().length === 0) {
        <p class="hint" i18n>Ningún job todavía. Llena el formulario y lanza uno.</p>
      } @else {
        <ul>
          @for (j of jobs(); track j.id) {
            <li>
              <div class="row">
                <span class="badge st--{{ j.status }}">{{ j.status }}</span>
                @if (j.validationAvailable && j.validationVerdict) {
                  <button class="verdict v--{{ j.validationVerdict }}"
                          (click)="toggleValidation(j.id)" type="button"
                          [attr.aria-expanded]="expandedJobId() === j.id"
                          [title]="verdictTitle(j.validationVerdict)">
                    {{ verdictIcon(j.validationVerdict) }} {{ verdictLabel(j.validationVerdict) }}
                  </button>
                }
                <span class="meta">{{ j.scenarioName }} · N={{ j.nBodies }}</span>
              </div>
              @if (expandedJobId() === j.id) {
                @let v = validationCache()[j.id];
                @if (v) {
                  <div class="vpanel">
                    <p class="vsummary">{{ v.summary }}</p>
                    <ul class="vchecks">
                      @for (c of v.checks; track c.id) {
                        <li class="vcheck c--{{ c.severity }}">
                          <div class="vrow">
                            <span class="vlabel">{{ c.label }}</span>
                            <span class="vsev">{{ verdictIcon(c.severity) }}</span>
                          </div>
                          <div class="vrow vnums">
                            <span>observed: <code>{{ formatNum(c.observed) }}</code></span>
                            <span class="vthr">pass ≤ {{ formatNum(c.passThreshold) }} · warn ≤ {{ formatNum(c.warnThreshold) }}</span>
                          </div>
                          <div class="vmsg">{{ c.message }}</div>
                        </li>
                      }
                    </ul>
                  </div>
                } @else {
                  <p class="hint" i18n>Cargando validación…</p>
                }
              }
              @if (j.status === 'running' || j.progressPct > 0) {
                <div class="bar">
                  <div class="fill" [style.width.%]="j.progressPct"></div>
                </div>
                <div class="meta">{{ j.progressSteps }}/{{ j.nSteps }} pasos ({{ j.progressPct }}%)</div>
              }
              @if (j.status === 'failed') {
                <div class="meta error">{{ j.errorMessage }}</div>
              }
              <div class="row">
                <code class="id">{{ j.id.slice(0, 8) }}…</code>
                @if (j.hdf5Available) {
                  <button class="replay" (click)="onReplay(j.id)" type="button"
                          i18n-title title="Reproducir este run en el viewer">▶ Replay</button>
                  <a class="download" [href]="downloadUrl(j.id)" download>↓ HDF5</a>
                }
                @if (j.reportAvailable) {
                  <a class="download report" [href]="reportUrl(j.id, 'pdf')" download
                     i18n-title title="Reporte científico (PDF con plots, binarios, escapers)">📄 PDF</a>
                  <a class="download report tex" [href]="reportUrl(j.id, 'tex')" download
                     i18n-title title="Fuente LaTeX para edición / Overleaf">.tex</a>
                  <a class="download report json" [href]="reportUrl(j.id, 'json')" download
                     i18n-title title="Datos crudos del análisis">JSON</a>
                }
              </div>
            </li>
          }
        </ul>
      }
    </aside>
  `,
  styles: [`
    :host { display: block; }
    .drawer {
      position: absolute; left: 0.75rem; top: 4rem; bottom: 0.75rem; z-index: 11;
      width: 360px; max-height: calc(100vh - 5rem);
      background: rgba(11, 18, 32, 0.92); color: #e5e7eb;
      border: 1px solid #1f2937; border-radius: 8px;
      backdrop-filter: blur(6px);
      display: flex; flex-direction: column; padding: 0.75rem;
    }
    header {
      display: flex; align-items: center; gap: 0.5rem;
      padding-bottom: 0.5rem; border-bottom: 1px solid #1f2937; margin-bottom: 0.5rem;
    }
    .title { flex: 1; font-weight: 600; font-size: 0.95rem; }
    .ghost {
      background: transparent; color: #94a3b8; border: 1px solid #374151;
      border-radius: 4px; cursor: pointer; padding: 0.15rem 0.5rem; font-size: 0.85rem;
    }
    .ghost:hover { color: #e5e7eb; border-color: #4b5563; }
    .close { color: #94a3b8; }

    .form { padding: 0 0.25rem; }
    .form h4 { margin: 0 0 0.5rem 0; font-size: 0.85rem; color: #cbd5e1; }
    .form label {
      display: flex; flex-direction: column; gap: 0.15rem;
      font-size: 0.78rem; margin-bottom: 0.5rem; color: #cbd5e1;
    }
    .form input, .form select {
      padding: 0.3rem 0.45rem; background: #1f2937; color: #e5e7eb;
      border: 1px solid #374151; border-radius: 4px; font-size: 0.85rem;
    }
    .form select option { background: #0b1220; color: #e5e7eb; }
    .form .primary {
      width: 100%; padding: 0.45rem; cursor: pointer; margin-top: 0.4rem;
      background: #2563eb; color: white; border: none; border-radius: 4px;
    }
    .form .primary.small { width: auto; padding: 0.3rem 0.6rem; margin-top: 0; font-size: 0.8rem; }
    .form .primary:disabled { background: #374151; color: #6b7280; cursor: not-allowed; }
    .form .error { color: #fca5a5; font-size: 0.78rem; margin: 0.5rem 0 0 0; }
    .gaia-row { display: flex; gap: 0.4rem; align-items: stretch; }
    .gaia-row select { flex: 1; }
    .form.gaia .hint { font-size: 0.72rem; color: #94a3b8; padding: 0.4rem 0 0 0; margin: 0; line-height: 1.35; }
    .form.gaia .hint.error { color: #fca5a5; }
    hr { border: 0; border-top: 1px solid #1f2937; margin: 0.5rem 0; }

    .hint { font-size: 0.8rem; color: #94a3b8; line-height: 1.4; padding: 0.5rem; }
    ul { list-style: none; padding: 0; margin: 0; overflow-y: auto; flex: 1; }
    li { padding: 0.6rem 0.4rem; border-bottom: 1px solid #1f2937; font-size: 0.78rem; }
    .row { display: flex; align-items: center; gap: 0.5rem; }
    .row + .row { margin-top: 0.4rem; }
    .meta { color: #94a3b8; font-family: ui-monospace, monospace; font-size: 0.72rem; }
    .meta.error { color: #fca5a5; }
    .bar {
      width: 100%; height: 4px; background: #1f2937;
      border-radius: 2px; margin: 0.3rem 0; overflow: hidden;
    }
    .bar .fill { height: 100%; background: linear-gradient(90deg, #2563eb, #34d399); transition: width 0.3s; }
    .id { color: #60a5fa; font-family: ui-monospace, monospace; flex: 1; }
    .badge {
      padding: 0.1rem 0.4rem; border-radius: 999px; font-family: ui-monospace, monospace;
      font-size: 0.7rem; background: #374151; color: #cbd5e1;
    }
    .st--queued    { background: #374151; color: #9ca3af; }
    .st--running   { background: #1e3a8a; color: #93c5fd; }
    .st--completed { background: #064e3b; color: #6ee7b7; }
    .st--failed    { background: #7f1d1d; color: #fca5a5; }
    .download {
      color: #34d399; text-decoration: none; font-size: 0.78rem;
      padding: 0.15rem 0.5rem; border: 1px solid #047857; border-radius: 4px;
    }
    .download:hover { background: rgba(4, 120, 87, 0.2); }
    .download.report      { color: #fbcfe8; border-color: #be185d; }
    .download.report:hover{ background: rgba(190, 24, 93, 0.2); }
    .download.report.tex,
    .download.report.json { color: #93c5fd; border-color: #1e40af; padding: 0.15rem 0.35rem; }
    .download.report.tex:hover,
    .download.report.json:hover { background: rgba(30, 64, 175, 0.2); }
    .replay {
      color: #d8b4fe; background: transparent; cursor: pointer;
      font-size: 0.78rem; padding: 0.15rem 0.5rem;
      border: 1px solid #7c3aed; border-radius: 4px;
    }
    .replay:hover { background: rgba(124, 58, 237, 0.2); }

    .verdict {
      cursor: pointer; font-size: 0.7rem; font-weight: 600;
      padding: 0.1rem 0.45rem; border-radius: 999px; border: 1px solid;
      background: transparent; font-family: inherit;
    }
    .verdict.v--pass { color: #6ee7b7; border-color: #047857; background: rgba(4, 120, 87, 0.18); }
    .verdict.v--warn { color: #fcd34d; border-color: #b45309; background: rgba(180, 83, 9, 0.18); }
    .verdict.v--fail { color: #fca5a5; border-color: #b91c1c; background: rgba(185, 28, 28, 0.20); }
    .verdict:hover { filter: brightness(1.2); }

    .vpanel {
      margin-top: 0.5rem; padding: 0.5rem; border-radius: 4px;
      background: rgba(15, 23, 42, 0.6); border: 1px solid #1f2937;
    }
    .vsummary { margin: 0 0 0.5rem 0; font-size: 0.78rem; color: #cbd5e1; }
    .vchecks { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 0.4rem; }
    .vcheck {
      padding: 0.4rem; border-left: 3px solid #374151; border-radius: 0 3px 3px 0;
      background: rgba(31, 41, 55, 0.35);
    }
    .vcheck.c--pass { border-left-color: #10b981; }
    .vcheck.c--warn { border-left-color: #f59e0b; }
    .vcheck.c--fail { border-left-color: #ef4444; }
    .vrow { display: flex; justify-content: space-between; gap: 0.5rem; font-size: 0.72rem; }
    .vlabel { color: #e5e7eb; font-weight: 500; }
    .vsev { color: #cbd5e1; }
    .vnums { color: #94a3b8; margin-top: 0.15rem; font-family: ui-monospace, monospace; }
    .vthr { color: #6b7280; }
    .vmsg { color: #94a3b8; font-size: 0.7rem; margin-top: 0.25rem; line-height: 1.3; font-style: italic; }
  `]
})
export class BatchJobsComponent implements OnInit, OnDestroy {
  protected readonly batchSvc = inject(BatchJobService);
  protected readonly scenarioSvc = inject(ScenarioService);
  protected readonly jobs = this.batchSvc.jobs;
  protected readonly scenarios = this.scenarioSvc.scenarios;

  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);

  // Validation panel state. Cache keyed by jobId so reopening is instant and
  // a poll cycle doesn't refetch what's already in memory.
  protected readonly expandedJobId = signal<string | null>(null);
  protected readonly validationCache = signal<Record<string, ValidationReport>>({});

  // Gaia importer state.
  protected gaiaCluster = 'pleiades';
  protected readonly gaiaImporting = signal(false);
  protected readonly gaiaMessage = signal<string | null>(null);
  protected readonly gaiaError = signal(false);

  // Form state
  protected scenarioName = 'pleiades';
  protected nSteps = 5000;
  protected snapshotEvery = 50;
  protected dt = 0.005;

  @Output() close = new EventEmitter<void>();
  @Output() replay = new EventEmitter<string>();   // emits jobId

  protected onReplay(jobId: string): void {
    this.replay.emit(jobId);
  }

  private pollHandle: number | null = null;

  ngOnInit(): void {
    // Load scenarios if not already cached
    if (this.scenarios().length === 0) this.scenarioSvc.loadScenarios();
    this.refresh();
    this.pollHandle = window.setInterval(() => this.refresh(), 2000);
  }

  ngOnDestroy(): void {
    if (this.pollHandle !== null) clearInterval(this.pollHandle);
  }

  async refresh(): Promise<void> {
    await this.batchSvc.list(30);
  }

  async onSubmit(): Promise<void> {
    this.submitting.set(true);
    this.submitError.set(null);
    try {
      const job = await this.batchSvc.submit({
        scenarioName: this.scenarioName,
        nSteps: this.nSteps,
        dt: this.dt,
        snapshotEvery: this.snapshotEvery,
      });
      if (!job) {
        this.submitError.set('No se pudo crear el job (revisa la consola del browser).');
      }
    } finally {
      this.submitting.set(false);
    }
  }

  protected downloadUrl(id: string): string {
    return this.batchSvc.hdf5Url(id);
  }

  protected reportUrl(id: string, kind: 'pdf' | 'tex' | 'json'): string {
    return this.batchSvc.reportUrl(id, kind);
  }

  protected async toggleValidation(id: string): Promise<void> {
    if (this.expandedJobId() === id) {
      this.expandedJobId.set(null);
      return;
    }
    this.expandedJobId.set(id);
    if (this.validationCache()[id]) return;          // already loaded
    const v = await this.batchSvc.validation(id);
    if (v) this.validationCache.update(c => ({ ...c, [id]: v }));
  }

  protected verdictIcon(v: 'pass' | 'warn' | 'fail'): string {
    return v === 'pass' ? '✓' : v === 'warn' ? '⚠' : '✗';
  }

  protected verdictLabel(v: 'pass' | 'warn' | 'fail'): string {
    return v === 'pass' ? 'NBODY6-grade' : v === 'warn' ? 'Marginal' : 'Failed';
  }

  protected verdictTitle(v: 'pass' | 'warn' | 'fail'): string {
    return v === 'pass'
      ? 'Todos los checks dentro de tolerancia estricta'
      : v === 'warn'
        ? 'Algunos checks en zona de advertencia (no críticos)'
        : 'Al menos un check excede el umbral crítico';
  }

  protected async onImportGaia(): Promise<void> {
    this.gaiaImporting.set(true);
    this.gaiaError.set(false);
    this.gaiaMessage.set('Consultando Gaia DR3 (10–30 s)…');
    try {
      const result = await this.scenarioSvc.importGaiaCluster(this.gaiaCluster);
      if ('error' in result) {
        this.gaiaError.set(true);
        this.gaiaMessage.set(`Falló: ${result.error}`);
        return;
      }
      this.gaiaError.set(false);
      this.gaiaMessage.set(
        `OK — escenario ${result.name} (N=${result.nBodies ?? '?'}) disponible en el dropdown.`
      );
      // Pre-select the freshly imported scenario for the next job.
      this.scenarioName = result.name;
    } finally {
      this.gaiaImporting.set(false);
    }
  }

  /** Format a number for the validation panel: scientific for tiny/huge, fixed otherwise. */
  protected formatNum(v: number): string {
    if (!Number.isFinite(v)) return v > 0 ? '+∞' : '-∞';
    if (v === 0) return '0';
    const abs = Math.abs(v);
    if (abs < 1e-3 || abs >= 1e4) return v.toExponential(3);
    return v.toFixed(4);
  }
}
