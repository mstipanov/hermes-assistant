import java.util.Properties

plugins {
    id("com.android.application")
    // Reads app/google-services.json and wires the Firebase project (hermes-assistant-hr) into
    // the build. The build FAILS without that file, so a fresh clone has to bring it.
    id("com.google.gms.google-services") version "4.4.3"
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
        versionCode = 67
        versionName = "0.55"
        // The in-app updater reads <UPDATE_BASE_URL>/version and only installs APKs from that
        // host. The beta build type overrides it so betas update from the beta channel.
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://hermes-assistant.sting.hr\"")
        // Push relay that keeps the FCM registration token for this phone (POST /api/token).
        // Both build types use it: FCM tokens belong to one Firebase project, not to a channel.
        buildConfigField("String", "PUSH_BASE_URL", "\"https://push.sting.hr\"")
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
            isMinifyEnabled = true
            isShrinkResources = true
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
    implementation("com.google.firebase:firebase-messaging:24.1.2")
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

// AGP's Compose mapping producer asks for org.jetbrains.kotlin:compose-group-mapping:2.2.10,
// a version that was never published; align it with the Kotlin/Compose compiler used here.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name == "compose-group-mapping") {
            useVersion("2.4.20")
        }
    }
}

