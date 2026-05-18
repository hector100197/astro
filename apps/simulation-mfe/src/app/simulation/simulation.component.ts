import {
  AfterViewInit,
  Component,
  ElementRef,
  HostListener,
  NgZone,
  OnDestroy,
  ViewChild,
  computed,
  effect,
  inject,
  signal
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { SnapshotStreamService } from '../core/snapshot-stream.service';
import { ScenarioService } from '../core/scenario.service';
import { NBodyScene } from './three-scene';
import { DiagnosticsPlotComponent } from './diagnostics-plot.component';
import { RunHistoryComponent } from './run-history.component';
import { BatchJobsComponent } from './batch-jobs.component';
import { CustomScenarioComponent } from './custom-scenario.component';

/**
 * Live + Capture mode component.
 *
 * <p>Sem 1 (now): connects to ws://localhost:8081/snapshots, parses binary
 * frames, renders Points geometry that updates on every frame received.
 * Render loop is independent from the network — it runs at the display
 * refresh rate via {@code requestAnimationFrame} and reads whichever frame
 * is current. This is the foundation for fixed-timestep + interpolation
 * (Sem 2).
 *
 * <p>Capture mode (Sem 4): button hooks into CCapture.js to record the canvas.
 */
@Component({
  selector: 'app-simulation',
  standalone: true,
  imports: [DiagnosticsPlotComponent, RunHistoryComponent, BatchJobsComponent, CustomScenarioComponent],
  template: `
    <section class="simulation" [class.compare]="comparing()">
      <div class="pane pane--a">
        <canvas #canvas
                (pointerdown)="onPointerDown($event, 'A')"
                (pointerup)="onPointerUp($event, 'A')"></canvas>
        @if (comparing()) {
          <span class="pane-tag pane-tag--a">A · {{ selectedScenario() }}</span>
        }
      </div>
      @if (comparing()) {
        <div class="pane pane--b">
          <canvas #canvasB
                  (pointerdown)="onPointerDown($event, 'B')"
                  (pointerup)="onPointerUp($event, 'B')"></canvas>
          <span class="pane-tag pane-tag--b">B · {{ scenarioB() }}</span>
          <aside class="pane-controls">
            <label>
              <span i18n>Escenario B</span>
              <select [value]="scenarioB()" (change)="onScenarioBChange($any($event.target).value)"
                      [disabled]="!streamB.connectionState || streamB.connectionState() !== 'open'">
                @for (sc of scenarios(); track sc.name) {
                  @if (sc.supported) {
                    <option [value]="sc.name">{{ sc.displayName }} (N={{ sc.nBodies }})</option>
                  }
                }
              </select>
            </label>
            <button class="action small" (click)="onResetB()" type="button" i18n>↺ Reset B</button>
          </aside>
        </div>
      }

      <header class="title-bar">
        <h1>astro · N-body</h1>
        <span class="badge state state--{{ stream.connectionState() }}">{{ stream.connectionState() }}</span>
        @if (frameCount() > 0) {
          <span class="badge">frames {{ frameCount() }}</span>
        }
        @if (bodyCount() > 0) {
          <span class="badge">N = {{ bodyCount() }}</span>
        }
        @if (stream.interpolating()) {
          <span class="badge badge--interp" title="Decoupled timestep + interpolation">interp</span>
        }
        @if (halfMassRadius() !== null) {
          <span class="badge"
                title="Lagrangian radii: r₁₀ (core) / r₅₀ (half-mass) / r₉₀ (halo)">
            r₁₀={{ r10()!.toFixed(2) }} · r₅₀={{ halfMassRadius()!.toFixed(2) }} · r₉₀={{ r90()!.toFixed(2) }}
          </span>
        }
        @if (recording()) {
          <span class="badge badge--rec">● REC {{ recordingDurationSec() }}s</span>
        }
        <button class="header-btn" (click)="onToggleHistory()" type="button" i18n>
          Mis runs
        </button>
        <button class="header-btn" (click)="onToggleBatch()" type="button" i18n>
          Batch jobs
        </button>
        <button class="header-btn" (click)="onToggleCustom()" type="button" i18n>
          ↑ YAML custom
        </button>
        <button class="header-btn" (click)="onToggleCompare()" type="button"
                [class.active]="comparing()"
                i18n-title title="Lanzar un segundo run en paralelo para comparar">
          {{ comparing() ? '◧ Cerrar B' : '◫ Comparar' }}
        </button>
        <button class="header-btn" (click)="onCopyLink()" type="button"
                [class.copied]="linkCopied()"
                i18n-title title="Copiar URL con la configuración actual">
          {{ linkCopied() ? '✓ Copiado' : '🔗 Compartir' }}
        </button>
      </header>

      @if (historyOpen()) {
        <app-run-history (close)="historyOpen.set(false)" />
      }
      @if (batchOpen()) {
        <app-batch-jobs (close)="batchOpen.set(false)" (replay)="onReplay($event)" />
      }
      @if (customOpen()) {
        <app-custom-scenario
            (loaded)="onCustomLoaded($event)"
            (close)="customOpen.set(false)" />
      }

      @if (selectedBody() !== null) {
        <aside class="follow-card">
          <header>
            <span class="dot"></span>
            <strong i18n>Cuerpo seguido</strong>
            <button class="ghost" (click)="onClearSelection()" type="button" aria-label="Cerrar">✕</button>
          </header>
          <dl>
            <dt>id</dt><dd>#{{ selectedBody() }}</dd>
            <dt>r</dt><dd>{{ selectedRadius()?.toFixed(3) ?? '—' }}</dd>
            <dt>|v|</dt><dd>{{ selectedSpeed()?.toFixed(3) ?? '—' }}</dd>
            <dt>x,y,z</dt>
            <dd class="vec">
              {{ selectedPos()?.[0]?.toFixed(2) }},
              {{ selectedPos()?.[1]?.toFixed(2) }},
              {{ selectedPos()?.[2]?.toFixed(2) }}
            </dd>
          </dl>
          <p class="hint" i18n>Click otra partícula para seguirla, o pulsa ESC.</p>
        </aside>
      }

      @if (stream.replayInfo()) {
        <div class="scrubber">
          <span class="scrubber-label" i18n>▶ Replay</span>
          <input type="range" class="scrubber-input"
                 min="0" [max]="stream.replayInfo()!.totalFrames - 1"
                 [value]="currentReplayFrame()"
                 (input)="onSeek($any($event.target).value)" />
          <span class="scrubber-meta">
            {{ currentReplayFrame() }} / {{ stream.replayInfo()!.totalFrames - 1 }}
          </span>
        </div>
      }

      <div class="plots" [class.split]="comparing()">
        <app-diagnostics-plot [stream]="stream"
                              [label]="comparing() ? 'A · E vs t' : 'Energía vs tiempo (Hénon)'" />
        @if (comparing()) {
          <app-diagnostics-plot [stream]="streamB" label="B · E vs t" />
        }
      </div>

      <aside class="controls" [class.collapsed]="panelCollapsed()">
        <button class="toggle" (click)="panelCollapsed.update(c => !c)" type="button"
                [attr.aria-label]="panelCollapsed() ? 'Expand panel' : 'Collapse panel'">
          {{ panelCollapsed() ? '‹' : '›' }}
        </button>
        @if (!panelCollapsed()) {
          <div class="content">
            <h3 i18n>Controles</h3>

            <label>
              <span i18n>Escenario</span>
              <select [value]="selectedScenario()" (change)="onScenarioChange($any($event.target).value)"
                      [disabled]="!isConnected()">
                @for (sc of scenarios(); track sc.name) {
                  <option [value]="sc.name" [disabled]="!sc.supported">
                    {{ sc.displayName }} (N={{ sc.nBodies }}{{ sc.supported ? '' : ' — sólo CLI' }})
                  </option>
                }
              </select>
            </label>

            <label>
              Δt
              <input type="number" [value]="dt()" (change)="onDtChange($any($event.target).value)"
                     [disabled]="!isConnected()" step="0.001" min="0.0001" max="0.1" />
            </label>
            <label>
              N <span class="muted">({{ bodyCount() || n() }})</span>
              <input type="number" [value]="n()" (change)="onNChange($any($event.target).value)"
                     [disabled]="!isConnected()" step="100" min="2" max="10000" />
            </label>
            <label>
              ε (softening)
              <input type="number" [value]="softening()" (change)="onSofteningChange($any($event.target).value)"
                     [disabled]="!isConnected()" step="0.001" min="0" max="1" />
            </label>

            <div class="actions">
              <button class="action primary" (click)="onPlayPause()" [disabled]="!isConnected()">
                @if (paused()) {
                  <span i18n>▶ Reanudar</span>
                } @else {
                  <span i18n>⏸ Pausar</span>
                }
              </button>
              <button class="action" (click)="onReset()" [disabled]="!isConnected()" i18n>↺ Reiniciar</button>
            </div>

            <hr />

            <div class="actions">
              <button class="action small" (click)="onConnect()" [disabled]="isConnected()" i18n>Conectar</button>
              <button class="action small" (click)="onDisconnect()" [disabled]="!isConnected()" i18n>Desconectar</button>
            </div>
            <button class="action" [class.primary]="!recording()" [class.danger]="recording()"
                    (click)="onToggleRecording()" [disabled]="!isConnected()">
              @if (recording()) {
                <span i18n>■ Detener y descargar</span>
              } @else {
                <span i18n>● Grabar WebM</span>
              }
            </button>

            <p class="hint" i18n>N y ε se aplicarán cuando exista soporte de re-allocación dinámica del kernel.</p>
          </div>
        }
      </aside>
    </section>
  `,
  styles: [`
    :host {
      display: block;
      position: fixed;
      inset: 0;
      background: #050810;
    }
    .simulation { position: absolute; inset: 0; }
    .pane { position: absolute; inset: 0; overflow: hidden; }
    .pane > canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }

    /* Split layout: A on left half, B on right half. */
    .simulation.compare .pane--a { right: 50%; border-right: 1px solid #1f2937; }
    .simulation.compare .pane--b { left:  50%; }

    .pane-tag {
      position: absolute; top: 0.75rem; left: 50%; transform: translateX(-50%);
      z-index: 10; pointer-events: none;
      font-family: ui-monospace, monospace; font-size: 0.78rem;
      padding: 0.18rem 0.6rem; border-radius: 999px;
      background: rgba(11, 18, 32, 0.78); color: #cbd5e1;
      border: 1px solid #374151; backdrop-filter: blur(6px);
    }
    .pane-tag--a { color: #93c5fd; border-color: #1e40af; }
    .pane-tag--b { color: #f9a8d4; border-color: #be185d; }

    .pane-controls {
      position: absolute; right: 0.75rem; top: 3.25rem; z-index: 11;
      width: 200px; padding: 0.5rem 0.65rem;
      background: rgba(11, 18, 32, 0.92); color: #e5e7eb;
      border: 1px solid #be185d; border-radius: 6px;
      backdrop-filter: blur(6px);
      font-size: 0.78rem;
    }
    .pane-controls label { display: block; margin-bottom: 0.5rem; font-size: 0.78rem; }
    .pane-controls select {
      width: 100%; margin-top: 0.2rem; padding: 0.25rem 0.4rem;
      background: #1f2937; color: #e5e7eb; border: 1px solid #374151; border-radius: 4px;
      font-size: 0.78rem;
    }
    .pane-controls .small { width: 100%; margin: 0; padding: 0.25rem; font-size: 0.78rem; }

    /* Plots: single pane = original layout. Comparing = two side-by-side. */
    .plots { position: absolute; left: 0.75rem; right: 0.75rem; bottom: 0.75rem; z-index: 9; }
    .plots app-diagnostics-plot { display: block; }
    .plots:not(.split) app-diagnostics-plot { max-width: 720px; }
    .plots.split { display: grid; grid-template-columns: 1fr 1fr; gap: 0.5rem; }
    .plots.split app-diagnostics-plot { max-width: none; }


    .title-bar {
      position: absolute; top: 0.75rem; left: 0.75rem; z-index: 10;
      display: flex; align-items: center; gap: 0.6rem; flex-wrap: wrap;
      pointer-events: none;
    }
    .title-bar h1 {
      margin: 0; font-size: 0.95rem; font-weight: 600; letter-spacing: 0.04em;
      color: #e2e8f0; padding: 0.25rem 0.6rem; background: rgba(11, 18, 32, 0.78);
      border: 1px solid #1f2937; border-radius: 6px; backdrop-filter: blur(6px);
    }
    .badge {
      font-family: ui-monospace, "SF Mono", monospace; font-size: 0.78rem;
      padding: 0.2rem 0.55rem; border-radius: 999px;
      background: rgba(31, 41, 55, 0.8); color: #cbd5e1;
      border: 1px solid #374151;
    }
    .state--open       { background: rgba(6, 78, 59, 0.85);   color: #6ee7b7; border-color: #047857; }
    .state--error      { background: rgba(127, 29, 29, 0.85); color: #fca5a5; border-color: #b91c1c; }
    .state--closed     { background: rgba(55, 65, 81, 0.85);  color: #9ca3af; }
    .state--connecting { background: rgba(30, 58, 138, 0.85); color: #93c5fd; }
    .badge--interp     { background: rgba(76, 29, 149, 0.85); color: #d8b4fe; border-color: #7c3aed; }
    .badge--rec        { background: rgba(127, 29, 29, 0.95); color: #fecaca; border-color: #dc2626;
                         animation: pulse 1.2s ease-in-out infinite; font-weight: 600; }
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50%      { opacity: 0.55; }
    }

    .controls {
      position: absolute; right: 0.75rem; top: 0.75rem; z-index: 10;
      width: 280px; max-height: calc(100% - 1.5rem);
      background: rgba(11, 18, 32, 0.86); color: #e5e7eb;
      border: 1px solid #1f2937; border-radius: 8px;
      backdrop-filter: blur(6px);
      display: flex; flex-direction: column;
    }
    .controls.collapsed { width: 36px; }
    .controls .toggle {
      align-self: flex-end; width: 28px; height: 28px;
      margin: 4px; padding: 0; cursor: pointer;
      background: #1f2937; color: #e5e7eb; border: 1px solid #374151;
      border-radius: 4px; font-size: 1rem; line-height: 1;
    }
    .controls .content { padding: 0 1rem 1rem 1rem; overflow-y: auto; }
    .controls h3 { margin: 0.25rem 0 0.5rem 0; font-size: 0.95rem; }
    .hint { font-size: 0.78rem; color: #94a3b8; margin: 0 0 0.75rem 0; line-height: 1.4; }
    label { display: block; margin: 0.5rem 0; font-size: 0.85rem; }
    input, select {
      width: 100%; margin-top: 0.2rem; padding: 0.3rem 0.45rem;
      background: #1f2937; color: #e5e7eb; border: 1px solid #374151; border-radius: 4px;
      font-family: inherit; font-size: 0.85rem;
    }
    input:disabled, select:disabled { opacity: 0.6; }
    select option { background: #0b1220; color: #e5e7eb; }
    .header-btn {
      background: rgba(31, 41, 55, 0.85); color: #e5e7eb;
      border: 1px solid #374151; border-radius: 6px;
      font-size: 0.78rem; padding: 0.25rem 0.6rem; cursor: pointer;
      pointer-events: auto;  /* override .title-bar pointer-events: none */
    }
    .header-btn:hover { background: rgba(55, 65, 81, 0.95); }
    .header-btn.copied { background: rgba(6, 78, 59, 0.85); border-color: #047857; color: #6ee7b7; }
    .header-btn.active { background: rgba(190, 24, 93, 0.85); border-color: #be185d; color: #fbcfe8; }

    .follow-card {
      position: absolute; left: 0.75rem; bottom: 0.75rem; z-index: 12;
      width: 220px; padding: 0.6rem 0.75rem;
      background: rgba(11, 18, 32, 0.92); color: #e5e7eb;
      border: 1px solid #7c3aed; border-radius: 8px;
      backdrop-filter: blur(6px);
      font-size: 0.8rem;
    }
    .follow-card header {
      display: flex; align-items: center; gap: 0.4rem; margin-bottom: 0.4rem;
    }
    .follow-card header strong { flex: 1; color: #d8b4fe; font-size: 0.82rem; }
    .follow-card .dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: #a78bfa; box-shadow: 0 0 6px #a78bfa;
    }
    .follow-card .ghost {
      background: transparent; color: #94a3b8; border: 1px solid #374151;
      border-radius: 4px; cursor: pointer; padding: 0.05rem 0.4rem; font-size: 0.78rem;
    }
    .follow-card dl {
      margin: 0; display: grid; grid-template-columns: auto 1fr;
      gap: 0.15rem 0.6rem; font-family: ui-monospace, monospace; font-size: 0.78rem;
    }
    .follow-card dt { color: #94a3b8; }
    .follow-card dd { margin: 0; color: #e5e7eb; }
    .follow-card .vec { font-size: 0.74rem; }
    .follow-card .hint {
      margin: 0.45rem 0 0 0; font-size: 0.7rem; color: #94a3b8; line-height: 1.3;
    }

    .scrubber {
      position: absolute; left: 50%; bottom: 0.75rem; transform: translateX(-50%);
      z-index: 12; display: flex; align-items: center; gap: 0.75rem;
      width: min(640px, calc(100vw - 2rem));
      padding: 0.5rem 0.85rem;
      background: rgba(11, 18, 32, 0.92); color: #e5e7eb;
      border: 1px solid #7c3aed; border-radius: 8px;
      backdrop-filter: blur(6px);
    }
    .scrubber-label { color: #d8b4fe; font-weight: 600; font-size: 0.85rem; flex-shrink: 0; }
    .scrubber-input { flex: 1; accent-color: #a78bfa; cursor: pointer; }
    .scrubber-meta {
      font-family: ui-monospace, monospace; font-size: 0.78rem;
      color: #94a3b8; flex-shrink: 0; min-width: 80px; text-align: right;
    }
    .muted { color: #6b7280; font-size: 0.78rem; font-weight: 400; }
    hr { border: 0; border-top: 1px solid #1f2937; margin: 0.85rem 0 0.5rem 0; }
    .actions { display: grid; grid-template-columns: 1fr 1fr; gap: 0.5rem; margin-top: 0.5rem; }
    button.action {
      width: 100%; padding: 0.45rem; cursor: pointer;
      background: #374151; color: #e5e7eb; border: 1px solid #4b5563; border-radius: 4px;
      font-size: 0.85rem;
    }
    button.action.primary { background: #2563eb; color: white; border-color: #2563eb; }
    button.action.danger  { background: #dc2626; color: white; border-color: #b91c1c; }
    button.action.small { padding: 0.3rem; font-size: 0.78rem; }
    button.action:disabled { background: #1f2937; color: #6b7280; border-color: #374151; cursor: not-allowed; opacity: 0.7; }
    .actions + button.action { margin-top: 0.5rem; }
  `]
})
export class SimulationComponent implements AfterViewInit, OnDestroy {
  @ViewChild('canvas') canvasRef!: ElementRef<HTMLCanvasElement>;
  /** Pane B canvas — only present in the DOM when comparing() is true. */
  @ViewChild('canvasB') canvasBRef?: ElementRef<HTMLCanvasElement>;

  private readonly ngZone = inject(NgZone);
  protected readonly stream = inject(SnapshotStreamService);
  /** Secondary stream, instantiated lazily when comparison mode is toggled on.
   *  Manual construction (not DI) so we get a *separate* instance from the
   *  root singleton — each pane needs its own buffer + signals. */
  protected streamB: SnapshotStreamService = new SnapshotStreamService(this.ngZone);
  protected readonly scenarioService = inject(ScenarioService);
  protected readonly scenarios = this.scenarioService.scenarios;

  protected readonly dt = signal(0.005);
  protected readonly n = signal(3000);
  protected readonly softening = signal(0.01);
  protected readonly selectedScenario = signal('default_plummer');

  protected readonly paused = signal(false);
  protected readonly frameCount = signal(0);
  protected readonly bodyCount = computed(() => this.stream.latestFrame()?.n ?? 0);
  protected readonly halfMassRadius = computed(() => {
    const d = this.stream.latestDiagnostics();
    return d && d.r50 > 0 ? d.r50 : null;
  });
  protected readonly r10 = computed(() => this.stream.latestDiagnostics()?.r10 ?? null);
  protected readonly r90 = computed(() => this.stream.latestDiagnostics()?.r90 ?? null);
  /**
   * Current frame in replay: in replay mode the backend's stepIndex is the
   * cursor position into the saved snapshots, so we can read it straight.
   */
  protected readonly currentReplayFrame = computed(() => {
    const d = this.stream.latestDiagnostics();
    if (!d || !this.stream.replayInfo()) return 0;
    return Math.max(0, Math.min(
        Number(d.stepIndex), this.stream.replayInfo()!.totalFrames - 1));
  });
  protected readonly panelCollapsed = signal(false);
  protected readonly historyOpen = signal(false);
  protected readonly batchOpen = signal(false);
  protected readonly customOpen = signal(false);
  protected readonly linkCopied = signal(false);
  protected readonly isConnected = computed(() => this.stream.connectionState() === 'open');

  // ===== Click-to-follow body selection (per pane) =====
  /** Index of the body the user has picked in pane A. */
  protected readonly selectedBody = signal<number | null>(null);
  protected readonly selectedPos = signal<[number, number, number] | null>(null);
  protected readonly selectedSpeed = signal<number | null>(null);
  protected readonly selectedRadius = computed(() => {
    const p = this.selectedPos();
    return p ? Math.hypot(p[0], p[1], p[2]) : null;
  });
  private lastFollowA: { x: number; y: number; z: number; t: number } | null = null;
  private lastFollowB: { x: number; y: number; z: number; t: number } | null = null;
  /** Pointer-down state for click-vs-drag discrimination, keyed by pane. */
  private downPosA: { x: number; y: number; t: number } | null = null;
  private downPosB: { x: number; y: number; t: number } | null = null;
  private static readonly CLICK_MAX_PIXELS = 5;
  private static readonly CLICK_MAX_MS = 350;

  // ===== Comparison mode =====
  protected readonly comparing = signal(false);
  protected readonly scenarioB = signal('aarseth_standard');
  /** Default scenario for pane B picks something different from A so the
   *  side-by-side is immediately interesting (NBODY6 reference Plummer). */
  private static readonly COMPARE_DEFAULT_B = 'aarseth_standard';

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private urlParamsApplied = false;

  protected onToggleHistory(): void {
    this.historyOpen.update(o => !o);
    if (this.historyOpen()) { this.batchOpen.set(false); this.customOpen.set(false); }
  }
  protected onToggleBatch(): void {
    this.batchOpen.update(o => !o);
    if (this.batchOpen()) { this.historyOpen.set(false); this.customOpen.set(false); }
  }
  protected onToggleCustom(): void {
    this.customOpen.update(o => !o);
    if (this.customOpen()) { this.historyOpen.set(false); this.batchOpen.set(false); }
  }

  /** Replay a finished batch job's HDF5 in the live viewer. */
  protected onReplay(jobId: string): void {
    this.stream.sendReplayCommand(jobId);
    this.frameCount.set(0);
    this.batchOpen.set(false);   // close the drawer so the user sees the playback
  }

  // ===== Click-to-follow handlers (per-pane) =====

  protected onPointerDown(e: PointerEvent, pane: 'A' | 'B'): void {
    const stamp = { x: e.clientX, y: e.clientY, t: performance.now() };
    if (pane === 'A') this.downPosA = stamp; else this.downPosB = stamp;
  }

  /** Detect short, non-drag pointer ups → treat as a "pick this body" click. */
  protected onPointerUp(e: PointerEvent, pane: 'A' | 'B'): void {
    const down = pane === 'A' ? this.downPosA : this.downPosB;
    if (pane === 'A') this.downPosA = null; else this.downPosB = null;
    const scene = pane === 'A' ? this.scene : this.sceneB;
    if (!down || !scene) return;
    const dist = Math.hypot(e.clientX - down.x, e.clientY - down.y);
    const elapsed = performance.now() - down.t;
    if (dist > SimulationComponent.CLICK_MAX_PIXELS) return;
    if (elapsed > SimulationComponent.CLICK_MAX_MS)  return;
    const idx = scene.pickAt(e.clientX, e.clientY);
    if (idx === null) {
      this.clearSelection(pane);
      return;
    }
    scene.setSelectedBody(idx);
    if (pane === 'A') {
      this.selectedBody.set(idx);
      this.lastFollowA = null;
      this.selectedSpeed.set(null);
    }
    // Pane B has no info card right now (kept simple); the trail+halo are still drawn.
  }

  /** Clear selection in both panes (kept as-is for the panel "✕" button). */
  protected onClearSelection(): void { this.clearSelection('A'); }

  private clearSelection(pane: 'A' | 'B'): void {
    if (pane === 'A') {
      this.scene?.setSelectedBody(null);
      this.selectedBody.set(null);
      this.selectedPos.set(null);
      this.selectedSpeed.set(null);
      this.lastFollowA = null;
    } else {
      this.sceneB?.setSelectedBody(null);
      this.lastFollowB = null;
    }
  }

  /** Each rAF tick of pane A: refresh the visible info card. */
  private refreshSelectionMetrics(): void {
    if (this.selectedBody() === null || !this.scene) return;
    const pos = this.scene.getSelectedPosition();
    if (!pos) { this.clearSelection('A'); return; }
    this.selectedPos.set(pos);
    const now = performance.now();
    if (this.lastFollowA) {
      const dt = (now - this.lastFollowA.t) / 1000;
      if (dt > 0) {
        const dx = pos[0] - this.lastFollowA.x;
        const dy = pos[1] - this.lastFollowA.y;
        const dz = pos[2] - this.lastFollowA.z;
        const speed = Math.hypot(dx, dy, dz) / dt;
        const prev = this.selectedSpeed() ?? speed;
        this.selectedSpeed.set(prev * 0.7 + speed * 0.3);
      }
    }
    this.lastFollowA = { x: pos[0], y: pos[1], z: pos[2], t: now };
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.selectedBody() !== null) this.clearSelection('A');
    if (this.sceneB?.getSelectedIndex() != null) this.clearSelection('B');
  }

  // ===== Comparison mode handlers =====

  protected onToggleCompare(): void {
    if (this.comparing()) {
      this.tearDownPaneB();
      this.comparing.set(false);
    } else {
      this.comparing.set(true);
      // Wait one tick so the @if renders the second canvas before we grab it.
      setTimeout(() => this.bootPaneB(), 0);
    }
  }

  private bootPaneB(): void {
    const canvas = this.canvasBRef?.nativeElement;
    if (!canvas) {
      console.warn('[compare] canvasB not in DOM');
      return;
    }
    if (this.sceneB) this.sceneB.dispose();
    this.sceneB = new NBodyScene(canvas);
    this.sceneB.start(() => {
      const positions = this.streamB.sampleInterpolated();
      if (!positions) return;
      this.sceneB!.updatePositions(positions);
    });
    if (!this.scenarioB() || this.scenarioB() === this.selectedScenario()) {
      this.scenarioB.set(SimulationComponent.COMPARE_DEFAULT_B);
    }
    this.streamB.connect('ws://localhost:8081/snapshots');
    // Once the socket is open, push the chosen scenario to it.
    if (this.paneBWaitHandle !== null) clearInterval(this.paneBWaitHandle);
    this.paneBWaitHandle = window.setInterval(() => {
      if (this.streamB.connectionState() === 'open') {
        this.streamB.sendScenarioCommand(this.scenarioB());
        if (this.paneBWaitHandle !== null) clearInterval(this.paneBWaitHandle);
        this.paneBWaitHandle = null;
      }
    }, 200);
  }

  private tearDownPaneB(): void {
    if (this.paneBWaitHandle !== null) {
      clearInterval(this.paneBWaitHandle);
      this.paneBWaitHandle = null;
    }
    this.streamB.disconnect();
    this.sceneB?.dispose();
    this.sceneB = null;
  }

  protected onScenarioBChange(name: string): void {
    if (!name || name === this.scenarioB()) return;
    this.scenarioB.set(name);
    this.streamB.sendScenarioCommand(name);
  }

  protected onResetB(): void {
    this.streamB.sendCommand('reset');
  }

  /** Seek the replay cursor to a specific frame. */
  protected onSeek(raw: string): void {
    const frame = Number(raw);
    if (!Number.isFinite(frame) || frame < 0) return;
    this.stream.sendSeekReplay(Math.floor(frame));
  }

  /** When the user uploads custom YAML, load it on the live stream + close drawer. */
  protected onCustomLoaded(scenarioName: string): void {
    this.selectedScenario.set(scenarioName);
    this.stream.sendScenarioCommand(scenarioName);
    this.frameCount.set(0);
    this.syncUrl();
    setTimeout(() => this.customOpen.set(false), 1500);
  }

  // ===== URL deep-linking =====

  /**
   * Read query params on first connect and apply them.
   * Supported params:  ?scenario=name  ?n=NN  ?dt=NN  ?eps=NN
   * Lets a colleague open exactly the same setup via shared link.
   */
  private applyUrlParams(): void {
    const qp = this.route.snapshot.queryParamMap;
    const scenario = qp.get('scenario');
    const nParam   = qp.get('n');
    const dtParam  = qp.get('dt');
    const epsParam = qp.get('eps');

    let applied = 0;
    if (scenario && scenario !== this.selectedScenario()) {
      this.selectedScenario.set(scenario);
      this.stream.sendScenarioCommand(scenario);
      applied++;
    }
    // Send N + dt + eps a touch later so they apply AFTER the scenario reset.
    if (nParam || dtParam || epsParam) {
      setTimeout(() => {
        if (nParam) {
          const n = Number(nParam);
          if (Number.isFinite(n) && n >= 2) {
            this.n.set(n); this.stream.sendSetNCommand(n);
          }
        }
        if (dtParam) {
          const dt = Number(dtParam);
          if (Number.isFinite(dt) && dt > 0) {
            this.dt.set(dt); this.stream.sendCommand('setDt', dt);
          }
        }
        if (epsParam) {
          const eps = Number(epsParam);
          if (Number.isFinite(eps) && eps >= 0) {
            this.softening.set(eps); this.stream.sendCommand('setSoftening', eps);
          }
        }
      }, 800);
      applied++;
    }
    if (applied > 0) console.info(`[URL] applied ${applied} param(s) from query string`);
  }

  /** Update the URL bar to reflect current state (no navigation, no history entry). */
  private syncUrl(): void {
    const params: Record<string, string> = {};
    if (this.selectedScenario() && this.selectedScenario() !== 'default_plummer') {
      params['scenario'] = this.selectedScenario();
    }
    params['n']   = String(this.n());
    params['dt']  = String(this.dt());
    params['eps'] = String(this.softening());

    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: params,
      replaceUrl: true,
      queryParamsHandling: 'merge',
    });
  }

  protected async onCopyLink(): Promise<void> {
    this.syncUrl();
    // Wait one tick for the URL to update, then read it.
    await new Promise(r => setTimeout(r, 50));
    try {
      await navigator.clipboard.writeText(window.location.href);
      this.linkCopied.set(true);
      setTimeout(() => this.linkCopied.set(false), 2000);
    } catch (e) {
      console.warn('[URL] clipboard write failed (browser permissions?):', e);
    }
  }

  // Capture mode (MediaRecorder API — built-in, no external dep needed)
  protected readonly recording = signal(false);
  protected readonly recordingDurationSec = signal(0);
  private mediaRecorder: MediaRecorder | null = null;
  private recordedChunks: Blob[] = [];
  private recordingStartedAt = 0;
  private recordingTimer: number | null = null;

  private scene: NBodyScene | null = null;
  /** Pane B's scene — created on toggle-on, destroyed on toggle-off. */
  private sceneB: NBodyScene | null = null;
  /** Pending interval that waits for streamB's socket to open before sending
   *  its scenario load — kept so we can clear it on tearDown / destroy. */
  private paneBWaitHandle: number | null = null;

  ngAfterViewInit(): void {
    this.scene = new NBodyScene(this.canvasRef.nativeElement);

    // Load the scenario catalog (one-shot REST call) so the dropdown is populated.
    this.scenarioService.loadScenarios();

    // Render loop independent from the network: rAF ticks at the display refresh
    // rate (60 Hz / 120 Hz / 240 Hz depending on monitor), and we sample the
    // interpolated stream each tick. The kernel can produce snapshots at any
    // rate — the buffer + interpolation absorb the difference.
    this.scene.start(() => {
      const positions = this.stream.sampleInterpolated();
      if (!positions) return;
      this.scene!.updatePositions(positions);
      this.frameCount.update(c => c + 1);
      this.refreshSelectionMetrics();
    });

    this.onConnect();

    // Apply ?scenario=…&n=…&dt=…&eps=… once the websocket is open.
    // Watching connectionState lets us defer until the stream can accept commands.
    const stop = setInterval(() => {
      if (!this.isConnected() || this.urlParamsApplied) return;
      this.applyUrlParams();
      this.urlParamsApplied = true;
      clearInterval(stop);
    }, 250);
  }

  ngOnDestroy(): void {
    if (this.recording()) this.stopRecording();
    this.tearDownPaneB();
    this.stream.disconnect();
    this.scene?.dispose();
  }

  protected onConnect(): void {
    this.paused.set(false);
    this.stream.connect('ws://localhost:8081/snapshots');
  }

  protected onDisconnect(): void {
    this.stream.disconnect();
    this.paused.set(false);
  }

  protected onPlayPause(): void {
    if (this.paused()) {
      this.stream.sendCommand('resume');
      this.paused.set(false);
    } else {
      this.stream.sendCommand('pause');
      this.paused.set(true);
    }
  }

  protected onReset(): void {
    this.stream.sendCommand('reset');
    this.frameCount.set(0);
  }

  protected onDtChange(raw: string): void {
    const value = Number(raw);
    if (!Number.isFinite(value) || value <= 0) return;
    this.dt.set(value);
    this.stream.sendCommand('setDt', value);
    this.syncUrl();
  }

  protected onSofteningChange(raw: string): void {
    const value = Number(raw);
    if (!Number.isFinite(value) || value < 0) return;
    this.softening.set(value);
    this.stream.sendCommand('setSoftening', value);
    this.syncUrl();
  }

  protected onNChange(raw: string): void {
    const value = Number(raw);
    if (!Number.isFinite(value) || value < 2 || value > 10000) return;
    if (value === this.n()) return;
    this.n.set(value);
    this.stream.sendSetNCommand(value);
    this.frameCount.set(0);
    this.syncUrl();
  }

  protected onScenarioChange(name: string): void {
    if (!name || name === this.selectedScenario()) return;
    this.selectedScenario.set(name);
    this.stream.sendScenarioCommand(name);
    this.frameCount.set(0);
    this.syncUrl();
  }

  /**
   * Toggle WebM capture using the browser's built-in MediaRecorder API.
   *
   * <p>{@code canvas.captureStream(60)} produces a MediaStream that mirrors
   * the canvas at the given fps. MediaRecorder consumes that stream and
   * produces compressed WebM (VP9 if available, VP8 fallback) chunks that
   * we accumulate and assemble into a Blob on stop. The Blob is offered
   * via a temporary download link.
   *
   * <p>This is simpler and more robust than CCapture.js, which is a hacky
   * page-stalling approach that breaks easily when the rAF loop is
   * non-trivial. MediaRecorder is W3C standard and works in all modern browsers.
   */
  protected onToggleRecording(): void {
    if (this.recording()) {
      this.stopRecording();
    } else {
      this.startRecording();
    }
  }

  private startRecording(): void {
    const canvas = this.canvasRef.nativeElement;
    const stream = canvas.captureStream(60);

    // Pick best available codec — Chrome supports VP9, Safari may need VP8.
    const mimeCandidates = [
      'video/webm;codecs=vp9',
      'video/webm;codecs=vp8',
      'video/webm'
    ];
    const mimeType = mimeCandidates.find(m => MediaRecorder.isTypeSupported(m)) ?? '';
    if (!mimeType) {
      console.error('[capture] no supported WebM codec available in this browser');
      return;
    }

    this.recordedChunks = [];
    this.mediaRecorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 8_000_000 });

    this.mediaRecorder.ondataavailable = ev => {
      if (ev.data && ev.data.size > 0) this.recordedChunks.push(ev.data);
    };
    this.mediaRecorder.onstop = () => this.finalizeDownload();

    this.recordingStartedAt = performance.now();
    this.recordingDurationSec.set(0);
    this.mediaRecorder.start(1000); // collect data every 1s
    this.recording.set(true);

    // Update visible duration in title bar
    this.recordingTimer = window.setInterval(() => {
      const sec = (performance.now() - this.recordingStartedAt) / 1000;
      this.recordingDurationSec.set(Math.floor(sec));
    }, 250);
  }

  private stopRecording(): void {
    if (!this.mediaRecorder || this.mediaRecorder.state === 'inactive') return;
    this.mediaRecorder.stop();
    this.recording.set(false);
    if (this.recordingTimer !== null) {
      clearInterval(this.recordingTimer);
      this.recordingTimer = null;
    }
  }

  private finalizeDownload(): void {
    const blob = new Blob(this.recordedChunks, { type: 'video/webm' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    const ts = new Date().toISOString().replace(/[:.]/g, '-');
    a.download = `astro-nbody-${ts}.webm`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 5000);

    this.recordedChunks = [];
    this.mediaRecorder = null;
  }
}
