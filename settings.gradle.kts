pluginManagement {
    val playRoot = file(providers.environmentVariable("PLAY1_HOME").getOrElse("/opt/play1"))
    // The play1 fork publishes its Gradle plugin to a flat Maven repo inside
    // the framework distribution. Earlier 1.13.x consumers used
    // includeBuild("/opt/play1"), which builds the plugin from source — that
    // requires writing into /opt/play1/.gradle/ and the included build's
    // build/ dirs, and silently fails on read-only installs (e.g. /opt/play1
    // owned by root). Resolving from the file:// repo only reads from
    // /opt/play1, so the framework can stay read-only.
    repositories {
        maven { url = uri(playRoot.resolve("framework/gradle-plugin-repo")) }
        gradlePluginPortal()
    }
    // Plugin version comes from the framework's on-disk version file
    // (the same source build.gradle.kts validates against the pinned range).
    val versionFile = playRoot.resolve("framework/src/play/version")
    require(versionFile.isFile) {
        "play1 framework not found at $versionFile — set PLAY1_HOME to the Play 1.13.x install"
    }
    val installed = versionFile.readText().trim()
    plugins {
        id("org.playframework.play1") version installed
    }
}
rootProject.name = "jclaw"
