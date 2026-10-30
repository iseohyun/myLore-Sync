plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.iseohyun.mylore.sync"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.iseohyun.mylore.sync"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ZXing
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // ML Kit Document Scanner & Korean OCR
    implementation(libs.mlkit.document.scanner)
    implementation(libs.mlkit.text.korean)

    // Coil Image Loading
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("grantPermissions") {
    group = "verification"
    description = "Grants MANAGE_EXTERNAL_STORAGE and Notification Listener permissions to the wirelessly connected device via ADB."
    doLast {
        val packageName = "com.iseohyun.mylore.sync"
        println("Granting permissions to wireless ADB device...")
        ProcessBuilder("adb", "shell", "appops", "set", packageName, "MANAGE_EXTERNAL_STORAGE", "allow").start().waitFor()
        ProcessBuilder("adb", "shell", "cmd", "notification", "allow_listener", "$packageName/.collector.MyloreNotificationListener").start().waitFor()
        ProcessBuilder("adb", "shell", "pm", "grant", packageName, "android.permission.READ_CONTACTS").start().waitFor()
        ProcessBuilder("adb", "shell", "pm", "grant", packageName, "android.permission.WRITE_CONTACTS").start().waitFor()
        println("Wireless device permissions granted successfully!")
    }
}
