---
name: echo-windows-shipping
description: Build, verify, and ship Echo releases with complete Windows laptop update and installer acceptance. Use before release versioning, packaging, GitHub assets, or Windows updater changes.
---

# Echo release and Windows shipping

Use this skill whenever an Echo release or update path changes. A release is complete only when the Git source, packages, public updater path, interactive installer, installed app, and preserved user data have been checked and recorded. A successful build or direct MSI install alone does not prove the Windows in-app update works.

## Current baseline and rules

- Current release: Android 0.2.4/code15 and Windows 0.2.4, from immutable source commit 0962c97a9d648fcbdcc92a5703d278c8bac601d3. Never replace its tag or assets.
- The working branch may lag v0.2.4 and still carry 0.2.3 defaults. Inspect its Git diff, source tag, version fields, and [docs/LOGS.md](../../../docs/LOGS.md) before selecting release content.
- Automatic signed-in foreground connected-playback participation and startup recovery remain unreleased candidate work. The v0.2.4 release includes the Windows scrollable queue.
- Publishing and production changes require explicit owner authorization. Prepare and review the exact artifacts first; do not infer permission to publish from a request to build or document them.
- Never blanket-stage a mixed checkout. Publish from the exact reviewed commit. Preserve signing keys, user data, credentials, source notices, and license obligations.
- Keep HTTPS, repository/host, size, SHA-256, APK signer/package, and MSI identity checks intact. Never add GitHub credentials to either app or log secrets.
- Android testing is emulator-only and installation is same-key/in-place. Windows installation is per-user and interactive; never silently install or clear app data.

## 1. Freeze reviewed source

1. Read [docs/IMP.md](../../../docs/IMP.md) and inspect Git status plus the full diff. Identify pre-existing edits and confirm which changes are part of this release.
2. Keep unrelated work out of the release. Do not use blanket Git add/reset/clean or a moving branch name as the source manifest.
3. Finish review and commit the exact source and notices before packaging. The source packager requires a committed ref. Record that commit and build both platforms from it.
4. Never reuse or replace an existing release tag or asset. Use a new monotonically newer release and preserve prior release records.

## 2. Set every version owner

For Android, increase Gradle property **echoVersionCode** above every published code and set matching **echoVersionName**. Latest published is code15/version0.2.4; this working branch may still default to code14/version0.2.3, so reconcile the release tag and installed baseline before changing it. The updater asset name is **EchoMusic-<versionCode>.apk**. Sign with the original installed-app certificate. This checkout does not declare a release signing key in app/build.gradle.kts; use the protected existing signing procedure and compare the APK signer before upload. Never substitute a debug or newly generated key.

For Windows, update both **DESKTOP_VERSION** in DesktopUpdater.kt and **packageVersion** in desktop/build.gradle.kts. They must be identical. Keep the MSI identity stable: ProductName **EchoMusic**, x64 architecture, UpgradeCode **{A810BA6C-3581-4E29-A09D-F344CA0230AD}**, per-user install under **%LOCALAPPDATA%\EchoMusicDesktop**, and user data under separate **%LOCALAPPDATA%\EchoMusic**. MSI version limits are major/minor at most 255 and patch at most 65535. Do not configure the installer to remove or overwrite user data.

## 3. Build and inspect

Use the project Gradle wrapper, Android Studio JBR/SDK 36, and project-local Gradle cache. Public Windows packaging uses the GPL-licensed Eclipse Temurin JDK 21.0.12.1+1 with jpackage. JBR can build/test the project but may not contain jpackage; a failed package invocation with JBR is not a Windows packaging failure.

Run relevant module tests, Android release build/lint/shrinking, desktop tests, and release-specific acceptance checks. Then build the MSI using Temurin. Typical commands:

- <code>.\gradlew.bat :core:test :data:test :playback:testDebugUnitTest :app:testDebugUnitTest :desktop:test</code>
- <code>.\gradlew.bat :app:assembleRelease :app:lintRelease</code>
- <code>.\gradlew.bat :desktop:createDistributable :desktop:packageMsi "-PdesktopJavaHome=&lt;absolute Temurin JDK 21 path&gt;"</code>
- <code>.\gradlew.bat :desktop:msiCheck "-PechoMsiPath=&lt;absolute MSI path&gt;" "-PechoMsiVersion=&lt;version&gt;"</code>

Confirm the APK package, SDK/version metadata, and original signer. Confirm the MSI reports EchoMusic, intended version, x64, and stable UpgradeCode. Inspect the MSI install directory and data separation from the artifact itself.

Keep the Temurin runtime and dependency/native source records and notices complete. Produce corresponding source from the exact committed ref with **scripts/package_corresponding_source.py** and validate the archive and manifest. Do not include local credentials, build caches, private data, or signing keys.

## 4. Verify GitHub assets

After explicit owner authorization, upload the reviewed APK/MSI/source under a new release with exact asset names. Verify each public asset URL, byte size, and SHA-256 against the uploaded local artifact and GitHub metadata. Keep the Windows MSI among the first 100 public releases until discovery supports bounded pagination. Do not upload dummy assets or mutate a prior release to make discovery pass.

The updater acceptance helper checks public Windows discovery/download without installation:

- <code>.\gradlew.bat :desktop:publishedWindowsUpdateCheck -PechoPublishedWindowsBaseline=&lt;old installed version&gt;</code>

A passing helper verifies public metadata, exact download, digest, and MSI identity. It does not replace installed-app UI acceptance.

## 5. Verify the real Windows laptop path

Use an installed baseline older than the release. Verify the running process path is **%LOCALAPPDATA%\EchoMusicDesktop\EchoMusic.exe** and the shortcut targets that installed copy. A project-generated portable image is separate and cannot prove MSI upgrade behavior.

1. Record the installed version and SHA-256 hashes of the existing library and DPAPI credential files, when present. Record hashes only; never print credential contents.
2. In the installed Echo app, open **App updates → Check for updates** and confirm it selects the new Windows version even if a newer Android-only release exists.
3. Download inside Echo. Confirm progress/cancellation and that the staged installer passes exact byte count, GitHub SHA-256, and native MSI identity.
4. Choose **Install** and handle the normal Windows Installer prompt interactively. Capture a safe verbose installer log and its real exit code. Do not call an opened installer success until Windows reports the expected result.
5. Close an old Echo process if it remains open. Relaunch from the existing Start-menu shortcut. Verify process path, installed file/configuration or MSI registration, and **App updates → Installed version** all show the new version.
6. Compare library and DPAPI credential hashes with the pre-install values. Confirm playlists remain visible. Installation is not accepted if user data or credentials changed unexpectedly.

Record these gates separately: discovery, in-app download, checksum/identity, installer launch/result, installed version after restart, and data/credential preservation. Direct browser/CLI download or a successful standalone MSI run is useful diagnosis but is not an end-to-end updater pass.

## 6. Diagnose failures without weakening safeguards

### Socket connection timeout

- Record the safe exception type/stage and time. Do not log tokens, authenticated URLs, key values, or private paths.
- Compare public release discovery, direct anonymous asset reachability, the updater acceptance helper, and the actual app download on the same network.
- If the helper/direct asset succeeds but the app times out, inspect the app networking path and timeout/redirect handling. If all fail, report the network limitation and retry later on a permitted network.
- Never disable TLS, host checks, exact size/hash checks, timeouts, cancellation, or MSI identity validation. Do not report Windows public download as passed while it still times out.

### Windows Installer error 1305/2203

A client-side checksum and file ACL do not prove the Windows Installer service can read the staged source. The 0.2.3 history records a staged-source failure (1305/2203, STG_E_FILENOTFOUND); its root cause remains unresolved. The v0.2.4 release record confirms MSI build identity but does not report a new end-to-end in-app update/install pass.

- Capture a verbose interactive log and exact MSI source path. Check the file still exists and matches the public byte count and digest; check relevant ACLs without printing secrets.
- Compare in-app staged installation with a verified copy from a user-chosen readable location. A copy fallback is not a fix unless the complete chooser and actual installer-service read pass end to end.
- Do not claim the handoff issue is fixed from unit coverage, a successful direct release-folder install, a copied file, or a 0 exit code from a different source path.
- Keep the staged file and installer protections. Do not use quiet install, elevated workarounds that bypass the intended per-user flow, manual uninstall, or library clearing.

## Completion report

Mark a release shipped only when the exact source, intended packages, public asset metadata, Windows discovery/download/installer/restart, and data preservation have evidence. State separately what passed, failed, was not run, or was externally blocked. If a required Windows step remains unresolved, call the release built/published only as applicable; do not call the updater end to end verified.

See [the 0.2.4 record](../../../docs/releases/0.2.4.md) for published evidence and limits.
