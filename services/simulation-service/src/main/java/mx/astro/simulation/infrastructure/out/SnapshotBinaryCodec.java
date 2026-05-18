package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.Snapshot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Binary wire protocol for snapshots. Little-endian, fixed layout:
 *
 * <pre>
 *   offset  bytes  field
 *   0       8      timestamp (float64, simulation time in Hénon units)
 *   8       4      N (int32, number of bodies)
 *   12      4*N    x (float32 × N)
 *   12+4N   4*N    y (float32 × N)
 *   12+8N   4*N    z (float32 × N)
 * </pre>
 *
 * Total size: {@code 12 + 12·N} bytes. For N=3000 → 36 012 bytes per frame.
 *
 * Velocities and masses are NOT sent on the wire — they stay server-side and
 * are persisted with the run. The client only needs positions to render.
 *
 * <p>Float32 is used in the wire format because (a) sub-pixel precision is
 * unnecessary at any reasonable display resolution, and (b) it halves bandwidth
 * vs float64. The kernel computes in float64 internally; the codec narrows here.
 */
public final class SnapshotBinaryCodec {

    private SnapshotBinaryCodec() {}

    /** Frame header size in bytes (timestamp + N). */
    public static final int HEADER_BYTES = 8 + 4;

    /**
     * Encodes a snapshot into a freshly-allocated direct ByteBuffer ready to send.
     * The returned buffer is flipped (position=0, limit=size).
     */
    public static ByteBuffer encode(Snapshot snapshot) {
        final int n = snapshot.n();
        final int size = HEADER_BYTES + 12 * n;

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.putDouble(snapshot.time());
        buf.putInt(n);

        for (int i = 0; i < n; i++) buf.putFloat((float) snapshot.x()[i]);
        for (int i = 0; i < n; i++) buf.putFloat((float) snapshot.y()[i]);
        for (int i = 0; i < n; i++) buf.putFloat((float) snapshot.z()[i]);

        buf.flip();
        return buf;
    }

    /** Bytes that {@link #encode} will produce for a snapshot of size N. */
    public static int frameSize(int n) {
        return HEADER_BYTES + 12 * n;
    }
}
