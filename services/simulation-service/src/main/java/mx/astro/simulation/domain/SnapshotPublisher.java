package mx.astro.simulation.domain;

/**
 * Domain port: somewhere a snapshot can be sent. Implementations include
 * a WebSocket driving adapter, an HDF5 writer, an in-memory ring buffer
 * for replay, etc.
 *
 * <p>Pure interface — no infrastructure imports.
 */
public interface SnapshotPublisher {

    /**
     * Publishes a snapshot. Must be safe to call from the kernel thread.
     * Implementations should not block; backpressure is the implementation's
     * responsibility (drop-oldest, throttle, etc.).
     */
    void publish(Snapshot snapshot);
}
