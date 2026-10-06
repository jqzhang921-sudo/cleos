import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Passwords live in keystore.properties (git-ignored), never in this file.
// Keys: storeFile, storePassword, keyAlias, keyPassword.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.cleo.cleos"
    // Current AndroidX/Compose artifacts refuse to be compiled against anything older.
    // targetSdk stays at 36 (Android 16, what the phone runs): compileSdk only decides
    // which APIs the code may reference, targetSdk decides runtime behaviour.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cleo.cleos"
        minSdk = 29
        targetSdk = 36
        versionCode = 97
        versionName = "0.41.20"
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("cleo") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val cleo = signingConfigs.findByName("cleo")
        // Debug and release are signed with the same key on purpose. Android refuses to
        // install an update signed by a different key, and the only way past that refusal
        // is uninstalling, which wipes the app's data. With one key there is no refusal.
        debug {
            if (cleo != null) signingConfig = cleo
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (cleo != null) signingConfig = cleo
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // What a TA noted, and letters, wait for their time here (ai/Later.kt).
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
