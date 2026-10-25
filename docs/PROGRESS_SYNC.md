# Progress sync investigation

Reviewed October 25, 2026 against Orbit Reader and the clean local BookOrbit checkout at commit `ce118e38`. The deployed server and account settings were not inspected.

## Audiobook progress and Hardcover

Orbit Reader saves listening progress with `PUT /api/v1/audiobooks/:bookId/playback-state`. It supplies asset position, operation ID, capture time, manifest revision, and base revision. The server computes the book percentage, stores it in `audiobookProgress`, updates reading status, and optionally synchronizes linked ebook progress.

In the reviewed server source, `server/src/modules/audiobook/audiobook.service.ts`, `putPlaybackState` does not emit `ACHIEVEMENT_EVENT_BOOK_PROGRESS_CHANGED` or a reading-session event. Hardcover's event listener schedules progress synchronization only from those events. Reading-status transitions can still trigger a sync when enabled, which explains why an initial or completed status may sync while intermediate listening progress does not.

Hardcover's repository reads the same `audiobookProgress.percentage` that this endpoint updates. The missing trigger is a server-side gap. Orbit Reader also lacks listening-session reporting, but the position endpoint should notify downstream consumers after accepting progress regardless of client session reporting.

A server fix should emit the existing book-progress event after a committed playback-state update, with the established payload contract. It must preserve stale-revision rejection and operation-ID replay behavior. Add server tests proving that accepted updates schedule Hardcover progress sync and rejected writes do not. Consider event delivery after an operation replay if an earlier response or downstream notification was lost. `BookService.saveProgress` has the equivalent trigger gap for ebook position-only writes. Keep auto-sync permission, token, per-user preference, and book matching checks in the existing Hardcover scheduler and sync service.

No server files or deployed services were changed in this app task. Until the server fix is deployed, use the web client's manual Hardcover sync after BookOrbit has received the position. If BookOrbit itself shows an old position, inspect the app's pending progress, account connection, and conflict state first.

## Ebook app behavior

Ebook positions now retain an account- and file-revision-scoped server baseline and pending upload on disk. Reader location changes checkpoint the latest value and schedule background work. Leaving the reader queues a flush after any pending work. The worker validates the file revision, re-reads server progress, and refuses a changed snapshot. Explicit Sync actions resolve the conflict. An unreadable or unavailable initial snapshot requires manual review rather than assuming that the current server position is safe to replace.

WorkManager retries transient network and server failures. A successful write is read back and acknowledged without discarding a newer local checkpoint. Percentages compare at the server's PostgreSQL `real` precision. Automatic and explicit writers share an account/file lock. The server does not offer conditional ebook writes, so a competing device can still write between the baseline check and POST.

Normal navigation and backgrounding trigger an exit flush. Abrupt process death can skip the final location callback. Already scheduled work persists, but Android force-stop suspends it until the app runs again. Reading/listening duration sessions remain separate follow-up work.
