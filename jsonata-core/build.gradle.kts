plugins {
    kotlin("jvm")
}
description = "JSONata engine for the JVM: data model, parser (port of jsonata-js 2.2.2), evaluator, standard library. Kotlin stdlib only."
kotlin {
    // The public API is what jsonata-host and jsonata-service build on; ABI validation is added with the first public API.
    explicitApi()
}
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
    testImplementation(kotlin("test"))
}
// The core runs inside IDE processes: its runtime classpath is the Kotlin standard library only (no JSON, I/O, or network library).
val verifyCoreDependencies by tasks.registering {
    val runtime = configurations.named("runtimeClasspath")
    doLast {
        val modules = runtime.get().resolvedConfiguration.resolvedArtifacts.map { "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}" }.toSet()
        val unexpected = modules - setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")
        check(unexpected.isEmpty()) { "jsonata-core must depend only on kotlin-stdlib, found: $unexpected" }
    }
}
tasks.named("check") { dependsOn(verifyCoreDependencies) }
tasks.test { systemProperty("jsonata.compat", rootProject.file("compat").absolutePath) }
