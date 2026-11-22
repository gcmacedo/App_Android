import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Endereço e chave do banco vêm do teste.properties (raiz do projeto).
// Esta versão é só de teste: aponta para o traffia-teste.
val teste = Properties().apply {
    val f = rootProject.file("teste.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.traffia.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.traffia.player.teste"
        minSdk = 26
        targetSdk = 35
        versionCode = 80
        versionName = "0.8.0-teste"
        buildConfigField("String", "SUPABASE_URL", "\"${teste.getProperty("supabase.url", "").trim()}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${teste.getProperty("supabase.key", "").trim()}\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Vídeo
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Rede
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR Code
    implementation("com.google.zxing:core:3.5.3")

    // GPS
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Câmera frontal + detecção de rosto (funciona sem internet)
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("com.google.mlkit:face-detection:16.1.7")
}
