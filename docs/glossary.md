# Glossary

Each entry has two parts:

- **In general**: what the word means in everyday software engineering.
- **In Wally**: what it means in this project specifically.

Terms are in alphabetical order. Words in *italics* inside an entry have their own entry.

---

## A

### Acquire / release
**In general:** Rules about the order in which one *thread* sees another thread's writes to memory. Modern CPUs and compilers reorder instructions to go faster, so without rules thread B might see thread A's second write before its first. A **release** write means "everything I wrote before this is visible to anyone who sees this write." An **acquire** read means "once I've seen this value, I'll also see everything written before it."

**In Wally:** The producer fills in a record, then writes its length with a release write. The reader reads the length with an acquire read. If the reader sees a non-zero length, it's guaranteed to see the complete record. See `Mem.setIntRelease` and `Mem.getIntAcquire`.

### Alignment
**In general:** Placing data at memory addresses that are multiples of some number (2, 4, 8...). CPUs read aligned data faster, and some atomic instructions only work on aligned data.

**In Wally:** Every record starts at a multiple of 8 bytes. If a record is 19 bytes, the next one starts 24 bytes later. The extra bytes are wasted on purpose.

### Allocation
**In general:** Asking for memory to hold something. In Java, `new Something()` allocates an object on the *heap*. Allocation itself is cheap, but every allocated object eventually has to be cleaned up by *garbage collection*.

**In Wally:** The goal is no allocation per event. See *zero-allocation*.

### API
**In general:** Application Programming Interface: the set of classes and methods a library offers to the people who use it. It's the library's "front door".

**In Wally:** Everything in the `io.wally.api` package, such as `Claim` and `RecordHandler`. Everything else is internal and can change without notice.

### Appender
**In general:** Something that adds items to the end of a sequence.

**In Wally:** The object a *producer* uses to write records. Each thread gets its own appender, which reuses one *claim* object for every record.

### Append-only
**In general:** Data that is only ever added at the end, never changed or deleted in the middle. Simple, fast, and easy to recover after a crash.

**In Wally:** The log is append-only. Old data is removed only by deleting whole *segments* (see *retention*).

### Arena
**In general:** A memory-management pattern where many allocations share one lifetime and are freed together.

**In Wally:** Java's `java.lang.foreign.Arena`. Off-heap memory and mapped files are tied to an arena, and closing the arena frees or unmaps them all at once, at a moment Wally chooses.

### Atomic operation
**In general:** An operation that happens all at once from the point of view of other threads: nobody can see it half-done. Examples: "add 5 to this number and give me the old value" (fetch-and-add), or "set this to X only if it's still Y" (*CAS*).

**In Wally:** Producers use one atomic fetch-and-add on the log's tail to reserve space, so two producers never get the same slot. See *lock-free*.

---

## B

### Backpressure
**In general:** When a system slows down or refuses new work because the next stage can't keep up, instead of running out of memory or crashing.

**In Wally:** In queue mode, if the slowest consumer falls too far behind, appending returns a `BACK_PRESSURED` code instead of growing the log forever.

### Benchmark
**In general:** A program that measures how fast something is, in a repeatable way.

**In Wally:** The `wally-bench` module uses *JMH* to measure how many nanoseconds an append and read take and how many bytes they allocate. See [Benchmarks](benchmarks.md).

### Byte / byte order (endianness)
**In general:** A byte is 8 bits, the basic unit of memory. A number bigger than one byte (an `int` is 4 bytes) can be stored lowest byte first (**little-endian**) or highest byte first (**big-endian**).

**In Wally:** All numbers in Wally's files are little-endian, which matches the CPUs Wally supports (x86-64 and ARM64).

---

## C

### Cache line
**In general:** CPUs don't read memory one byte at a time. They read it in chunks called cache lines (usually 64 bytes) and keep copies in fast caches close to each core.

**In Wally:** Counters written by different threads are kept far apart in memory so they don't share a cache line. See *false sharing*.

### CAS (compare-and-swap)
**In general:** An *atomic operation*: "if the value is still X, change it to Y, and tell me whether it worked." The building block of most lock-free code. If another thread changed it first, you retry.

**In Wally:** Used where retrying is fine (for example, updating cursors). For the hot path of claiming space, Wally uses fetch-and-add, which never needs a retry. See [ADR 0004](adr/0004-fetch-add-tail.md).

### Checkpoint
**In general:** A point in a log that says "everything before here has been safely processed elsewhere."

**In Wally:** In WAL mode, the application calls `checkpoint(position)` to tell Wally that segments before that position may be deleted.

### Checksum (CRC32C)
**In general:** A small number calculated from a block of data. If even one bit of the data changes, the checksum almost certainly changes too, so you can detect corruption. CRC32C is a common, fast checksum.

**In Wally:** Each record can store a CRC32C of its payload. After a crash, recovery uses it to detect records that were only half written to disk.

### Claim
**In general:** Reserving something before using it.

**In Wally:** Reserving space in the log for a record, then writing the data directly into that space (*zero-copy*), then *committing*. The `Claim` interface represents this reserved space.

### Commit
**In general:** Making a change final and visible.

**In Wally:** Writing a record's length with a release write (see *acquire / release*). Until then, readers treat the record as not existing yet.

### Concurrency
**In general:** Several things in progress at the same time, usually on different *threads*. Hard to get right because threads can interfere with each other in surprising ways.

**In Wally:** Many producers can append at once, and readers read while producers write, all without *locks*.

### Consumer
**In general:** The part of a system that receives and processes messages.

**In Wally:** Code that reads records from the log through a *tailer*.

### Consumer group
**In general:** A named reader that remembers where it got to, so it can continue after a restart. (Kafka popularized the term.)

**In Wally:** A *tailer* with a name, like `"payments"`. Its *cursor* is saved to disk.

### Crash recovery
**In general:** What a system does when it starts after an unexpected stop, to get back to a correct state.

**In Wally:** On startup, Wally reads the log from the last *checkpoint*, checks each record's *checksum*, and cuts the log off at the first record that is incomplete or corrupt.

### Cursor
**In general:** A marker that remembers a position, like a bookmark.

**In Wally:** A *position* in the log that a *consumer group* has read up to.

---

## D

### Durability
**In general:** The promise that once the system says "saved", the data survives a crash or power failure.

**In Wally:** Controlled by the *flush policy*. See [Guarantees](guarantees.md).

---

## E

### Epsilon GC
**In general:** A special Java *garbage collector* that never collects anything. When memory runs out, the program crashes. It's used for testing and for very short-lived programs.

**In Wally:** Used as a test: Wally appends and reads 50 million records with Epsilon GC and a small *heap*. If Wally allocated even one byte per record, the program would run out of memory and fail.

### Event
**In general:** A record that something happened, like "order #123 placed".

**In Wally:** Used interchangeably with *record*: a chunk of bytes appended to the log.

### Event queue
**In general:** A *queue* of events, where producers add events and consumers process them.

**In Wally:** One of Wally's two modes: the log plus named *cursors* plus *retention*.

---

## F

### False sharing
**In general:** A performance problem where two threads write to *different* variables that happen to sit on the same *cache line*. The CPU cores keep stealing the line from each other, so both slow down as if they were sharing the same variable.

**In Wally:** Hot counters, such as the log's tail, are surrounded by 128 bytes of empty padding to avoid this.

### FFM API (Foreign Function & Memory API)
**In general:** A modern Java API (final since Java 22) for working with memory outside the *heap* and calling native code, safely and quickly.

**In Wally:** The foundation of Wally: `MemorySegment`, `Arena`, `ValueLayout` and `VarHandle` all come from it. It replaces the old, unsupported `sun.misc.Unsafe`. See [ADR 0001](adr/0001-ffm-over-unsafe.md).

### Flush / fsync
**In general:** Forcing data that the operating system is holding in memory (the *page cache*) to be written to the physical disk.

**In Wally:** Done with `MemorySegment.force()`. When and how often depends on the *flush policy*.

### Flush policy
**In general:** A rule for how often to *flush*. Flushing more often is safer but slower.

**In Wally:** `none()` (let the OS decide), `periodic(5ms)` (every few milliseconds, many records at once) or `sync()` (before every append returns).

### Flyweight
**In general:** A design pattern where one small object is reused to look at many pieces of data, instead of creating one object per piece.

**In Wally:** The `Claim` object is reused for every record an appender writes, so no object is created per record.

### Frame / record
**In general:** A chunk of data with a small header describing it (length, type...).

**In Wally:** A 16-byte header plus the payload. The header holds the frame length, the type (`DATA` or `PADDING`), flags and a *checksum*. See [File format](format.md).

---

## G

### Garbage collection (GC)
**In general:** Java's automatic memory cleanup. You create objects; when nothing refers to them anymore, the garbage collector frees their memory. It's convenient, but the collector sometimes has to pause your program, and the more objects you create, the more work it has.

**In Wally:** The thing Wally is designed to avoid on its fast path. It keeps data *off-heap* and creates no objects per record.

### Gradle
**In general:** A build tool for Java. It downloads libraries, compiles code, runs tests and packages the result.

**In Wally:** Configured by the `*.gradle.kts` files. Run `./gradlew check` to build and test everything.

---

## H

### Heap
**In general:** The memory area where Java stores objects created with `new`. It's managed by the *garbage collector*.

**In Wally:** Wally keeps record data *off-heap* so the garbage collector never has to look at it.

### Hot path
**In general:** The code that runs most often, like for every single message. This is where performance matters most.

**In Wally:** Appending, claiming, committing and reading records.

---

## J

### JIT compiler
**In general:** Java first runs your code slowly, interpreted, then the Just-In-Time compiler turns frequently used code into fast machine code while the program runs.

**In Wally:** Tests and benchmarks "warm up" first, so they measure the fast compiled code rather than the slow start.

### JMH
**In general:** Java Microbenchmark Harness, the standard tool for measuring small pieces of Java code accurately. It handles warm-up, repetition and statistics for you.

**In Wally:** Used in `wally-bench`. Its `gc` profiler shows how many bytes each operation allocates.

### JPMS (Java modules)
**In general:** The Java Platform Module System. A `module-info.java` file declares which packages other code may use.

**In Wally:** `wally-core` exports only `io.wally.api`, so users can't accidentally depend on internal code.

### JVM
**In general:** The Java Virtual Machine, the program that runs compiled Java code.

**In Wally:** Wally needs a JVM for Java 25 or newer.

---

## L

### Latency
**In general:** How long one operation takes, such as from "send" to "received". Often reported as percentiles: **p99** = 99% of operations were faster than this. The rare slow ones are called tail latency.

**In Wally:** Measured in nanoseconds. Avoiding GC pauses mainly improves tail latency.

### Lock
**In general:** A mechanism that lets only one thread into a piece of code at a time. Safe and simple, but other threads must wait (contention), and a thread holding a lock can be paused by the OS while everyone waits for it.

**In Wally:** Not used on the hot path.

### Lock-free
**In general:** Code where threads coordinate with *atomic operations* instead of locks, so a paused thread never stops the others from making progress.

**In Wally:** Appending and reading are lock-free. Flushing to disk is not, since the operating system itself may block.

---

## M

### Memory-mapped file
**In general:** The operating system makes a file appear as a block of memory. Reading or writing that memory reads or writes the file, and the OS moves data between memory and disk in the background.

**In Wally:** Every *segment* file is memory-mapped. Appending a record is just writing to memory, with no per-record system call.

### MemorySegment
**In general:** Java's (FFM API) safe handle to a block of memory, either on-heap, off-heap or a memory-mapped file. It knows its size, so out-of-bounds access throws an error instead of corrupting memory.

**In Wally:** All record data is read and written through `MemorySegment`s.

---

## O

### Off-heap
**In general:** Memory outside the Java *heap*, which the garbage collector doesn't manage. You decide when it's freed.

**In Wally:** Where all records live.

---

## P

### Padding
**In general:** Empty space added on purpose, for *alignment*, to avoid *false sharing*, or to fill a gap.

**In Wally:** (1) Bytes added after a record to reach an 8-byte boundary. (2) A `PADDING` frame that fills the end of a segment when the next record doesn't fit, or replaces an aborted claim. Readers skip padding frames.

### Page cache
**In general:** Memory the operating system uses to hold recently used file data. Writes go here first and reach the disk later.

**In Wally:** Writes to a memory-mapped segment land in the page cache. They survive Wally crashing, but not the whole machine losing power, unless *flushed*.

### Page fault
**In general:** When a program touches memory the OS hasn't loaded yet, the OS pauses the program briefly to load or create that page. Usually invisible, but expensive on a hot path.

**In Wally:** A background task prepares the next segment before it's needed, so producers don't hit page faults when switching segments.

### Payload
**In general:** The actual data being carried, as opposed to headers about it.

**In Wally:** The bytes the user appends. Wally doesn't care what's inside them.

### Position
**In general:** Where something is in a sequence.

**In Wally:** A record's distance in bytes from the very start of the log, across all segments: `segmentIndex × segmentSize + offsetInSegment`. Positions only ever grow. See [ADR 0002](adr/0002-position-addressing.md).

### Producer
**In general:** The part of a system that creates and sends messages.

**In Wally:** Code that appends records through an *appender*.

---

## Q

### Queue
**In general:** A first-in, first-out (FIFO) line: items come out in the order they went in. Java has many built in, such as `ArrayBlockingQueue`.

**In Wally:** Wally behaves like a queue, but records are kept in files, every consumer gets every record, and nothing is removed when it's read.

---

## R

### Race condition
**In general:** A bug where the result depends on the exact timing of threads. These are notoriously hard to reproduce because they only show up sometimes.

**In Wally:** Prevented by the claim/commit protocol and *acquire / release* ordering. Tools like jcstress (see the table at the end) test for them.

### Retention
**In general:** How long data is kept before it's deleted.

**In Wally:** A segment is deleted only once every *consumer group* has read past it and, in WAL mode, it's before the last *checkpoint*.

---

## S

### Segment
**In general:** One piece of something split into pieces.

**In Wally:** One fixed-size, memory-mapped file of the log, such as `00000000000000000003.wly`. Deleting old data means deleting whole segment files.

### Serialization
**In general:** Turning an object into bytes (to save or send it) and back again (deserialization).

**In Wally:** Wally stores bytes and doesn't care how you serialize. *Zero-copy* formats like SBE fit best, because you can write fields straight into the claimed space.

### Single writer principle
**In general:** A performance rule: if only one thread ever writes a piece of data, you need no locks or atomic operations for it.

**In Wally:** Single-producer mode is the default and fastest. Multi-producer mode is opt-in.

---

## T

### Tailer
**In general:** A reader that follows the end of a log as it grows, like the `tail -f` command.

**In Wally:** The object you read records with. It calls your `RecordHandler` for each record.

### Thread
**In general:** An independent path of execution inside a program. Several threads can run at once on different CPU cores, sharing the same memory.

**In Wally:** Producers and consumers typically run on different threads.

### Throughput
**In general:** How many operations happen per second.

**In Wally:** Millions of records per second per producer. See [Benchmarks](benchmarks.md).

### Torn write
**In general:** A write that was only partly saved when a crash happened, so half the data is new and half is old or zero.

**In Wally:** Detected by a zero length or a bad *checksum*, and cut off during *crash recovery*.

---

## V

### VarHandle
**In general:** A Java tool for reading and writing a variable or memory location with special rules: atomically, or with *acquire / release* ordering.

**In Wally:** The `Mem` class wraps VarHandles to read and write `int`s and `long`s in `MemorySegment`s with the right ordering.

---

## W

### Write-Ahead Log (WAL)
**In general:** A log where changes are written *before* they're applied, so after a crash the system can replay the log and recover. Used by almost every database.

**In Wally:** One of Wally's two modes: the log plus a *flush policy*, *crash recovery* and *checkpoints*.

---

## Z

### Zero-allocation
**In general:** Code that creates no new objects while it runs, so it gives the *garbage collector* nothing to do.

**In Wally:** Once warmed up, appending and reading records allocates nothing. Proven by *JMH*, by a per-thread allocation counter, and by the *Epsilon GC* test.

### Zero-copy
**In general:** Moving data without copying it from one buffer to another.

**In Wally:** With `tryClaim`, you write your data directly into the log's memory instead of building it somewhere else and copying it in.

---

## Tools mentioned in the docs

| Tool | What it is |
|---|---|
| **jcstress** | OpenJDK tool that runs tiny concurrent tests millions of times to find memory-ordering bugs. |
| **Lincheck** | Tool that checks whether a concurrent data structure behaves like a correct single-threaded one. |
| **jqwik** | Property-based testing: generates thousands of random test cases and checks a rule holds for all of them. |
| **HdrHistogram** | Records latency measurements accurately, including the rare slow ones. |
| **MkDocs** | Turns these Markdown files into this website. |
