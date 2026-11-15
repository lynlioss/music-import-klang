# Klang music provider API 1

Music downloads and declarative lyrics are provider types in Plugin API 1, which
is still being developed. Implement an exported Android
Service returning `Messenger(...).binder` from `onBind`. Advertise the intent
`io.github.lynlioss.klang.MUSIC_IMPORT_V1` and embed your manifest with service
metadata `klang.plugin` referencing a raw JSON resource. Keep service/package
names permanent. Declare network access. The host binds from its foreground WorkManager worker.

Every request includes `Bundle.token` (random UUID), `Bundle.json` and
`Message.replyTo` (host Messenger). Respond with the same token. Reject unauthorized
calling UIDs via `Message.sendingUid`. The reference provider accepts only the
Klang application package. A package name identifies the caller; a provider can
add a pinned signing certificate for stronger caller authentication.

| Message code | Direction | Payload |
| --- | --- | --- |
| 1 RESOLVE | Host → provider | `json`: raw link string |
| 2 DOWNLOAD | Host → provider | `json`: one original song JSON, `output`: writable ParcelFileDescriptor |
| 3 CANCEL | Host → provider | token; stop associated work and native process |
| 10 RESULT | Provider → host | JSON string, or `metadata`: readable ParcelFileDescriptor for large metadata |
| 11 PROGRESS | Provider → host | JSON string with phase, progress 0..1, optional audioFormat/eta |
| 12 FAILURE | Provider → host | `json`: readable error message |

Resolve performs no music downloads. It returns:

```json
{
  "title": "Collection or song title",
  "collection": true,
  "artwork": "https://example.com/cover.jpg",
  "notice": null,
  "songs": [{
    "id": "provider:recording-id",
    "url": "https://example.com/song",
    "title": "Song",
    "artist": "Artist",
    "album": "Album",
    "duration": 200000,
    "artwork": "https://example.com/cover.jpg",
    "anyProviderField": "opaque to Klang"
  }]
}
```

Duration is milliseconds. The host retains each original song object as opaque
provider payload and passes it back verbatim after selection and duplicate checks.
Reserve `payload` for the host journal; don't emit that key in song objects.
Collections preserve song order. Maximum 10,000 songs / 16 MiB metadata. Individual
payloads and progress messages must stay below Binder's transaction limit.

`albumArtist` is an optional common album credit, separate from each track's
`artist` (which retains guests). Embed that credit in the downloaded file's
album artist tag. Link resolution and recording matching allow five minutes;
the host allows a 30-second grace period for resolve and 45 minutes per download.

Klang owns foreground scheduling and keeps the provider service bound throughout
DOWNLOAD. No second foreground service or background activity launch is needed.
The provider is destroyed when its last client unbinds. Write the complete tagged file to `output`; close your duplicate of the
descriptor. Return `{"extension":"opus","song":{...},"audioFormat":"OPUS · 140 kbps",
"recording":"https://..."}`. Accepted file types: mp3, m4a, opus, ogg, flac.
The host validates playable duration, music tags and embedded artwork before
publishing. Metadata enrichment may change the title/album but preserve identity.

Supported progress phases: matching, downloading, tagging. Terminal success/error
is a RESULT/FAILURE; never publish files directly to the user's library. The host
can cancel at any point, imposes metadata/download deadlines and unbinds when
finished. Stop work if the response Binder dies. Queue and duplicate decisions
stay in Klang; providers receive no library access.
