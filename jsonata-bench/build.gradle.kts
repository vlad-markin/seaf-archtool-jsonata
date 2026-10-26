plugins {
    kotlin("jvm")
    application
}
description = "Performance stand (story 6, CAP-14): synthetic architecture models, 15 queries, the engine against jsonata-js 2.2.2. Not published."
dependencies {
    implementation(project(":jsonata-json"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.10")
    testImplementation(kotlin("test"))
}
application {
    mainClass.set("su.seaf.archtool.jsonata.bench.StandKt")
}
// ./gradlew :jsonata-bench:stand -Psizes=10000,50000 -Pqueries=path_filter,sort_fn -Pengines=jvm,js
tasks.register<JavaExec>("stand") {
    group = "verification"
    description = "Runs the performance stand; results in build/stand/"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("su.seaf.archtool.jsonata.bench.StandKt")
    workingDir = rootProject.projectDir
    val props = listOf("sizes", "queries", "engines", "xmx", "heap", "warmup", "iters", "jsTimeout")
    args = props.mapNotNull { p -> project.findProperty("stand.$p")?.let { "--$p=$it" } ?: project.findProperty(p)?.let { "--$p=$it" } }
}
tasks.test { systemProperty("jsonata.root", rootProject.projectDir.absolutePath) }
