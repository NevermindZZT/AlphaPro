import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

fun getGitCommitCount(): Int {
    return try {
        val stdout = ByteArrayOutputStream()
        project.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            standardOutput = stdout
        }
        stdout.toString().trim().toInt()
    } catch (_: Exception) {
        1
    }
}

val versionMajor = 1
val versionMinor = 0
val versionPatch = 0
val commitCount = getGitCommitCount()

val computedVersionName = "$versionMajor.$versionMinor.$versionPatch.$commitCount"
// Format: 1 00 00 0007 -> 100000007 (Major * 100_000_000 + Minor * 1_000_000 + Patch * 10_000 + commitCount)
val computedVersionCode = versionMajor * 100_00_0000 + versionMinor * 100_0000 + versionPatch * 10000 + commitCount

// Android/Compose regression tests are deliberately excluded from ordinary JVM test runs.
val androidUiRegression = providers.gradleProperty("androidUiRegression")
    .map { it.toBoolean() }
    .getOrElse(false)

android {
    namespace = "com.letter.alphapro"
    compileSdk = 37

    val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
    val releaseKeyFile = rootProject.file("keystore/release.jks")
    val hasReleaseKey = releaseKeyFile.exists()

    val keystoreProperties = Properties().apply {
        if (keystorePropertiesFile.exists()) {
            load(FileInputStream(keystorePropertiesFile))
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeyFile
                storePassword = keystoreProperties.getProperty("storePassword") ?: System.getenv("ALPHA_KEYSTORE_PASSWORD") ?: ""
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: System.getenv("ALPHA_KEY_ALIAS") ?: "letter"
                keyPassword = keystoreProperties.getProperty("keyPassword") ?: System.getenv("ALPHA_KEY_PASSWORD") ?: ""
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.letter.alphapro"
        minSdk = 26
        targetSdk = 37
        versionCode = computedVersionCode
        versionName = computedVersionName
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
        buildConfig = true
    }
    if (androidUiRegression) {
        sourceSets.getByName("test").java.srcDir("src/androidRuntimeTest/java")
        testOptions {
            unitTests.isIncludeAndroidResources = true
            unitTests.all {
                val testHome = rootProject.file(".gradle/robolectric")
                it.systemProperty("user.home", testHome.absolutePath)
                it.systemProperty("maven.repo.local", testHome.resolve("repository").absolutePath)
                it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
                it.systemProperty("sun.net.client.defaultConnectTimeout", "15000")
                it.systemProperty("sun.net.client.defaultReadTimeout", "60000")
                it.doFirst { testHome.mkdirs() }
            }
        }
    }
    packaging {
        // AGP 8.4 cannot guarantee 16KB ZIP alignment for mmap-loaded native libraries.
        // Compress/extract them; each decoder ELF is separately linked with 16KB LOAD alignment.
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:bluetooth"))
    implementation(project(":core:location"))
    implementation(project(":core:ptp"))
    implementation(project(":core:heif"))
    implementation(project(":core:ftp"))

    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.exifinterface)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation("junit:junit:4.13.2")
    if (androidUiRegression) {
        testImplementation("org.robolectric:robolectric:4.12.2")
        testImplementation("androidx.compose.ui:ui-test-junit4")
        debugImplementation("androidx.compose.ui:ui-test-manifest")
    }
    debugImplementation(libs.androidx.ui.tooling)
}
