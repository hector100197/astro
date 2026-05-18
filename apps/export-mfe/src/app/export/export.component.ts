import { Component, signal } from '@angular/core';

/**
 * Headless mode UI:
 *   - Form to configure a batch run (N, dt, n_steps, scenario)
 *   - Submit job to export-service via REST
 *   - Poll job status, show progress
 *   - Download HDF5 / manifest when complete
 *
 * TODO Sem 4.
 */
@Component({
  selector: 'app-export',
  standalone: true,
  template: `
    <section class="export">
      <h2 i18n>Modo Headless</h2>
      <p i18n>Lanza corridas batch sin streaming. Resultado en HDF5 listo para análisis científico.</p>
      <button (click)="onSubmit()" i18n>Lanzar corrida</button>
      <ul>
        @for (job of jobs(); track job.id) {
          <li>{{ job.id }} — {{ job.status }}</li>
        } @empty {
          <li i18n>(sin corridas todavía)</li>
        }
      </ul>
    </section>
  `,
  styles: [`
    :host { display: block; padding: 1.5rem; color: #e5e7eb; background: #0b1220; height: 100%; }
    button { padding: 0.5rem 1rem; background: #2563eb; color: white; border: none; cursor: pointer; }
    ul { list-style: none; padding: 0; margin-top: 1rem; }
    li { padding: 0.5rem; border-bottom: 1px solid #1f2937; }
  `]
})
export class ExportComponent {
  readonly jobs = signal<{ id: string; status: string }[]>([]);

  onSubmit(): void {
    // TODO Sem 4: POST /api/export-jobs with config DTO
  }
}
