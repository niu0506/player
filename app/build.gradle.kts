import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 读取 release 签名配置：
// - 本地构建：读取根目录 keystore.properties（已被 gitignore，不会提交）
// - CI 构建：读取 GitHub Secrets 注入的环境变量（见 .github/workflows/build.yml）
val keystoreProperties = Properties().apply {
    val localFile = rootProject.file("keystore.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    } else {
        setProperty("storeFile", System.getenv("KEYSTORE_FILE") ?: "")
        setProperty("storePassword", System.getenv("KEYSTORE_PASSWORD") ?: "")
        setProperty("keyAlias", System.getenv("KEY_ALIAS") ?: "")
        setProperty("keyPassword", System.getenv("KEY_PASSWORD") ?: "")
    }
}
val hasReleaseSigning =
    !keystoreProperties.getProperty("storeFile").isNullOrBlank()

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
    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // 有签名配置则用正式签名；否则回退 debug 签名保证本地也能出可安装包
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
        }
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
