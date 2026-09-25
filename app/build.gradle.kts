plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val enable_asan: Boolean by extra

android {
    namespace = "org.androidaudioplugin.ports.lv2.sfizz"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.androidaudioplugin.ports.lv2.sfizz"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = libs.versions.aap.lv2.get()

        ndk {
            // should we make it customizable? We skip others just to reduce build time.
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments ("-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                    "-DSFIZZ_USE_SNDFILE=0", "-DENABLE_LTO=off", "-DSFIZZ_VST=off",
                    "-DSFIZZ_LV2_UI=off", "-DSFIZZ_JACK=off", "-DSFIZZ_RENDER=off", "-DSFIZZ_SHARED=off",
                    "-DANDROID_STL=c++_shared", "-DAAP_ENABLE_ASAN=" + (if (enable_asan) "1" else "0"))
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    ndkVersion = libs.versions.ndk.get()

    buildTypes {
        debug {
            packaging.jniLibs.keepDebugSymbols.add("**/*.so")
        }
        release {
            isMinifyEnabled = false
            proguardFiles (getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path ("../native/CMakeLists.txt")
        }
    }
    buildFeatures {
        prefab = true
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = enable_asan
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        // Resource providers must be able to return seekable AssetFileDescriptors.
        noCompress += listOf("sfz", "wav", "flac", "ogg", "mp3", "txt", "md", "git", "gitattributes")
    }
}

dependencies {
    implementation (libs.activity.compose)
    implementation ("androidx.compose.material3:material3:1.4.0")
    implementation (libs.aap.lv2)
    implementation (project(":sfz-provider"))
    implementation (libs.aap.core)
    implementation (libs.aap.midi.device.service)
    implementation (libs.aap.ui.compose)
    implementation (libs.aap.ui.compose.app)
    implementation (libs.aap.ui.web)
    implementation (libs.aap.js.controller)
    androidTestImplementation (libs.aap.testing)
    androidTestImplementation (libs.androidx.rules)
    //  If you want to test aap-core and aap-lv2 locally, switch to these local references
    //  (along with settings.gradle.kts changes)
    /*
    implementation (project(":androidaudioplugin-lv2"))
    implementation (project(":androidaudioplugin"))
    implementation (project(":androidaudioplugin-midi-device-service"))
    implementation (project(":androidaudioplugin-ui-compose-app"))
    implementation (project(":androidaudioplugin-ui-web"))
    androidTestImplementation (project(":androidaudioplugin-testing"))
     */

    implementation (libs.androidx.appcompat)
    implementation (libs.startup.runtime)

    testImplementation (libs.junit)
    androidTestImplementation (libs.test.ext.junit)
}

val deleteLV2Manifests by tasks.registering(Delete::class) {
    delete("src/main/assets/lv2/sfizz.lv2")
}

tasks.configureEach {
    // FIXME: it's hacky, but `clean` didn't work.
    if (name == "externalNativeBuildCleanDebug" || name == "externalNativeBuildCleanRelease")
        dependsOn(deleteLV2Manifests)
}

afterEvaluate {
    // LV2 ttl resources are copied at CMake build.
    // Thus collecting assets must wait for the native build.
    // But Gradle does not know that it has to wait,
    // so declare task dependency here.
    tasks.named("mergeDebugAssets").configure { mustRunAfter("mergeDebugNativeLibs") }
    tasks.named("mergeReleaseAssets").configure { mustRunAfter("mergeReleaseNativeLibs") }
}
