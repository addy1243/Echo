plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}
android {
    namespace = "dev.echo.playback"
    compileSdk = 36
    defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    coreLibraryDesugaring(libs.android.desugar)
    implementation(project(":data"))
    api(project(":core"))
    api(libs.media3.session)
    implementation(libs.media3.player)
    implementation(libs.media3.hls)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test)
    androidTestImplementation(libs.androidx.runner)
}
