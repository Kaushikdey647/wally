package io.wally.internal.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A counter with 128-byte padding to prevent false sharing.
 *
 * <p>When multiple threads access separate counters on the same cache line (typically 64 bytes),
 * writes to one counter invalidate the cache line for other threads, causing performance degradation
 * called "false sharing". Padding ensures each counter occupies its own cache line.
 *
 * <p><b>Memory Layout:</b>
 * <pre>
 * | 56 bytes padding | 8 bytes value | 64 bytes padding | = 128 bytes total
 * </pre>
 *
 * <p><b>Thread Safety:</b> All operations use VarHandle with appropriate memory ordering semantics.
 *
 * <p><b>Usage:</b>
 * <ul>
 *   <li>{@link #getAndAdd(long)} - Atomic fetch-and-add (for MPSC tail claim)</li>
 *   <li>{@link #get()} - Volatile read</li>
 *   <li>{@link #set(long)} - Volatile write</li>
 *   <li>{@link #lazySet(long)} - Lazy write (no memory barrier)</li>
 * </ul>
 *
 * @see <a href="https://mechanical-sympathy.blogspot.com/2011/07/false-sharing.html">False Sharing</a>
 */
public class PaddedCounter {

    // Pre-padding: 7 longs = 56 bytes
    // Ensures the value field doesn't share a cache line with preceding fields
    private long p1, p2, p3, p4, p5, p6, p7;

    /**
     * The actual counter value.
     * Accessed via VarHandle for proper memory ordering.
     */
    private volatile long value;

    // Post-padding: 8 longs = 64 bytes
    // Ensures the value field doesn't share a cache line with following fields
    private long p8, p9, p10, p11, p12, p13, p14, p15;

    // VarHandle for atomic operations
    private static final VarHandle VALUE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            VALUE = lookup.findVarHandle(PaddedCounter.class, "value", long.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Creates a counter initialized to zero.
     */
    public PaddedCounter() {
        this(0L);
    }

    /**
     * Creates a counter initialized to the given value.
     *
     * @param initialValue the initial value
     */
    public PaddedCounter(long initialValue) {
        this.value = initialValue;
    }

    /**
     * Atomically adds the given delta and returns the previous value.
     *
     * <p>This is the core operation for lock-free MPSC: multiple producers call
     * {@code getAndAdd(recordLength)} to claim space in the log without blocking.
     *
     * <p><b>Memory Ordering:</b> This operation has read-modify-write semantics with
     * volatile memory ordering, ensuring visibility across threads.
     *
     * @param delta the value to add
     * @return the previous value before the addition
     */
    public long getAndAdd(long delta) {
        return (long) VALUE.getAndAdd(this, delta);
    }

    /**
     * Atomically increments the counter and returns the previous value.
     *
     * @return the previous value before the increment
     */
    public long getAndIncrement() {
        return getAndAdd(1L);
    }

    /**
     * Gets the current value with volatile semantics (acquire fence).
     *
     * <p>Ensures that any writes to this counter by other threads are visible to the caller.
     *
     * @return the current value
     */
    public long get() {
        return (long) VALUE.getVolatile(this);
    }

    /**
     * Sets the value with volatile semantics (release fence).
     *
     * <p>Ensures that all prior writes by this thread are visible to other threads
     * before they observe this new value.
     *
     * @param newValue the new value
     */
    public void set(long newValue) {
        VALUE.setVolatile(this, newValue);
    }

    /**
     * Sets the value with lazy semantics (no memory barrier).
     *
     * <p><b>Warning:</b> This does not guarantee immediate visibility to other threads.
     * Use only when you know other synchronization will enforce visibility, or when
     * eventual visibility is acceptable.
     *
     * <p><b>Use case:</b> Single-producer mode where only one thread writes the tail.
     *
     * @param newValue the new value
     */
    public void lazySet(long newValue) {
        VALUE.setRelease(this, newValue);
    }

    /**
     * Compares and sets the value atomically.
     *
     * <p>Sets the value to {@code newValue} if the current value equals {@code expectedValue}.
     *
     * @param expectedValue the expected current value
     * @param newValue the new value to set
     * @return {@code true} if successful, {@code false} if the current value was not equal to expected
     */
    public boolean compareAndSet(long expectedValue, long newValue) {
        return VALUE.compareAndSet(this, expectedValue, newValue);
    }

    @Override
    public String toString() {
        return Long.toString(get());
    }

    /**
     * Prevents the JVM from optimizing away the padding fields.
     *
     * <p>Called from tests to ensure padding is present in the compiled class.
     *
     * @return sum of all padding fields (should be ignored by caller)
     */
    long preventOptimization() {
        return p1 + p2 + p3 + p4 + p5 + p6 + p7 +
               p8 + p9 + p10 + p11 + p12 + p13 + p14 + p15;
    }
}
