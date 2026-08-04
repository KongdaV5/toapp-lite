plugins {
    id("com.android.application")
}

android {
    namespace = "com.kongda.toapplite.builder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kongda.toapplite.builder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

val generatedTemplateAssets = layout.buildDirectory.dir("generated/templateAssets")
val shellUnsignedApk = project(":shell").layout.buildDirectory.file(
    "outputs/apk/release/shell-release-unsigned.apk"
)

val prepareTemplateApk by tasks.registering(Copy::class) {
    dependsOn(":shell:assembleRelease")
    from(shellUnsignedApk)
    into(generatedTemplateAssets)
    rename { "template.apk" }
}

android.sourceSets.getByName("main").assets.srcDir(generatedTemplateAssets)

tasks.configureEach {
    if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
        dependsOn(prepareTemplateApk)
    }
}

dependencies {
    implementation("com.github.MuntashirAkon:apksig-android:4.4.0")
}
