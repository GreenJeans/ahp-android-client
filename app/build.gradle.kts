plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ahpclient"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.greenjeans.ahpclient"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePath = providers.gradleProperty("AHP_KEYSTORE_FILE").orElse(providers.environmentVariable("AHP_KEYSTORE_FILE")).orNull
    val keystorePassword = providers.gradleProperty("AHP_KEYSTORE_PASSWORD").orElse(providers.environmentVariable("AHP_KEYSTORE_PASSWORD")).orNull
    val keyAliasName = providers.gradleProperty("AHP_KEY_ALIAS").orElse(providers.environmentVariable("AHP_KEY_ALIAS")).orNull
    val keyPasswordValue = providers.gradleProperty("AHP_KEY_PASSWORD").orElse(providers.environmentVariable("AHP_KEY_PASSWORD")).orNull
    val hasReleaseSigning = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() &&
        !keyAliasName.isNullOrBlank() && !keyPasswordValue.isNullOrBlank() && file(keystorePath).exists()

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-ahp"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.browser)
    implementation(libs.biometric)
    implementation(libs.fragment)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.work.runtime.ktx)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.billing.ktx)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.accessibility)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.work.testing)
}
