plugins {
    `java-library`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform { excludeTags("epsilon") }
}

// Steady-state loop under Epsilon GC: any per-op allocation exhausts the small heap and fails the run.
val epsilonTest = tasks.register<Test>("epsilonTest") {
    description = "Runs zero-allocation checks under Epsilon GC."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("epsilon") }
    maxHeapSize = "128m"
    jvmArgs("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC")
}

tasks.check { dependsOn(epsilonTest) }
