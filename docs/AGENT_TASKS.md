# Tasks

| Owner | Scope | Dependencies | State |
|---|---|---|---|
| Orchestrator + player/artwork, history and offline subagents | Player playlist actions, artwork, listening events/preferences, downloads/cache, Google account/Drive sync, queue append/search/shuffle and sleep timer | Public Web client ID configured; live OAuth/Drive consent remains owner verification; see ACCOUNTS.md | Integrated for 0.1.7/code8; executed checks in INTEGRATION_STATUS.md |
| Sol | Gradle, Compose UI, integration, build | core contracts | Completed |
| Persistence | Room library, MediaStore, settings, tests | core contracts | Completed |
| Playback | Media3 service, car hierarchy, controller helpers | core + persistence contracts | Completed |
| Integrations | Spotify PKCE/import, authorized provider, matching, YouTube, tests | core contracts | Completed |
| Sol + integration review | GitHub updater, validation, popup and phone checks | Public repo + signing identity | Completed |
| Sol | Spotify account onboarding | Owner has no Premium; subscription-free file import replaces normal login UI | Stopped |
| Sol | Playlist-file import, recording selection, discovery, smart shuffle and new-music searches | Existing Room/YouTube/Media3 paths | Implemented; 39 app/data JVM, 3 data emulator, 2 UI and 1 live discovery checks pass |
| Sol | Public Spotify share-link preview/import | Canonical public pages; explicit partial/unknown count preview | Implemented; 41 JVM, 3 parser/live data and 3 UI/live checks pass; code6 installed in place |
| Sol | Compact tabs, Home routing fix, immersive player, playback-ordered queue, recording-based smart shuffle and auto matching | Existing controller/provider/Room paths | Implemented; 45 JVM, 10 distinct app and 5 data scenarios pass; code7 installed in place |
| Deferred | Laptop application | User's later-priority instruction | Pending |
| YouTube orchestration + provider/playback/UI agents | Key-free search, lazy native streaming, regressions and maintenance docs | NewPipeExtractor + existing Media3/Room | Implemented; live emulator checks passed |
| External verification | Spotify authorization, Commons streaming, real car host | Account registration/provider availability/DHU | Pending external checks |

Completed here means implementation integrated and available checks passed, not complete product acceptance. Persistence: JVM tests and all four Android Room/Keystore instrumentation checks passed in the earlier validation. The phone-discovered duplicate Home key crash is covered by a persisted-history/activity-recreation regression. Commons live playback previously hit HTTP429, and Spotify still needs its own account-authorization checks. The user subsequently returned to YouTube scope: key-free search, real native audio, pause/seek/next and service-recreated queue restoration passed on the dedicated API36 emulator. The YouTube change used no phone access. Earlier updater-only phone checks and renewed device authorization remain separate historical evidence; device availability must be checked again before use. Car host validation requires DHU/device. See [current validation evidence](INTEGRATION_STATUS.md), [release history](../CHANGELOG.md), and root AGENTS.md.
