import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "dev.flipper"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core)
    api(libs.kotlinx.coroutines.android)
    api(libs.protobuf.javalite)
    testImplementation(libs.junit)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().configureEach {
            builtins { create("java") { option("lite") } }
        }
    }
}
