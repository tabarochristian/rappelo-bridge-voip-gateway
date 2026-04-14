import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.gateway"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.gateway"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default API configuration - override via local.properties
        buildConfigField("String", "API_BASE_URL", "\"https://api.gateway.local/\"")
        buildConfigField("String", "DEFAULT_STUN_SERVER", "\"34.35.42.181:3478\"")
        buildConfigField("int", "LOCAL_HTTP_PORT", "8080")
        buildConfigField("int", "COMMAND_POLL_INTERVAL_MS", "5000")
        buildConfigField("int", "MAX_QUEUE_SIZE", "5")
    }

    signingConfigs {
        create("release") {
            // Configure via local.properties:
            // RELEASE_STORE_FILE=path/to/keystore.jks
            // RELEASE_STORE_PASSWORD=password
            // RELEASE_KEY_ALIAS=alias
            // RELEASE_KEY_PASSWORD=password
            val properties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                properties.load(localPropertiesFile.inputStream())
                storeFile = properties.getProperty("RELEASE_STORE_FILE")?.let { path -> file(path) }
                storePassword = properties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = properties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = properties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            buildConfigField("boolean", "ENABLE_LOCAL_HTTP_SERVER", "true")
            buildConfigField("boolean", "ENABLE_CERT_PINNING", "false")
            buildConfigField("String", "LOG_LEVEL", "\"VERBOSE\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "ENABLE_LOCAL_HTTP_SERVER", "true")
            buildConfigField("boolean", "ENABLE_CERT_PINNING", "true")
            buildConfigField("String", "LOG_LEVEL", "\"INFO\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
        )
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // PJSIP AAR location
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    testOptions {
        unitTests.all {
            it.jvmArgs("-noverify")
        }
    }
}

dependencies {
    // PJSIP AAR - place pjsua2.aar in app/libs/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)
    implementation(libs.material)

    // Lifecycle
    implementation(libs.bundles.lifecycle)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Network
    implementation(libs.bundles.network)

    // NanoHTTPD
    implementation(libs.nanohttpd)

    // Security
    implementation(libs.androidx.security.crypto)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Testing
    testImplementation(libs.bundles.testing)
}

// KSP configuration for Room
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}
