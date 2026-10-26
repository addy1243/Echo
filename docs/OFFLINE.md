# Offline audio and frequent-song cache

Explicit downloads and automatic caching are separate controls. Downloads are pinned until removed; the opt-in automatic cache saves songs after **three qualified listens** (a completion or at least 30 seconds actually listened). Pauses, seeks, failed playback and short skips do not create qualified listens. Automatic downloads require a validated, unmetered connection and stop if it becomes metered. Manual downloads may use mobile data.

Automatic candidates are triggered by newly completed qualified listens, including later listens of previously evicted songs. Enabling the option or restarting the app does not eagerly download the entire historical library; this prevents an over-budget popular library from repeatedly evicting and re-downloading itself.

## Storage and integrity

Audio is kept inside Echo's private `filesDir/offline-audio`, without shared-storage permissions. The manifest stores stable track IDs, display titles, completion state, byte counts and SHA-256; temporary signed URLs and request headers are never persisted. Names are SHA-256 hashes of stable IDs. Track metadata, playlists and likes remain in the existing library, so downloading never replaces the track's canonical source.

Each song is capped at 100 MiB. Pinned downloads have a 2 GiB budget; the automatic cache has a separate 256 MiB budget and evicts oldest saved automatic entries first. At least 100 MiB free space is reserved. Explicit downloads never get evicted to make room. A cached song can be pinned without downloading it again, subject to the download budget. Clear automatic cache removes only automatic entries, while Remove deletes one saved copy without deleting the library track.

Transfers are serialized. A temporary `.part` becomes available only after a successful HTTP 200, allowed audio MIME, size checks, flush/fsync, digest and atomic file rename. Playback verifies the byte count and digest before using a saved copy. Missing, truncated or damaged copies become Retry entries. The resolver checks complete local copies before online extraction. Offline mode rejects uncached remote audio and prevents new audio network opens; enabling it stops current remote playback and cancels downloads. Existing local files and complete saved copies remain playable.

## Interruption and retry

**Keep Echo open while downloading.** Transfers run in the app process, not a background download service. Android may stop them when the process ends. Incomplete manifest entries become interrupted Retry entries on restart, partial files are removed, and Retry begins again with a freshly resolved source. There are no automatic transfer retries, range-resume claims, signed-URL persistence or rate-limit loops. Cancel interrupts the active network call; Remove waits for cancellation before deleting files. Failed automatic entries require an explicit retry or removal, preventing repeated unattended source requests.

This first implementation deliberately uses existing OkHttp/providers and app-private files. If background completion becomes required, move transfers into a foreground data-sync service or Media3 DownloadService with a durable queue, notification, network constraints and fresh-source handling. Android documents that pattern in [Downloading media](https://developer.android.com/media/media3/exoplayer/downloading-media). Storage behavior follows [app-specific files](https://developer.android.com/training/data-storage/app-specific). No background-survival promise is made here.

## Source and account boundaries

Downloads use only the same available progressive YouTube recordings and licensed Commons audio already accepted by their providers. Authentication, paid/member content, DRM and captcha bypass remain unsupported. HTTPS and delivery-host allowlists apply; redirects are rejected. Playback/source availability and service terms still apply. Local files are already offline and Spotify metadata must first be matched to a playable recording.

Offline copies and settings belong to this device. They are not part of metadata backup or account playlist sync and disappear when Echo is uninstalled. Signing out does not silently erase audio or the user's library. No network needed for saved audio does not imply uncached artwork, search, updates or authentication can work offline.

## Checks

`OfflinePolicyTest` covers stable path-safe filenames; consent, unmetered and substantive-listen thresholds; empty/partial/oversized/low-space rejection; and missing/truncated/same-length-corrupt saved files. Integrated build, tests and runtime evidence are recorded by the orchestration agent in the release/integration notes. Real airplane-mode playback, process-kill/retry, disk-full behavior and mobile-network transitions require device checks before a reliability claim.
