/**
 * Mirror of {@code mx.astro.contracts.DiagnosticsDto}.
 *
 * Wire format (JSON, sent as TextMessage on the same WebSocket as the
 * binary snapshot frames):
 *
 * <pre>
 *   { "type": "diagnostics", "t": 1.5, "step": 300,
 *     "K": 1.0, "U": -2.0, "E": -1.0,
 *     "P": [0,0,0], "L": [0,0,1], "Q": 1.0 }
 * </pre>
 */
export interface Diagnostics {
  readonly simTime: number;
  readonly stepIndex: number;
  /** Kinetic energy. */
  readonly K: number;
  /** Potential energy. */
  readonly U: number;
  /** Total energy K + U. */
  readonly E: number;
  /** Linear momentum [Px, Py, Pz]. */
  readonly P: readonly [number, number, number];
  /** Angular momentum [Lx, Ly, Lz]. */
  readonly L: readonly [number, number, number];
  /** Virial ratio 2K/|U| (~1 for relaxed bound system). */
  readonly Q: number;
  /** Lagrangian radius enclosing 10% of mass (core size). */
  readonly r10: number;
  /** Half-mass radius (canonical cluster size scale). */
  readonly r50: number;
  /** Lagrangian radius enclosing 90% of mass (halo extent). */
  readonly r90: number;
}

interface DiagnosticsWire {
  type: string;
  t: number;
  step: number;
  K: number;
  U: number;
  E: number;
  P: number[];
  L: number[];
  Q: number;
  r10: number;
  r50: number;
  r90: number;
}

/**
 * Parses a server JSON message and returns Diagnostics if it is one,
 * or null if the message is some other text-channel payload (future
 * status / warning / error messages will be added in later sprints).
 */
export function parseDiagnostics(json: string): Diagnostics | null {
  let payload: DiagnosticsWire;
  try {
    payload = JSON.parse(json);
  } catch {
    return null;
  }
  if (payload?.type !== 'diagnostics') return null;

  return {
    simTime: payload.t,
    stepIndex: payload.step,
    K: payload.K,
    U: payload.U,
    E: payload.E,
    P: [payload.P[0] ?? 0, payload.P[1] ?? 0, payload.P[2] ?? 0],
    L: [payload.L[0] ?? 0, payload.L[1] ?? 0, payload.L[2] ?? 0],
    Q: payload.Q,
    r10: payload.r10 ?? 0,
    r50: payload.r50 ?? 0,
    r90: payload.r90 ?? 0
  };
}
