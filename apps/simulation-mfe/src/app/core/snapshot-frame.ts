/**
 * Mirror of the wire protocol defined in
 * services/simulation-service/.../SnapshotBinaryCodec.java
 *
 * Layout (little-endian):
 *   0       8      timestamp (float64, simulation time in Hénon units)
 *   8       4      N (int32)
 *   12      4·N    x (float32 × N)
 *   12+4N   4·N    y (float32 × N)
 *   12+8N   4·N    z (float32 × N)
 *
 * Total bytes: 12 + 12·N. For N=3000 → 36 012 B per frame.
 */

const HEADER_BYTES = 12;

/** Verifies the runtime is little-endian. Modern x86_64/ARM64 always are. */
function assertLittleEndian(): void {
  const probe = new ArrayBuffer(2);
  new DataView(probe).setInt16(0, 256, true);
  if (new Int16Array(probe)[0] !== 256) {
    throw new Error(
      'Big-endian platform detected. SnapshotFrame parser assumes little-endian.'
    );
  }
}
assertLittleEndian();

export interface SnapshotFrame {
  /** Simulation time in Hénon units. */
  readonly time: number;
  /** Number of bodies. */
  readonly n: number;
  /** Positions as Float32Array, length 3·N, layout [x0..xN-1, y0..yN-1, z0..zN-1]. */
  readonly positionsXYZ: Float32Array;
}

/**
 * Parses a binary snapshot frame received over WebSocket.
 *
 * <p>Allocates a fresh Float32Array of length 3·N and copies the position data
 * into a contiguous interleaved buffer suitable for direct upload as a Three.js
 * BufferAttribute (which expects [x0,y0,z0, x1,y1,z1, ...]).
 *
 * <p>Performance: at N=3000, this is ~36 KB allocation + interleave. Negligible
 * at 60 fps. If profile shows GC pressure later, switch to a pooled buffer.
 */
export function parseSnapshotFrame(buffer: ArrayBuffer): SnapshotFrame {
  if (buffer.byteLength < HEADER_BYTES) {
    throw new Error(`Frame too short: ${buffer.byteLength} bytes`);
  }

  const view = new DataView(buffer);
  const time = view.getFloat64(0, true);
  const n = view.getInt32(8, true);

  const expectedLength = HEADER_BYTES + 12 * n;
  if (buffer.byteLength !== expectedLength) {
    throw new Error(
      `Frame length mismatch: expected ${expectedLength} bytes for N=${n}, got ${buffer.byteLength}`
    );
  }

  // Views into the wire arrays — no copy yet.
  const x = new Float32Array(buffer, HEADER_BYTES,            n);
  const y = new Float32Array(buffer, HEADER_BYTES + 4 * n,    n);
  const z = new Float32Array(buffer, HEADER_BYTES + 8 * n,    n);

  // Interleave into [x0,y0,z0, x1,y1,z1, ...] for Three.js.
  const positionsXYZ = new Float32Array(3 * n);
  for (let i = 0; i < n; i++) {
    positionsXYZ[3 * i + 0] = x[i];
    positionsXYZ[3 * i + 1] = y[i];
    positionsXYZ[3 * i + 2] = z[i];
  }

  return { time, n, positionsXYZ };
}
