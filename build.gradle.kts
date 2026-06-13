plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.github.cursorterm"
version = "1.8.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        goland("2025.1")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
}

tasks {
    patchPluginXml {
        sinceBuild.set("251")
        untilBuild.set("999.*")
    }
}
