import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64(),
    )

    iosTargets.forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "AhpClientShared"
            isStatic = true
            export(project(":core-ahp"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":core-ahp"))
                @Suppress("DEPRECATION")
                implementation(compose.runtime)
                @Suppress("DEPRECATION")
                implementation(compose.foundation)
                @Suppress("DEPRECATION")
                implementation(compose.material3)
                @Suppress("DEPRECATION")
                implementation(compose.ui)
                implementation(libs.lifecycle.runtime.compose)
                implementation(libs.datastore.preferences)
                implementation(libs.okio)
                implementation(libs.markdown.renderer.m3)
            }
        }

        jvmMain {
            kotlin.srcDir("../app/src/commonUi/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }

        iosMain {
            kotlin.srcDir("../app/src/commonUi/kotlin")
        }
    }
}
