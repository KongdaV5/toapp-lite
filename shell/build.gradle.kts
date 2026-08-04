plugins {
    id("com.android.application")
}

android {
    namespace = "com.kongda.toapplite.shell"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.toapplite.generated.placeholderaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
