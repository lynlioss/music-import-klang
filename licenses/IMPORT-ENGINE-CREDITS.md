Music Import for Klang bundles youtubedl-android 0.18.1 (GPL-3.0), including yt-dlp, Python,
QuickJS and FFmpeg. Native executables are packaged by the upstream Android
library rather than downloaded as a separate app.

- Android bundle, source and build instructions: https://github.com/JunkFood02/youtubedl-android
- Python build instructions: https://github.com/JunkFood02/youtubedl-android/blob/master/BUILD_PYTHON.md
- FFmpeg build instructions: https://github.com/JunkFood02/youtubedl-android/blob/master/BUILD_FFMPEG.md
- yt-dlp source and license: https://github.com/yt-dlp/yt-dlp (Unlicense)
- FFmpeg source and licensing: https://ffmpeg.org/legal.html
- Python source and licensing: https://docs.python.org/3/license.html
- QuickJS source and licensing: https://bellard.org/quickjs/ (MIT)

The plugin can refresh the yt-dlp Python extractor from upstream stable releases.
The native Python, QuickJS and FFmpeg binaries are updated with the APK.

Spotify metadata matching follows the approach used by spotDL
(https://github.com/spotDL/spotify-downloader). spotDL is not bundled; the plugin
reads public Spotify metadata and passes matched YouTube recordings to yt-dlp.
