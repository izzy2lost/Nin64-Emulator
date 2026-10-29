import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseKeystorePropertyNames = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

fun hasReleaseKeystoreProperties(): Boolean =
    releaseKeystorePropertyNames.all { !releaseKeystoreProperties.getProperty(it).isNullOrBlank() }

fun releaseKeystoreProperty(name: String): String =
    releaseKeystoreProperties.getProperty(name)
        ?: throw GradleException("Missing $name in ${releaseKeystorePropertiesFile.path}")

android {
    namespace = "com.izzy2lost.nin64"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.izzy2lost.nin64"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.2"

        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "x86_64"
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf("-DANDROID_STL=c++_shared", "-DNIN64_VERSION=$versionName")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystoreProperties()) {
                storeFile = rootProject.file(releaseKeystoreProperty("storeFile"))
                storePassword = releaseKeystoreProperty("storePassword")
                keyAlias = releaseKeystoreProperty("keyAlias")
                keyPassword = releaseKeystoreProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
            assets.directories.add("${rootDir}/third_party/mupen64plus-libretro-nx/mupen64plus-core/data")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.3"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.register<Exec>("buildMupen64PlusNextAndroid") {
    group = "native"
    description = "Builds the vendored Mupen64Plus-Next libretro core for packaged Android ABIs and copies it into app/src/main/jniLibs."
    workingDir = rootDir
    commandLine("bash", "${rootDir}/scripts/build_mupen64plus_next_android.sh")
}

tasks.named("preBuild") {
    dependsOn("buildMupen64PlusNextAndroid")
}

tasks.matching { it.name in setOf("assembleRelease", "bundleRelease") }.configureEach {
    doFirst {
        val missingProperties = releaseKeystorePropertyNames
            .filter { releaseKeystoreProperties.getProperty(it).isNullOrBlank() }
        if (missingProperties.isNotEmpty()) {
            throw GradleException(
                "Missing release signing properties in ${releaseKeystorePropertiesFile.path}: " +
                    missingProperties.joinToString(", ")
            )
        }

        val keystoreFile = rootProject.file(releaseKeystoreProperty("storeFile"))
        if (!keystoreFile.isFile) {
            throw GradleException("Release keystore does not exist: ${keystoreFile.path}")
        }
    }
}

dependencies {
    implementation("com.google.oboe:oboe:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.0.1")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("com.android.billingclient:billing:9.0.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
