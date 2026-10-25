# Online music review — 2026-10-25

Follow-up implementation: the user authorized the key-free approach. Echo now has NewPipe-backed YouTube song search and lazy native audio resolution; the old Data API link client is removed. Findings 2–4 below are addressed (playlist start selection, cleared/labeled search results, and cancellable search state). Finding 1's mixed unavailable-file queue behavior and finding 5's Commons API error parsing remain separate existing work. Current build/runtime evidence is in [INTEGRATION_STATUS.md](INTEGRATION_STATUS.md); the remainder of this file preserves the original review.

What this change does: This review records how Echo currently handles online music and the smallest path toward broader in-app playback. Application code is unchanged. Three read-only agent reviews covered providers, playback, and UI; the orchestrator checked the connected flows and consolidated the findings.

The user's target is broad music search and ad-free playback inside Echo, with YouTube acceptable and Spotube suggested as architectural inspiration. No subscription preference was established. “Any music” is an aspiration, not a catalog or availability guarantee.

## Current behavior

| Source | Search/import | Playback in Echo |
|---|---|---|
| Device files | MediaStore scan and document picker | Yes |
| Wikimedia Commons | Explicit search of approved openly licensed recordings | Implemented; live playback remains unverified |
| Spotify | Authorized playlist metadata import | Only after matching a local recording |
| YouTube | Data API music search | External watch link only |

The native online flow already exists: Search → Licensed audio → Commons result → persist track in Room → MediaController → service source resolution → Media3. Saving, liking, queueing, and playlist additions reuse the existing library.

`SourceResolver` registers only local files and Commons (`data/src/main/java/dev/echo/data/provider/SourceResolver.kt:16`). Both providers require a matching provider ID; their priority order does not search another catalog for an equivalent recording. The existing `TrackMatcher` is not wired into automatic playback resolution.

## Must fix before broader online playback

1. **An unavailable entry can reject a playable queue.**
   - What this is: The service resolves the requested queue before accepting it (`playback/src/main/java/dev/echo/playback/EchoPlaybackService.kt:336`, especially line 350).
   - Problem: A valid song followed by a deleted local file with a cached URI causes the whole request to fail. Browser folder playback also fails if a Spotify playlist contains an unmatched entry.
   - Fix: Define unavailable-entry handling in the service and preserve the selected occurrence when filtering. Cover explicit selection, folder expansion, duplicates, and all-unavailable queues. Simply dropping entries can shift the selected index.
   - If skipped: One unavailable song prevents other songs from playing.

2. **Playlist Play and Shuffle require the first original entry to be playable.**
   - What this is: Playlist buttons call `play(songs)` with index zero (`app/src/main/java/dev/echo/app/EchoApp.kt:269`).
   - Problem: The ViewModel rejects an unmatched first Spotify track even when later tracks have local matches (`app/src/main/java/dev/echo/app/EchoViewModel.kt:113`).
   - Fix: Playlist-wide actions start at the first eligible track and disable when none exists. Retain an explicit error for a deliberately selected unavailable row; test the mixed playlist case.
   - If skipped: Imported playlists appear playable but their main buttons fail.

## Should fix

3. **Search errors leave old results beside the new query.**
   - What this is: Online results are stored independently of the submitted query (`app/src/main/java/dev/echo/app/EchoViewModel.kt:135`).
   - Problem: Search A succeeds, then search B fails; A's results remain visible under B. The YouTube path has the same behavior.
   - Fix: Clear results on submission or label them with their submitted query; test a successful search followed by a failed one.
   - If skipped: Users cannot tell which search produced the displayed songs.

4. **Unrelated actions can allow overlapping searches and stale responses.**
   - What this is: All actions share one `busy` boolean (`app/src/main/java/dev/echo/app/EchoViewModel.kt:92`).
   - Problem: Liking a result while a search runs can clear `busy`; a second search can start and finish before the first, whose late response then replaces newer results.
   - Fix: Give search its own job/state and ignore superseded responses. Test responses finishing out of order.
   - If skipped: Search results can jump back to an older query.

5. **Commons API errors can look like an empty catalog.**
   - What this is: The provider checks HTTP status, then reads `query.pages` (`data/src/main/java/dev/echo/data/provider/CommonsProvider.kt:30`).
   - Problem: HTTP 200 with a MediaWiki `error` object becomes “No licensed audio results found.”
   - Fix: Parse API errors before results and cover the response with a deterministic fixture test.
   - If skipped: Provider failures are hidden as unsuccessful searches.

## Spotube comparison and source constraints

The current [Spotube README](https://github.com/team-spotube/spotube) describes separate metadata, playlist, and audio-source plugins. Its [dependency manifest](https://github.com/team-spotube/spotube/blob/master/pubspec.yaml) includes YouTube extraction libraries and bundled metadata/audio plugins. This establishes its declared architecture and dependencies; it does not verify that a particular extraction route currently works.

Reuse the separation of catalog identity from playback source. Keep Echo's Kotlin/Compose, Room, and Media3 modules; a Flutter port or community plugin runtime would add scope without proving playback. If copying source, inspect the applicable license: Spotube's current root [license](https://github.com/team-spotube/spotube/blob/master/LICENSE) is BSD-4-Clause, and dependencies have their own licenses.

YouTube's official APIs do not provide the proposed ad-free background audio experience: its [developer policies](https://developers.google.com/youtube/terms/developer-policies) prohibit blocking ads, separating audio/video, and background API players. Personal use does not remove those policy restrictions. Unofficial extraction is a separate technical approach with service-change and availability risks; using Spotube as inspiration does not make it an officially supported integration.

Spotify's [Android SDK](https://developer.spotify.com/documentation/android) controls playback in the Spotify app. It does not supply unrestricted stream URLs for Echo's Media3 queue. A streaming provider such as [Jamendo](https://developer.jamendo.com/v3.0/tracks) exposes audio URLs but has its own catalog, client registration, and usage terms; it does not satisfy broad mainstream availability by itself.

## Smallest implementation sequence

1. Fix the mixed-playlist and search bugs above with focused regression tests.
2. Establish one audio source's actual search → stream capability before expanding the UI. For a YouTube-based approach, explicitly distinguish unofficial extraction from the existing authorized Data API discovery. Check the selected library's license, Android compatibility, maintenance, and failure behavior before adopting it. Do not add multiple fallback services speculatively.
3. Reuse `MusicMetadataProvider`, `AudioSourceProvider`, `Track`, and the service resolver. Keep stable provider IDs in Room and resolve expiring URLs at playback time. Update the ViewModel's `sourceUri != null` gates for any provider that resolves by ID; adding a provider alone is insufficient.
4. Wire that source into Home/Discover, Search, and Settings. Separate native playback, external playback, and metadata-only entries visibly. Review playlist matching, backups, and queue persistence if the Track model changes; add a tested Room migration only if the schema changes.
5. On the emulator only, demonstrate a real stream with advancing position, pause/resume, seek where supported, next track, queue restoration, and useful offline/rate-limit/expired-source errors. Preserve provider error handling and existing local playback. Android Auto discovery is a later explicit scope: today its search covers saved library rows only.

Suggested delegation when implementation begins: provider agent owns catalog/search/resolution; playback agent owns queue behavior and recovery; UI agent owns discovery and search state. The orchestrator owns contracts, integration, test evidence, and acceptance. Agents should edit separate files and agree on contracts before parallel changes.

## Verification

The offline Gradle invocation for `:app:testDebugUnitTest :data:testDebugUnitTest :playback:testDebugUnitTest` completed successfully using the installed Gradle 8.14 distribution and project-local cache. All 86 tasks were up-to-date; tests were not freshly executed. Existing XML reports contain 27 tests with zero failures/errors. The wrapper initially attempted to download an uncached distribution and failed DNS; the installed distribution avoided that download. Local daemon connectivity required execution outside the sandbox.

No live audio probes, credential checks, emulator tests, or phone access were performed. The earlier Commons HTTP 429 is documented prior evidence, not a fresh result. Git status was unavailable because Git failed resolving `C:/`; no staging or commits were attempted.

Verdict: keep the existing architecture; fix findings 1–5 and verify one real audio source before claiming broad online playback.
