plugins {
    id("com.android.application")
}

val m8PackagingSpike = providers.gradleProperty("anvildeskM8PackagingSpike").orNull == "true"
val m8ProductionCandidate = providers.gradleProperty("anvildeskM8ProductionCandidate").orNull == "true"
require(!(m8PackagingSpike && m8ProductionCandidate)) { "M8 variants are mutually exclusive" }


val developmentKeystorePath = providers.environmentVariable("ANVILDESK_DEV_KEYSTORE").orNull
val developmentStorePassword = providers.environmentVariable("ANVILDESK_DEV_STORE_PASSWORD").orNull
val developmentKeyAlias = providers.environmentVariable("ANVILDESK_DEV_KEY_ALIAS").orNull
val developmentKeyPassword = providers.environmentVariable("ANVILDESK_DEV_KEY_PASSWORD").orNull
val developmentSigningValues = listOf(
    developmentKeystorePath,
    developmentStorePassword,
    developmentKeyAlias,
    developmentKeyPassword,
)
val developmentSigningRequested = developmentSigningValues.any { !it.isNullOrBlank() }
val developmentSigningComplete = developmentSigningValues.all { !it.isNullOrBlank() }

if (m8ProductionCandidate && !developmentSigningComplete) {
    error("M8 production candidate requires complete stable development signing")
}

if (developmentSigningRequested && !developmentSigningComplete) {
    error(
        "Stable development signing is only partially configured. " +
            "Set ANVILDESK_DEV_KEYSTORE, ANVILDESK_DEV_STORE_PASSWORD, " +
            "ANVILDESK_DEV_KEY_ALIAS, and ANVILDESK_DEV_KEY_PASSWORD together.",
    )
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

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    if (developmentSigningComplete) {
        signingConfigs {
            create("ciDevelopment") {
                storeFile = file(developmentKeystorePath!!)
                storePassword = developmentStorePassword
                keyAlias = developmentKeyAlias
                keyPassword = developmentKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (m8PackagingSpike) {
                applicationIdSuffix = ".m8spike"
                versionNameSuffix = "-m8-spike"
            }
            if (developmentSigningComplete) {
                signingConfig = signingConfigs.getByName("ciDevelopment")
            }
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir("build/generated/rootless-runtime/jniLibs")
    if (m8PackagingSpike || m8ProductionCandidate) {
        sourceSets.getByName("main").jniLibs.srcDir("build/generated/m8-x11/jniLibs")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:runtime"))
}
