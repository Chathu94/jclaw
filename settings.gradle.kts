pluginManagement {
    // The play1 fork publishes its Gradle plugin to a flat Maven repo inside
    // the framework distribution. Earlier 1.13.x consumers used
    // includeBuild("/opt/play1"), which builds the plugin from source — that
    // requires writing into /opt/play1/.gradle/ and the included build's
    // build/ dirs, and silently fails on read-only installs (e.g. /opt/play1
    // owned by root). Resolving from the file:// repo only reads from
    // /opt/play1, so the framework can stay read-only.
    // Plugin version comes from the framework's on-disk version file
    // (the same source build.gradle.kts validates against the pinned range).
    // PLAY1_HOME lets non-root dev shells use a local Play install while
    // preserving /opt/play1 as the devcontainer/CI default. When /opt/play1
    // is absent, fall back to the user-local setup path used by this repo's
    // non-root bootstrap instructions.
    val defaultPlayRoot = java.io.File("/opt/play1").takeIf { it.exists() }
        ?: java.io.File(System.getProperty("user.home"), ".local/share/jclaw-toolchain/play1")
    val playRoot = java.io.File(providers.environmentVariable("PLAY1_HOME").orElse(defaultPlayRoot.absolutePath).get())
    val versionFile = playRoot.resolve("framework/src/play/version")
    require(versionFile.isFile) {
        "play1 framework not found at $versionFile — is ${playRoot.absolutePath} a Play 1.13.x install?"
    }
    val installed = versionFile.readText().trim()
    repositories {
        maven { url = uri(playRoot.resolve("framework/gradle-plugin-repo")) }
        gradlePluginPortal()
    }
    plugins {
        id("org.playframework.play1") version installed
    }
}
rootProject.name = "jclaw"
