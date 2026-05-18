package mx.astro.simulation.infrastructure.out;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * FFM (Foreign Function &amp; Memory) loader for the Fortran kernel.
 *
 * <p>Loaded once at application startup; subsequent calls reuse the cached
 * {@link MethodHandle}s. The native library is found via the configured
 * {@code astro.kernel.library-path} (default {@code ../../kernel/build/libnbody.dylib}
 * on macOS, {@code .so} on Linux).
 *
 * <p>Activated only outside the {@code mock} Spring profile.
 *
 * <h3>C signatures (matching Fortran {@code bind(C, name=…)} declarations)</h3>
 * <pre>
 *   void nbody_init_plummer(double *x, double *y, double *z,
 *                           double *vx, double *vy, double *vz,
 *                           double *m, int n, int seed);
 *
 *   void nbody_step(double *x, double *y, double *z,
 *                   double *vx, double *vy, double *vz,
 *                   const double *m, int n, double dt, double eps);
 * </pre>
 *
 * <p>Fortran's default argument-passing convention is by reference; only
 * {@code n}, {@code dt}, {@code eps} and {@code seed} are declared
 * {@code value}. So array params come through as ADDRESS (pointer) and
 * scalars as their JAVA_* primitive layouts.
 */
@Component
@Profile("!mock")
public class FortranKernelLoader {

    private static final Logger log = LoggerFactory.getLogger(FortranKernelLoader.class);

    /**
     * Process-wide arena holding the loaded library. Lifetime = JVM lifetime;
     * we never unload mid-run.
     */
    private final Arena libArena = Arena.ofShared();

    private final MethodHandle stepHandle;
    private final MethodHandle initPlummerHandle;
    private final MethodHandle writeSnapshotH5Handle;
    private final MethodHandle openRunHandle;
    private final MethodHandle appendSnapshotHandle;
    private final MethodHandle closeRunHandle;
    private final String libraryPath;

    public FortranKernelLoader(@Value("${astro.kernel.library-path}") String libraryPath) {
        this.libraryPath = resolve(libraryPath);
        log.info("Loading Fortran kernel from {}", this.libraryPath);

        if (!Files.exists(Paths.get(this.libraryPath))) {
            throw new IllegalStateException(
                    "Kernel library not found at " + this.libraryPath +
                    " — build it first with: make -C kernel"
            );
        }

        SymbolLookup lookup = SymbolLookup.libraryLookup(Paths.get(this.libraryPath), libArena);
        Linker linker = Linker.nativeLinker();

        // void nbody_init_plummer(x, y, z, vx, vy, vz, m, n, seed)
        this.initPlummerHandle = linker.downcallHandle(
                lookup.find("nbody_init_plummer")
                      .orElseThrow(() -> new IllegalStateException("Symbol nbody_init_plummer not found")),
                FunctionDescriptor.ofVoid(
                        ADDRESS, ADDRESS, ADDRESS,    // x, y, z
                        ADDRESS, ADDRESS, ADDRESS,    // vx, vy, vz
                        ADDRESS,                      // m
                        JAVA_INT,                     // n
                        JAVA_INT                      // seed
                )
        );

        // void nbody_step(x, y, z, vx, vy, vz, m, n, dt, eps)
        this.stepHandle = linker.downcallHandle(
                lookup.find("nbody_step")
                      .orElseThrow(() -> new IllegalStateException("Symbol nbody_step not found")),
                FunctionDescriptor.ofVoid(
                        ADDRESS, ADDRESS, ADDRESS,    // x, y, z
                        ADDRESS, ADDRESS, ADDRESS,    // vx, vy, vz
                        ADDRESS,                      // m
                        JAVA_INT,                     // n
                        JAVA_DOUBLE, JAVA_DOUBLE      // dt, eps
                )
        );

        // int nbody_write_snapshot_h5(const char* path, x,y,z, vx,vy,vz, m, n, sim_time)
        this.writeSnapshotH5Handle = linker.downcallHandle(
                lookup.find("nbody_write_snapshot_h5")
                      .orElseThrow(() -> new IllegalStateException("Symbol nbody_write_snapshot_h5 not found")),
                FunctionDescriptor.of(JAVA_INT,           // returns int status
                        ADDRESS,                          // path (NUL-terminated)
                        ADDRESS, ADDRESS, ADDRESS,        // x, y, z
                        ADDRESS, ADDRESS, ADDRESS,        // vx, vy, vz
                        ADDRESS,                          // m
                        JAVA_INT,                         // n
                        JAVA_DOUBLE                       // sim_time
                )
        );

        // int nbody_open_run(const char* path, int n)
        this.openRunHandle = linker.downcallHandle(
                lookup.find("nbody_open_run")
                      .orElseThrow(() -> new IllegalStateException("Symbol nbody_open_run not found")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)
        );

        // int nbody_append_snapshot(int idx, x,y,z, vx,vy,vz, m, int n, double sim_time)
        this.appendSnapshotHandle = linker.downcallHandle(
                lookup.find("nbody_append_snapshot")
                      .orElseThrow(() -> new IllegalStateException("Symbol nbody_append_snapshot not found")),
                FunctionDescriptor.of(JAVA_INT,
                        JAVA_INT,                         // snapshot_idx
                        ADDRESS, ADDRESS, ADDRESS,        // x, y, z
                        ADDRESS, ADDRESS, ADDRESS,        // vx, vy, vz
                        ADDRESS,                          // m
                        JAVA_INT,                         // n
                        JAVA_DOUBLE                       // sim_time
                )
        );

        // int nbody_close_run()
        this.closeRunHandle = linker.downcallHandle(
                lookup.find("nbody_close_run")
                      .orElseThrow(() -> new IllegalStateException("Symbol nbody_close_run not found")),
                FunctionDescriptor.of(JAVA_INT)
        );

        log.info("Fortran kernel loaded — 6 handles ready (init/step/write_h5 + open_run/append/close_run)");
    }

    public MethodHandle stepHandle()             { return stepHandle; }
    public MethodHandle initPlummerHandle()      { return initPlummerHandle; }
    public MethodHandle writeSnapshotH5Handle()  { return writeSnapshotH5Handle; }
    public MethodHandle openRunHandle()          { return openRunHandle; }
    public MethodHandle appendSnapshotHandle()   { return appendSnapshotHandle; }
    public MethodHandle closeRunHandle()         { return closeRunHandle; }
    public String libraryPath()                  { return libraryPath; }

    /**
     * Resolves a configured library path relative to the working directory
     * (Maven runs services in their module dir, so {@code ../../kernel/build/...}
     * resolves correctly).
     */
    private static String resolve(String configured) {
        Path p = Paths.get(configured);
        if (p.isAbsolute()) return p.toString();
        return p.toAbsolutePath().normalize().toString();
    }
}
