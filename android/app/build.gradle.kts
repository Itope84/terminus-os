plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.terminus.rec"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.terminus.rec"
        minSdk = 29
        targetSdk = 34
        versionCode = 1319
        versionName = "2.5.2"
        // App strings are English only; this drops the locale strings that
        // material/appcompat/car ship in resources.arsc.
        resourceConfigurations += listOf("en")
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            // Shrink only (see proguard-rules.pro): same package, same debug signing, updates in place.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // Android Auto: templated messaging screen (Ask Terminus + recent chats).
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // github.com/aesrael/agent-webview-android at a pinned commit (JitPack). Bump the hash to update.
    implementation("com.github.aesrael.agent-webview-android:agent-webview:26c2694")
    testImplementation("junit:junit:4.13.2")
    // JVM unit tests can't use Android's stubbed org.json: real jar, tests only.
    testImplementation("org.json:json:20231013")
}
