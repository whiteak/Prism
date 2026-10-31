plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val media3 = "1.8.0"

android {
    namespace = "com.prism.clipper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.prism.clipper"
        minSdk = 29
        targetSdk = 35
        versionCode = (findProperty("clipper.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("clipper.versionName") as String?) ?: "1.0"
    }

    signingConfigs {
        create("release") {
            val store = System.getenv("CLIPPER_STORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("CLIPPER_KEY_PASSWORD")
                keyAlias = System.getenv("CLIPPER_KEY_ALIAS")
                keyPassword = System.getenv("CLIPPER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
