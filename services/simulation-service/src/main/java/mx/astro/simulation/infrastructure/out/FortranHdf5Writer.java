package mx.astro.simulation.infrastructure.out;

import mx.astro.simulation.domain.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;

/**
 * Writes a {@link Snapshot} to disk as an HDF5 file in GADGET-like format
 * via the Fortran kernel's {@code nbody_write_snapshot_h5} routine.
 *
 * <p>Active outside the {@code mock} profile (mock has no Fortran kernel,
 * so this bean would have nothing to call).
 */
@Component
@Profile("!mock")
public class FortranHdf5Writer {

    private static final Logger log = LoggerFactory.getLogger(FortranHdf5Writer.class);

    private final FortranKernelLoader loader;
    private final Path outputDir;

    public FortranHdf5Writer(
            FortranKernelLoader loader,
            @Value("${astro.output.hdf5-dir:../../data}") String outputDir
    ) {
        this.loader = loader;
        this.outputDir = Paths.get(outputDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.outputDir);
        } catch (IOException e) {
            log.warn("Could not create HDF5 output dir {}: {}", this.outputDir, e.getMessage());
        }
    }

    /**
     * Writes the given snapshot to {@code <outputDir>/<runId>.h5}, returning
     * the absolute path on success or {@code null} on failure.
     */
    public Path writeFinal(UUID runId, Snapshot snapshot) {
        Path target = outputDir.resolve(runId + ".h5");
        return write(target, snapshot) ? target : null;
    }

    /** Lower-level write to an arbitrary path. Returns true on success. */
    public boolean write(Path target, Snapshot snapshot) {
        final int n = snapshot.n();
        try (Arena arena = Arena.ofConfined()) {
            // Allocate native arrays + copy
            NativeArrays na = NativeArrays.allocate(arena, n);
            na.writeAll(snapshot.x(), snapshot.y(), snapshot.z(),
                    snapshot.vx(), snapshot.vy(), snapshot.vz(),
                    snapshot.mass());

            // NUL-terminated UTF-8 path
            byte[] pathBytes = (target.toString() + "\0").getBytes(StandardCharsets.UTF_8);
            MemorySegment cPath = arena.allocate(pathBytes.length);
            MemorySegment.copy(pathBytes, 0, cPath, JAVA_BYTE, 0, pathBytes.length);

            int status;
            try {
                status = (int) loader.writeSnapshotH5Handle().invoke(
                        cPath,
                        na.x(), na.y(), na.z(),
                        na.vx(), na.vy(), na.vz(),
                        na.m(),
                        n,
                        snapshot.time()
                );
            } catch (Throwable t) {
                log.error("Native nbody_write_snapshot_h5 failed for {}", target, t);
                return false;
            }

            if (status != 0) {
                log.warn("HDF5 write returned status {} for {}", status, target);
                return false;
            }
            log.info("Wrote HDF5 snapshot to {}", target);
            return true;
        }
    }
}
