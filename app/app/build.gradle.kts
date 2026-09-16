import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ncnn publishes no Maven artifact, so fetch the Android prebuilt from its GitHub release.
// Only the arm64-v8a slice is unpacked (~17 MB); the full zip covers five ABIs.
val ncnnVersion = "20260526"
val ncnnRoot = layout.projectDirectory.dir(".cxx-deps/ncnn-$ncnnVersion-android-vulkan")

val downloadNcnn = tasks.register("downloadNcnn") {
    description = "Downloads and unpacks the ncnn Android Vulkan prebuilt"
    val url = "https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/" +
        "ncnn-$ncnnVersion-android-vulkan.zip"
    val outDir = ncnnRoot.asFile
    val prefix = "ncnn-$ncnnVersion-android-vulkan/arm64-v8a/"
    outputs.dir(outDir)
    doLast {
        if (outDir.resolve("lib/cmake/ncnn/ncnnConfig.cmake").exists()) return@doLast
        println("Downloading ncnn from $url")
        outDir.mkdirs()
        URI(url).toURL().openStream().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                    val target = outDir.resolve(entry.name.removePrefix(prefix))
                    target.parentFile.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }
}

android {
    namespace = "sh.emilien.RATapp"
    compileSdk {
        version = release(37)
    }
    // Must be installed via: sdkmanager "ndk;30.0.15729638"
    ndkVersion = "30.0.15729638"

    defaultConfig {
        applicationId = "sh.emilien.RATapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OpenCV's native lib alone is 33 MB per ABI, so ship one. This also
        // means the APK will not install on x86_64 emulators — on-device only.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DNCNN_ROOT=${ncnnRoot.asFile.absolutePath}"
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }
    androidResources {
        // ncnn mmaps the weights straight out of the APK, and LiteRT maps the
        // .tflite the same way — a compressed asset cannot be mapped at all.
        noCompress += listOf("bin", "param", "tflite")
    }
    packaging {
        // Shipped by both our native build and the OpenCV AAR.
        jniLibs.pickFirsts += "lib/*/libc++_shared.so"
    }
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(downloadNcnn) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.opencv)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.litert)
    implementation(libs.litert.api)
    implementation(libs.litert.gpu)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}