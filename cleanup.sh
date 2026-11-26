#!/bin/bash
# Cleanup script to remove old WAL code
# Run this after reviewing the changes

set -e

echo "🧹 Cleaning up old WAL code..."

# Remove old source directories
echo "Removing old source packages..."
rm -rf wally-core/src/main/java/io/wally/api
rm -rf wally-core/src/main/java/io/wally/internal/segment
rm -rf wally-core/src/main/java/io/wally/internal/concurrent
rm -rf wally-core/src/main/java/io/wally/internal/log

# Remove old test directories
echo "Removing old tests..."
rm -rf wally-core/src/test/java/io/wally/_old

# Remove old benchmarks
echo "Removing old benchmarks..."
rm -f wally-bench/src/jmh/java/io/wally/bench/MPSCLatencyBenchmark.java
rm -f wally-bench/src/jmh/java/io/wally/bench/MPSCThroughputBenchmark.java
rm -f wally-bench/src/jmh/java/io/wally/bench/MPSCCorrectnessTest.java
rm -f wally-bench/src/jmh/java/io/wally/bench/CompetitorBenchmark.java
rm -f wally-bench/src/jmh/java/io/wally/bench/SegmentLogBenchmark.java

echo "✅ Cleanup complete!"
echo ""
echo "Remaining structure:"
tree wally-core/src/main/java/io/wally -L 2 || find wally-core/src/main/java/io/wally -type d
echo ""
echo "Run './gradlew clean build' to verify everything still compiles."
