plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.essentialqueue"
    // The Compose Material 3 alpha line is compiled against API 37 and requires apps to match.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.essentialqueue"
        minSdk = 35
        // Kept at 36 until the Android 17 behavior changes are checked against the editor flow.
        targetSdk = 36
        // CI derives these from the git tag (see .github/scripts/version.sh). Local builds use 0.1 and 100.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 100
        versionName = System.getenv("VERSION_NAME") ?: "0.1"
        // Diagnostic logs help while testing but have no place in a published release, so tag builds
        // turn them off with -PenableLogs=false. Every other build keeps them.
        val enableLogs = (project.findProperty("enableLogs") as String?)?.toBooleanStrictOrNull() ?: true
        buildConfigField("boolean", "ENABLE_LOGS", enableLogs.toString())

        // Package native libraries for 64-bit ARM only (Leitzphone / Xiaomi 17 Ultra are arm64-v8a devices).
        // The app itself is pure Kotlin, so this only matters for any native .so a dependency might ship,
        // but it keeps the APK single-ABI as requested and future-proofs against such a dependency.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // A fixed release key lets new builds install over old ones. Without it the build falls back to the
    // debug key, which differs per machine and per CI run.
    val ksPath = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        // Sign with APK Signature Scheme v3 only. Every supported device (minSdk 35) verifies v3, so v1 and v2 add nothing.
        getByName("debug") {
            enableV1Signing = false
            enableV2Signing = false
            enableV3Signing = true
        }
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                // Both PKCS#12 (.p12, .pfx) and JKS are accepted. SIGNING_STORE_TYPE overrides the guess.
                storeType = System.getenv("SIGNING_STORE_TYPE")?.takeIf { it.isNotBlank() }
                    ?: if (ksPath.endsWith(".p12", true) || ksPath.endsWith(".pfx", true)) "pkcs12" else "jks"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                // A .p12 key usually shares the store password, so the key password is optional.
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("SIGNING_STORE_PASSWORD")
                enableV1Signing = false
                enableV2Signing = false
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Material 3 Expressive is only available on the 1.5.0 alpha line and needs @OptIn.
    // Bump this version when a newer alpha or the stable release is out.
    implementation("androidx.compose.material3:material3:1.5.0-alpha29")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
}
