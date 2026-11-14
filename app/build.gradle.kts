plugins {
    id("com.android.application")
}

val m8PackagingSpike = providers.gradleProperty("anvildeskM8PackagingSpike").orNull == "true"
val m8EmulatorTests = providers.gradleProperty("anvildeskM8EmulatorTests").orNull == "true"
val m8ProductionCandidate = providers.gradleProperty("anvildeskM8ProductionCandidate").orNull == "true"
val m8CompositeJniDiagnostic = providers.gradleProperty("anvildeskM8CompositeJniDiagnostic").orNull == "true"
require(!(m8PackagingSpike && m8ProductionCandidate)) { "M8 variants are mutually exclusive" }
require(!m8CompositeJniDiagnostic || (!m8PackagingSpike && !m8ProductionCandidate && !m8EmulatorTests)) {
    "Composite JNI diagnostic requires a dedicated ARM64 test-only build"
}


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

// This is only a transport-library packaging spike. Keep production package builds blocked
// until reviewed AnvilDesk JNI, Surface lifecycle and authenticated X11 tests exist.
if (m8ProductionCandidate) {
    error("M8 production candidate BLOCKED: functional JNI/Surface/X11 integration is not yet validated")
}

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0-dev"

        ndk {
            abiFilters += listOf(if (m8EmulatorTests) "x86_64" else "arm64-v8a")
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
            if (m8CompositeJniDiagnostic) {
                applicationIdSuffix = ".m8jnidiag"
                versionNameSuffix = "-m8-jni-diagnostic"
            }
            if (developmentSigningComplete) {
                signingConfig = signingConfigs.getByName("ciDevelopment")
            }
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir("build/generated/rootless-runtime/jniLibs")
    if (m8CompositeJniDiagnostic) {
        // Only the instrumentation APK can contain this native diagnostic.
        // Never add this path to the main source set or published APK.
        sourceSets.getByName("androidTest").jniLibs.srcDir(
            "build/generated/m8-composite-jni-diagnostic/androidTest/jniLibs"
        )
    }
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.18.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
