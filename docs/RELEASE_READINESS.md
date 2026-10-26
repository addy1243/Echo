# GitHub update release — 0.1.8 / code9

The owner authorized public GitHub updates and approved GPL-3.0-or-later on 2026-10-26. GitHub write access was verified privately through the configured Git credential helper. Credentials and private signing keys are never printed, committed or uploaded.

## Prepared release

- APK: build/release/EchoMusic-9.apk, package dev.echo.music, version0.1.8/code9, minimum SDK26.
- APK SHA-256: a2daf451c21939144ef05a1a2479fa174444463f3608ccc1eb38f12a0eb33bc9.
- Signing certificate SHA-256: c71752f76fa5e39cc5c653688e7abfdbf108900fd6334202f4e641b7884531ad, identical to code8 and the existing preview signing identity. The published APK remains debug-signed to preserve in-place updates; a differently signed APK will be rejected.
- Source: build/release/EchoMusic-9-source.zip will be produced from the reviewed release commit by scripts/package_corresponding_source.py. It includes the exact Echo sources/build scripts, pinned NewPipe/desugaring repository sources, runtime dependency source archives and a URL/SHA-256 manifest. No caches, app-data captures or signing keys are included.
- Preserved baseline: build/release/EchoMusic-8-baseline.apk, used only for isolated emulator upgrade checks.

## Distribution resolution

The original missing-license/Google-SDK compatibility issue was resolved with the owner's approved root LICENSE/COPYING grant and replacement of the bundled proprietary identity SDKs. Google authentication now delegates to Android AccountManager and the installed Google authenticator, verifies Google userinfo and separately requests Drive access. The Android package/signing OAuth registration remains required; no Web audience or secret is embedded. Live owner sign-in/consent/Drive transfers remain unverified and must not be claimed as validated.

The final runtime inventory contains135 components with their actual artifact hashes and original licenses; no Google identity/credentials runtime SDK remains. The APK packages full GPL/Apache/Rhino/Protobuf/nanojson/desugaring terms plus69 original embedded legal notices. Settings → Licenses & source displays them. Third-party music/artwork is not relicensed by Echo's GPL grant.

## Verification

Build,86 JVM tests, debug lint and an unsigned minified-release/shrinking build passed. The published artifact is the same-key debug APK, not the unsigned release output. All eight platform-auth guard/profile/migration, license display, history, search/queue and sleep UI/service scenarios passed in targeted emulator checks; the notice test uses parent lazy-list scrolling to reach offscreen files. No real owner account selection or consent was automated.

Source input preflight and both script self-checks passed. Source packaging refuses a dirty worktree, a mismatched inspected inventory, changed source/artifact hashes or missing source for runtime classes. Its source manifest records the exact release commit.

Logs: build/release/platform-auth-build.log, build/release/gpl-platform-final-build.log, build/release/gpl-final-validation.log, build/release/gpl-feature-emulator.log (seven pass; notice scrolling assertion superseded by build/release/notices-ui-emulator.log), and build/release/updater-emulator.log. Source and notice generation are documented in THIRD_PARTY_NOTICES.md.

## Publication and live verification

Create the stable/latest v0.1.8 release at the exact reviewed commit, upload EchoMusic-9.apk and matching EchoMusic-9-source.zip, and verify GitHub's public SHA-256/size metadata before making the release available. No token or signing key may be attached. The updater ignores drafts/prereleases/equal or older versions and verifies download bytes, package, SDK and signer before installer handoff.

LiveGitHubUpdateTest explicitly opts in to the real public endpoint/download using installed code8 and expected code9/hash. It validates private staging and the normal installer/permission intent without accepting system installation. Android permission and user confirmation remain required. Publication/live results are appended to integration status after those actions succeed; this preparation document alone does not claim they occurred.