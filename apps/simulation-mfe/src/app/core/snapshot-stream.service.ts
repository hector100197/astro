import { Injectable, NgZone, signal } from '@angular/core';
import { parseSnapshotFrame, type SnapshotFrame } from './snapshot-frame';
import { parseDiagnostics, type Diagnostics } from './diagnostics';

/**
 * One frame as held in the ring buffer.
 * {@link arrivalTime} is real time ({@code performance.now()}) — used to
 * interpolate at render time. {@link simTime} is the server's simulation time
 * in Hénon units, kept for the diagnostics panel.
 */
interface BufferedFrame {
  readonly arrivalTime: number;
  readonly simTime: number;
  readonly positionsXYZ: Float32Array;
  readonly n: number;
}

/**
 * WebSocket client + decoupled-timestep ring buffer.
 *
 * <p>Implements the canonical pattern from Glenn Fiedler's
 * <em>Fix Your Timestep!</em> (2004): the kernel produces snapshots at its
 * own rate, the buffer holds recent frames timestamped on arrival, and the
 * render loop samples an <strong>interpolated</strong> position between the
 * two frames that bracket {@code performance.now() - INTERP_DELAY}.
 *
 * <p>The interpolation delay (~60 ms) is the "lag behind newest frame"
 * window that lets us always have a future frame to interp toward,
 * absorbing network jitter without stutter.
 *
 * <p>Frames are decoded outside Angular's zone to keep change detection
 * silent during 60+ Hz streams.
 */
@Injectable({ providedIn: 'root' })
export class SnapshotStreamService {

  /** How far behind the newest arrival the renderer plays back, in milliseconds. */
  private static readonly INTERP_DELAY_MS = 60;

  /** Ring buffer capacity — 4 seconds at 60 Hz. */
  private static readonly BUFFER_SIZE = 240;

  private socket: WebSocket | null = null;
  private framesReceived = 0;

  private readonly buffer: BufferedFrame[] = [];
  /** Reusable scratch buffer for interpolated positions; size adapts to N. */
  private interpScratch: Float32Array | null = null;

  /** Latest snapshot frame (for badges / metadata). Render loop should NOT use this. */
  readonly latestFrame = signal<SnapshotFrame | null>(null);
  /** Connection state, displayed in the UI. */
  readonly connectionState = signal<'idle' | 'connecting' | 'open' | 'closed' | 'error'>('idle');
  /** True once we have ≥2 frames so interpolation is meaningful. */
  readonly interpolating = signal<boolean>(false);

  /**
   * Latest diagnostics frame (energy, momentum, virial). Updated outside
   * Angular's zone — components consume via signal in render loops or effects.
   */
  readonly latestDiagnostics = signal<Diagnostics | null>(null);
  /**
   * Rolling buffer of recent diagnostics for time-series plots. Capped at
   * {@link DIAGNOSTICS_HISTORY_SIZE} entries (≈10 s at 60 Hz).
   */
  readonly diagnosticsHistory = signal<readonly Diagnostics[]>([]);
  private static readonly DIAGNOSTICS_HISTORY_SIZE = 600;

  /**
   * Replay state announced by the server when the stream switches to playback.
   * Null while live; populated when the user clicks ▶ Replay on a batch job.
   * Drives the time scrubber UI.
   */
  readonly replayInfo = signal<{ jobId: string; totalFrames: number } | null>(null);

  constructor(private readonly ngZone: NgZone) {}

  connect(url: string): void {
    if (this.socket && this.socket.readyState !== WebSocket.CLOSED) {
      console.warn('[SnapshotStream] already connected, ignoring connect()');
      return;
    }

    this.connectionState.set('connecting');

    this.ngZone.runOutsideAngular(() => {
      const ws = new WebSocket(url);
      ws.binaryType = 'arraybuffer';

      ws.onopen = () => {
        this.connectionState.set('open');
        console.info(`[SnapshotStream] connected to ${url}`);
      };

      ws.onmessage = (event) => {
        const data = event.data;
        if (data instanceof ArrayBuffer) {
          try {
            const frame = parseSnapshotFrame(data);
            this.pushFrame(frame);
            this.latestFrame.set(frame);
            this.framesReceived++;
          } catch (err) {
            console.error('[SnapshotStream] frame parse failed:', err);
          }
          return;
        }
        if (typeof data === 'string') {
          // Try diagnostics first (the common case at 60 Hz).
          const diag = parseDiagnostics(data);
          if (diag) { this.pushDiagnostics(diag); return; }
          // Else: small control messages (replayInfo, future ones).
          try {
            const msg = JSON.parse(data);
            if (msg?.type === 'replayInfo' && typeof msg.totalFrames === 'number') {
              this.replayInfo.set({ jobId: String(msg.jobId), totalFrames: msg.totalFrames });
            }
          } catch { /* ignore non-JSON */ }
          return;
        }
        console.warn('[SnapshotStream] unknown message kind:', typeof data);
      };

      ws.onerror = (err) => {
        console.error('[SnapshotStream] error:', err);
        this.connectionState.set('error');
      };

      ws.onclose = (ev) => {
        console.info(`[SnapshotStream] closed (code=${ev.code} reason="${ev.reason}", frames=${this.framesReceived})`);
        this.connectionState.set('closed');
        this.interpolating.set(false);
        this.socket = null;
      };

      this.socket = ws;
    });
  }

  disconnect(): void {
    if (this.socket) {
      this.socket.close(1000, 'client disconnect');
      this.socket = null;
    }
    this.buffer.length = 0;
    this.interpolating.set(false);
    this.latestDiagnostics.set(null);
    this.diagnosticsHistory.set([]);
  }

  /**
   * Send a control command to the server. JSON payload over the same WebSocket
   * that streams binary snapshots back. See {@code mx.astro.contracts.ControlCommand}
   * on the server side for the protocol.
   */
  sendCommand(action: 'pause' | 'resume' | 'setDt' | 'setSoftening' | 'reset', value?: number): void {
    const ws = this.socket;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      console.warn(`[SnapshotStream] cannot send '${action}' — socket not open`);
      return;
    }
    const payload = value !== undefined ? { action, value } : { action };
    ws.send(JSON.stringify(payload));
  }

  /** Special-case command for scenario swap (carries scenarioName, not numeric value). */
  sendScenarioCommand(scenarioName: string): void {
    const ws = this.socket;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      console.warn(`[SnapshotStream] cannot send loadScenario — socket not open`);
      return;
    }
    this.replayInfo.set(null);   // exit replay mode locally
    ws.send(JSON.stringify({ action: 'loadScenario', scenarioName }));
  }

  /** Jump the replay cursor to a specific frame (no-op if not in replay). */
  sendSeekReplay(frameIndex: number): void {
    const ws = this.socket;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify({ action: 'seekReplay', value: frameIndex }));
  }

  /** Resize the running stream to a new N (only meaningful for procedural ICs). */
  sendSetNCommand(nBodies: number): void {
    const ws = this.socket;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      console.warn(`[SnapshotStream] cannot send setN — socket not open`);
      return;
    }
    ws.send(JSON.stringify({ action: 'setN', nBodies }));
  }

  /**
   * Switch the live stream into REPLAY mode for a completed batch job.
   * Backend reads the job's multi-snapshot HDF5 and emits frames at the
   * usual rate; frontend sees them just like a live simulation.
   */
  sendReplayCommand(jobId: string): void {
    const ws = this.socket;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      console.warn('[SnapshotStream] cannot send replayJob — socket not open');
      return;
    }
    ws.send(JSON.stringify({ action: 'replayJob', jobId }));
  }

  /**
   * Sample the snapshot stream at the current render time, returning a
   * Float32Array of interpolated [x,y,z]·N positions.
   *
   * <p>Returns a reference to a reused scratch buffer — the caller must
   * consume it before the next call (e.g., upload to GPU within the same
   * rAF tick). Returns null if no frames are available yet.
   */
  sampleInterpolated(): Float32Array | null {
    const buf = this.buffer;
    if (buf.length === 0) return null;
    if (buf.length === 1) return buf[0].positionsXYZ;

    const renderTime = performance.now() - SnapshotStreamService.INTERP_DELAY_MS;

    // Find the pair of frames that bracket renderTime, scanning newest-first.
    let prev: BufferedFrame | null = null;
    let next: BufferedFrame | null = null;
    for (let i = buf.length - 1; i >= 1; i--) {
      if (buf[i].arrivalTime >= renderTime && buf[i - 1].arrivalTime <= renderTime) {
        prev = buf[i - 1];
        next = buf[i];
        break;
      }
    }

    // Outside the buffer window: clamp to the most recent frame.
    if (!prev || !next) {
      this.interpolating.set(false);
      return buf[buf.length - 1].positionsXYZ;
    }

    const span = next.arrivalTime - prev.arrivalTime;
    const alpha = span > 0 ? (renderTime - prev.arrivalTime) / span : 0;
    this.interpolating.set(true);
    return this.lerp(prev.positionsXYZ, next.positionsXYZ, alpha);
  }

  private pushFrame(frame: SnapshotFrame): void {
    this.buffer.push({
      arrivalTime: performance.now(),
      simTime: frame.time,
      positionsXYZ: frame.positionsXYZ,
      n: frame.n
    });
    if (this.buffer.length > SnapshotStreamService.BUFFER_SIZE) {
      this.buffer.shift();
    }
  }

  private pushDiagnostics(diag: Diagnostics): void {
    this.latestDiagnostics.set(diag);
    const history = this.diagnosticsHistory();
    const next = history.length >= SnapshotStreamService.DIAGNOSTICS_HISTORY_SIZE
      ? [...history.slice(1), diag]
      : [...history, diag];
    this.diagnosticsHistory.set(next);
  }

  private lerp(a: Float32Array, b: Float32Array, alpha: number): Float32Array {
    const len = a.length;
    if (!this.interpScratch || this.interpScratch.length !== len) {
      this.interpScratch = new Float32Array(len);
    }
    const out = this.interpScratch;
    for (let i = 0; i < len; i++) {
      out[i] = a[i] + alpha * (b[i] - a[i]);
    }
    return out;
  }
}
