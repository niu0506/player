import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// 本地签名配置（keystore.properties 已被 .gitignore 忽略，CI 经环境变量注入）。
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 优先取环境变量，其次取本地 properties 文件。
fun envOrProp(envKey: String, propKey: String): String? =
    System.getenv(envKey) ?: keystoreProperties.getProperty(propKey)

android {
    namespace = "com.example.player"

    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.player"
        minSdk = 24
        // targetSdk 35；compileSdk 36 由 media3 1.11 的传递依赖要求
        targetSdk = 35
        versionCode = 46
        versionName = "1.5.11"
    }

    signingConfigs {
        create("release") {
            // 签名信息优先级：环境变量 > keystore.properties
            storeFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }
                ?: keystoreProperties.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = envOrProp("KEYSTORE_PASSWORD", "storePassword")
            keyAlias = envOrProp("KEY_ALIAS", "keyAlias")
            keyPassword = envOrProp("KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        release {
            // 开启混淆与资源压缩
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")

            // 无签名信息时回退为无签名构建（*-unsigned.apk），不使构建失败
            if (signingConfigs.getByName("release").storeFile == null) {
                signingConfig = null
            }
        }
    }
    buildFeatures { viewBinding = true }
    testOptions {
        // 单元测试中未实现的 Android API 返回默认值而非抛异常
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    // Room 导出的 schema 位置（须提交到版本库，作为迁移 diff 基准）
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.appcompat)
    implementation(libs.recyclerview)
    implementation(libs.activity.ktx)
    implementation(libs.material)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)

    testImplementation(libs.json)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
}
