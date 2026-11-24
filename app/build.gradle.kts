import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val rustCrateDir = rootProject.file("rust/vibemis_core")
val rustGeneratedJniDir = layout.buildDirectory.dir("generated/rustJniLibs")

android {
    ndkVersion = "27.0.12077973"
    compileSdk = 37
    namespace = "com.ineedmypills.vibemis"

    defaultConfig {
        applicationId = "com.ineedmypills.vibemis"
        minSdk = 26
        targetSdk = 35

        versionName = "0.1.0"
        versionCode = 1

        ndk.debugSymbolLevel = "FULL"

        resValue(
            "string",
            "obtainium_app_url",
            "https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.ineedmypills.vibemis%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fineedmypills%2FVibemis%22%2C%22author%22%3A%22ineedmypills%22%2C%22name%22%3A%22Vibemis%22%7D"
        )
    }

    buildFeatures {
        buildConfig = true
        resValues = true
        compose = true
    }

    sourceSets {
        getByName("main") {
            java.directories.add("src/main/kotlin")
            kotlin.directories.add("src/main/kotlin")
            jniLibs.directories.add("build/generated/rustJniLibs")
        }
        getByName("test") {
            java.directories.add("src/test/kotlin")
            kotlin.directories.add("src/test/kotlin")
        }
    }

    compileOptions {
        encoding = "UTF-8"
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    lint {
        disable += "MissingTranslation"
        lintConfig = file("lint.xml")
    }

    bundle {
        language {
            enableSplit = false
        }
        density {
            enableSplit = false
        }
    }

    signingConfigs {
        val storeFilePath = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
        val storePasswordEnv = System.getenv("SIGNING_STORE_PASSWORD")
        val keyAliasEnv = System.getenv("SIGNING_KEY_ALIAS")
        val keyPasswordEnv = System.getenv("SIGNING_KEY_PASSWORD")
        if (storeFilePath != null &&
            !storePasswordEnv.isNullOrEmpty() &&
            !keyAliasEnv.isNullOrEmpty() &&
            !keyPasswordEnv.isNullOrEmpty()
        ) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_label", "Vibemis (Debug)")
            isMinifyEnabled = false
            buildConfigField("boolean", "LOG_VERBOSE", "true")
        }
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            resValue("string", "app_label", "Vibemis")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "LOG_VERBOSE", "false")
        }
        create("perf") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".perf"
            resValue("string", "app_label", "Vibemis")
            buildConfigField("boolean", "LOG_VERBOSE", "false")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

val buildRustCore = tasks.register("buildRustCore") {
    group = "build"
    description = "Compiles the pure-Rust vibemis_core native library via Cargo"
    inputs.dir(rustCrateDir.resolve("src"))
    inputs.file(rustCrateDir.resolve("Cargo.toml"))
    outputs.dir(rustGeneratedJniDir)

    doLast {
        val outDir = rustGeneratedJniDir.get().asFile
        outDir.mkdirs()

        val rustupProc = ProcessBuilder("rustup", "target", "list", "--installed")
            .redirectErrorStream(true)
            .start()
        val installedTargets = rustupProc.inputStream.bufferedReader().readLines().map { it.trim() }.toSet()
        rustupProc.waitFor()

        val targetAbiMap = mapOf(
            "aarch64-linux-android" to Triple("arm64-v8a", "aarch64-linux-android29-clang", "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"),
            "armv7-linux-androideabi" to Triple("armeabi-v7a", "armv7a-linux-androideabi29-clang", "CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER"),
            "x86_64-linux-android" to Triple("x86_64", "x86_64-linux-android29-clang", "CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"),
            "i686-linux-android" to Triple("x86", "i686-linux-android29-clang", "CARGO_TARGET_I686_LINUX_ANDROID_LINKER")
        )

        val sdkDir = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: File(System.getProperty("user.home"), "Android/Sdk").absolutePath
        val ndkBinDir = File(sdkDir, "ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin")
        var builtAnyAndroidTarget = false

        for ((rustTarget, abiInfo) in targetAbiMap) {
            val (abiName, clangBinary, linkerEnvVar) = abiInfo
            val linkerFile = File(ndkBinDir, clangBinary)
            if (linkerFile.exists()) {
                if (rustTarget !in installedTargets) {
                    val addTargetProc = ProcessBuilder("rustup", "target", "add", rustTarget)
                        .inheritIO()
                        .start()
                    check(addTargetProc.waitFor() == 0) { "Failed to install rustup target $rustTarget" }
                }
                val pb = ProcessBuilder("cargo", "build", "--release", "--target", rustTarget)
                    .directory(rustCrateDir)
                    .inheritIO()
                pb.environment()[linkerEnvVar] = linkerFile.absolutePath
                pb.environment()["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
                check(pb.start().waitFor() == 0) { "Cargo build failed for target $rustTarget" }
                val soFile = rustCrateDir.resolve("target/$rustTarget/release/libvibemis_core.so")
                val destAbiDir = File(outDir, abiName).apply { mkdirs() }
                soFile.copyTo(File(destAbiDir, "libvibemis_core.so"), overwrite = true)
                builtAnyAndroidTarget = true
            }
        }

        check(builtAnyAndroidTarget) {
            "No Android NDK clang linker found in $ndkBinDir; cannot build libvibemis_core.so"
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildRustCore)
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    debugImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.okhttp)

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)

    testImplementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testImplementation(libs.compose.ui.test.junit4)
}
