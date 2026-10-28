import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose") version "1.8.2"
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets.main {
        kotlin.srcDir("../data/src/main/java")
        kotlin.srcDir("../app/src/main/java")
        kotlin.include("dev/echo/desktop/**", "dev/echo/data/provider/YouTubeProvider.kt", "dev/echo/data/provider/YouTubeDownloader.kt",
            "dev/echo/data/provider/RecordingMatch.kt", "dev/echo/data/library/ListeningHistory.kt",
            "dev/echo/app/DiscoveryMix.kt", "dev/echo/app/ListeningPreferences.kt", "dev/echo/app/QueueActions.kt",
            "dev/echo/data/firebase/**", "dev/echo/app/account/CloudPlaylistSnapshot.kt",
            "dev/echo/app/account/PlaylistSyncMerge.kt", "dev/echo/app/account/SyncDeviceReport.kt",
            "dev/echo/data/spotify/PlaylistFile.kt", "dev/echo/data/spotify/SpotifyPlaylistLinks.kt",
            "dev/echo/data/provider/TrackMatcher.kt", "dev/echo/data/offline/OfflinePolicy.kt")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.newpipe.extractor)
    implementation("javax.inject:javax.inject:1")
    implementation("org.json:json:20240303")
    implementation("uk.co.caprica:vlcj:4.12.1")
    testImplementation(libs.junit)
}

sourceSets.main { resources.srcDir(rootProject.file("licenses")) }

compose.desktop {
    application {
        mainClass = "dev.echo.desktop.MainKt"
        providers.gradleProperty("desktopJavaHome").orNull?.let { javaHome = it }
        jvmArgs += listOf("--add-modules=jdk.httpserver")
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)
            packageName = "EchoMusic"
            packageVersion = "0.2.1"
            windows {
                iconFile.set(project.file("resources/echo.ico"))
                installationPath = "EchoMusicDesktop"
                upgradeUuid = "a810ba6c-3581-4e29-a09d-f344ca0230ad"
                menu = true
                shortcut = true
                perUserInstall = true
                dirChooser = false
            }
            modules("jdk.httpserver", "java.instrument", "java.net.http", "java.scripting", "jdk.dynalink", "jdk.unsupported")
        }
    }
}

tasks.register<JavaExec>("playbackCheck") {
    group = "verification"
    description = "Opt-in real VLC playback check using a generated temporary WAV (and YouTube with -PechoLiveYouTube=true)."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.echo.desktop.PlaybackCheckKt")
    jvmArgs("--add-modules=jdk.httpserver")
    if (providers.gradleProperty("echoLiveYouTube").orNull == "true") args("--youtube")
    if (providers.gradleProperty("echoPackagedRuntimeCheck").orNull == "true") {
        providers.gradleProperty("desktopJavaHome").orNull?.let { executable = "$it/bin/java.exe" }
        doFirst {
            val release = Properties().apply {
                layout.buildDirectory.file("compose/binaries/main/app/EchoMusic/runtime/release").get().asFile.inputStream().use(::load)
            }
            val modules = release.getProperty("MODULES").trim('"').split(' ')
            require(modules.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_.]*")) })
            jvmArgs("--limit-modules=${modules.joinToString(",")}")
        }
    }
}

tasks.register<JavaExec>("firebaseReadinessCheck") {
    group = "verification"
    description = "Opt-in saved-user Firebase login and read-only library readiness check; no library upload."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.echo.desktop.FirebaseReadinessCheckKt")
    jvmArgs("--add-modules=jdk.httpserver")
    providers.gradleProperty("echoDesktopOauthJson").orNull?.let { args("--import-and-sign-in", it) }
}

tasks.register<JavaExec>("connectedPlaybackLiveCheck") {
    group = "verification"
    description = "Explicit saved-account RTDB acceptance with two temporary native players; no library sync or installation."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.echo.desktop.ConnectedPlaybackLiveCheckKt")
    jvmArgs("--add-modules=jdk.httpserver")
    if (providers.gradleProperty("echoConnectedLocalOnly").orNull == "true") args("--local-only")
    else if (providers.gradleProperty("echoConnectedAndroidPeer").orNull == "true") args("--android-peer")
}

tasks.register<JavaExec>("cloudSyncCheck") {
    group = "verification"
    description = "Explicit real-account sync acceptance; may upload/apply library changes."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.echo.desktop.DesktopCloudSyncCheckKt")
    jvmArgs("--add-modules=jdk.httpserver")
    args(providers.gradleProperty("echoCloudAction").getOrElse("sync"))
    providers.gradleProperty("echoAndroidSnapshot").orNull?.let { args(rootProject.file(it).absolutePath) }
    providers.gradleProperty("echoVerifyPlaylist").orNull?.let {
        args(it, providers.gradleProperty("echoVerifyPresence").getOrElse("present"))
    }
}

tasks.register<JavaExec>("msiCheck") {
    group = "verification"
    description = "Read-only identity inspection of the built Windows MSI; never installs."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.echo.desktop.DesktopMsiCheckKt")
    args(providers.gradleProperty("echoMsiPath").getOrElse(layout.buildDirectory.file("compose/binaries/main/msi/EchoMusic-0.2.1.msi").get().asFile.absolutePath),
        providers.gradleProperty("echoMsiVersion").getOrElse("0.2.1"))
}

tasks.register<JavaExec>("publishedWindowsUpdateCheck") {
    group = "verification"
    description = "Opt-in live GitHub Windows update check; no download or installation."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.echo.desktop.DesktopUpdateCheckKt")
    providers.gradleProperty("echoPublishedWindowsBaseline").orNull?.let { args("--download-from-version", it) }
}
