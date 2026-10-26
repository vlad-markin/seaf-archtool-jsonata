plugins {
    kotlin("jvm")
    application
}
description = "Command-line tools of the engine (console debugger, detector of differences and SEAF checks; later the lake gate). Built from source, not published."
dependencies {
    implementation(project(":jsonata-json"))
    implementation(project(":jsonata-seaf"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
    testImplementation(kotlin("test"))
}
application {
    mainClass.set("su.seaf.archtool.jsonata.tools.MainKt")
    applicationName = "jsonata-tools"
}
tasks.named<JavaExec>("run") { standardInput = System.`in` }
tasks.test { systemProperty("jsonata.root", rootProject.projectDir.absolutePath) }
