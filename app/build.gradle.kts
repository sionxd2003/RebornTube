import java.time.LocalDate
import java.time.format.DateTimeFormatter

val calVerDate = LocalDate.now()
val calVerName = calVerDate.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
val calVerCode = calVerDate.format(DateTimeFormatter.BASIC_ISO_DATE).toInt()

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.reborntube"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.reborntube.app"
        minSdk = 24
        targetSdk = 35
        versionCode = calVerCode
        versionName = calVerName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    val media3 = "1.5.1"

    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-cast:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
