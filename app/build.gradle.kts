import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
}

// Release signing lives outside the repository (keystore.properties is
// gitignored). Without it `assembleRelease` produces an unsigned APK.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "hr.sting.hermes"
    compileSdk = 36

    defaultConfig {
        applicationId = "hr.sting.hermes"
        minSdk = 26
        targetSdk = 36
        versionCode = 48
        versionName = "0.48"
        // The in-app updater reads <UPDATE_BASE_URL>/version and only installs APKs from that
        // host. The beta build type overrides it so betas update from the beta channel.
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://hermes-assistant.sting.hr\"")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
        // Test channel: the same code and the same release key, so a beta installs over the
        // stable app (and back). The "-beta<n>" suffix only changes the version name, e.g.
        // 0.47-beta1; pass -PbetaNumber=2 for a second beta of the same version. The APK lands
        // in app/build/outputs/apk/beta, which the distributor serves as hermes-beta.sting.hr;
        // the stable channel scans apk/release only, so a beta can never become the stable
        // release. Every published beta needs a higher versionCode, or the updater skips it.
        create("beta") {
            initWith(getByName("release"))
            versionNameSuffix = "-beta" + (project.findProperty("betaNumber") as String? ?: "1")
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "UPDATE_BASE_URL", "\"https://hermes-beta.sting.hr\"")
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

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("Hermes-${output.versionName.get()}.apk")
        }
    }
}
