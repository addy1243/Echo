# GitHub updates

Android source defaults are **0.2.1/code12**, published alongside Windows 0.2.1; see [the release record](releases/0.2.1.md) and [Windows update contract](DESKTOP_UPDATES.md). Earlier records describe prior public releases.


Previous stable release: [Echo Music 0.1.9 / code10](https://github.com/addy1243/Echo/releases/tag/v0.1.9), with same-key `EchoMusic-10.apk` and exact corresponding source at commit `2c32cb911541dd384f5abff11ff952cdc4a6dc8b`. The real updater on the code8 emulator passed public code10 background completion, cancellation, checksum/identity verification and normal installer handoff without accepting installation. See [CHANGELOG.md](../CHANGELOG.md) and [integration evidence](INTEGRATION_STATUS.md).

Echo checks the latest published stable release from `addy1243/Echo` once per 24 hours at startup. Settings → Check for updates checks immediately. No notification is shown when there is no newer compatible asset. Drafts and prereleases are ignored. A public repository is required; no GitHub token or client secret is included in the APK.

When a newer release is found, Echo offers **Update app** / **Later**, displays release notes and download progress, and offers **Install update** once verification completes. If Android requires **Allow from this source**, grant it to Echo, return and tap **Install update**. Android requires your installation confirmation; this app does not silently install APKs or bypass device restrictions.

Version0.1.9/code10 adds background update downloads. An installed older build must complete its existing update procedure once to obtain this behavior.

See the [0.1.9 release record](releases/0.1.9.md) for downloads, hashes, installation and verification limits. Later documentation does not alter the published source tag or assets.

## Publishing a future update

1. Increase the Android integer `versionCode` above the installed version. Current source defaults are code **11**, version **0.2.0**. `versionName` is the label; update ordering uses `versionCode`. Check the built APK's output metadata too: an older output directory may still contain an APK built with earlier version defaults.
2. Build/sign the APK with the **same signing certificate** as the installed app. Keep your key safe and outside Git. A fresh CI-generated debug key will not update an existing installation. The current preview APK is debug-signed; a release signed with a different key cannot replace it in place. Export the library before any intentional signing-key migration; this updater does not uninstall or erase your library.
3. Rename the APK to **`EchoMusic-<versionCode>.apk`**, for example `EchoMusic-10.apk` for an update to code 9.
4. Upload it as a release asset under [addy1243/Echo Releases](https://github.com/addy1243/Echo/releases), add notes, and publish as the latest stable release. Source ZIP/TAR files are not APK updates. GitHub's asset metadata must include its SHA-256 digest; if an older asset lacks that digest, upload it again.
5. Open the older Echo build and choose Settings → Check for updates. Publishing source commits alone does not publish an APK.

Example preview build (still uses this machine's existing debug key):

```powershell
.\gradlew.bat :app:assembleDebug '-PechoVersionCode=10' '-PechoVersionName=0.1.9'
Copy-Item app/build/outputs/apk/debug/app-debug.apk EchoMusic-10.apk
```

For a release build, use Android Studio's signed APK flow and the original signing key. The 0.1.8 release was published with owner authorization; future releases require their own authorized publication and newer version code.

## Integrity and recovery

Echo accepts only HTTPS GitHub release assets from its configured repository, enforces a maximum size, checks exact size and SHA-256, and verifies APK package name, expected/newer version code, minimum Android SDK and signing certificates before offering installation. Redirects are restricted to GitHub's download hosts. The APK is staged in private storage and shared read-only through a non-exported FileProvider. Incomplete downloads never replace a verified pending APK.

Cancellation closes the HTTP call. Failed downloads can be retried; GitHub rate-limit cooldowns are respected. A verified pending APK survives process death and the unknown-sources permission round trip; startup rechecks package/signers/version before offering it again. Completed/stale pending APKs are cleaned up after the installed version catches up. User-started downloads run in a dataSync foreground service with progress and Cancel in an Android notification. Hide the dialog or leave Echo while downloading; reopening Echo restores the verified update. Notification visibility requires notification permission. Completion never starts the installer in the background. Transfers do not resume after force-stop, process termination or reboot; retry starts from the beginning. Android/OEM limits still apply.

## Earlier updater verification snapshot

The GitHub repository was verified public; `/releases/latest` returned HTTP404 because there is no published release yet. Therefore live GitHub download and in-place installer completion require a published newer signed APK. **Four new JVM tests passed** for naming, URL/digest/size boundaries, identity/version/signers and cancelling a blocked response body. **Four Android checks passed on the connected Android16 phone**: JSON release selection, real APK signer reading/pinning, FileProvider isolation, and popup/progress/cancel/install/retry callbacks. The first UI attempt failed because the phone was dozing; it passed after the user unlocked the screen. Build and lint pass. Version0.1.1/code2 was installed in place, retaining app data.

References: [GitHub release assets and digests](https://docs.github.com/en/rest/releases/assets), [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller).
