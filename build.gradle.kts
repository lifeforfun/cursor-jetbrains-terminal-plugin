plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.github.cursorterm"
version = "3.0.2"

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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("999.*")
    }

    val verifyPluginZip by registering {
        dependsOn("buildPlugin")
        doLast {
            val zip = layout.buildDirectory.file("distributions/cursor-cli-terminal-plugin-${version}.zip").get().asFile
            check(zip.isFile) {
                "Missing plugin distribution: ${zip.absolutePath}\nRun: ./gradlew buildPlugin"
            }
            logger.lifecycle("Plugin zip ready: ${zip.absolutePath} (${zip.length()} bytes)")
        }
    }

    named("build") {
        dependsOn(verifyPluginZip)
    }
}
