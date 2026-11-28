package io.wally.stress;

import io.wally.spsc.Reader;
import io.wally.spsc.RingBuffer;
import io.wally.spsc.RingBufferConfig;
import io.wally.spsc.Writer;
import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.JJJ_Result;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

/**
 * Same publication check across a wrap: the ring is pre-advanced to index 48 of 64, so the claim
 * writes a sentinel, publishes the skip, then places the frame at index 0 over a stale frame
 * (length 8, payload 7) left from the previous lap.
 */
@JCStressTest
@Outcome(id = "0, 0, 0", expect = ACCEPTABLE, desc = "Nothing or only the wrap skip visible")
@Outcome(id = "16, 42, 42", expect = ACCEPTABLE, desc = "Complete message after wrap")
@Outcome(expect = FORBIDDEN, desc = "Torn, stale or skipped message")
@State
public class SpscWrapPublishTest {

    private final Writer writer;
    private final Reader reader;

    public SpscWrapPublishTest() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(64));
        writer = ring.writer();
        reader = ring.reader();
        for (int i = 0; i < 3; i++) {
            writer.tryClaim(8);
            writer.buffer().set(JAVA_LONG, writer.offset(), 7L);
            writer.commit();
        }
        reader.read((buf, off, len, pos) -> { }, 3);
    }

    @Actor
    public void producer() {
        if (writer.tryClaim(16)) {
            writer.buffer().set(JAVA_LONG, writer.offset(), 42L);
            writer.buffer().set(JAVA_LONG, writer.offset() + 8, 42L);
            writer.commit();
        }
    }

    @Actor
    public void consumer(JJJ_Result r) {
        reader.read((buf, off, len, pos) -> {
            r.r1 = len;
            r.r2 = buf.get(JAVA_LONG, off);
            r.r3 = buf.get(JAVA_LONG, off + 8);
        }, 1);
    }
}
