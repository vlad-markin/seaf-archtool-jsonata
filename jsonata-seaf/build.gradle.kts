plugins {
    kotlin("jvm")
}
description = "SEAF ArchTool functions on the engine: core functions, manifest functions, JSON Schema, SEAF limits and detector."
kotlin { explicitApi() }
dependencies {
    api(project(":jsonata-core"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
    testImplementation(project(":jsonata-json"))
    testImplementation(kotlin("test"))
}
tasks.test { systemProperty("jsonata.compat", rootProject.file("compat").absolutePath) }
