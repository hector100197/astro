package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.Integrator;
import mx.astro.simulation.domain.Snapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.lang.foreign.Arena;

/**
 * Real leapfrog integrator backed by the Fortran kernel
 * (brute-force O(N²) acceleration with Plummer softening, OpenMP-parallelised).
 *
 * <p>Allocates a confined {@link Arena} per step, copies the snapshot in,
 * invokes {@code nbody_step}, reads positions and velocities back. The
 * per-call allocation is wasteful (~168 KB at N=3000 × 60 Hz ≈ 10 MB/s
 * churn) but correct; the V2 optimization will hold a per-stream Arena
 * across ticks once we refactor to a {@code KernelSession} API.
 */
@Component
@Profile("!mock")
public class FortranIntegrator implements Integrator {

    private final FortranKernelLoader loader;

    public FortranIntegrator(FortranKernelLoader loader) {
        this.loader = loader;
    }

    @Override
    public Snapshot step(Snapshot current, double dt, double softening) {
        final int n = current.n();
        try (Arena arena = Arena.ofConfined()) {
            NativeArrays na = NativeArrays.allocate(arena, n);
            na.writeAll(current.x(), current.y(), current.z(),
                    current.vx(), current.vy(), current.vz(),
                    current.mass());

            try {
                loader.stepHandle().invoke(
                        na.x(), na.y(), na.z(),
                        na.vx(), na.vy(), na.vz(),
                        na.m(),
                        n,
                        dt,
                        softening
                );
            } catch (Throwable t) {
                throw new IllegalStateException("Native nbody_step failed", t);
            }

            return new Snapshot(
                    current.stepIndex() + 1,
                    current.time() + dt,
                    na.readSegment(na.x()),
                    na.readSegment(na.y()),
                    na.readSegment(na.z()),
                    na.readSegment(na.vx()),
                    na.readSegment(na.vy()),
                    na.readSegment(na.vz()),
                    current.mass()
            );
        }
    }

    @Override
    public String name() {
        return "fortran_leapfrog_brute_force_o2";
    }
}
