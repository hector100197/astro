package mx.astro.simulation.infrastructure.out;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Tiny helper bundling six SoA double arrays + masses, allocated in a single
 * arena. All segments are flat {@code double[N]} buffers laid out for the
 * Fortran kernel's contiguous-array argument convention.
 *
 * <p>Usage pattern:
 * <pre>
 *   try (Arena arena = Arena.ofConfined()) {
 *       NativeArrays na = NativeArrays.allocate(arena, n);
 *       na.copyInPositions(...);
 *       loader.stepHandle().invoke(na.x(), na.y(), na.z(),
 *                                   na.vx(), na.vy(), na.vz(),
 *                                   na.m(), n, dt, eps);
 *       double[] x = na.readX();
 *       ...
 *   }
 * </pre>
 *
 * <p>Confined arenas pin to the calling thread; we use them when the call
 * sequence stays on a single scheduler worker.
 */
final class NativeArrays {

    private static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
    private static final long DBYTES = D.byteSize();

    private final int n;
    private final MemorySegment x, y, z, vx, vy, vz, m;

    private NativeArrays(int n,
                         MemorySegment x, MemorySegment y, MemorySegment z,
                         MemorySegment vx, MemorySegment vy, MemorySegment vz,
                         MemorySegment m) {
        this.n = n;
        this.x = x;   this.y = y;   this.z = z;
        this.vx = vx; this.vy = vy; this.vz = vz;
        this.m = m;
    }

    static NativeArrays allocate(Arena arena, int n) {
        long bytes = (long) n * DBYTES;
        return new NativeArrays(n,
                arena.allocate(bytes), arena.allocate(bytes), arena.allocate(bytes),
                arena.allocate(bytes), arena.allocate(bytes), arena.allocate(bytes),
                arena.allocate(bytes));
    }

    int n() { return n; }
    MemorySegment x()  { return x; }
    MemorySegment y()  { return y; }
    MemorySegment z()  { return z; }
    MemorySegment vx() { return vx; }
    MemorySegment vy() { return vy; }
    MemorySegment vz() { return vz; }
    MemorySegment m()  { return m; }

    void writeAll(double[] xa, double[] ya, double[] za,
                  double[] vxa, double[] vya, double[] vza,
                  double[] ma) {
        MemorySegment.copy(xa,  0, x,  D, 0, n);
        MemorySegment.copy(ya,  0, y,  D, 0, n);
        MemorySegment.copy(za,  0, z,  D, 0, n);
        MemorySegment.copy(vxa, 0, vx, D, 0, n);
        MemorySegment.copy(vya, 0, vy, D, 0, n);
        MemorySegment.copy(vza, 0, vz, D, 0, n);
        MemorySegment.copy(ma,  0, m,  D, 0, n);
    }

    double[] readSegment(MemorySegment seg) {
        double[] out = new double[n];
        MemorySegment.copy(seg, D, 0, out, 0, n);
        return out;
    }
}
