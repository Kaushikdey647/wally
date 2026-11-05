plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
}

dependencies {
    jmh(project(":wally-core"))
}

jmh {
    jmhVersion = "1.37"
    providers.gradleProperty("jmh.profilers").orNull?.let { profilers.add(it) }
    providers.gradleProperty("jmh.includes").orNull?.let { includes.add(it) }
}
