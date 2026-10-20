plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    // JediTerm (terminal emulator) is published to the JetBrains dependencies repo, not Maven Central.
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Target IntelliJ IDEA Community 2024.2 (build 242). Works in any 2024.2+ JetBrains IDE.
        intellijIdeaCommunity("2024.2")
        instrumentationTools()
    }

    // SSH client — actively maintained drop-in fork of JCraft JSch (same com.jcraft.jsch package).
    implementation("com.github.mwiede:jsch:0.2.25")

    // Embedded terminal emulator widget. 3.53 is built with Kotlin 1.9, matching the
    // 2024.2 platform toolchain; the Kotlin stdlib is provided by the IDE at runtime.
    implementation("org.jetbrains.jediterm:jediterm-core:3.53") {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("org.jetbrains.jediterm:jediterm-ui:3.53") {
        exclude(group = "org.jetbrains.kotlin")
    }
    
    // FTP Client
    implementation("commons-net:commons-net:3.11.1")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            // No upper bound: this is distributed internally and installed across
            // rolling IDE upgrades (e.g. 251+), so we don't want a cap that blocks
            // each new IDE release. Re-add an untilBuild only if a future platform
            // version is known to break the plugin.
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks {
    wrapper {
        gradleVersion = "8.10"
    }
}
