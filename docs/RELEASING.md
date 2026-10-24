# GitHub APK releases

GitHub Releases distributes an installable APK without a Play Store account. The
`Release Android APK` workflow runs when you push a `v*` tag. You can also run it
from the Actions tab with an existing tag.

## Set up signing once

Create a release keystore on a trusted computer with JDK 17. Keep it outside the
repository. This command prompts for passwords rather than putting them in shell
history:

```sh
keytool -genkeypair -v -keystore /safe/path/orbit-reader-release.jks \
  -alias orbit-reader -keyalg RSA -keysize 3072 -validity 10000
```

In the repository's Settings → Secrets and variables → Actions, add these
repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the entire keystore file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `orbit-reader`, or the alias in your existing keystore |
| `ANDROID_KEY_PASSWORD` | Key password, usually the same as the keystore password |

On Linux, write the encoded keystore to a private temporary file with
`base64 -w 0 /safe/path/orbit-reader-release.jks > /safe/path/keystore-base64.txt`,
paste its contents into the secret, then delete that temporary file. On macOS,
use `base64 -i /safe/path/orbit-reader-release.jks -o /safe/path/keystore-base64.txt`.
Treat the encoded file as a private key. Never commit it or paste it into an issue
or chat.

Back up the keystore and passwords securely. Every update must use the same key
and application ID. Losing the key prevents updates to existing installations.
The workflow fails if signing secrets are missing and never generates a new key.

## Publish a version

1. Commit and push the release changes, including the workflow. No version edits
   in `app/build.gradle.kts` are needed. The tagged commit must include this workflow.
2. Choose a version and create its tag on GitHub, or create and push it locally:

   ```sh
   git tag -a v0.1.0 -m "Release 0.1.0"
   git push origin v0.1.0
   ```

The workflow runs formatting checks, unit tests, debug and release lint, and both
builds. It signs the release APK, verifies its signature and alignment, and
publishes `orbit-reader-v0.1.0.apk` plus `SHA256SUMS` with generated release notes.
The tag sets the APK's displayed version automatically: `v0.1.0` produces
`0.1.0`. Tags such as `v0.2.0-beta.1` produce `0.2.0-beta.1` and become prereleases.
Use the tag field on GitHub, not the release title, to choose the version.

The internal Android `versionCode` is the release workflow's GitHub run number
plus one, so it increases with each new run and stays above the original code of
`1`. Retrying the same run keeps the same code. Publish versions in order and
retry failed runs before publishing a newer version. Keep this workflow's run
counter when maintaining release automation. Local builds default to version
`0.0.0-dev` and code `1`.

Publishing starts with a draft so a failed upload cannot expose a release with
missing assets. If publishing fails after creating the draft, delete that draft
before rerunning the workflow for the same tag. Keep the tag. Existing releases
are never overwritten. Manual runs require the workflow on the default branch;
choose the default branch in Actions and enter the existing release tag.

## Install and update

Open the repository's Releases page on an Android 8.0 or newer device, download
the APK under Assets, and open it. Android may ask you to allow installation from
your browser or file manager. Install future releases over the existing app to
keep its data. Updates are downloaded and installed manually.

An existing debug install usually has a different signing key. Android will
reject installing the release over it. Uninstalling the debug app removes its
local data, including downloaded books, saved credentials, and unsynced progress
and annotations. Sync progress and export local notes and bookmarks before
switching. Keep the application ID `app.bookorbit.community` unchanged.

See [Android signing documentation](https://developer.android.com/studio/publish/app-signing)
for signing-key management and [GitHub CLI release documentation](https://cli.github.com/manual/gh_release_create)
for release publishing behavior.
