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
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Multi-snapshot HDF5 writer — opens a file, appends many snapshots, closes.
 *
 * <p>Uses the kernel's {@code nbody_open_run / nbody_append_snapshot /
 * nbody_close_run} trio. Only ONE run may be open per JVM at a time
 * (the kernel keeps file_id in module-level state); a {@link ReentrantLock}
 * here enforces that constraint at the Java boundary.
 *
 * <p>Active outside the {@code mock} profile.
 */
@Component
@Profile("!mock")
public class FortranHdf5RunWriter {

    private static final Logger log = LoggerFactory.getLogger(FortranHdf5RunWriter.class);

    private final FortranKernelLoader loader;
    private final Path outputDir;
    private final ReentrantLock kernelLock = new ReentrantLock();

    public FortranHdf5RunWriter(
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
     * Open a multi-snapshot HDF5 file for the given run id. Returns a
     * {@link RunHandle} you must {@link RunHandle#close} when done — the
     * underlying kernel lock is held until then.
     */
    public RunHandle openRun(UUID runId, int n) {
        Path target = outputDir.resolve(runId + "-multi.h5");
        kernelLock.lock();
        try (Arena arena = Arena.ofConfined()) {
            byte[] pathBytes = (target.toString() + "\0").getBytes(StandardCharsets.UTF_8);
            MemorySegment cPath = arena.allocate(pathBytes.length);
            MemorySegment.copy(pathBytes, 0, cPath, JAVA_BYTE, 0, pathBytes.length);

            int status = (int) loader.openRunHandle().invoke(cPath, n);
            if (status != 0) {
                kernelLock.unlock();
                throw new IOException("nbody_open_run returned status " + status);
            }
            return new RunHandle(target, n);
        } catch (Throwable t) {
            if (kernelLock.isHeldByCurrentThread()) kernelLock.unlock();
            throw new RuntimeException("openRun failed for " + target, t);
        }
    }

    /** Handle to an open multi-snapshot HDF5 run. AutoCloseable. */
    public class RunHandle implements AutoCloseable {
        private final Path path;
        private final int n;
        private boolean closed = false;

        private RunHandle(Path path, int n) {
            this.path = path;
            this.n = n;
        }

        public Path path() { return path; }

        /** Append a snapshot at the given step index. Caller serialises calls. */
        public void appendSnapshot(int snapshotIdx, Snapshot snapshot) {
            if (closed) throw new IllegalStateException("RunHandle is closed");
            if (snapshot.n() != n) {
                throw new IllegalArgumentException(
                        "Snapshot N=" + snapshot.n() + " does not match run N=" + n);
            }
            try (Arena arena = Arena.ofConfined()) {
                NativeArrays na = NativeArrays.allocate(arena, n);
                na.writeAll(snapshot.x(), snapshot.y(), snapshot.z(),
                        snapshot.vx(), snapshot.vy(), snapshot.vz(),
                        snapshot.mass());

                int status = (int) loader.appendSnapshotHandle().invoke(
                        snapshotIdx,
                        na.x(), na.y(), na.z(),
                        na.vx(), na.vy(), na.vz(),
                        na.m(),
                        n,
                        snapshot.time()
                );
                if (status != 0) {
                    throw new IOException("nbody_append_snapshot returned status " + status);
                }
            } catch (Throwable t) {
                throw new RuntimeException(
                        "appendSnapshot failed at step " + snapshotIdx, t);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            try {
                int status = (int) loader.closeRunHandle().invoke();
                if (status != 0) {
                    log.warn("nbody_close_run returned status {} for {}", status, path);
                }
            } catch (Throwable t) {
                log.warn("close_run failed for {}: {}", path, t.getMessage());
            } finally {
                closed = true;
                if (kernelLock.isHeldByCurrentThread()) kernelLock.unlock();
            }
        }
    }
}
