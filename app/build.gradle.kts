import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ★ 发布签名（2026-10-01）：从源码根目录的 keystore.properties 读取。
//   为什么必须换掉 debug 签名：debug keystore 是公开的通用文件 —— 任何人拿它签一个
//   同包名的 APK，在已安装设备上都能被当成「同一签名的升级包」装上；应用商店也一律拒收。
//   keystore.properties / wmusic-release.jks / 签名信息.txt 三件套一起备份，丢了就再也
//   无法给已发布的应用出升级包。
//   文件缺失时回退 debug 签名（保证别人 clone 下来还能跑），并打一条警告。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}
val hasReleaseKey = keystorePropsFile.exists() &&
    !keystoreProps.getProperty("storePassword").isNullOrBlank()

android {
    namespace = "com.ncm.watch"
    compileSdk = 35

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                // storeFile 写成相对源码根目录的路径，换机器只要拷这两个文件即可
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true   // API 26~27 需要 v1 才能装
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.ncm.watch"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            // 手表流畅度关键：release 关闭 debuggable + R8 优化
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("WMusic: 未找到 keystore.properties，release 退回 debug 签名（不可用于发布）")
                signingConfigs.getByName("debug")
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    // ★ media3-session 与 media3-exoplayer 必须**同版本共存**（2026-10-01 对齐 OPPO 手表音乐包）：
    //   前者只提供 MediaSession / MediaSessionService 这套「给系统看的门面」，
    //   后者提供实际解码播放的 ExoPlayer；两者共用同一套 media3-common，
    //   版本号必须完全一致，否则 common 会出现两份、运行时 cast 崩溃。
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
