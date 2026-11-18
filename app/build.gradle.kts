plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.spool"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.spool"
        minSdk = 31
        targetSdk = 36
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    // The signing key comes from the SPOOL_KEYSTORE_BASE64 repo secret (decoded by CI to
    // signing/spool.jks). Same key every build, so updates install over the old app.
    val keystore = rootProject.file("signing/spool.jks")
    val keystorePassword = System.getenv("SPOOL_KEYSTORE_PASSWORD") ?: "spoolspool"
    signingConfigs {
        create("shared") {
            storeFile = keystore
            storePassword = keystorePassword
            keyAlias = "spool"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore.exists()) signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            if (keystore.exists()) signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui-compose:$media3")
    implementation("androidx.media3:media3-inspector:$media3")
    // Used directly by TagReader (metadata entry types).
    implementation("androidx.media3:media3-extractor:$media3")
    implementation("androidx.media3:media3-container:$media3")

    testImplementation("junit:junit:4.13.2")
}
