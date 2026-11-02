import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDirs("src/main/kotlin", "src/ahpProtocol/kotlin")
            dependencies {
                api(libs.coroutines.core)
                api(libs.kotlinx.serialization.json)
            }
        }
        jvmMain {
            kotlin.srcDir("src/jvmMain/kotlin")
            dependencies {
                api(libs.okhttp)
            }
        }
        jvmTest {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(project.dependencies.platform(libs.junit.bom))
                implementation(kotlin("test"))
                implementation(libs.okhttp.mockwebserver)
                implementation(libs.coroutines.test)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
        iosMain {
            kotlin.srcDir("src/iosMain/kotlin")
        }
    }
}

val jvmTestTask = tasks.named<Test>("jvmTest")

val testTask = if ("test" in tasks.names) {
    tasks.named("test") {
        dependsOn(jvmTestTask)
    }
} else {
    tasks.register("test") {
        group = "verification"
        description = "Runs the JVM unit test suite."
        dependsOn(jvmTestTask)
    }
}

val jacocoReportTask = tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn(jvmTestTask)
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    executionData.setFrom(jvmTestTask.map { it.extensions.getByType(JacocoTaskExtension::class.java).destinationFile })
    classDirectories.setFrom(mainCompilation.output.classesDirs)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/jvmMain/kotlin", "src/ahpProtocol/kotlin"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

jvmTestTask.configure {
    finalizedBy(jacocoReportTask)
}

// Spec Step 1.12: at least 80% line coverage on the packages that carry the protocol, sign-in and connection logic.
val jacocoVerifyTask = tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(jvmTestTask)
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    executionData.setFrom(jvmTestTask.map { it.extensions.getByType(JacocoTaskExtension::class.java).destinationFile })
    classDirectories.setFrom(mainCompilation.output.classesDirs)
    sourceDirectories.setFrom(files("src/main/kotlin", "src/jvmMain/kotlin", "src/ahpProtocol/kotlin"))
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf(
                "dev.ahpclient.auth",
                "dev.ahpclient.tunnel",
                "dev.ahpclient.ahp",
                "dev.ahpclient.state",
            )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(jacocoVerifyTask)
}

// Step 1.6 live probe: .\gradlew.bat -q --console=plain :core-ahp:probe --args="--watch 20"
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Signs in, attaches to a Remote Tunnel Access agent host and lists sessions (read-only)."
    val mainCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles
    mainClass.set("dev.ahpclient.probe.ProbeMainKt")
    workingDir = rootProject.projectDir // so a relative --capture path lands in the gitignored <repo>/captures/
    standardInput = System.`in`
}
