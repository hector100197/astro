package mx.astro.export.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to run a simulation in headless mode and persist the result to HDF5.
 * Domain layer.
 */
public record ExportJob(
        UUID id,
        Instant createdAt,
        JobStatus status,
        String hdf5Path,
        String manifestJson
) {}
