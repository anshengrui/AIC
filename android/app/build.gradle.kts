import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val easyAccessLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun easyAccessConfig(name: String, fallback: String): String =
    providers.environmentVariable(name).orNull
        ?: easyAccessLocalProperties.getProperty(name)
        ?: fallback

fun quotedBuildConfig(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.easyaccess.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.easyaccess.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.9.1"

        buildConfigField(
            "String",
            "EASYACCESS_API_BASE_URL",
            quotedBuildConfig(
                easyAccessConfig(
                    "EASYACCESS_API_BASE_URL",
                    "http://127.0.0.1:8000/api",
                )
            ),
        )
        buildConfigField(
            "String",
            "EASYACCESS_API_TOKEN",
            quotedBuildConfig(easyAccessConfig("EASYACCESS_API_TOKEN", "")),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
