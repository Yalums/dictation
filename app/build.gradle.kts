plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dictation.server"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dictation.server"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            applicationIdSuffix = ".phone"
        }
        create("relay") {
            dimension = "device"
            applicationIdSuffix = ".relay"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("../main/java")
            res.srcDirs("../main/res")
            assets.srcDirs("../main/assets")
            manifest.srcFile("../main/AndroidManifest.xml")
        }
        getByName("phone") {
            java.srcDirs("../phone/java")
        }
        getByName("relay") {
            java.srcDirs("../relay/java")
            res.srcDirs("../relay/res")
            manifest.srcFile("../relay/AndroidManifest.xml")
        }
        getByName("test") {
            java.srcDirs("../test/java")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")

    // Androidx
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WebSocket
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // Test
    testImplementation("junit:junit:4.13.2")
}
