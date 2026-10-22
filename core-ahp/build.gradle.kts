import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

// Bytecode level only: no JDK toolchain is pinned, so any JDK 17+ can build this module.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // api, not implementation: OkHttpClient, CoroutineScope and the Flow types appear in this module's public signatures.
    api(libs.ahp)
    api(libs.okhttp)
    api(libs.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Spec Step 1.12: at least 80% line coverage on the packages that carry the protocol, sign-in and connection logic.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
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

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// Step 1.6 live probe: .\gradlew.bat -q --console=plain :core-ahp:probe --args="--watch 20"
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Signs in, attaches to a Remote Tunnel Access agent host and lists sessions (read-only)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.ahpclient.probe.ProbeMainKt")
    workingDir = rootProject.projectDir // so a relative --capture path lands in the gitignored <repo>/captures/
    standardInput = System.`in`
}
