pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
// Downloads the JDK toolchain when the machine does not have it (cloud sessions).
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
rootProject.name = "jsonata-kotlin"
// Phases 1-5: core, JSON codec, compatibility harness. jsonata-service and jsonata-host are added in later phases.
include("jsonata-core", "jsonata-json", "jsonata-compat")
