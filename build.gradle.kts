plugins {
    kotlin("jvm") version "2.4.20" apply false
}
allprojects {
    group = "su.seaf.archtool"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            // Libraries run inside JetBrains IDEs (JBR 21) through jsonata-host, so bytecode and API stay at 21;
            // virtual threads are available there. The service runs on JDK 25.
            jvmToolchain(21)
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
                languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
            }
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        reports.html.required.set(false)
        testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
