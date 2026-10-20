# Orbit Reader for Android

An independent native Android client for a self-hosted BookOrbit server. This is the first audiobook milestone, not a claim of iOS feature parity or official BookOrbit endorsement.

This is a standalone Android project. It builds from this repository's root and connects to an existing BookOrbit server over HTTP. A BookOrbit source checkout, Node.js, pnpm, and a local PostgreSQL database are not required.

## Build and install

Requires JDK 17 and Android SDK platform 35 with build tools 35.0.0. Open this directory in Android Studio, or set `JAVA_HOME` and `ANDROID_HOME` and run:

```sh
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper includes a distribution checksum. SDK paths belong in an untracked `local.properties` or `ANDROID_HOME`.

Use the server's root URL, including its reverse-proxy path if present. A trailing `/api/v1` is accepted. HTTPS is preferred. HTTP is supported for trusted local servers with an explicit message on the sign-in screen. Normal certificate validation stays enabled. Password login must be enabled on the server. OIDC and magic-link sign-in are pending.

## First milestone

- Native Kotlin and Jetpack Compose screens, with light and dark themes.
- Password sign-in using native refresh sessions. Credentials are encrypted with an Android Keystore key and excluded from backup.
- Server-side audiobook search and filtering, in pages of 30. The client keeps one page in memory.
- Multi-file audio, chapters, background playback, audio focus, headset disconnection handling, and Android media controls through Media3.
- Configurable rewind and forward intervals, playback speed remembered per book, named bookmarks, and a sleep timer. Separate chapter and whole-book timelines show elapsed and total times, with chapter boundaries marked on the book track. Both timelines seek across audio files when durations are available.
- Complete-book downloads through foreground WorkManager jobs, with at most two books transferring concurrently. Wi-Fi-only is the default. Files stream through a 64 KiB buffer. Progress shows the percentage and transferred/total bytes across all files, including files completed before a retry. Unknown sizes remain indeterminate until their lengths are known. Pausing and interruptions retain partial audio and completed assets. Partial files resume with byte ranges and a strong HTTP validator when the server supports them; otherwise the unfinished asset restarts safely. Temporary server failures retry automatically.
- Offline playback from private app storage, scoped by server and account.
- Local listening positions and queued, revision-checked server updates. Conflicts require an explicit choice between this device and the server.

This milestone does not include EPUB, PDF, or comic readers. Android Auto, Wear OS, widgets, bookmarks, listening statistics, and Read Along are also pending. The Downloads tab shows complete and unfinished downloads, account-scoped audio storage usage, and device free space. Resume, retry, pause, and removal are available on the book sheet. Removal is disabled for the active audiobook. Downloads are retained on sign-out and can only be browsed after signing back into the same account.

## API contract

The upstream API contracts are defined by [BookOrbit's shared types](https://github.com/bookorbit/bookorbit/tree/main/packages/types/src) and [server modules](https://github.com/bookorbit/bookorbit/tree/main/server/src/modules). `app/src/main/java/app/bookorbit/community/data/Models.kt` contains the Kotlin wire projections used by this client. Unknown response fields are tolerated. Request fields are explicit because the server rejects unknown DTO fields. No local dependency on the BookOrbit monorepo is required. Audiobook playback requires the server's v2 audiobook manifest API.

| Request                                      | Contract                                                                                                            |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `POST auth/login`                            | `username`, `password`, `clientKind: native`, `deviceLabel`; reads `user`, `accessToken`, `refreshToken`            |
| `POST auth/refresh`                          | Body refresh token; atomically saves rotated credentials; retries a rejected request once                           |
| `POST auth/logout`                           | Body refresh token; clears local credentials even if revocation fails                                               |
| `POST books/query`                           | `BookQuery`, audio format filter, server search, title sort, 30-item pages; displayed page 1 maps to server page 0; reads `BooksPage.items/total/page/size` |
| `GET audiobooks/:id/manifest`                | Audiobook manifest v2, with assets ordered by sequence                                                              |
| `GET audiobooks/:id/assets/:assetId/content` | Authenticated, ranged streaming                                                                                     |
| `GET books/files/:fileId/download`           | Download permission enforced by the existing server; UI checks the account permission too                           |
| `GET/PUT audiobooks/:id/playback-state`      | Asset-relative milliseconds, manifest revision, base revision, capture time, and stable operation UUID              |

All paths use `/api/v1`. Access tokens stay in headers. The HTTP client rejects cross-origin credential forwarding and does not follow redirects. Reverse proxies should route the API directly rather than redirecting it. Library ownership and content access remain enforced by the existing backend.

Progress saves occur every 10 seconds while playing, on pause, on seeks, and during orderly service shutdown. Abrupt process termination can lose the last interval. Offline work is retried when connectivity returns. A changed manifest returns 412 and needs a fresh download; conflicts return 409 and are never automatically rebased. Download assets are length-checked when the server provides a size. Download requests use identity encoding, Range, and If-Range when a saved strong HTTP ETag is available. The client verifies Content-Range, validators, and lengths before appending or completing an asset. Servers without this support restart unfinished assets. BookOrbit v3.4.0 ignores ranges on its download endpoint and omits ETag, so it uses this safe restart behavior. File integrity hashes, pre-download space estimates, and cleanup of obsolete revisions remain follow-up work.

## iOS parity backlog

Public research on October 18, 2026 found the [official server repository](https://github.com/bookorbit/bookorbit) and [iOS support repository](https://github.com/bookorbit/bookorbit-ios-support), but no public official iOS application source. The [Play Store BookOrbit listing](https://play.google.com/store/apps/details?id=com.bookorbit) names `deranjer` and links to a separate [Android client repository](https://github.com/deranjer/bookorbit-android). No code from that client is used here.

The [official iOS release history](https://github.com/bookorbit/bookorbit-ios-support/blob/main/RELEASES.md) and [App Store listing](https://apps.apple.com/us/app/bookorbit-the-official-app/id6811807346) provide the parity baseline:

1. Finish audiobook verification on physical devices when one is available, and add listening sessions. Download recovery and management, bookmarks, per-book speed, and configurable skip intervals are implemented.
2. Add EPUB/KEPUB, PDF, and comic readers, offline progress, annotations, typography, and reading preferences.
3. Add Read Along, text to speech, Notebook, library organization, requests, uploads, metadata editing, and activity.
4. Build tablet layouts, accessibility coverage, widgets, Android Auto, and a Wear OS companion. These are Android equivalents of the iPad, CarPlay, and Apple Watch experiences.

Acceptance requires tests with large libraries, account switching, expired tokens, interrupted transfers, offline launches, and concurrent progress changes. Audio playback tests must include real multi-file books, battery restrictions, Bluetooth controls, calls, and screen-off use before calling this production-ready.

## Emulator verification

On October 19, 2026, the Android 15 Pixel 9 Pro emulator passed password sign-in, library browsing, two-file streaming, chapter selection, speed selection, sleep-timer configuration, complete-book download, and offline playback after restarting the app with its server connection removed. Playback continued while the screen was asleep and advanced to the second file. Android's media-pause command paused playback, and progress synchronized after reconnecting. The player was changed to open fully expanded so playback controls are visible immediately.

The end-of-book Play action was also fixed and verified to restart playback from the first file. The updated APK was installed and left on the emulator with the synthetic library available.

Separate chapter and whole-book timelines were subsequently verified on the same emulator. A whole-book seek to 0:20 selected the second file at 0:00, and a chapter seek to 0:10 moved the whole-book position to 0:30. Seven timeline tests cover cross-file chapters, exact boundaries, clamping, chapter markers, and unknown durations.

An approximately 246 MB, 8.5-hour AAC audiobook in an M4B container was also copied into a private test fixture, downloaded completely through the app, and played from local storage with its embedded cover and 33 chapter entries. Chapter jumping was verified. Download progress now reports percentage and transferred/total size, with three additional tests covering aggregate progress, unknown sizes, and byte totals over 2 GB.

Physical Bluetooth devices, phone-call interruptions, battery restrictions, interrupted large transfers, and further audiobook codecs still need device testing. Sleep-timer expiry was not tested during this run.

## Download recovery verification

The automated suite covers interrupted transfers, validated byte-range resume, servers that ignore ranges, weak or missing validators, invalid range offsets, changed files, unknown manifest sizes, incomplete responses, account-scoped storage accounting, and the server's zero-based library pagination. The synthetic HTTP fixture uses the same pagination convention and supports conditional ranges.

A separate BookOrbit v3.4.0 instance was installed on the Raspberry Pi with its own PostgreSQL database, library, generated test account, and two synthetic 10-minute MP3 assets. No production library is mounted. Native sign-in, the first library page, authenticated downloads, ranged streaming, progress writes, stale-revision rejection with 409, and changed-manifest rejection with 412 were checked against that server. The first-page check found and fixed a client bug that previously skipped the first 30 server books.

On the Android 15 emulator, pausing a download retained 867 kB after closing and reopening the app. The Downloads tab exposed the incomplete book and its Resume action. After a further airplane-mode interruption, downloading completed automatically when Wi-Fi returned. The downloaded book played with airplane mode on and Wi-Fi off, including while the activity was in the background. Reopening the book after restarting the app recovered its saved position around 1:20. Removing the local download cleared the Downloads view while the server library still contained the book. The app also showed the explicit device-versus-server progress conflict after reconnecting. On October 20, the remaining choice-and-sync check passed after the emulator host returned: choosing "Use this device" resumed the offline position and saved it to the test server at 1:50 with revision 4. After another app restart, Downloads still showed 0 B used, and streaming resumed from that saved position without another conflict. Physical Bluetooth devices, incoming calls, battery restrictions, and sleep-timer expiry still need device testing.

## Listening preferences and bookmarks

The player has Bookmarks and Skip intervals controls. Bookmarks save the current audio file and position, with an editable name up to 120 characters. Tap a bookmark to seek without changing whether playback is paused. Each book supports up to 500 bookmarks. If its manifest changes, old bookmarks remain visible for renaming or deletion, but cannot seek into different audio.

Playback speed is saved separately for each book. Back and forward intervals offer 5, 10, 15, 20, 30, 45, or 60 seconds, with defaults of 15 and 30. The intervals apply to all audiobooks for that account, including Android media controls. Skips cross file and chapter boundaries when file durations are known. With unknown durations, skips stay in the current file and clamp to its length when available.

Bookmarks, speed, and skip intervals are local to this device and scoped to both server and account. They survive app restarts and download removal. They do not synchronize to BookOrbit or other devices. Release packaging is deferred until ebook support is included. Physical-phone checks are deferred until a device is available.

On October 20, 2026, the Android 15 emulator verified creating, renaming, deleting, and reopening bookmarks after restarting the app. A bookmark returned from the first file to the second at exactly 0:25 while keeping playback paused. The app restored 1.5× speed and 20-second back / 45-second forward intervals after restarting. Back from the second file at 0:00 landed in the first at 9:40; forward then landed in the second at 0:25. Automated tests cover storage isolation, independent book speeds, invalid input, bookmark limits, download removal, changed manifests, and skip calculations with known and unknown durations.

## Repeatable smoke fixture

```sh
python3 scripts/fixture-server.py --port 4890
adb reverse tcp:4890 tcp:4890
```

Sign into `http://127.0.0.1:4890` with the fixture-only username and password `reader`. It provides synthetic two-part audio, not a real library. Check search, playback while backgrounded, chapters, speed, downloads, and offline relaunch after stopping the fixture. Never deploy the fixture as a BookOrbit server.

For private real-audiobook previews, pass `--book-dir /path/to/private-fixture`. That directory must contain a v2 `manifest.json`, `cover.jpg`, and one `<fileId>.<format>` file per manifest asset. Supported fixture formats are M4B, M4A, and MP3. File responses stream in bounded chunks and support byte ranges. Keep audiobook copies and their metadata outside the repository. Playback progress stays in the test server rather than reaching your production library.

## License and project history

The project uses the GNU Affero General Public License version 3 only. See [LICENSE](LICENSE). The prototype began in an uncommitted `android/` directory in a BookOrbit fork and was extracted into this independent repository on October 19, 2026. The server and web client are maintained separately by the BookOrbit project.
