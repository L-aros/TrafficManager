plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.io.ByteArrayOutputStream
import java.util.Properties

fun Project.execAndGet(vararg args: String): String? {
    val stdout = ByteArrayOutputStream()
    val result = runCatching {
        providers.exec {
            commandLine = args.toList()
            standardOutput = stdout
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }.result.get().exitValue
    }.getOrNull()
    if (result != 0) return null
    return stdout.toString().trim().ifBlank { null }
}

android {
    namespace = "com.laros.lsp.traffics"
    compileSdk = 35

    signingConfigs {
        create("release") {
            val props = Properties().apply {
                val file = rootProject.file("keystore/keystore.properties")
                if (file.exists()) {
                    file.inputStream().use { load(it) }
                }
            }
            val storeFilePath = props.getProperty("storeFile")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = rootProject.file(storeFilePath)
            }
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = "com.laros.lsp.traffics"
        minSdk = 29
        targetSdk = 35
        val baseVersion = (project.findProperty("VERSION_NAME") as String?)?.trim()
            ?.takeIf { it.isNotBlank() } ?: "0.1.0"
        val propVersionCode = (project.findProperty("VERSION_CODE") as String?)
            ?.trim()
            ?.toIntOrNull()
        val envBuildNumber = System.getenv("GITHUB_RUN_NUMBER")
            ?: System.getenv("BUILD_NUMBER")
            ?: System.getenv("CI_PIPELINE_IID")
        val gitBuildNumber = project.execAndGet("git", "rev-list", "--count", "HEAD")
        val buildNumber = propVersionCode
            ?: envBuildNumber?.toIntOrNull()
            ?: gitBuildNumber?.toIntOrNull()
            ?: 1
        versionCode = buildNumber
        versionName = baseVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Place LSPosed/Xposed API jar in app/libs/api-82.jar for compile-only.
    compileOnly(files("libs/api-82.jar"))
}
