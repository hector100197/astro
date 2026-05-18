package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.InitialCondition;
import mx.astro.simulation.domain.Snapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.lang.foreign.Arena;

/**
 * Real Plummer initial condition via the Fortran kernel.
 *
 * <p>Allocates native arrays in a confined arena, calls
 * {@code nbody_init_plummer}, and reads positions / velocities / masses
 * back into a Java {@link Snapshot}. Arena is closed when the
 * try-with-resources block exits.
 *
 * <p>Active outside the {@code mock} profile.
 */
@Component
@Profile("!mock")
public class FortranInitialCondition implements InitialCondition {

    private final FortranKernelLoader loader;

    public FortranInitialCondition(FortranKernelLoader loader) {
        this.loader = loader;
    }

    @Override
    public Snapshot generate(int n, long seed) {
        try (Arena arena = Arena.ofConfined()) {
            NativeArrays na = NativeArrays.allocate(arena, n);

            try {
                loader.initPlummerHandle().invoke(
                        na.x(), na.y(), na.z(),
                        na.vx(), na.vy(), na.vz(),
                        na.m(),
                        n,
                        (int) seed
                );
            } catch (Throwable t) {
                throw new IllegalStateException("Native nbody_init_plummer failed", t);
            }

            return new Snapshot(
                    0L,
                    0.0,
                    na.readSegment(na.x()),
                    na.readSegment(na.y()),
                    na.readSegment(na.z()),
                    na.readSegment(na.vx()),
                    na.readSegment(na.vy()),
                    na.readSegment(na.vz()),
                    na.readSegment(na.m())
            );
        }
    }

    @Override
    public String name() {
        return "fortran_plummer";
    }
}
