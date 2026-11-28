plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
}

dependencies {
    jmh(project(":wally-core"))

    // Baseline
    jmh("org.jctools:jctools-core:4.0.5")

    // Latency measurement
    jmh("org.hdrhistogram:HdrHistogram:2.2.2")
}

val results = layout.projectDirectory.dir("results")

jmh {
    jmhVersion = "1.37"
    resultFormat = "JSON"
    resultsFile = results.file("jmh-throughput.json")
    providers.gradleProperty("jmh.profilers").orNull?.let { profilers.add(it) }
    providers.gradleProperty("jmh.includes").orNull?.let { includes.add(it) }
}

// Fixed-rate 1P1C latency, one JVM per implementation: ./gradlew :wally-bench:latency [-Prate=1000000]
val latency = tasks.register("latency") {
    group = "benchmark"
    description = "Runs the fixed-rate SPSC latency harness for each implementation."
}
listOf("wally", "jctools").forEach { impl ->
    val task = tasks.register<JavaExec>("latency${impl.replaceFirstChar(Char::uppercase)}") {
        group = "benchmark"
        classpath = sourceSets["jmh"].runtimeClasspath
        mainClass = "io.wally.bench.SpscLatency"
        args(impl, providers.gradleProperty("rate").getOrElse("1000000"), "5", "20", results.asFile.path)
        jvmArgs("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch")
    }
    latency { dependsOn(task) }
}
tasks.named("latencyJctools") { mustRunAfter("latencyWally") }
