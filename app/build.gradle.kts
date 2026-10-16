import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun envOrProp(envKey: String, propKey: String): String? =
    System.getenv(envKey) ?: keystoreProperties.getProperty(propKey)

android {
    namespace = "com.example.player"

    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.player"
        minSdk = 24
        targetSdk = 35
        versionCode = 39
        versionName = "1.5.4"
    }

    signingConfigs {
        create("release") {

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

            if (signingConfigs.getByName("release").storeFile == null) {
                signingConfig = null
            }
        }
    }
    buildFeatures { viewBinding = true }
    testOptions {

        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
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
