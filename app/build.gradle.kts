import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val mobiFixtureAssets = layout.buildDirectory.dir("generated/androidTest/mobi-fixture")
val copyMobiFixtureForAndroidTest by tasks.registering {
    outputs.dir(mobiFixtureAssets)
    doLast {
        val directory = mobiFixtureAssets.get().asFile.apply { mkdirs() }
        val text = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Axiom MOBI test</title></head><body><p>Axiom local MOBI fixture validates text MOBI conversion, search, cleanup, and DRM rejection.</p></body></html>"
        val title = "Axiom MOBI test"
        val titleBytes = title.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val record0Offset = 78 + 16 + 2
        val record0Size = 16 + 232 + titleBytes.size
        val record1Offset = record0Offset + record0Size
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        fun u16(value: Int) = out.writeShort(value)
        fun u32(value: Long) = out.writeInt(value.toInt())
        val pdbName = title.toByteArray(Charsets.US_ASCII).copyOf(32)
        out.write(pdbName)
        u16(0); u16(0); repeat(4) { u32(0) }
        u32(0); u32(0)
        out.write("BOOK".toByteArray(Charsets.US_ASCII))
        out.write("MOBI".toByteArray(Charsets.US_ASCII))
        u32(0); u32(0); u16(2)
        u32(record0Offset.toLong()); out.writeByte(0); out.write(byteArrayOf(0, 0, 0))
        u32(record1Offset.toLong()); out.writeByte(0); out.write(byteArrayOf(0, 0, 0))
        u16(0)
        u16(1); u16(0); u32(textBytes.size.toLong()); u16(1); u16(4096); u16(0); u16(0)
        out.write("MOBI".toByteArray(Charsets.US_ASCII)); u32(232); u32(2); u32(65001); u32(1); u32(6)
        out.write(ByteArray(40) { 0xff.toByte() })
        u32(0xffffffffL); u32((16 + 232).toLong()); u32(titleBytes.size.toLong()); u32(9); u32(0); u32(0); u32(6)
        u32(0xffffffffL); u32(0xffffffffL); u32(0); u32(0xffffffffL); u32(0); u32(0)
        out.write(ByteArray(32)); u32(0xffffffffL)
        u32(0xffffffffL); u32(0); u32(0); u32(0)
        out.write(ByteArray(8))
        u16(1); u16(1)
        u32(0); u32(0xffffffffL); u32(0); u32(0xffffffffL); u32(0); u32(0); u32(0)
        u32(0xffffffffL); u32(0); u32(0); u32(0); u16(0); u16(0); u32(0xffffffffL)
        check(bytes.size() == record0Offset + 16 + 232)
        out.write(titleBytes)
        check(bytes.size() == record1Offset)
        out.write(textBytes)
        out.flush()
        directory.resolve("axiom-self-authored.mobi").writeBytes(bytes.toByteArray())
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "org.readera.openreadera"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "org.readera.openreadera"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir(mobiFixtureAssets)

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}
tasks.configureEach {
    if (name == "mergeDebugAndroidTestAssets") {
        dependsOn(copyMobiFixtureForAndroidTest)
    }
}



dependencies {
    // AndroidX & Lifecycle
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose & Material 3
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Stable native Ink authoring; Compose authoring is not needed for this overlay.
    implementation("androidx.ink:ink-authoring:1.0.0")
    implementation("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Coroutines & Datastore
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking & OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")

    // Google Play Services Auth (OAuth 2.0 / Google Sign-In)
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Storage Access Framework DocumentFile
    implementation("androidx.documentfile:documentfile:1.0.1")

    // WorkManager for background periodic synchronization
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // PDF editing and OCR
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // Bundled Latin model: reader and searchable-copy OCR work on first use without a download.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Testing
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.ink:ink-geometry:1.0.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
