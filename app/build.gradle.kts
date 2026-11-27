plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "ai.yasmin.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.yasmin.assistant"
        minSdk = 26          // Android 8.0
        targetSdk = 35       // runs on Android 8 ... 16
        versionCode = 35
        versionName = "2.42"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // v2.36: Camera Vision (in-app camera) and picture reading
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    testImplementation("junit:junit:4.13.2")   // v2.21: JVM unit tests for the wake / sleep parser and the mode state machine
}

// CameraX pulls in appcompat, whose resources fail to process in the phone (Termux) build. The camera page does not use it, so leave it out.
configurations.all { exclude(group = "androidx.appcompat", module = "appcompat") }
