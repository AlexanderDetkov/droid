import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.adetkov.droid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.adetkov.droid"
        minSdk = 34
        targetSdk = 37
        versionCode = 2
        versionName = "0.9.1"
        externalNativeBuild { ndkBuild { abiFilters += listOf("arm64-v8a", "x86_64") } }  // the CPUs the APKs are split by
    }

    // Release signing from keystore.properties (storeFile, storePassword, keyAlias, keyPassword), kept out of git.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // libturn.so (src/main/cpp): Smart Turn on sherpa-onnx's own onnxruntime
    ndkVersion = "30.0.16248370"  // r30, as on GitHub's runners
    externalNativeBuild { ndkBuild { path = file("src/main/cpp/Android.mk") } }
    packaging {
        jniLibs.excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")  // the JNI library doesn't use them
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true  // android.util.Log in Runner
    }
    // One APK per CPU: phones, and x86_64 emulators and Chromebooks (a universal APK would carry both native libraries).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
}

// sherpa-onnx isn't on Maven: fetch its release AAR once, checked against this SHA-256.
abstract class Download : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun run() {
        val out = output.get().asFile
        val bytes = URI(url.get()).toURL().readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(sha == sha256.get()) { "${url.get()}: SHA-256 is $sha, expected ${sha256.get()}" }
        out.writeBytes(bytes)
    }
}

val sherpaOnnx = tasks.register<Download>("downloadSherpaOnnx") {
    val version = "2.0.2"
    url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
    sha256 = "6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e"
    output = layout.projectDirectory.file("libs/sherpa-onnx-$version.aar")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(files(sherpaOnnx))  // on-device speech; Setup downloads the models
    implementation(libs.commons.compress)  // unpacks the voice models (tar.bz2)
    implementation(libs.androidx.webkit)  // pages the agent makes: a message channel only Droid's pages can use

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
