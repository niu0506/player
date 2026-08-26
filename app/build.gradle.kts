plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.player"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.player"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
}
