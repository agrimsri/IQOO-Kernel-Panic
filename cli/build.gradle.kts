// TraceVeil CI: gate a release on audit evidence exported from the phone ("traceveil.audit.v1" bundles).
// Plain Kotlin/JVM on top of :core only (no Android). Build: ./gradlew :cli:installDist (scripts in
// cli/build/install/traceveil-ci/bin) or ./gradlew :cli:fatJar (one runnable jar in cli/build/libs).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    // Test-only certificate chain builder for `verify` (core/src/testFixtures).
    testImplementation(testFixtures(project(":core")))
}

application {
    mainClass.set("com.dpdpxray.cli.MainKt")
    applicationName = "traceveil-ci"
}

val samplesDir = rootProject.layout.projectDirectory.dir("docs/samples")
val ciDocsDir = rootProject.layout.projectDirectory.dir("docs/ci")

tasks.jar {
    manifest { attributes("Implementation-Title" to "TraceVeil CI", "Implementation-Version" to project.version) }
}

tasks.test {
    // The committed sample bundles are test inputs: the golden test fails when they drift from the fixtures.
    inputs.files(fileTree(samplesDir), fileTree(ciDocsDir)).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("traceveil.samplesDir", samplesDir.asFile.absolutePath)
    systemProperty("traceveil.ciDocsDir", ciDocsDir.asFile.absolutePath)
}

tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Builds one runnable jar: java -jar cli/build/libs/traceveil-ci-<version>-all.jar check bundle.json"
    archiveBaseName.set("traceveil-ci")
    archiveClassifier.set("all")
    manifest {
        attributes(
            "Main-Class" to "com.dpdpxray.cli.MainKt",
            "Implementation-Title" to "TraceVeil CI",
            "Implementation-Version" to project.version,
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    val runtime = configurations.runtimeClasspath
    dependsOn(runtime)
    from({ runtime.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
}

tasks.register<JavaExec>("writeSamples") {
    group = "documentation"
    description = "Regenerates docs/samples/*_sample.json from the synthetic test fixtures"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.dpdpxray.cli.samples.WriteSamplesKt")
    args(samplesDir.asFile.absolutePath)
}
