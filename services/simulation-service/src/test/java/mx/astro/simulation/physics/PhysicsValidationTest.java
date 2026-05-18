package mx.astro.simulation.physics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI-enforced physics validation against the Fortran kernel.
 *
 * <p>Two tests:
 * <ol>
 *   <li><strong>Kepler 2-body</strong> — two equal masses on a circular orbit
 *       of radius 0.5 each (separation 1). The reduced two-body problem
 *       gives an analytical period T = 2π in Hénon units (G=M=1, μ=½).
 *       After integrating for one period the bodies must return to within
 *       a tolerance of their initial positions.</li>
 *   <li><strong>Energy conservation</strong> — a Plummer N=100 cluster
 *       integrated for 1000 steps with dt=0.01. The leapfrog integrator
 *       is symplectic so |ΔE/E| should oscillate within a bounded window
 *       (target &lt; 0.5%).</li>
 * </ol>
 *
 * <p>Tests are enabled only when {@code kernel/build/libnbody.dylib} (or
 * {@code .so}) is present — they skip on machines without the kernel built,
 * making them safe to run via {@code mvn test} without manual prerequisites.
 */
class PhysicsValidationTest {

    private static final String KERNEL_PATH = resolveKernelPath();

    static boolean kernelAvailable() {
        return KERNEL_PATH != null && Files.exists(Path.of(KERNEL_PATH));
    }

    private static String resolveKernelPath() {
        String configured = System.getenv("ASTRO_KERNEL_LIB");
        if (configured != null) return Path.of(configured).toAbsolutePath().toString();
        // Default: relative to test working dir (service module dir).
        Path defaultPath = Path.of("../..", "kernel", "build", "libnbody.dylib").toAbsolutePath().normalize();
        if (Files.exists(defaultPath)) return defaultPath.toString();
        Path soPath = Path.of("../..", "kernel", "build", "libnbody.so").toAbsolutePath().normalize();
        if (Files.exists(soPath)) return soPath.toString();
        return null;
    }

    /** Holder for the MethodHandles we resolve once per test class. */
    private static class Kernel {
        final MethodHandle step;
        final MethodHandle initPlummer;
        final Arena arena = Arena.ofShared();

        Kernel() {
            SymbolLookup lookup = SymbolLookup.libraryLookup(Path.of(KERNEL_PATH), arena);
            Linker linker = Linker.nativeLinker();
            this.initPlummer = linker.downcallHandle(
                    lookup.find("nbody_init_plummer").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ADDRESS, ADDRESS, ADDRESS,
                            ADDRESS, ADDRESS, ADDRESS,
                            ADDRESS, JAVA_INT, JAVA_INT));
            this.step = linker.downcallHandle(
                    lookup.find("nbody_step").orElseThrow(),
                    FunctionDescriptor.ofVoid(
                            ADDRESS, ADDRESS, ADDRESS,
                            ADDRESS, ADDRESS, ADDRESS,
                            ADDRESS, JAVA_INT, JAVA_DOUBLE, JAVA_DOUBLE));
        }
    }

    // ---------------------------------------------------------------------
    // Test 1 — Kepler 2-body
    // ---------------------------------------------------------------------

    @Test
    @EnabledIf("kernelAvailable")
    @DisplayName("Kepler 2-body: circular orbit closes after one period (T=2π)")
    void kepler_two_body_closes() throws Throwable {
        Kernel k = new Kernel();
        try (Arena callArena = Arena.ofConfined()) {
            int n = 2;
            long bytes = (long) n * Double.BYTES;
            MemorySegment x  = callArena.allocate(bytes), y  = callArena.allocate(bytes), z  = callArena.allocate(bytes);
            MemorySegment vx = callArena.allocate(bytes), vy = callArena.allocate(bytes), vz = callArena.allocate(bytes);
            MemorySegment m  = callArena.allocate(bytes);

            // Equal masses 0.5 each. Separation d = 1. Each body orbits the centre
            // of mass at radius R = 0.5 with the SAME angular velocity ω.
            //   F_grav  = G·M1·M2/d² = 1·0.5·0.5/1² = 0.25
            //   F_cent  = M1·R·ω²    = 0.5·0.5·ω² = 0.25·ω²
            //   ⇒ ω = 1, v_circ = R·ω = 0.5, period T = 2π/ω = 2π   (Hénon units)
            double R = 0.5, vCirc = 0.5;
            double[] xa = {  R, -R };
            double[] ya = {  0,  0 };
            double[] za = {  0,  0 };
            double[] vxa = { 0,  0 };
            double[] vya = {  vCirc, -vCirc };
            double[] vza = { 0,  0 };
            double[] ma  = { 0.5, 0.5 };

            MemorySegment.copy(xa,  0, x,  JAVA_DOUBLE, 0, n);
            MemorySegment.copy(ya,  0, y,  JAVA_DOUBLE, 0, n);
            MemorySegment.copy(za,  0, z,  JAVA_DOUBLE, 0, n);
            MemorySegment.copy(vxa, 0, vx, JAVA_DOUBLE, 0, n);
            MemorySegment.copy(vya, 0, vy, JAVA_DOUBLE, 0, n);
            MemorySegment.copy(vza, 0, vz, JAVA_DOUBLE, 0, n);
            MemorySegment.copy(ma,  0, m,  JAVA_DOUBLE, 0, n);

            // Integrate one full period
            double T = 2.0 * Math.PI;
            int nSteps = 10_000;
            double dt = T / nSteps;
            double eps = 0.0;  // no softening; bodies never approach close enough

            for (int s = 0; s < nSteps; s++) {
                k.step.invoke(x, y, z, vx, vy, vz, m, n, dt, eps);
            }

            double[] xf = new double[n], yf = new double[n];
            MemorySegment.copy(x, JAVA_DOUBLE, 0, xf, 0, n);
            MemorySegment.copy(y, JAVA_DOUBLE, 0, yf, 0, n);

            double err1 = Math.hypot(xf[0] - xa[0], yf[0] - ya[0]);
            double err2 = Math.hypot(xf[1] - xa[1], yf[1] - ya[1]);
            double tol = 1e-3;  // 0.1% of orbital radius

            System.out.printf("Kepler closure: body0 err=%.2e, body1 err=%.2e, tol=%.0e%n",
                    err1, err2, tol);
            assertTrue(err1 < tol, () -> "Body 0 did not return within tolerance: " + err1);
            assertTrue(err2 < tol, () -> "Body 1 did not return within tolerance: " + err2);
        }
    }

    // ---------------------------------------------------------------------
    // Test 2 — Energy conservation on a Plummer cluster
    // ---------------------------------------------------------------------

    @Test
    @EnabledIf("kernelAvailable")
    @DisplayName("Plummer N=100, 1000 steps: |ΔE/E| < 0.5%")
    void plummer_energy_conservation() throws Throwable {
        Kernel k = new Kernel();
        try (Arena callArena = Arena.ofConfined()) {
            int n = 100;
            long bytes = (long) n * Double.BYTES;
            MemorySegment x  = callArena.allocate(bytes), y  = callArena.allocate(bytes), z  = callArena.allocate(bytes);
            MemorySegment vx = callArena.allocate(bytes), vy = callArena.allocate(bytes), vz = callArena.allocate(bytes);
            MemorySegment m  = callArena.allocate(bytes);

            // Initial Plummer state (seed 42 for determinism).
            k.initPlummer.invoke(x, y, z, vx, vy, vz, m, n, 42);

            double[] xa = read(x, n), ya = read(y, n), za = read(z, n);
            double[] vxa = read(vx, n), vya = read(vy, n), vza = read(vz, n);
            double[] ma = read(m, n);
            double eps = 0.05;
            double E0 = totalEnergy(xa, ya, za, vxa, vya, vza, ma, eps);

            int nSteps = 1000;
            double dt = 0.01;
            for (int s = 0; s < nSteps; s++) {
                k.step.invoke(x, y, z, vx, vy, vz, m, n, dt, eps);
            }

            xa = read(x, n); ya = read(y, n); za = read(z, n);
            vxa = read(vx, n); vya = read(vy, n); vza = read(vz, n);
            double E1 = totalEnergy(xa, ya, za, vxa, vya, vza, ma, eps);
            double drift = Math.abs((E1 - E0) / E0);

            double tol = 5e-3;  // 0.5% — generous; we typically see < 1e-4
            System.out.printf("Plummer energy: E0=%+.6f → E1=%+.6f, |ΔE/E|=%.4e (tol=%.0e)%n",
                    E0, E1, drift, tol);
            assertTrue(drift < tol,
                    () -> String.format("Energy drift %e exceeds tolerance %e", drift, tol));
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static double[] read(MemorySegment seg, int n) {
        double[] a = new double[n];
        MemorySegment.copy(seg, JAVA_DOUBLE, 0, a, 0, n);
        return a;
    }

    private static double totalEnergy(double[] x, double[] y, double[] z,
                                      double[] vx, double[] vy, double[] vz,
                                      double[] m, double eps) {
        int n = x.length;
        double K = 0;
        for (int i = 0; i < n; i++) {
            K += 0.5 * m[i] * (vx[i]*vx[i] + vy[i]*vy[i] + vz[i]*vz[i]);
        }
        double U = 0;
        double eps2 = eps * eps;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = x[i]-x[j], dy = y[i]-y[j], dz = z[i]-z[j];
                double r = Math.sqrt(dx*dx + dy*dy + dz*dz + eps2);
                U -= m[i] * m[j] / r;
            }
        }
        return K + U;
    }
}
