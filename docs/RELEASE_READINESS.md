# Release status

## Published: Android 0.2.0/code11 and Windows 0.2.0

The combined [v0.2.0 release](https://github.com/addy1243/Echo/releases/tag/v0.2.0) is public/stable with both binaries, matching complete source and SHA256SUMS. Public asset sizes/digests match the reviewed files. All 197 JVM tests, Android build/lint/shrinking, 16 Room checks, seven candidate/platform checks and native packaged-module playback/MSI checks pass. No physical phone was accessed or Windows installer accepted. See [the complete 0.2.0 record](releases/0.2.0.md); older records below remain unchanged.

## Published: 0.1.9 / code10

**[v0.1.9 is public and stable](https://github.com/addy1243/Echo/releases/tag/v0.1.9).** Public latest-release metadata and both uploaded artifact sizes/SHA-256 digests were rechecked during this post-release documentation update. Publication is complete.

The [complete 0.1.9 release record](releases/0.1.9.md) is the reference for downloads, installation, hashes, immutable source tag/commit, signing identity, feature changes, executed checks and known limits. Matching GPL and dependency sources accompany the APK; later documentation does not replace uploaded artifacts.

Release checks passed: 89 JVM tests, application lint with zero errors, real emulator queue/UI regressions, screen-off song download/integrity/offline playback and public code8→code10 background cancellation/download/installer-intent checks. Published code10 was installed in place on the emulator and passed Profile/notification navigation.

Post-release acceptance: **Android Google sign-in works**, owner-confirmed 2026-10-27. Separate Drive consent, real playlist transfers/two-device sync, phone/OEM/Doze behavior and normal system-installer completion remain unverified. Force-stop/reboot/range-resume support is not included; desktop development is separate.

## Historical release — 0.1.8 / code9

The owner authorized public GitHub updates and approved GPL-3.0-or-later on 2026-10-26. GitHub write access was verified privately through the configured Git credential helper. Credentials and private signing keys are never printed, committed or uploaded.


**Published:** [v0.1.8](https://github.com/addy1243/Echo/releases/tag/v0.1.8), release ID424426308, exact commit2bb31c605a2cf31ec2ea5151ab26ea8a18e20bdb. GitHub verified APK size29814483 and SHA-256a2daf451c21939144ef05a1a2479fa174444463f3608ccc1eb38f12a0eb33bc9; matching source ZIP size94016740 and SHA-25607922fb5ffcd2d6fd59e724a61c7d120256095d9ce84d1debd0c1f843383a5c0. Both are public release assets. The unauthenticated latest-release API exposes the stable release correctly.

**Live verification passed:** installed code8 downloaded the public code9 APK through the real updater, verified checksum/size/package/SDK/signer, staged it privately and returned the normal permission/installer intent. The installed version stayed8 because the test did not accept system installation. Log: build/release/live-github-update-emulator.log (one passing live scenario,7.36seconds). The final GPL code9 candidate also passed all five updater Android checks against code8 (build/release/gpl-updater-candidate-emulator.log). No phone install/reset or credential/signing-key publication occurred.

### Release artifacts

- APK: build/release/EchoMusic-9.apk, package dev.echo.music, version0.1.8/code9, minimum SDK26.
- APK SHA-256: a2daf451c21939144ef05a1a2479fa174444463f3608ccc1eb38f12a0eb33bc9.
- Signing certificate SHA-256: c71752f76fa5e39cc5c653688e7abfdbf108900fd6334202f4e641b7884531ad, identical to code8 and the existing preview signing identity. The published APK remains debug-signed to preserve in-place updates; a differently signed APK will be rejected.
- Source: build/release/EchoMusic-9-source.zip was produced from the reviewed release commit by scripts/package_corresponding_source.py. It includes the exact Echo sources/build scripts, pinned NewPipe/desugaring repository sources, runtime dependency source archives and a URL/SHA-256 manifest. No caches, app-data captures or signing keys are included.
- Preserved baseline: build/release/EchoMusic-8-baseline.apk, used only for isolated emulator upgrade checks.

### Distribution resolution

The original missing-license/Google-SDK compatibility issue was resolved with the owner's approved root LICENSE/COPYING grant and replacement of the bundled proprietary identity SDKs. Google authentication now delegates to Android AccountManager and the installed Google authenticator, verifies Google userinfo and separately requests Drive access. The Android package/signing OAuth registration remains required; no Web audience or secret is embedded. Live owner sign-in/consent/Drive transfers remain unverified and must not be claimed as validated.

The final runtime inventory contains135 components with their actual artifact hashes and original licenses; no Google identity/credentials runtime SDK remains. The APK packages full GPL/Apache/Rhino/Protobuf/nanojson/desugaring terms plus69 original embedded legal notices. Settings → Licenses & source displays them. Third-party music/artwork is not relicensed by Echo's GPL grant.

### Verification

Build,86 JVM tests, debug lint and an unsigned minified-release/shrinking build passed. The published artifact is the same-key debug APK, not the unsigned release output. All eight platform-auth guard/profile/migration, license display, history, search/queue and sleep UI/service scenarios passed in targeted emulator checks; the notice test uses parent lazy-list scrolling to reach offscreen files. No real owner account selection or consent was automated.

Source input preflight and both script self-checks passed. Source packaging refuses a dirty worktree, a mismatched inspected inventory, changed source/artifact hashes or missing source for runtime classes. Its source manifest records the exact release commit.

Logs: build/release/platform-auth-build.log, build/release/gpl-platform-final-build.log, build/release/gpl-final-validation.log, build/release/gpl-feature-emulator.log (seven pass; notice scrolling assertion superseded by build/release/notices-ui-emulator.log), and build/release/updater-emulator.log. Source and notice generation are documented in THIRD_PARTY_NOTICES.md.

### Publication and live verification

Create the stable/latest v0.1.8 release at the exact reviewed commit, upload EchoMusic-9.apk and matching EchoMusic-9-source.zip, and verify GitHub's public SHA-256/size metadata before making the release available. No token or signing key may be attached. The updater ignores drafts/prereleases/equal or older versions and verifies download bytes, package, SDK and signer before installer handoff.

LiveGitHubUpdateTest explicitly opts in to the real public endpoint/download using installed code8 and expected code9/hash. It validates private staging and the normal installer/permission intent without accepting system installation. Android permission and user confirmation remain required. Public-release/live results are recorded above and in integration status. Actual system installer completion, physical-phone updates and owner Google account/Drive access remain unverified.