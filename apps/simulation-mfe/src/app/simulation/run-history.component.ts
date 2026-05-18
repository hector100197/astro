import { Component, EventEmitter, Output, computed, inject, signal } from '@angular/core';
import { ScenarioService, RunSummary } from '../core/scenario.service';

/**
 * Side drawer that lists persisted simulation runs from Postgres and lets
 * the user download each one's HDF5 file. Closes the loop:
 * run a sim → see it in the list → click download → analyse in Python.
 */
@Component({
  selector: 'app-run-history',
  standalone: true,
  template: `
    <aside class="drawer">
      <header>
        <span class="title" i18n>Mis runs ({{ runs().length }})</span>
        <button class="ghost" (click)="refresh()" [disabled]="loading()" type="button" i18n-title title="Recargar">⟳</button>
        <button class="ghost close" (click)="close.emit()" type="button" aria-label="Cerrar">✕</button>
      </header>

      @if (loading()) {
        <p class="hint" i18n>Cargando…</p>
      } @else if (runs().length === 0) {
        <p class="hint" i18n>Aún no hay runs persistidos. Conecta una sesión, déjala correr unos segundos y desconecta — el run quedará aquí.</p>
      } @else {
        <ul>
          @for (r of runs(); track r.id) {
            <li>
              <div class="row">
                <span class="badge st--{{ r.status }}">{{ r.status }}</span>
                <span class="when">{{ relativeTime(r.createdAt) }}</span>
              </div>
              <div class="meta">
                <span>{{ scenarioFor(r) }}</span>
                <span>·</span>
                <span>N={{ paramN(r) }}</span>
                <span>·</span>
                <span>Δt={{ paramDt(r) }}</span>
              </div>
              <div class="row">
                <code class="id">{{ r.id.slice(0, 8) }}…</code>
                @if (r.hdf5Path) {
                  <a class="download" [href]="downloadUrl(r.id)" download>↓ HDF5</a>
                } @else {
                  <span class="no-file" i18n>(sin .h5)</span>
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
      width: 320px; max-height: calc(100vh - 5rem);
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
    .hint { font-size: 0.8rem; color: #94a3b8; line-height: 1.4; padding: 0.5rem; }
    ul { list-style: none; padding: 0; margin: 0; overflow-y: auto; flex: 1; }
    li { padding: 0.6rem 0.5rem; border-bottom: 1px solid #1f2937; font-size: 0.78rem; }
    .row { display: flex; align-items: center; gap: 0.5rem; }
    .row + .row { margin-top: 0.3rem; }
    .meta {
      color: #94a3b8; margin: 0.25rem 0; font-family: ui-monospace, monospace;
      display: flex; gap: 0.4rem; flex-wrap: wrap;
    }
    .id { color: #60a5fa; font-family: ui-monospace, monospace; flex: 1; }
    .when { color: #94a3b8; margin-left: auto; }
    .badge {
      padding: 0.1rem 0.4rem; border-radius: 999px; font-family: ui-monospace, monospace;
      font-size: 0.7rem; background: #374151; color: #cbd5e1;
    }
    .st--running   { background: #1e3a8a; color: #93c5fd; }
    .st--stopped   { background: #064e3b; color: #6ee7b7; }
    .st--completed { background: #064e3b; color: #6ee7b7; }
    .st--failed    { background: #7f1d1d; color: #fca5a5; }
    .download {
      color: #34d399; text-decoration: none; font-size: 0.78rem;
      padding: 0.15rem 0.5rem; border: 1px solid #047857; border-radius: 4px;
    }
    .download:hover { background: rgba(4, 120, 87, 0.2); }
    .no-file { color: #6b7280; font-size: 0.72rem; }
  `]
})
export class RunHistoryComponent {
  protected readonly scenarioService = inject(ScenarioService);
  protected readonly runs = this.scenarioService.runs;
  protected readonly loading = signal(false);

  @Output() close = new EventEmitter<void>();

  constructor() {
    this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      await this.scenarioService.loadRuns(30);
    } finally {
      this.loading.set(false);
    }
  }

  protected downloadUrl(id: string): string {
    return this.scenarioService.hdf5Url(id);
  }

  protected scenarioFor(r: RunSummary): string {
    const fromManifest = r.manifest?.['scenario']?.['source'];
    return r.scenarioName ?? fromManifest ?? 'default';
  }

  protected paramN(r: RunSummary): number | string {
    return r.manifest?.['parameters']?.['n_bodies'] ?? '?';
  }

  protected paramDt(r: RunSummary): number | string {
    return r.manifest?.['parameters']?.['dt'] ?? '?';
  }

  protected relativeTime(iso: string): string {
    const dt = Date.now() - new Date(iso).getTime();
    const s = Math.floor(dt / 1000);
    if (s < 60) return `hace ${s}s`;
    const m = Math.floor(s / 60);
    if (m < 60) return `hace ${m}m`;
    const h = Math.floor(m / 60);
    if (h < 24) return `hace ${h}h`;
    return new Date(iso).toLocaleDateString();
  }
}
