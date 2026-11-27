import java.util.Properties
import java.io.FileInputStream
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.vovchenko.webdavsync.build.StripDav4jvmFactories

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "org.vovchenko.webdavsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.vovchenko.webdavsync"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // Security audit finding #15: obfuscate/shrink the release build (was previously
            // shipped unobfuscated). Verified with a real `./gradlew assembleRelease` afterward.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    // dav4jvm 5.0.0 is class-file 65 (Java 21). The compiler and unit tests run on 21
    // so they can load it. App sources still emit JVM 17 bytecode.
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The UnifiedPush connector depends on the JVM `tink`. Credentials use tink-android
// (Android Keystore). Resolve every `tink` request to that one artifact.
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.google.crypto.tink:tink"))
            .using(module("com.google.crypto.tink:tink-android:1.24.0"))
    }
}

// Output APK as WebDAV-sync-{versionName}.apk (e.g. WebDAV-sync-1.0.0.apk).
// AGP 9 puts versionName on each output, not on the variant.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { name -> "WebDAV-sync-${name ?: "0"}.apk" }
            )
        }
    }
}

val dav4jvmUpstream = configurations.register("dav4jvmUpstream") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = true
}

val strippedDav4jvmJar = layout.buildDirectory.file("dav4jvm/dav4jvm-no-caldav.jar")

val stripDav4jvmFactories = tasks.register("stripDav4jvmFactories") {
    inputs.files(dav4jvmUpstream)
    outputs.file(strippedDav4jvmJar)
    doLast {
        val jar = inputs.files.files.single { it.name.startsWith("dav4jvm-") && it.extension == "jar" }
        StripDav4jvmFactories.rewriteJar(jar, strippedDav4jvmJar.get().asFile)
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("com.google.crypto.tink:tink-android:1.24.0")

    // WebDAV-Push transport (no FCM). Tink resolution: see configurations.all above.
    implementation("org.unifiedpush.android:connector:3.3.6")
    // Built-in "Google Play (FCM)" push service: FOSS, no Firebase SDK; works only where Play services exist.
    implementation("org.unifiedpush.android:embedded-fcm-distributor:3.1.0")

    // OkHttp 5.5.0 is the engine. The redirect and PUT-replay checks passed on it.
    // Do not add OkHttp 4 beside it.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-auth:3.6.0")
    // The published jar is rewritten by stripDav4jvmFactories. Its transitives are declared
    // here so they stay in Gradle's conflict resolution instead of arriving as a second copy.
    add("dav4jvmUpstream", "com.github.bitfireAT:dav4jvm:5.0.0") {
        exclude(group = "org.ogce", module = "xpp3")
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation(files(strippedDav4jvmJar).builtBy(stripDav4jvmFactories))
    implementation("com.google.guava:guava:33.7.2-jre")
    implementation("io.ktor:ktor-client-encoding:3.6.0")

    implementation("androidx.documentfile:documentfile:1.1.0")

    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.hilt:hilt-work:1.4.0")
    ksp("androidx.hilt:hilt-compiler:1.4.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
