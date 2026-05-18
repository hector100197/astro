import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  effect,
  inject,
  input
} from '@angular/core';
import uPlot, { type AlignedData, type Options } from 'uplot';
import { SnapshotStreamService } from '../core/snapshot-stream.service';

/**
 * Time-series plot of physics diagnostics (K, U, E) using uPlot.
 *
 * <p>uPlot is a ~30 KB canvas-based chart library — orders of magnitude
 * faster than Plotly/D3 for streaming updates because it skips DOM nodes
 * per data point. We update via {@code setData()} on every diagnostics
 * frame; the canvas re-renders incrementally.
 *
 * <p>The plot subscribes to {@link SnapshotStreamService.diagnosticsHistory}
 * via a signal effect, so it stays in sync without manual rAF coordination.
 *
 * <p>Foundation for Sem 3 validation panel: when the real Fortran kernel
 * produces real K and U, the energy curve will show actual drift —
 * a flat line means the leapfrog integrator is conserving correctly,
 * a drifting line means a bug or too-large Δt.
 */
@Component({
  selector: 'app-diagnostics-plot',
  standalone: true,
  template: `
    <div class="diagnostics-plot">
      <div class="header">
        <span class="title">{{ label() }}</span>
        @if (latestSimTime() !== null) {
          <span class="meta">t = {{ latestSimTime()!.toFixed(2) }}</span>
        }
        @if (latestE() !== null) {
          <span class="meta">E = {{ latestE()!.toFixed(4) }}</span>
        }
        @if (latestQ() !== null) {
          <span class="meta">Q = {{ latestQ()!.toFixed(3) }}</span>
        }
      </div>
      <div class="canvas-host" #host></div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      width: 100%;
      pointer-events: auto;
    }
    .diagnostics-plot {
      background: rgba(11, 18, 32, 0.86);
      border: 1px solid #1f2937;
      border-radius: 8px;
      backdrop-filter: blur(6px);
      padding: 0.5rem 0.75rem 0.25rem 0.75rem;
    }
    .header {
      display: flex; align-items: center; gap: 0.85rem;
      font-size: 0.8rem; color: #cbd5e1;
      padding-bottom: 0.25rem;
    }
    .title { font-weight: 600; color: #e5e7eb; }
    .meta {
      font-family: ui-monospace, "SF Mono", monospace;
      color: #94a3b8;
    }
    .canvas-host { height: 140px; }
    /* uPlot defaults are light-themed — override key elements for dark mode */
    :host ::ng-deep .u-legend { display: none; }
    :host ::ng-deep .u-axis { color: #94a3b8 !important; }
  `]
})
export class DiagnosticsPlotComponent implements AfterViewInit, OnDestroy {
  @ViewChild('host', { static: true }) host!: ElementRef<HTMLDivElement>;

  /** Stream to subscribe to. Defaults to the root singleton, but the
   *  comparison view passes an explicit secondary stream instance.
   *  Signal input so the effect re-binds if the parent ever swaps it. */
  readonly stream = input<SnapshotStreamService>(inject(SnapshotStreamService));
  /** Header label — useful in comparison mode to tell which pane is which. */
  readonly label = input<string>('Energía vs tiempo (Hénon)');

  private chart: uPlot | null = null;
  private resizeObserver: ResizeObserver | null = null;

  // Reusable typed-array buffers for the plot data; we resize when history grows.
  private dataT = new Float64Array(0);
  private dataK = new Float64Array(0);
  private dataU = new Float64Array(0);
  private dataE = new Float64Array(0);

  /** Latest values for header meta strip. */
  protected readonly latestSimTime = () => this.stream().latestDiagnostics()?.simTime ?? null;
  protected readonly latestE       = () => this.stream().latestDiagnostics()?.E ?? null;
  protected readonly latestQ       = () => this.stream().latestDiagnostics()?.Q ?? null;

  constructor() {
    // Re-render on every diagnostics history mutation. The effect tracks both
    // `stream` (so swapping pane wires correctly) and the history signal.
    effect(() => {
      const history = this.stream().diagnosticsHistory();
      this.applyData(history);
    });
  }

  ngAfterViewInit(): void {
    const el = this.host.nativeElement;

    const opts: Options = {
      width: el.clientWidth,
      height: 140,
      pxAlign: 1,
      cursor: { drag: { x: false, y: false }, points: { show: false } },
      legend: { show: false },
      scales: {
        x: { time: false },
        y: { auto: true }
      },
      axes: [
        {
          stroke: '#94a3b8',
          grid: { stroke: 'rgba(148, 163, 184, 0.12)', width: 1 },
          ticks: { stroke: 'rgba(148, 163, 184, 0.25)' }
        },
        {
          stroke: '#94a3b8',
          grid: { stroke: 'rgba(148, 163, 184, 0.12)', width: 1 },
          ticks: { stroke: 'rgba(148, 163, 184, 0.25)' },
          size: 50
        }
      ],
      series: [
        { label: 't' },
        { label: 'K', stroke: '#34d399', width: 1.5 },
        { label: 'U', stroke: '#fbbf24', width: 1.5 },
        { label: 'E', stroke: '#a78bfa', width: 2 }
      ]
    };

    const initial: AlignedData = [
      this.dataT as unknown as number[],
      this.dataK as unknown as number[],
      this.dataU as unknown as number[],
      this.dataE as unknown as number[]
    ];
    this.chart = new uPlot(opts, initial, el);

    this.resizeObserver = new ResizeObserver(() => {
      if (!this.chart) return;
      this.chart.setSize({ width: el.clientWidth, height: 140 });
    });
    this.resizeObserver.observe(el);

    // Apply any history that may already exist.
    this.applyData(this.stream().diagnosticsHistory());
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    this.chart?.destroy();
    this.chart = null;
  }

  private applyData(history: readonly { simTime: number; K: number; U: number; E: number }[]): void {
    if (!this.chart) return;
    const n = history.length;
    if (this.dataT.length !== n) {
      this.dataT = new Float64Array(n);
      this.dataK = new Float64Array(n);
      this.dataU = new Float64Array(n);
      this.dataE = new Float64Array(n);
    }
    for (let i = 0; i < n; i++) {
      this.dataT[i] = history[i].simTime;
      this.dataK[i] = history[i].K;
      this.dataU[i] = history[i].U;
      this.dataE[i] = history[i].E;
    }
    this.chart.setData([
      this.dataT as unknown as number[],
      this.dataK as unknown as number[],
      this.dataU as unknown as number[],
      this.dataE as unknown as number[]
    ] as AlignedData);
  }
}
