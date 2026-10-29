plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Production builds include the decoder. Only explicit -PnativeHeif=false builds omit it.
val nativeHeifEnabled = providers.gradleProperty("nativeHeif").map { it.toBooleanStrict() }.getOrElse(true)

android {
    namespace = "com.letter.alphapro.core.heif"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        if (nativeHeifEnabled) {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    if (nativeHeifEnabled) {
        ndkVersion = "26.1.10909125"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation("junit:junit:4.13.2")
}
