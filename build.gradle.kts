subprojects {
    group = "io.wally"
    version = "0.1.0-SNAPSHOT"

    repositories { mavenCentral() }

    plugins.withType<JavaPlugin> {
        configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(25)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-Xlint:all,-processing")
        }
        tasks.named<JavaCompile>("compileJava") { options.compilerArgs.add("-Werror") }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
