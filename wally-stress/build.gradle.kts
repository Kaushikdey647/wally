plugins {
    java
}

// Own source set so the root -Werror on compileJava doesn't apply to jcstress-generated code.
val jcstress: SourceSet = sourceSets.create("jcstress")
val jcstressVersion = "0.16"

dependencies {
    "jcstressImplementation"(project(":wally-core"))
    "jcstressImplementation"("org.openjdk.jcstress:jcstress-core:$jcstressVersion")
    "jcstressAnnotationProcessor"("org.openjdk.jcstress:jcstress-core:$jcstressVersion")
}

// ./gradlew :wally-stress:jcstress [-Pjcstress.args="-m default"]
tasks.register<JavaExec>("jcstress") {
    group = "verification"
    description = "Runs jcstress memory-ordering tests."
    classpath = jcstress.runtimeClasspath
    mainClass = "org.openjdk.jcstress.Main"
    args(providers.gradleProperty("jcstress.args").getOrElse("-m quick").split(" "))
    val dir = layout.buildDirectory.dir("jcstress").get().asFile
    workingDir = dir
    doFirst { dir.mkdirs() }
}

tasks.check { dependsOn(tasks.named("jcstressClasses")) }
