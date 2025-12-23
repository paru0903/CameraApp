
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.cameraapp"
    compileSdk = 34 // 36 だと未対応ライブラリがあるので 34 を推奨

    defaultConfig {
        applicationId = "com.example.cameraapp"
        minSdk = 24
        targetSdk = 34
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
        debug {
            // 必要に応じて追加
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // ---- Version Catalog 経由の既存依存関係 ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.play.services.maps)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // ---- CameraX（1.3.4 で統一）----
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")        // ★ 追加
    implementation("androidx.camera:camera-extensions:1.3.4")   // ★ 追加（HDR/NR/Portrait 等）

    // ---- ライフサイクル / コルーチン ----
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2") // ★ 追加
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3") // ★ 追加

    // ---- Activity KTX ----
    implementation("androidx.activity:activity-ktx:1.8.0") // 重複を排除して 1 本化

    // ---- OkHttp（WebSocket 対応）----
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // ★ 4.12.0 に統一
// （ネストされた dependencies {} は削除済み）
}