# Offline audio and frequent-song cache

Explicit downloads and automatic caching are separate controls. Downloads are pinned until removed; the opt-in automatic cache saves songs after **three qualified listens** (a completion or at least 30 seconds actually listened). Pauses, seeks, failed playback and short skips do not create qualified listens. Automatic downloads require a validated, unmetered connection and stop if it becomes metered. Manual downloads may use mobile data.

Automatic candidates are triggered by newly completed qualified listens, including later listens of previously evicted songs. Enabling the option or restarting the app does not eagerly download the entire historical library; this prevents an over-budget popular library from repeatedly evicting and re-downloading itself.

## Storage and integrity

Audio is kept inside Echo's private `filesDir/offline-audio`, without shared-storage permissions. The manifest stores stable track IDs, display titles, completion state, byte counts and SHA-256; temporary signed URLs and request headers are never persisted. Names are SHA-256 hashes of stable IDs. Track metadata, playlists and likes remain in the existing library, so downloading never replaces the track's canonical source.

Each song is capped at 100 MiB. Pinned downloads have a 2 GiB budget; the automatic cache has a separate 256 MiB budget and evicts oldest saved automatic entries first. At least 100 MiB free space is reserved. Explicit downloads never get evicted to make room. A cached song can be pinned without downloading it again, subject to the download budget. Clear automatic cache removes only automatic entries, while Remove deletes one saved copy without deleting the library track.

Transfers are serialized. A temporary `.part` becomes available only after a successful HTTP 200, allowed audio MIME, size checks, flush/fsync, digest and atomic file rename. Playback verifies the byte count and digest before using a saved copy. Missing, truncated or damaged copies become Retry entries. The resolver checks complete local copies before online extraction. Offline mode rejects uncached remote audio and prevents new audio network opens; enabling it stops current remote playback and cancels downloads. Existing local files and complete saved copies remain playable.

## Interruption and retry

Manual song downloads run in a user-started Android dataSync foreground service, with an ongoing progress notification and Cancel downloads action. The Downloads screen shows received bytes and an honest percentage only when the server supplies a total. Notification taps open Downloads; notification visibility requires Android notification permission. A bounded wake lock supports screen-off transfers. Automatic frequent-song caching remains owned by the playback/app process and its existing unmetered/consent rules.

Leaving the activity does not cancel manual downloads. Force-stop, process termination, reboot and Android/OEM limits can interrupt them. Incomplete manifest entries become Retry entries on restart, partial files are removed, and Retry starts with a freshly resolved source. No range resume, unattended retries, signed-URL persistence or rate-limit loops are added. Cancel interrupts the active HTTP call; Remove waits for cancellation before deleting files. Existing OkHttp/provider validation and private atomic storage remain in use.

The services follow Android's [dataSync foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync) and [timeout](https://developer.android.com/develop/background-work/services/fgs/timeout) requirements. Storage follows [app-specific files](https://developer.android.com/training/data-storage/app-specific).

## Source and account boundaries

Downloads use only the same available progressive YouTube recordings and licensed Commons audio already accepted by their providers. Authentication, paid/member content, DRM and captcha bypass remain unsupported. HTTPS and delivery-host allowlists apply; redirects are rejected. Playback/source availability and service terms still apply. Local files are already offline and Spotify metadata must first be matched to a playable recording.

Offline copies and settings belong to this device. They are not part of metadata backup or account playlist sync and disappear when Echo is uninstalled. Signing out does not silently erase audio or the user's library. No network needed for saved audio does not imply uncached artwork, search, updates or authentication can work offline.

## Checks

`OfflinePolicyTest` covers stable path-safe filenames; consent, unmetered and substantive-listen thresholds; empty/partial/oversized/low-space rejection; and missing/truncated/same-length-corrupt saved files. Integrated build, tests and runtime evidence are recorded by the orchestration agent in the release/integration notes. Real airplane-mode playback, process-kill/retry, disk-full behavior and mobile-network transitions require device checks before a reliability claim.
