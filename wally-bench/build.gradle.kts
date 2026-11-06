plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
}

dependencies {
    jmh(project(":wally-core"))

    // Competitor baselines
    jmh("org.jctools:jctools-core:4.0.5")
    jmh("net.openhft:chronicle-queue:2026.8")
    jmh("com.lmax:disruptor:4.0.0")

    // Latency measurement
    jmh("org.hdrhistogram:HdrHistogram:2.2.2")
}

jmh {
    jmhVersion = "1.37"
    providers.gradleProperty("jmh.profilers").orNull?.let { profilers.add(it) }
    providers.gradleProperty("jmh.includes").orNull?.let { includes.add(it) }
}
