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

/** If the reader sees a non-zero length, it must see the whole payload written before commit. */
@JCStressTest
@Outcome(id = "0, 0, 0", expect = ACCEPTABLE, desc = "Message not yet visible")
@Outcome(id = "16, 42, 42", expect = ACCEPTABLE, desc = "Complete message")
@Outcome(expect = FORBIDDEN, desc = "Torn or stale message")
@State
public class SpscPublishTest {

    private final Writer writer;
    private final Reader reader;

    public SpscPublishTest() {
        RingBuffer ring = new RingBuffer(new RingBufferConfig().capacity(64));
        writer = ring.writer();
        reader = ring.reader();
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
