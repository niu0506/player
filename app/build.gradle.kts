import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 签名配置来源（二选一，优先级从高到低）：
 * 1. 环境变量：CI(GitHub Actions) 通过 Secrets 注入，本地也可以 export 后覆盖
 * 2. 本地文件 keystore.properties（已加入 .gitignore，不会提交）
 * 两者都没有时 release 构建回退为无签名（输出 *-unsigned.apk）。
 */
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun envOrProp(envKey: String, propKey: String): String? =
    System.getenv(envKey) ?: keystoreProperties.getProperty(propKey)

android {
    namespace = "com.example.player"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.player"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.2.2"
    }

    signingConfigs {
        create("release") {
            // 环境变量优先(CI 注入)，其次本地 keystore.properties
            storeFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
                ?: keystoreProperties.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = envOrProp("KEYSTORE_PASSWORD", "storePassword")
            keyAlias = envOrProp("KEY_ALIAS", "keyAlias")
            keyPassword = envOrProp("KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // 签名四要素齐备才生效；否则 AGP 回退为无签名产物（构建不失败，便于无密钥环境编译验证）
            if (signingConfigs.getByName("release").storeFile == null) {
                signingConfig = null
            }
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
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("com.google.android.material:material:1.14.0")
}
