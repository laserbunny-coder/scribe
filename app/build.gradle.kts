plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every build on GitHub gets a higher number, so a new one installs over the old.
val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.laserbunny.scribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.laserbunny.scribe"
        minSdk = 28
        targetSdk = 35
        versionCode = build
        versionName = "0.1.$build"
    }

    // One fixed key, kept in the repo, so each new build installs over the
    // last one. It only signs this personal app; it isn't a secret of any account.
    signingConfigs {
        create("scribe") {
            storeFile = file("scribe.keystore")
            storePassword = "scribe-app"
            keyAlias = "scribe"
            keyPassword = "scribe-app"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("scribe")
        }
        debug {
            signingConfig = signingConfigs.getByName("scribe")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
