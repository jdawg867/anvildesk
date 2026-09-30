plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.jdawg867.anvildesk"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.jdawg867.anvildesk"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:runtime"))
}
