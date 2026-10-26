# Google accounts and playlist sync

Google sign-in is optional. Listening, imports, offline audio and history work without an account. The Profile bottom tab contains Sign in with Google, Sign out, Connect Drive / Sync now, and an automatic sync switch. The device library remains after sign-out; connecting another account explicitly saves that existing library to the selected account. This is account-specific cloud storage, not separate local libraries.

## Platform account authentication

Echo uses Android AccountManager and the Google account provider already installed on the device. It does not bundle Google Play services auth, googleid or Credential Manager provider SDKs. The system account chooser is restricted to com.google accounts and grants visibility to the explicitly chosen account; Echo does not request GET_ACCOUNTS or enumerate unrelated accounts.

Sign-in requests the Google userinfo.email scope through the installed authenticator and verifies the resulting account through Google's fixed HTTPS userinfo endpoint. Only a verified email and stable Google subject are stored. Drive authorization requests the separate drive.appdata scope plus identity verification; its verified subject/email must match the signed-in account before any cloud merge or upload. Tokens and signed media URLs are never persisted or logged. HTTP401 invalidates the OS-cached token for a later bounded user retry. Automatic sync cannot run while a consent result is pending.

Older SDK-based sessions require an explicit new account choice after upgrading so Android can grant account visibility. Sign-out removes Echo's account association/automatic-sync setting; it never removes the device's Google account, local playlists, files or audio. It does not revoke existing Google consent or erase cloud snapshots. Process death during account selection/consent requires retrying the action.

## Owner console setup

1. Enable the Google Drive API in the Google Cloud project containing Echo's Android OAuth client. In **Google Auth Platform → Audience**, check the user type and publishing status. For personal testing with **External / Testing**, add each Google account that will connect Drive under **Test users → Add users**, then save. Creating an OAuth client alone does not complete this setup.
2. Register an Android OAuth client for package dev.echo.music and the SHA-1 of the certificate that signs the installed APK. Google documents AccountManager OAuth token requests using this package/certificate registration.
3. Current same-key preview certificate SHA-1: **19:76:9A:84:6D:ED:1F:EF:93:6E:48:42:3B:3E:D8:4A:24:14:B9:CA**. Recheck another machine/release signing identity; private keys must never be shared or committed.
4. The Web client created earlier is retained as an owner reference in gradle.properties, but the platform flow does not use a Web audience, secret, browser redirect, Desktop client or loopback workaround. No additional redirect URL is required for this AccountManager integration.
5. On an Android device with Google's account provider, explicitly select your account and approve identity/Drive access. Verify real authentication, revoked consent, switching accounts and two-device transfers before claiming live account sync is validated.

The platform flow is documented by Google and Android, and deterministic profile/selection/migration checks are included. It still needs a fresh real-account grant on the owner's setup. A successful build or visible sign-in button does not prove Google's authenticator accepts the registered client. A device without the Google account provider can use Echo locally and export/import library backups instead.

## Troubleshooting: Error 403 `access_denied`

On 2026-10-27 the owner reported Google's **Access blocked: Echo** screen with `403: access_denied`. The request details showed the registered Android client and `drive.appdata` alongside identity scopes. This establishes a failed live Google authorization attempt; it does not establish the cause or a successful Drive connection. Echo's client-side checks and emulator tests do not replace this live verification.

A likely cause is an External project in Testing whose selected account is missing from the test-user list. Check this first:

1. Open [Google Cloud Console](https://console.cloud.google.com/) and select the project containing the Android OAuth client shown in the error. Use the Android client's project, rather than assuming a separately created Web client belongs to the same project.
2. Open **Google Auth Platform → Audience** and inspect the publishing status. If it is **Testing**, find **Test users → Add users**.
3. Add the exact Google account selected in Echo and save. Repeat for each account used during testing.
4. Close the Google error screen. Return to **Profile → Connect Drive / Sync now** and retry; if Echo is signed out, start with **Sign in with Google**. Approve the normal Google consent prompts.
5. Confirm that authorization actually finishes, then verify a real playlist save/sync before marking the integration working. A saved console setting or an enabled button is not proof of authorization.

Google exempts requests limited to basic identity scopes from the Testing allowlist requirement. Echo's separate Drive request includes `drive.appdata`, so that exception does not apply to this request. Testing grants for scopes beyond basic identity expire after seven days; a later consent prompt can therefore be expected. Keep Testing for the current setup check; switching to Production is not required to test an explicitly listed account. See [Google's Audience rules](https://support.google.com/cloud/answer/15549945?hl=en).

If the account is already listed, inspect the full **Access blocked** message outside the request-details popup and confirm the selected Cloud project. Google also lists account-type and administrator restrictions as possible causes of this error; the screenshot alone cannot distinguish them. Recheck the Android package/signing SHA-1 against the installed APK using the owner setup above. Do not recreate the Web client or add browser redirect URLs as a speculative fix for the Android AccountManager flow. See [Google's error-code guidance](https://support.google.com/accounts/answer/16668185?hl=en).

The test-user change and a successful retry have not yet been confirmed. Live account authorization and two-device Drive sync remain unresolved. No APK rebuild or app-data reset is needed merely to update the Cloud test-user list.

## Sync behavior and data

Manual sync pulls and pushes. Automatic sync runs approximately once per minute while Echo is foreground and enabled; closed-app/background synchronization is not promised. Each device stores a complete playlist snapshot in the account's hidden Drive appDataFolder. Only Echo's app data is requested, not general Drive access.

Per-remote-device baselines drive three-way merge. Existing local edits are compared inside a Room transaction, so intervening changes reject the apply for retry. Concurrent differing edits retain the local version plus a deterministic sync-conflict copy. Track order and duplicates survive. Deletions propagate when the other side is unchanged; delete-versus-edit preserves the edit. Unseen stale devices can restore deleted playlists because this version has no global tombstones or remote-device management.

Cloud snapshots include playlist names/IDs and referenced track metadata. Audio, downloads, likes, listening logs, credentials, local matches, device file/artwork paths and expiring URLs are excluded. Local track IDs are device-qualified, preventing another phone's MediaStore IDs from playing unrelated local songs. Such recordings can be rematched locally in their playlist. Existing local sources remain preferred for matching IDs.

Responses/identities/source hosts are validated, bodies bounded, redirects disabled and rate limits respected. More than100 app-data files stops the sync explicitly. A network failure preserves local data; remote devices applied earlier in a multi-device operation can stay applied. Export a separate backup for independent recovery; sync snapshots are not immutable backup history.

References: [Google Android OAuth clients and AccountManager](https://support.google.com/cloud/answer/15549257?hl=en), [Android AccountManager](https://developer.android.com/reference/android/accounts/AccountManager), [Google userinfo](https://developers.google.com/resources/api-libraries/documentation/oauth2/v2/python/latest/oauth2_v2.userinfo.html), [Drive app-data folder](https://developers.google.com/workspace/drive/api/guides/appdata). Executed evidence and remaining limits are in [integration status](INTEGRATION_STATUS.md).

## Profile device count

Profile shows the local playlist count and the signed-in verified Google email. After a successful sync it shows the number of recognized per-installation playlist snapshots found in that account, including the confirmed current upload, with the last-checked time. Reports are stored per account; before the first successful sync the count is unknown. This is not online presence: registrations may be offline and reinstalls have separate identities. Duplicate snapshot registrations stop sync rather than inflating the count. Device removal and remote sign-out are not implemented.
