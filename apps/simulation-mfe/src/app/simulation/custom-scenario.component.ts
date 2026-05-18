import { Component, EventEmitter, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ScenarioService } from '../core/scenario.service';

const PLUMMER_EXAMPLE = `name: my_custom_plummer
description: My own Plummer cluster (uploaded from the UI)
n_bodies: 800
units: henon
initial_condition:
  type: plummer
  rng_seed: 123
simulation:
  integrator: leapfrog
  dt: 0.005
  softening: 0.02
  n_steps: 5000
`;

const EXPLICIT_EXAMPLE = `name: my_three_body
description: Custom 3-body configuration
n_bodies: 3
units: henon
initial_condition:
  type: explicit
  bodies:
    - { x:  1.0, y:  0.0, z: 0.0, vx: 0.0, vy:  0.5, vz: 0.0, mass: 1.0 }
    - { x: -1.0, y:  0.0, z: 0.0, vx: 0.0, vy: -0.5, vz: 0.0, mass: 1.0 }
    - { x:  0.0, y:  0.0, z: 0.0, vx: 0.0, vy:  0.0, vz: 0.0, mass: 0.1 }
simulation:
  integrator: leapfrog
  dt: 0.001
  softening: 0.0
  n_steps: 10000
`;

/**
 * Drawer for uploading a custom scenario YAML directly from the browser.
 *
 * <p>Shows two examples (Plummer + explicit), a textarea, and a button. On
 * submit, the YAML is POSTed to the backend, which validates the schema,
 * registers it ephemerally in the scenario catalog, and returns the
 * canonical name. We then issue {@code loadScenario} on the live stream
 * so the user immediately sees their scenario rendered.
 *
 * <p>Use case: "I have my own initial condition and I want to simulate it
 * without writing Python or touching the CLI."
 */
@Component({
  selector: 'app-custom-scenario',
  standalone: true,
  imports: [FormsModule],
  template: `
    <aside class="drawer">
      <header>
        <span class="title" i18n>Cargar YAML custom</span>
        <button class="ghost close" (click)="close.emit()" type="button" aria-label="Cerrar">✕</button>
      </header>

      <p class="hint" i18n>
        Pega tu propio escenario YAML aquí. Backend lo valida, lo registra
        en el catálogo de esta sesión, y lo carga al stream activo.
        Soporta <code>type: plummer</code> y <code>type: explicit</code>.
      </p>

      <div class="examples">
        <span i18n>Ejemplos:</span>
        <button class="ghost" (click)="loadExample('plummer')" type="button">Plummer</button>
        <button class="ghost" (click)="loadExample('explicit')" type="button">Explicit (3-body)</button>
      </div>

      <textarea [(ngModel)]="yamlBody" rows="18" spellcheck="false"></textarea>

      <button class="primary" (click)="onUpload()" [disabled]="uploading()" i18n>
        {{ uploading() ? 'Subiendo…' : '↑ Subir y cargar' }}
      </button>

      @if (resultMsg()) {
        <p class="result" [class.error]="resultIsError()">{{ resultMsg() }}</p>
      }
    </aside>
  `,
  styles: [`
    :host { display: block; }
    .drawer {
      position: absolute; left: 0.75rem; top: 4rem; bottom: 0.75rem; z-index: 11;
      width: 460px; max-height: calc(100vh - 5rem);
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
      border-radius: 4px; cursor: pointer; padding: 0.15rem 0.5rem; font-size: 0.78rem;
    }
    .ghost:hover { color: #e5e7eb; border-color: #4b5563; }
    .close { color: #94a3b8; }
    .hint { font-size: 0.78rem; color: #94a3b8; line-height: 1.5; margin: 0 0 0.5rem 0; }
    .hint code {
      background: #1f2937; padding: 0.05rem 0.3rem; border-radius: 3px;
      font-family: ui-monospace, monospace; font-size: 0.72rem; color: #d8b4fe;
    }
    .examples {
      display: flex; gap: 0.4rem; align-items: center;
      font-size: 0.78rem; color: #cbd5e1; margin-bottom: 0.4rem;
    }
    textarea {
      flex: 1; min-height: 280px;
      background: #050810; color: #e5e7eb;
      border: 1px solid #374151; border-radius: 4px;
      font-family: ui-monospace, "SF Mono", monospace;
      font-size: 0.78rem; line-height: 1.4;
      padding: 0.5rem; resize: vertical;
    }
    .primary {
      width: 100%; padding: 0.5rem; cursor: pointer; margin-top: 0.5rem;
      background: #2563eb; color: white; border: none; border-radius: 4px;
      font-size: 0.85rem;
    }
    .primary:disabled { background: #374151; color: #6b7280; cursor: not-allowed; }
    .result {
      font-size: 0.78rem; color: #6ee7b7; margin: 0.5rem 0 0 0;
      padding: 0.4rem 0.6rem; background: rgba(6, 78, 59, 0.3);
      border-left: 2px solid #047857; border-radius: 2px;
    }
    .result.error { color: #fca5a5; background: rgba(127, 29, 29, 0.3); border-left-color: #b91c1c; }
  `]
})
export class CustomScenarioComponent {
  protected readonly scenarioSvc = inject(ScenarioService);

  protected yamlBody = PLUMMER_EXAMPLE;
  protected readonly uploading = signal(false);
  protected readonly resultMsg = signal<string | null>(null);
  protected readonly resultIsError = signal(false);

  /** Emitted with the registered scenario name when upload+register succeeds. */
  @Output() loaded = new EventEmitter<string>();
  @Output() close = new EventEmitter<void>();

  protected loadExample(kind: 'plummer' | 'explicit'): void {
    this.yamlBody = kind === 'plummer' ? PLUMMER_EXAMPLE : EXPLICIT_EXAMPLE;
    this.resultMsg.set(null);
  }

  protected async onUpload(): Promise<void> {
    this.uploading.set(true);
    this.resultMsg.set(null);
    try {
      const result = await this.scenarioSvc.uploadCustomScenario(this.yamlBody);
      if ('error' in result) {
        this.resultIsError.set(true);
        this.resultMsg.set('✗ ' + result.error);
        return;
      }
      this.resultIsError.set(false);
      this.resultMsg.set(`✓ Registrado como "${result.name}". Cargando en el stream…`);
      // Tell the parent so it can issue loadScenario on the live socket.
      this.loaded.emit(result.name);
    } finally {
      this.uploading.set(false);
    }
  }
}
