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
tasks.register<JavaExec>("bench") {
    description = "Rough timing of compat/bench/cases.json (compare with compat/node/bench.mjs)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("su.seaf.archtool.jsonata.compat.BenchKt")
    args(rootProject.file("compat").absolutePath, providers.gradleProperty("benchIterations").getOrElse("10"), providers.gradleProperty("benchFilter").getOrElse(""), providers.gradleProperty("benchFresh").getOrElse("false"))
    jvmArgs("-Xmx2g")
    // -PbenchJfr=/path/file.jfr records a JFR profile of the run
    providers.gradleProperty("benchJfr").orNull?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
}
tasks.register<JavaExec>("fuzz") {
    description = "Differential fuzzing against jsonata 2.2.2 (needs Node 22 and npm ci in compat/node): -Pseed=1 -Pcount=5000, or -Pcases=file.jsonl to replay cases."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("su.seaf.archtool.jsonata.compat.fuzz.DiffFuzzKt")
    args(
        rootProject.file("compat").absolutePath,
        providers.gradleProperty("seed").getOrElse("1"),
        providers.gradleProperty("count").getOrElse("5000"),
        layout.buildDirectory.dir("fuzz").get().asFile.absolutePath,
        providers.gradleProperty("cases").getOrElse(""),
    )
    jvmArgs("-Xmx2g")
}
