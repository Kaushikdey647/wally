package io.wally.internal.log;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ZeroAllocationTest {

    @Test
    void steadyStateAppendAndReadDoNotAllocate() {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        AllocationLoop loop = new AllocationLoop();
        loop.run(5_000_000);  // warm up until C2-compiled

        long before = threads.getCurrentThreadAllocatedBytes();
        loop.run(1_000_000);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertTrue(allocated < 1024, "allocated " + allocated + " bytes over 1M records");
    }

    /** Run by the {@code epsilonTest} task: 50M records x 64 B would exhaust a 128 MB heap at 1 byte/op. */
    @Test
    @Tag("epsilon")
    void survivesFiftyMillionRecordsUnderEpsilonGc() {
        new AllocationLoop().run(50_000_000);
    }
}
