@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.impl.VariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val buildTime = System.currentTimeMillis()
val localProperties = Properties()
if (rootProject.file("local.properties").canRead()) {
    localProperties.load(rootProject.file("local.properties").inputStream())
}
val keystoreProperties = Properties()
if (rootProject.file("keystore.properties").canRead()) {
    keystoreProperties.load(rootProject.file("keystore.properties").inputStream())
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    namespace = "statusbar.lyric"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "statusbar.lyric"
        minSdk = 30
        targetSdk = 37
        versionCode = 730
        versionName = "7.3.0"
        buildConfigField("long", "BUILD_TIME", "${buildTime}L")
        buildConfigField("int", "COMPOSE_CONFIG_VERSION", "2")
    }

    val releaseSigning = runCatching {
        val storePath = keystoreProperties.getProperty("storeFile")
            ?: localProperties.getProperty("androidStoreFile")
        val storePwd = keystoreProperties.getProperty("storePassword")
            ?: localProperties.getProperty("androidStorePassword")
        val alias = keystoreProperties.getProperty("keyAlias")
            ?: localProperties.getProperty("androidKeyAlias")
        val keyPwd = keystoreProperties.getProperty("keyPassword")
            ?: localProperties.getProperty("androidKeyPassword")
        if (!storePath.isNullOrBlank()) {
            signingConfigs.create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = storePwd
                keyAlias = alias
                keyPassword = keyPwd
                enableV3Signing = true
                enableV4Signing = true
            }
        } else {
            null
        }
    }.getOrNull()

    buildTypes {
        all {
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    dependenciesInfo.includeInApk = false
    packaging.resources.excludes += setOf(
        "META-INF/*.version",
        "META-INF/proguard/**",
        "DebugProbesKt.bin",
        "kotlin-tooling-metadata.json"
    )
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                val vName = output.versionName.getOrElse("7.3.0")
                val vCode = output.versionCode.getOrElse(730)
                output.outputFileName.set("StatusBarLyric-$vName($vCode)-${variant.name}-$buildTime.apk")
            }
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)

    implementation(libs.ezxhelper.core)
    implementation(libs.ezxhelper.xposed.api)
    implementation(libs.ezxhelper.android.utils)
    implementation(libs.superlyricapi)

    debugImplementation(libs.androidx.ui.tooling.preview)
}
