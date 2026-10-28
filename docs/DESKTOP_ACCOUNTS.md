# Desktop Google account and Firebase setup

Account/library sync shipped in Android **0.2.0 / code11** and Windows **0.2.0**. The 0.2.1 candidate adds [connected playback](CONNECTED_PLAYBACK.md) after separate opt-in; its live emulator/native-Windows acceptance passed.

Google sign-in is optional. Local listening/library operations work without it. **Devices & account** contains Google sign-in/out, Firebase manual sync and automatic sync. Authentication alone does not authorize uploading the existing library; a separate confirmation describes playlists, favorites, history and account-wide clear/delete behavior.

## Single Echo project

Both clients use **echo-85da4**, project number **886330954520**:

- Desktop client: `886330954520-e55biu8k81s9akaago3csrpbcbmigdfo.apps.googleusercontent.com`.
- Android client: `886330954520-37dt7c9ipgu32i04hkfcrk16cru03160.apps.googleusercontent.com`.

These are public identifiers. Android's package/certificate client cannot replace the Desktop client. The owner migrated Android's registration after Google rejected a duplicate package/certificate pair. See [Android accounts](ACCOUNTS.md) and [Firebase configuration/rules](FIREBASE.md).

## Configure and connect

1. In [Google Auth Platform → Clients](https://console.cloud.google.com/auth/clients), select **echo-85da4** and download the **Desktop app** client's JSON. Keep it outside Git and do not paste its contents. Configure Branding/Audience/test users appropriately for the project's publishing status.
2. Choose **Devices & account → Import Desktop OAuth configuration** in Echo. The importer checks the installed-client type, Echo project/client identity and fixed endpoints, then protects the saved configuration with Windows DPAPI. Changing clients clears old tokens and requires fresh sign-in.
3. Choose **Sign in with Google** and finish account selection/consent in the system browser. Echo never handles passwords. PKCE and a random single-use loopback callback protect the response, and Echo verifies the returned Google account.
4. Choose **Connect Firebase / Sync now**, review the selected account/existing-library upload, and select one-time or automatic sync. Primary sync uses Firebase Authentication and the verified owner's Firestore document; no Drive permission is requested.
5. Cancel pending operations from Echo if needed. Closing a browser tab alone does not cancel the callback wait, which expires after three minutes. Failed authorization/sync preserves the local library.

Echo imports the generated Desktop client-secret field locally because its token exchange required it in executed checks. Desktop apps remain public OAuth clients; this field does not replace PKCE. No service-account/Admin SDK key or access/refresh token belongs in source, public assets or library backups.

## Storage and sign-out

Credentials/configuration are encrypted for the current Windows user in `%LOCALAPPDATA%\EchoMusic\google-account.dpapi`. Library, audio and sync baselines are separate. Unreadable credential files are preserved until an explicit successful reset/import. Do not copy DPAPI credentials to another Windows user.

Sign-out removes the local account/token association and retains the library and imported configuration. It does not delete the Google account/cloud library/old Drive snapshots or revoke Google's project-wide consent. Local libraries remain device-wide; review the selected account before uploading to a different account.

Backup export/import is a separate recovery tool: desktop import is additive, and backups exclude audio, credentials and history. Phone-local files require a laptop source or recording match.

## Verified behavior and limits

Desktop browser consent/Firebase login and normal Android-emulator Google/Firebase login succeeded. The normal Android saved sync state, desktop and Firestore matched canonically; automatic sync is enabled on both while open. Isolated live roundtrips covered duplicates, a desktop rename and deletion convergence. See [Firebase evidence](FIREBASE.md).

Tests cover PKCE/callbacks, account binding, scopes/lifetimes, client migration, DPAPI/corrupt-file preservation, merge conflicts and guarded apply. Real interactive revocation/expiry/account switching are not fully accepted. No physical phone was accessed. Metadata sync does not provide live playback presence, remote controls or synchronized/multi-output audio; see [design](DESKTOP_SYNC_DESIGN.md).
