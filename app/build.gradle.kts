import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cr.co.topoemlid"
    compileSdk = 36

    defaultConfig {
        applicationId = "cr.co.topoemlid"
        minSdk = 26
        targetSdk = 36
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = ciRun ?: 3
        versionName = if (ciRun != null) "0.3.$ciRun" else "0.3.0"
    }

    signingConfigs {
        getByName("debug") {
            val stableStore = rootProject.file(".ci/topoemlid-debug.jks")
            if (stableStore.exists()) {
                storeFile = stableStore
                storePassword = "topoemlid-debug"
                keyAlias = "topoemliddebug"
                keyPassword = "topoemlid-debug"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Paquete separado para pruebas: se instala junto a versiones anteriores
            // sin chocar con firmas debug históricas.
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.maplibre.gl:android-sdk:13.1.0")
    implementation("org.locationtech.jts:jts-core:1.20.0")
    implementation("org.locationtech.proj4j:proj4j:1.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.socket:socket.io-client:1.0.2") {
        exclude(group = "org.json", module = "json")
    }
}


kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
