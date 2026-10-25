plugins {
    kotlin("jvm")
}
description = "Compatibility harness against jsonata-js 2.2.2: test-suite runner, AST snapshot comparison, pass ratchet. Never published."
dependencies {
    implementation(project(":jsonata-core"))
    implementation(project(":jsonata-json"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
}
tasks.test {
    val compat = rootProject.file("compat")
    inputs.dir(compat).withPropertyName("compat")
    systemProperty("jsonata.compat", compat.absolutePath)
    systemProperty("jsonata.compat.report", layout.buildDirectory.file("compat-report.json").get().asFile.absolutePath)
}
tasks.test { systemProperty("jsonata.compat.update", providers.gradleProperty("compatUpdate").getOrElse("false")) }
