plugins {
    kotlin("jvm")
}
description = "Streaming JSON codec (Jackson core) between JSON text and the jsonata-core data model."
kotlin { explicitApi() }
dependencies {
    api(project(":jsonata-core"))
    implementation("com.fasterxml.jackson.core:jackson-core:2.21.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
    testImplementation(kotlin("test"))
}
