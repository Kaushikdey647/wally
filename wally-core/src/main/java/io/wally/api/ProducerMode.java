package io.wally.api;

/**
 * Specifies the concurrency mode for log producers.
 *
 * <p>Determines how Wally coordinates writes when multiple threads append records.
 *
 * <h2>Single Producer (SINGLE)</h2>
 * <p>Optimized for the case where only one thread appends to the log at a time.
 * <ul>
 *   <li><b>Performance:</b> Fastest - no atomic operations, plain volatile stores</li>
 *   <li><b>Constraint:</b> Only one {@link Appender} may be active at a time</li>
 *   <li><b>Use case:</b> Single writer thread, event sourcing with serialized commands</li>
 * </ul>
 *
 * <h2>Multi Producer (MULTI)</h2>
 * <p>Lock-free coordination for multiple concurrent producers.
 * <ul>
 *   <li><b>Performance:</b> Lock-free via atomic fetch-and-add on shared tail counter</li>
 *   <li><b>Constraint:</b> Each producer thread must have its own {@link Appender} instance</li>
 *   <li><b>Use case:</b> Multi-threaded applications, MPSC queues</li>
 * </ul>
 *
 * <h2>Choosing a Mode</h2>
 * <pre>
 * // Single producer (one writer thread)
 * Wally wal = Wally.builder()
 *     .directory(path)
 *     .producerMode(ProducerMode.SINGLE)
 *     .open();
 * Appender appender = wal.appender();  // Only one appender
 *
 * // Multi-producer (N writer threads)
 * Wally wal = Wally.builder()
 *     .directory(path)
 *     .producerMode(ProducerMode.MULTI)
 *     .open();
 *
 * // Thread 1
 * Appender appender1 = wal.appender();
 * appender1.append(...);
 *
 * // Thread 2
 * Appender appender2 = wal.appender();
 * appender2.append(...);
 * </pre>
 *
 * <h2>Implementation Notes</h2>
 * <ul>
 *   <li><b>SINGLE:</b> Tail updates use {@code VarHandle.setRelease} (no contention)</li>
 *   <li><b>MULTI:</b> Tail claims use {@code VarHandle.getAndAdd} (atomic fetch-add)</li>
 *   <li><b>Segment rollover:</b> Both modes coordinate via padding frames</li>
 *   <li><b>Commit protocol:</b> Same for both (release fence on length field)</li>
 * </ul>
 *
 * @see Wally.Builder#producerMode(ProducerMode)
 */
public enum ProducerMode {

    /**
     * Single producer mode: optimized for one writer thread.
     *
     * <p><b>Constraint:</b> Only one thread may append records. Multiple {@link Appender}
     * instances can exist (e.g., for testing), but they must not be used concurrently.
     *
     * <p><b>Performance:</b> Tail updates use plain volatile stores, no atomic operations.
     */
    SINGLE,

    /**
     * Multi-producer mode: lock-free coordination for multiple writers.
     *
     * <p><b>Constraint:</b> Each producer thread must have its own {@link Appender} instance.
     * Sharing an {@link Appender} across threads is not thread-safe.
     *
     * <p><b>Performance:</b> Tail claims use atomic fetch-and-add. Scales with producer count
     * until hardware contention limits are reached (typically 8-16 cores).
     *
     * <p><b>Coordination:</b> When a claim crosses a segment boundary, the claimant writes
     * padding and triggers rollover. Other claimants retry their claim in the new segment.
     */
    MULTI;

    /**
     * Returns whether this mode supports multiple concurrent producers.
     *
     * @return {@code true} for MULTI, {@code false} for SINGLE
     */
    public boolean isMultiProducer() {
        return this == MULTI;
    }
}
