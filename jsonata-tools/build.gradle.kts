plugins {
    kotlin("jvm")
    application
}
description = "Command-line tools of the engine (console debugger; later the deviation detector and the lake gate). Built from source, not published."
dependencies {
    implementation(project(":jsonata-json"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
    testImplementation(kotlin("test"))
}
application {
    mainClass.set("su.seaf.archtool.jsonata.tools.MainKt")
    applicationName = "jsonata-tools"
}
tasks.named<JavaExec>("run") { standardInput = System.`in` }
