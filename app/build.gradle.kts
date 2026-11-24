plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "dev.nodroid.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        applicationId = "dev.nodroid.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        // Release signing is only applied when CI provides a keystore via env vars.
        if (System.getenv("NODROID_KEYSTORE_PATH") != null) {
            create("release") {
                storeFile = file(System.getenv("NODROID_KEYSTORE_PATH"))
                storePassword = System.getenv("NODROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NODROID_KEY_ALIAS")
                // PKCS12 keystores (keytool's default) do not support a key
                // password different from the store password — keytool warns
                // "Different store and key passwords not supported for PKCS12
                // KeyStores. Ignoring user-specified -keypass value." The CI
                // keystore is PKCS12, so the key password IS the store
                // password; using NODROID_KEY_PASSWORD here fails signing
                // with "Given final block not properly padded".
                keyPassword = System.getenv("NODROID_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("NODROID_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-engine"))
    implementation(project(":core-database"))
    implementation(project(":core-security"))
    // RoomDatabase is a supertype of AppDatabase, used directly by NodRoidApp
    // and MainActivity; :core-database declares Room as implementation, so it
    // is not visible here.
    implementation(libs.androidx.room.runtime)
    implementation(project(":core-nodes"))
    implementation(project(":feature-canvas"))
    implementation(project(":feature-runtime"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
