import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing material is never committed. Provide it locally in
// android/keystore.properties (git-ignored) or leave it out and build debug only.
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !ksProps.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.chun.lingzhu"
    compileSdk = 35

    defaultConfig {
        // Fork-specific applicationId: this build cannot overwrite or upgrade the
        // official com.chun.lingzhu app, even if someone reuses its signing key.
        applicationId = "com.chun.lingzhu.opensource"
        minSdk = 26
        targetSdk = 35
        versionCode = 217
        versionName = "2.0.9"

        manifestPlaceholders["appLabel"] = "Ling"
        manifestPlaceholders["translateLabel"] = "Ling 翻译"
        manifestPlaceholders["shareLabel"] = "Ling 识题"

        buildConfigField("String", "CHANNEL", "\"开源版\"")
        buildConfigField("String", "APP_NAME", "\"Ling\"")
        // No preset endpoints or keys are compiled into this fork. Users fill their own.
        // No built-in update channel: a public fork must not pull the maintainer's
        // official APKs. Users may still enter their own manifest URL in settings.
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Fail closed: never fall back to the debug key for a release artifact.
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }

    // Duplicate META-INF entries collide across the bundled jars and fail the
    // packager. The license and notice texts are shipped inside the APK instead,
    // as assets/LICENSE, assets/THIRD_PARTY_NOTICES.md and assets/licenses/.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/LICENSE.md",
                "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md",
                "META-INF/DEPENDENCIES", "META-INF/ASL2.0",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
                "META-INF/versions/**", "**/module-info.class"
            )
        }
    }
}

// A release build without signing configuration must fail loudly, while debug
// builds stay usable without any secrets.
gradle.taskGraph.whenReady {
    val needsSigning = allTasks.any { t ->
        t.project == project && t.name in setOf("assembleRelease", "bundleRelease", "installRelease")
    }
    if (needsSigning && !hasReleaseSigning) {
        throw GradleException(
            "Release signing is not configured. Create android/keystore.properties with " +
                "storeFile/storePassword/keyAlias/keyPassword, or build with assembleDebug. " +
                "A release APK is never signed with the debug key."
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // JVM unit tests need a real org.json; the android.jar stub returns defaults.
    testImplementation("org.json:json:20240303")

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.poi:poi-scratchpad:4.1.2") {
        exclude(group = "log4j", module = "log4j")
        exclude(group = "commons-logging", module = "commons-logging")
    }
}
