import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

repositories {
    google()

    mavenCentral()
}

group = "one.wabbit"
version = "0.0.1"

plugins {
    id("com.android.kotlin.multiplatform.library")

    kotlin("multiplatform")

    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    id("maven-publish")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")

    }
    applyDefaultHierarchyTemplate()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            jvmArgs("-ea")
        }
    }

    androidLibrary {
        namespace = "one.wabbit.dotenv.parser"
        compileSdk = 34
        minSdk = 26
    }

    iosArm64()

    iosSimulatorArm64()

    macosArm64("hostNative")

    targets.withType(KotlinNativeTarget::class.java).configureEach {
        binaries.framework {
            baseName = "DotenvParser"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":kotlin-parsing-charset")) // 1.3.0

                implementation(project(":kotlin-parsing-charinput")) // 1.2.0

            }

        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":kotlin-exec")) // 0.0.1

            }

        }

        val commonTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-test:2.3.10")

            }

        }

    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("-ea")
}

dokka {
    moduleName.set("kotlin-dotenv-parser")
    dokkaPublications.html {
        suppressInheritedMembers.set(true)
        failOnWarning.set(true)
    }

    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/wabbit-corp/kotlin-dotenv-parser/tree/master/src")
            remoteLineSuffix.set("#L")
        }

    }

    pluginsConfiguration.html {
        footerMessage.set("(c) Wabbit Consulting Corporation")
    }
}
