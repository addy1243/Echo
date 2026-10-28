# Firebase library sync

The 0.2.1 candidate adds a separate, opt-in RTDB channel for live player snapshots and remote controls. It uses the same Firebase identity and project; Firestore remains the library backend. See [connected playback setup, tested rules and limits](CONNECTED_PLAYBACK.md). The owner supplied the RTDB URL and published its tested rules. Live native-player control and anonymous denial pass; final cross-platform acceptance is recorded in the connected-playback document.

Firebase is Echo's primary optional account-sync backend in the published Android/Windows 0.2.0 release. Normal Android-emulator Google/Firebase sign-in and canonical Android/desktop/Firestore library equality passed; automatic sync is enabled while both apps are open. Existing local libraries and legacy Drive snapshots remain intact. No physical phone was accessed. Historical setup/check results below are labeled by their stage.

## Configured project and setup

The owner supplied public Web configuration for **echo-85da4** (project number **886330954520**). Both clients use that project. It differs from the original Google OAuth project, and an executed Firebase check rejected the original Desktop client with an OAuth audience/project mismatch. The owner created a replacement Desktop client in echo-85da4; import its downloaded JSON through Echo's configuration action. Changing clients clears old tokens before fresh login. The imported JSON stays private and Windows DPAPI protects its saved copy.

1. Open [Firebase Console](https://console.firebase.google.com/) and select **echo-85da4**. A Firebase project is the same underlying Google Cloud project with Firebase services enabled. Desktop OAuth clients must belong to this project, or be explicitly safelisted as described in [Google's cross-project setup](https://support.google.com/firebase/answer/6401008). Existing Android AccountManager credentials also need real Firebase acceptance verification; desktop login does not establish Android acceptance.
2. In **Build → Authentication**, choose **Get started**, open **Sign-in method**, enable **Google**, select the project support email, and save. Keep the Cloud project's testing/test-user configuration appropriate for the accounts that will sign in.
3. In **Build → Firestore Database**, create the **default Cloud Firestore database** in Native/Standard mode, select the intended region, and start with production/locked rules. Do not enable public test-mode access. No Firebase Storage or Realtime Database is needed for this library-sync milestone.
4. In **Project settings → General**, register a **Web app** such as Echo client configuration. Hosting is unnecessary. Copy only its `projectId` and `apiKey` configuration values for Echo's build setup. The REST clients do not require a Web UI or Firebase JavaScript SDK.
5. Replace Firestore's initial deny-all rules with the reviewed [Echo rules](../firebase/firestore.rules) when the client build is ready. The prepared CLI configuration is [firebase/firebase.json](../firebase/firebase.json). Deployment is separate from local tests and has not been performed by the setup files.

The Firebase Web API key and project ID identify the client project; they are not service-account credentials and do not grant database access by themselves. Database access requires a Firebase user ID token and the owner-only rules. Do not put a service-account JSON, Admin SDK private key, Google Desktop OAuth client JSON, or access/refresh token into source control or either application binary. Keep the downloaded Desktop OAuth JSON private; Echo's existing Desktop importer protects its saved copy with Windows DPAPI.

Sources: [Firebase and Google Cloud projects](https://firebase.google.com/docs/projects/learn-more), [enable Google Authentication](https://firebase.google.com/docs/auth/android/google-signin), [create Firestore](https://firebase.google.com/docs/firestore/quickstart), [Firebase configuration](https://firebase.google.com/docs/projects/learn-more#config-files-objects), [Firestore REST authentication](https://firebase.google.com/docs/firestore/use-rest-api).

## Account and storage boundary

Both platforms exchange their existing verified Google OAuth access token for a Firebase user session. Firebase Authentication must return the expected verified Google account. Firestore uses that Firebase UID, rather than a user-supplied email address, as the ownership boundary. This connection requires no Drive consent. The existing GPL-compatible Android platform sign-in and Desktop PKCE flow remain; the shared Firebase transport uses the existing HTTP/JSON dependencies and does not bundle a proprietary identity SDK or an Admin SDK.

The first implementation stores one bounded, compressed library document at:

```text
users/{firebaseUid}/sync/library-v1
```

Its exact Firestore fields are `uid`, `format: echo-library-state`, `version: 1`, `encoding: gzip-base64`, and `payload`. Rules permit only a verified owner to get/create/update that exact document, require the exact envelope, and cap the encoded payload at 716,800 characters. List, delete, other users, unexpected fields and other document paths are denied. The app additionally caps expanded JSON and validates the inner schema; rules cannot inspect compressed content.

The inner version-2 state includes playlists with ordered duplicate occurrences, favorites/unlikes, stable listening-event identities, playlist-deletion markers and a history-clear epoch. Local audio paths, downloaded audio, temporary stream URLs and credentials are excluded. Local track identities are opaque and installation-qualified, so another installation cannot resolve them as an unrelated local file. History sync uses actual recorded listening time; it does not infer plays from seeking.

Cloud writes require the previously read Firestore update time (or require that the document does not yet exist). A concurrent edit must be fetched and merged again; it must not be overwritten blindly. Oversized libraries stop with a readable error and retain local data. This bounded single-document format is an initial scope limit, not unlimited storage; split snapshots only if real library sizes require it.

The migration must ask before the first upload of an installation's existing library/history. An existing Drive grant or successful Google login alone is not consent to upload old history. A failed Firebase setup or transfer leaves local libraries and prior Drive snapshots available. No audio transfer, live device presence, playback command channel, synchronized audio, or output routing is implemented merely by enabling Firebase.

## Local rules check

The rules test uses only the local Firestore emulator and a `demo-` project. It has no production credentials or project data. The test has no npm dependencies and uses Node's built-in test runner and HTTP client.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
firebase emulators:exec --only firestore --project demo-echo-rules --config firebase/firebase.json 'node --test firebase/firestore.rules.test.mjs'
```

Executed on 2026-10-27 with installed Firebase CLI 14.15.2, Node 22 and a locally downloaded Firestore emulator. The scenario passed 15 access/schema assertions: valid owner create/read, unauthenticated/other-user/unverified denial, ownership mismatch, unexpected schema/extra fields, empty/oversized payload, collection listing, document deletion and unsupported-path denial. Evidence: `build/firebase-rules.log`.

No rules were deployed and no real Firebase project data was read or written by this emulator check. Live Firebase sign-in, security-rule deployment and real Android/desktop transfers still require the configured project and explicit executed verification.

## Executed client checks — 2026-10-27

### Live library roundtrip and project consolidation

Published 0.2.0/code11 was installed in place on the emulator after the public-updater check. Its successful Firebase sync marker/sign-in persisted and its normal library again matched desktop and Firestore canonically (`build/release-020-installed-sync-equality.log`). No physical phone was accessed.

**Final normal-app acceptance:** after creating the replacement Android client in echo-85da4, the owner completed Google sign-in and Firebase sync through Echo's normal emulator UI. Echo's own account/sync markers confirm success and automatic sync enabled. Its sanitized baseline was compared with desktop and Firestore: canonical equality passed for playlists, favorites, history and saved tracks (`build/firebase-sync-and-android-icon-final.log`). Desktop automatic sync is also enabled. This supersedes earlier pending Android-login states below. Verification remains emulator-only; no physical phone was accessed or updated.

The owner authorized actual library sync and emulator-only Android checks. The real desktop library was uploaded through `DesktopFirebaseSync`, then compared canonically with the Firestore document. Automatic desktop sync is enabled for that account while Echo is open. Isolated Android Room received matching playlists, favorites, history, saved tracks and duplicate occurrences through the real Firebase transport. Android uploaded one owned verification playlist; desktop received and renamed it; Android received the rename and removed only that playlist; desktop received the deletion. Final desktop/cloud equality passed and unrelated favorites/history/playlists were preserved. Evidence: `build/firebase-desktop-first-sync.log`, `build/firebase-android-cloud-download.log`, `build/firebase-android-upload-owned.log`, `build/firebase-desktop-receive-and-rename.log`, `build/firebase-android-receive-desktop-edit.log`, `build/firebase-android-owned-cleanup.log`, `build/firebase-convergence-and-msi-final.log`.

The Android live fixture used a short-lived, non-refreshable Firebase session delivered to the test app's private cache and consumed/deleted before use; its host handoff file was removed afterward. It used its own Room database and did not open the Android application's real library. This verifies Android persistence/transport and cross-platform merge, **not Android AccountManager login or the physical phone's library**.

The owner chose a single project, **echo-85da4**, instead of an external-client safelist. Google rejected a duplicate Android package/certificate registration. The owner authorized replacing the old Android client `234985319029-hvl39fi82nfmcnm5dhv40f0nd1q2ra9m.apps.googleusercontent.com` in the old project with a new Android client in echo-85da4, for package `dev.echo.music` and SHA-1 `19:76:9A:84:6D:ED:1F:EF:93:6E:48:42:3B:3E:D8:4A:24:14:B9:CA` (rechecked against the actual debug APK). Completion of that console operation and normal Android sign-in remain pending. Existing project data is preserved; do not delete either entire project during this credential migration.

The owner subsequently supplied replacement Android OAuth client **886330954520-37dt7c9ipgu32i04hkfcrk16cru03160.apps.googleusercontent.com** in echo-85da4. AccountManager uses the package/certificate registration rather than bundling that ID or a client secret. The replacement client's creation is owner-confirmed; actual emulator sign-in and Firebase exchange through the normal Android UI remain the next acceptance check.

Shared merge regressions now also preserve standalone saved tracks and prevent a deleted deterministic conflict copy from being recreated from the same stale edit. Audio files, local paths and credentials remain excluded.

The new-project Desktop OAuth JSON was imported into Windows-protected local credentials. Browser consent and the Firebase identity exchange succeeded. The subsequent Firestore read returned HTTP403; no library upload was attempted. This confirms authentication separately from database permissions. The project must have its default Firestore database and reviewed rules published before a transfer can be accepted as working.

After the owner published the prepared rules, the read-only check succeeded: Firebase identity verified and the account's library document does not yet exist (`build/firebase-rules-published-readiness.log`). The earlier HTTP403 is resolved for this account's document read. No upload was attempted, so this does not establish write permission or Android/desktop convergence.

An attempted CLI rules deployment failed during remote rules compilation with HTTP401 (invalid administrative authentication). It did not publish rules. The prepared file can be published through the project's Firestore Rules tab; never replace it with public test-mode rules to bypass the setup failure.

Android debug build, lint and data JVM checks passed. Thirteen isolated emulator Room tests cover Firebase apply/rollback, clear revisions, retained local paths, history migration and prior playlist/library behavior. The updated Profile UI check passed on emulator-5556. No physical phone was installed or reset; the public 0.1.9 APK does not acquire Firebase support until updated with a new compatible build.
