# Orbit Reader

Independent Kotlin and Jetpack Compose Android client for a self-hosted BookOrbit server. This repository contains the Android app and local verification tooling. BookOrbit's server and Vue client belong to a separate upstream project.

## Build and verification

- JDK 17, Android SDK platform 35, and build tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME` or an untracked `local.properties`.
- Run commands from the repository root with the checked-in Gradle wrapper.
- Format Kotlin and Gradle changes with `./gradlew spotlessApply`.
- Verify with `./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.
- Use the smallest relevant checks while editing. Run the full verification command before declaring a release candidate. Explain skipped checks.
- For fixture changes, run `python3 -m py_compile scripts/fixture-server.py` and exercise affected HTTP endpoints.
- Use T3's `device_list` and `device_open` tools for emulator verification. Keep the returned launcher, config, and session flags on device commands. Prefer accessibility targets over coordinates.

## Architecture

- Compose components own presentation and interaction. State and operations belong in ViewModels, data classes, API/storage classes, or playback/download services.
- Use Media3 for audio and Android media controls; WorkManager owns durable downloads and progress sync.
- Keep the Android application ID stable unless the user requests a separate install. Changing it loses access to the existing install's local data.
- API wire models live in `app/src/main/java/app/bookorbit/community/data/Models.kt`. Compare changes with upstream BookOrbit's shared types and server DTOs. Do not introduce a local dependency on its monorepo.
- Update request shape, validation assumptions, and response parsing together. The server rejects unknown request fields.

## Scale, accounts, and playback

- Assume libraries with tens of thousands of books. Search and filter on the server, request bounded pages, and retain only the current page where practical.
- Scope local files, queued work, and credentials to both server and account. Enforce expected account scope before background API calls.
- Preserve token rotation, normal TLS verification, and credential origin checks. Never put access tokens in media URLs.
- Store credentials using Android Keystore encryption. Exclude credentials and downloaded books from backup and device transfer.
- Stream media through bounded buffers. Bound download concurrency and report meaningful byte progress. Avoid expensive per-chunk credential reads.
- Keep listening progress durable and revision checked. Never silently overwrite another device's progress after a conflict.
- Test timeline calculations across audio-file and chapter boundaries, including unknown durations.
- Cover art, chapter titles, and user text must not be interpreted as code or markup.

## Private data and fixtures

- Never commit audiobooks, private covers/manifests, credentials, signing keys, local configuration, build caches, or generated APKs.
- Keep real-media fixtures outside the repository. Use copies and isolate preview progress from the production server.
- The synthetic fixture's `reader` credentials are test-only. Never deploy that fixture as a production server.
- Do not log passwords, tokens, request payloads, or media contents.
- Do not connect to or modify homelab services without task authorization. Apply the machine-addresses skill when remote access is needed.

## Git and writing

- Use conventional commit messages with a short imperative subject and a body explaining nontrivial changes.
- Never add a `Co-authored-by` trailer.
- Never use em dashes in UI text, code comments, documentation, or commit messages.
- Add comments only to explain non-obvious decisions. Keep user-facing copy plain and concise.
- Do not claim official BookOrbit affiliation or complete iOS parity.
