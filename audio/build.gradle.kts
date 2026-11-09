plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.madclock.audio"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.madclock.audio"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDir("../shared/src/main/java")
    testOptions { unitTests.isIncludeAndroidResources = true }
}
apply(from = rootProject.file("gradle/release-signing.gradle"))
dependencies {
    constraints {
        testImplementation("com.google.guava:guava:33.7.2-jre") {
            because("Fix GHSA-xxph-c9ww-hj94 in the test runtime only")
        }
        testImplementation("org.bouncycastle:bcprov-jdk18on:1.85") {
            because("Fix published Bouncy Castle advisories in the test runtime only")
        }
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
tasks.withType<Test>().configureEach {
    javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
