plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}
android {
    namespace = "ru.ypmn.sdk"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx)
    implementation(libs.retrofit.scalars)
    implementation(libs.okhttp)
    implementation(libs.androidx.core)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
}
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.onemantooo.android-sdk"
            artifactId = "sdk"
            version = "0.0.1"
            afterEvaluate { from(components["release"]) }
        }
    }
}
