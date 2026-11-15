# Music Import for Klang

Optional Android service plugin for [Klang](https://github.com/lynlioss/klang).
Accepts Spotify and YouTube Music songs, albums, EPs and playlists, returns
metadata for review, then downloads selected public recordings with original
audio, embedded metadata and artwork. Spotify supplies metadata; recordings
come from YouTube Music. Native FLAC stays FLAC; lossy audio is never transcoded.

Klang owns selection, device duplicate checks, the download queue and MediaStore
publication. The plugin cannot see the user's library. The downloader runs under
its own Android UID and receives only links, selected metadata and output file
descriptors. Python, QuickJS, FFmpeg and yt-dlp are entirely in this APK.

Install from Klang → Settings → Plugins → Recommended plugins. Android asks for
installation confirmation. You can also download `plugin.apk` from a stable
release. Enable Music Import in Klang after installation. Android 16 / ARM64.

## Plugin API 1

See [API.md](API.md) for the complete provider-neutral protocol. The contract
uses Messenger messages with a response Messenger and random request token.
Metadata for large playlists travels over a pipe; finished audio streams through
a host-owned file descriptor. Cancellation kills the request's yt-dlp process.
The exported service currently accepts only the Klang package.

## Build and release

Run builds in CI or on a desktop, not a phone:

```
./gradlew :plugin:assembleRelease :plugin:lintRelease :plugin:testReleaseUnitTest
```

The manual **Release** GitHub workflow restores the repository signing key,
builds and tests, verifies the ARM64 bundle and retained ZIP constructors, and
publishes `plugin.apk`, `plugin.json` and their SHA-256 checksum files.
Update `version` in the manifest, embedded resource and Gradle configuration
before a new release. The signing identity must stay unchanged.

## Licensing and source

GPL-3.0, chosen for compatibility with the bundled youtubedl-android library.
Upstream licensing and source/build references are in [licenses](licenses/).
The complete custom implementation is here. FFmpeg, Python and Android runtime
binaries are supplied by youtubedl-android 0.18.1; see its reproducible build
instructions and source links. yt-dlp uses the Unlicense. No Liner code is shipped.
