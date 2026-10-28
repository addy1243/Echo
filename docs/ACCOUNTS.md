# Google accounts and Firebase library sync

Google sign-in is optional. Listening, imports, offline audio and local history work without it. Android **Profile** contains sign-in/out, **Connect Firebase / Sync now** and automatic sync; desktop exposes equivalent controls under **Devices & account**. Login and permission to upload the existing library are separate.

Firebase is the primary library backend in published Android **0.2.0 / code11** and Windows **0.2.0**. Published 0.2.1 separately adds opt-in [connected playback](CONNECTED_PLAYBACK.md); live emulator/native-Windows control acceptance passed. The immutable published [Android 0.1.9 / code10](releases/0.1.9.md) uses prior Drive playlist sync. Existing local libraries/Drive snapshots are preserved; source changes do not update a physical phone automatically.

## Authentication and project

Android uses AccountManager and Google's installed authenticator with identity-email scope and verified Google userinfo. No proprietary Google identity SDK is bundled. The chooser is restricted to Google accounts; Echo does not request GET_ACCOUNTS or enumerate unrelated accounts. Desktop uses system-browser PKCE, a single-use loopback callback and verified identity. Both exchange that identity credential for a Firebase session through existing HTTP dependencies.

The single project is **echo-85da4**, number **886330954520**. Android's public client is `886330954520-37dt7c9ipgu32i04hkfcrk16cru03160.apps.googleusercontent.com`, bound to package **dev.echo.music** and signing SHA-1 **19:76:9A:84:6D:ED:1F:EF:93:6E:48:42:3B:3E:D8:4A:24:14:B9:CA**. This was rechecked against the actual same-key debug APK; other signing certificates need their own registration. Never share/commit private signing keys.

Google's package/certificate registration is globally unique. The owner replaced the old project's Android client rather than maintaining an external-client safelist. Android does not bundle a Desktop secret or Web audience. See [desktop setup](DESKTOP_ACCOUNTS.md) and [Firebase setup/rules](FIREBASE.md).

Older SDK-based associations need fresh explicit account selection to grant platform visibility. Users handle passwords, unlock and normal consent prompts. Sign-out retains local playlists/favorites/history/files/downloads and does not remove the OS Google account or erase cloud data. Local libraries remain device-wide, so review the account before uploading.

## Sync behavior and privacy

Manual sync pulls and pushes. Automatic sync polls approximately once per minute while open/foreground and enabled; closed-app sync is not promised. Explicit first-upload confirmation covers existing library/history. Old Drive consent does not enable Firebase history upload.

The verified Firebase UID owns one bounded compressed Firestore document. Playlists preserve order and duplicate occurrences; saved-track metadata, favorites/unlikes, playlist deletion markers and stable history-event identities merge. History is bounded to 90 days/5,000 events. Confirmed history clearing advances an account-wide revision shared at the next sync. Audio/downloads, credentials, local paths and signed stream URLs are excluded. Device-local recordings use opaque installation-qualified IDs and need a playable source on each device.

Cloud writes compare the previously read document revision and retry conflicts a bounded number of times. Three-way merge preserves concurrent playlist edits as deterministic conflict copies; deletion/unlike markers and clear revisions prevent stale reintroduction. Local apply checks intervening edits and commits atomically. Oversized/failed transfers retain local data and report errors. Sync is not immutable backup history; export an independent metadata backup when needed.

Android protects Firebase sessions with encrypted credential storage; desktop uses Windows DPAPI. Public Firebase configuration identifies the project, without granting database access. Owner-only rules, tokens, host/body/schema validation and size limits enforce the boundary. No service-account/Admin SDK key is shipped. Exact schema/limits/recovery files: [FIREBASE.md](FIREBASE.md).

## Verification and troubleshooting

On **2026-10-27**, the owner completed normal Android-emulator Google/Firebase login after single-project consolidation. Echo's saved success marker and automatic-sync setting were verified. Its normal saved sync state, desktop and Firestore matched canonically. Live isolated Room roundtrips covered duplicates, a desktop edit and deletion convergence while preserving unrelated favorites/history. No physical phone was accessed or updated.

JVM/Room tests cover merge, account/bootstrap guards, stable event identity, clear revisions, tombstones, local paths and rollback. Real interactive revocation/expiry/account switching, physical-phone convergence and Windows installation retention remain separate acceptance checks.

For identity errors, check **echo-85da4 → Google Auth Platform → Clients/Audience**, package/signing registration, account restrictions and the actual Google error. For Firebase audience mismatch, verify the identity client's project. For Firestore denial, check the default database and reviewed owner-only rules; do not enable public test rules. Ordinary setup changes need no app-data reset or project deletion.

Old Drive registration counts are snapshot counts, not online presence. Firebase controls do not provide live device counts, remote playback commands, audio transfer or synchronized listening. See [connected-audio design](DESKTOP_SYNC_DESIGN.md).

References: [AccountManager](https://developer.android.com/reference/android/accounts/AccountManager), [Android OAuth registration](https://support.google.com/cloud/answer/15549257), [cross-project registration](https://support.google.com/firebase/answer/6401008), [Firestore REST authentication](https://firebase.google.com/docs/firestore/use-rest-api).
