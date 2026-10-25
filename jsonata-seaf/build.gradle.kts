plugins {
    kotlin("jvm")
}
description = "SEAF ArchTool functions on the engine: core functions, manifest functions, JSON Schema, SEAF limits and detector."
kotlin { explicitApi() }
dependencies {
    api(project(":jsonata-core"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
    // JSON Schema of manifest functions and $jsonschema (decision D1 of the IT lead; the same version as lang-schema)
    implementation("com.networknt:json-schema-validator:3.0.8")
    testImplementation(project(":jsonata-json"))
    testImplementation(kotlin("test"))
}
tasks.test { systemProperty("jsonata.compat", rootProject.file("compat").absolutePath) }
