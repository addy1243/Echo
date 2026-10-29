plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}
val spotifyClientId = providers.gradleProperty("spotifyClientId").orElse("").get()
require(spotifyClientId.isEmpty() || spotifyClientId.matches(Regex("[a-zA-Z0-9]{32}"))) { "spotifyClientId must be the public 32-character Spotify app ID" }
android {
    namespace = "dev.echo.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.echo.music"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("echoVersionCode").orElse("13").get().toInt()
        versionName = providers.gradleProperty("echoVersionName").orElse("0.2.2").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].assets.srcDir(rootProject.file("licenses"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}
kapt { correctErrorTypes = true }
dependencies {
    coreLibraryDesugaring(libs.android.desugar)
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":playback"))
    implementation(platform(libs.compose.bom))
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.navigation.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material)
    implementation(libs.compose.icons)
    implementation(libs.coil)
    implementation(libs.browser)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.test)
    androidTestImplementation(libs.androidx.runner)
}
