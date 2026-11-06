import java.util.Properties

plugins {
    id("com.android.application")
}

// 可选签名：本地放 keystore.properties，CI 里用 Secrets 注入同名字段。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun prop(key: String, env: String): String? =
    keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)

val ksPath = prop("storeFile", "KEYSTORE_FILE")
val ksFile = ksPath?.let { rootProject.file(it) }
val hasKeystore = ksFile != null && ksFile.exists()

android {
    namespace = "io.github.endlessworld.ohealthmcs"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.endlessworld.ohealthmcs"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = ksFile
                storePassword = prop("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = prop("keyAlias", "KEY_ALIAS")
                keyPassword = prop("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) {
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
}

dependencies {
    // libxposed 现代 API，仅编译期需要，运行时由 LSPosed 提供
    compileOnly("io.github.libxposed:api:102.0.0")
}
