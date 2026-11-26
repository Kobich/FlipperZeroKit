import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

val prototypeKey = providers.environmentVariable("FLIPPER_PROTOTYPE_KEYSTORE")
    .map { rootProject.file(it) }.getOrElse(File(System.getProperty("user.home"), ".android/debug.keystore"))
val prototypeCertificate = rootProject.file("signing/prototype-sha256.txt").readText().trim()
val verifyPrototypeSigning = tasks.register("verifyPrototypeSigning") {
    inputs.file(prototypeKey)
    inputs.property("certificate", prototypeCertificate)
    doLast {
        val keyFile = inputs.files.singleFile
        check(keyFile.isFile) { "Provide the existing Android debug.keystore or FLIPPER_PROTOTYPE_KEYSTORE. A new signing key must not be generated automatically." }
        val store = KeyStore.getInstance("JKS")
        keyFile.inputStream().use { store.load(it, "android".toCharArray()) }
        val certificate = checkNotNull(store.getCertificate("androiddebugkey")) { "Prototype signing alias is missing" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(fingerprint == inputs.properties["certificate"]) { "Prototype certificate mismatch. Refusing to publish an APK that cannot update the test channel." }
    }
}
tasks.matching { it.name == "preDebugBuild" || it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyPrototypeSigning) }

android {
    namespace = "dev.flipper.zerokit.sample"
    ndkVersion = libs.versions.ndk.get()
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.flipper.zerokit.sample"
        minSdk = 23
        targetSdk = 37
        versionCode = 16
        versionName = "1.15-prototype"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("prototype") {
            storeFile = prototypeKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("prototype") }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("prototype")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
    }
}

kapt { correctErrorTypes = true }

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":flipper"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.compose)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
