plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.ai_assistant"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ai_assistant"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.graphics.path)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // OkHttp para chamadas das APIs (STT Whisper, LLM OpenAI-like, TTS áudio)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // LibADB Android e provedor de segurança Conscrypt / BouncyCastle para pairing ADB / Shell Daemon
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.80")
    implementation("org.conscrypt:conscrypt-android:2.7.0")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}