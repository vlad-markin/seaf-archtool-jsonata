pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
// Downloads the JDK toolchain when the machine does not have it (cloud sessions).
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
rootProject.name = "jsonata-kotlin"
// Core, JSON codec, compatibility harness, command-line tools (not published). jsonata-service and jsonata-host come later.
include("jsonata-core", "jsonata-json", "jsonata-compat", "jsonata-tools")
