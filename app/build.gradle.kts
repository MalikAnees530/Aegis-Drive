import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.malik.aegisdrive"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "aegis_drive.jks")
            storePassword = System.getenv("KEYSTORE_PWD")
            keyAlias = System.getenv("SIGNKEY_ALIAS")
            keyPassword = System.getenv("SIGNKEY_PWD")
        }
    }

    defaultConfig {
        applicationId = "com.malik.aegisdrive"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Tests annotated @LiveSms send a REAL SMS to the saved emergency contact.
        // Keep them out of `connectedAndroidTest` (and Android Studio's "run all tests")
        // so no one messages a real person by accident. Run them deliberately with:
        //   adb shell am instrument -w -e class <TestClass> \
        //       com.malik.aegisdrive.test/androidx.test.runner.AndroidJUnitRunner
        testInstrumentationRunnerArguments["notAnnotation"] = "com.malik.aegisdrive.LiveSms"

        // Load GROK_API_KEY from local.properties first, then Gradle/env fallbacks
        val localProps = Properties().apply {
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) {
                localPropsFile.inputStream().use { load(it) }
            }
        }
        val grokApiKey = localProps.getProperty("GROK_API_KEY")
            ?: providers.gradleProperty("GROK_API_KEY").orNull
            ?: System.getenv("GROK_API_KEY")
            ?: "YOUR_GROK_API_KEY_HERE"
        buildConfigField("String", "GROK_API_KEY", "\"$grokApiKey\"")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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

    kotlinOptions {
        jvmTarget = "11"
    }

    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation(libs.androidx.constraintlayout)

    // Declared explicitly (not just inherited via lifecycle-runtime-ktx) because the emergency
    // SMS dispatch uses Dispatchers.IO directly; a transitive-only coroutines dependency can
    // vanish on any unrelated library bump.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // CameraX Tools
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // TensorFlow Lite Tools
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // 🚀 NEW: MediaPipe Tools (Required for Hybrid LSTM Pipeline)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.17.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Navigation Component Tools
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("com.google.android.material:material:1.11.0")

    // 🚀 NEW: Android 12+ Splash Screen API
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Play Services Location
    implementation(libs.play.services.location)
}
