# Research and verification

The optional plugin owns Spotify/YouTube metadata, recording matching and source downloads. Klang owns the provider-neutral UI, duplicate checks and publication. Three songs can download concurrently, with up to eight fragments each. Source codecs are preserved, without audio transcoding.

## Research and verification (2026-11-15)

The reference checkout used for research was `/tmp/klang-tryliner-desktop`. Liner's
[audio selection](https://github.com/tryliner/desktop/blob/main/src/shared/youtube/innertube/innertubeAudioFormats.ts)
prefers itags 251 (Opus), 140 (AAC), 250, 249 and 139. It uses YouTube Music
catalog matching, multiple client fallbacks, stream probes and parallel range
downloads. Its WEB_REMIX path depends on Electron's PO-token provider and
youtubei.js, so that component cannot simply run in this Android worker. Klang
keeps yt-dlp's maintained extractor and bundled QuickJS for stream resolution.
This change independently implements the catalog and metadata behavior; it
does not depend on Liner's services or ship its code.

Open-source protocol references:

- [ytmusicapi search](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/mixins/search.py)
  and [response parser](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/parsers/search.py) (MIT).
- [spotDL YouTube Music provider](https://github.com/spotDL/spotify-downloader/blob/master/spotdl/providers/audio/ytmusic.py):
  song-first catalog search with structured artist/duration matching.
- [yt-dlp YouTube extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_video.py):
  source quality sorting, Premium formats and DRC distinction.
- [YouTube Music audio quality](https://support.google.com/youtubemusic/answer/9076559):
  higher settings can reach 256 kbps AAC/Opus. Those settings do not grant
  anonymous downloads access to Premium formats. Klang currently has no
  authenticated YouTube account/cookie import.

Live checks of the reported examples:

- `9PumlOWjXMM` (hey now): accessible best stream was itag 251, Opus,
  139.011 kbps, 48 kHz stereo. No FLAC or higher-rate stream was exposed.
  A standalone yt-dlp/FFmpeg import used GNX catalog metadata and cover art.
  All 10,875 compressed audio packet hashes matched between source WebM and
  tagged Opus. Tags and embedded artwork were checked with ffprobe; full
  decoding to a null sink completed without errors.
- Spotify `1EaKU4dMbesXXd3BrLCtYG` (ELEMENT.): embed returned Kendrick Lamar,
  208,733 ms and explicit status. A public catalog search returned `SwvzXM-EVrQ`,
  ELEMENT., Kendrick Lamar, 209 seconds, and DAMN. COLLECTORS EDITION.; full
  extraction agreed. Current flat search returned both channel and uploader,
  so the old failure was not reproducible with today's service response. The
  matched recording downloaded successfully with readable title/artist/album
  tags and embedded artwork in a standalone yt-dlp run.

These are standalone research checks, not an Android build or proof of device
playback. Captured minimal catalog responses cover these two cases in CI.

## Album and pagination checks

Spotify GNX (`0hvT3yIEysuuvkK73vgdcW`) returned 12 embedded tracks and a matching public album count. Album metadata and track numbers are retained. A standalone yt-dlp flat extraction of `https://music.youtube.com/browse/MPREb_wCND3crJyDP` resolved the album playlist and returned all 12 tracks.

Liner's `spotifyPathfinder.ts` uses the web-player `fetchPlaylistContents` operation for pagination. A fresh anonymous token from Spotify's public embed successfully read a live public playlist through that endpoint (HTTP 200, 50 tracks/count 50). The plugin uses the same public protocol with bounded pages and a Web API fallback. It reports incomplete imports explicitly. This check does not verify a playlist above 100 tracks on Android.

## CI and device checks

The plugin's manual Release workflow builds, lints, tests and verifies ARM64 runtimes and the reflected ZIP constructors. Klang's separate CI checks the host and rejects download runtimes in its APK. No Android builds ran on the phone during this change.

Test song, album, EP and playlist imports; metadata/artwork in another player; duplicate warnings and overrides; cancellation; network interruption; process recreation; playlist order; background-only completion alerts; plugin installation and updates. Verify original Opus/AAC remains unchanged. Anonymous YouTube source quality, matching accuracy and speed still require device testing.
