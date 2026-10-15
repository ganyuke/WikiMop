import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.nextcolor.wikimop"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.nextcolor.wikimop"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.1"
    }

    // The release key never lives in the repo. It comes from keystore.properties
    // (local builds, gitignored) or RELEASE_KEYSTORE* env vars (CI). Without
    // either, release builds are signed with the debug key so they still install.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val keystorePath = System.getenv("RELEASE_KEYSTORE") ?: keystoreProps.getProperty("storeFile")
    val keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = keystorePassword
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias", "nextcolor")
                keyPassword = keystorePassword
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Writes the screen renders to app/build/screenshots when the tests run.
            it.systemProperty("roborazzi.test.record", "true")
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    // The Material 3 Expressive components (button groups, floating toolbars,
    // segmented lists) are in the 1.5 line, which the BOM does not select yet.
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.21.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
}
