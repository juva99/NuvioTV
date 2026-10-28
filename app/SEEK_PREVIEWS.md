# Experimental seek previews

Enable **Settings → Playback → Player Interface → Seek previews (experimental)**,
then tap left/right for the normal 10-second skip, or hold to scrub faster, with
controls visible or hidden. The seek still commits immediately on release; it
does not wait for a thumbnail. The preview stays available for up to three seconds
after a skip, so a held key is not required to see it.

The thumbnail represents an approximate nearby keyframe. The label is the selected
seek time, not a claimed exact timestamp of the extracted frame.

## First-version support

- Off by default; internal players only (ExoPlayer and MPV). The opt-in is
  device-local and is excluded from profile settings sync.
- Android 8.1 / API 27 or later, using scaled platform frame extraction.
- Known progressive HTTP(S) video formats, with valid HTTP byte-range responses.
- No live streams, adaptive manifests, local torrent playback, or known DRM tracks.
- Unknown/unsupported formats, platform decoder failures, and self-signed TLS
  endpoints fall back to the existing timestamp-only UI. Preview decoding support
  can differ from the main playback engine.

This version does not discover provider storyboards or generate thumbnails ahead
of time. Those can be added as separate providers later.

## Playback safeguards

- The card responds immediately. Uncached extraction waits 180 ms to coalesce
  inputs, then may finish after a quick tap has committed. Cached frames are instant.
- One process-wide background decoder thread, even across player instances.
- One operation in flight; only the latest target is retained. No key-repeat queue.
- Ten-second position buckets and an 8 MiB session LRU cache.
- A 240 dp-wide card keeps the last image dimmed while the next image loads.
  Decoder output remains 320 × 180, nearest sync frame, with no exact-frame decoding.
- At least 500 ms between completed extractions and subsequent uncached requests.
- Each extraction's range reader has a 6 MiB payload budget and a 2.5-second
  deadline, with 1-second connection/read timeouts. Its 128 KiB read window bounds
  transient network memory. Cookies and request headers are preserved.
- Redirects are handled explicitly (up to five hops) within the same deadline.
  Non-empty redirect bodies are cancelled rather than drained, and source auth
  headers survive cross-origin redirects as in playback. The resolved URL is reused
  for later reads in that extraction. HTTPS downgrades fail closed.
- Invalid/ignored ranges are rejected without downloading the whole response.
  Standard TLS verification is required; the playback client's separate TLS
  fallback is not used because it would escape the preview request budget.
- At least five seconds buffered ahead of the **real** playhead, or essentially
  the rest of the file buffered. The UI preview timeline is not a buffer signal.
- Buffer pressure aborts network reads and imposes a 15-second cooldown. Failed
  or slower-than-1.5-second extractions also cool down; three such results disable
  further extraction for that source session. Cache hits remain available.
  Expected buffering after a committed skip pauses reads until the buffer recovers,
  without adding that cooldown; the three-second preview window still applies.
- Preview expiry, backgrounding, player release, source changes, and disabling the
  feature abort reads. Session/revision checks reject late results.

**Limits:** coroutine cancellation cannot forcibly stop a native decoder. The
network deadline bounds reads, not native CPU/codec execution. A blocked native
operation is never replaced with an overlapping worker. Some platform decoders
still allocate full-resolution intermediates, so low-resolution output is not a
guarantee of cheap 4K HDR extraction.

## Validation

Automated coverage lives in `SeekPreviewCoordinatorTest`, `SeekPreviewPolicyTest`,
and `SeekPreviewRangeReaderTest`. The PR build workflow runs these alongside the
existing regression tests.

```powershell
.\gradlew.bat :app:testFullDebugUnitTest --tests 'com.nuvio.tv.ui.screens.player.SeekPreview*Test'
```

An opt-in `SeekPreviewFrameExtractorDeviceTest` exercises the real platform decoder
and bounded reader on a device. Supply `seekPreviewUrl` as an instrumentation
runner argument pointing to a legal, range-capable HTTP video. Without that
argument (or below API 27) the smoke test is skipped, rather than depending on an
external video service in the default test suite.

Emulator validation: the full debug APK and instrumentation APK build and run on
an Android TV API 36 x86_64 AVD. Both extraction and production-player integration
tests **pass** with `https://samplelib.com/lib/preview/mp4/sample-20s.mp4` (1080p,
H.264 High profile, level 4.0, yuv420p). An earlier reference trailer returned no
frame even for the local-file baseline; compatibility still depends on the clip.

Opt in to the additional production-player test with `seekPreviewPlayer=true`.
It hosts the real `PlayerScreen` with the supplied URL (without adding a production
debug/deep-link entry point), enables previews temporarily, waits for playback and
a healthy buffer, verifies immediate 10-second forward/backward skips and
post-commit previews, and captures both overlays to
`cache/seek-preview-url-player.png` and `cache/seek-preview-url-seek-only.png` in
the target app. It restores the previous opt-in afterward. The test drives the
same runtime events as the D-pad handler, not actual remote key/focus behavior.
Physical-TV codec, remote-input, and performance testing are still required.

Before enabling by default or declaring production readiness, test on both a TV
emulator and lower-powered physical TVs:

1. Enable the setting, play a seekable HTTP MP4/MKV, wait for a healthy buffer.
2. Verify a tap skips immediately and can show a preview afterward; check long holds
   and automatic preview expiry in both overlays.
3. Reverse direction rapidly; verify no stale image is labelled as the new target.
4. Release the key, switch sources/episodes, background the app, and exit during
   extraction. Verify reads stop and old images don't reappear.
5. Test non-range servers, expired links, cookies/auth headers, slow responses,
   live/adaptive video, local torrents, API 24–26, and both internal engines.
6. Compare feature off/on using identical 1080p and 4K HEVC/HDR sources. Record
   dropped frames, rebuffering, memory, extra transferred bytes, and preview latency.
7. Check long titles, subtitle/skip overlays, screen edges, and RTL locales for
   clipping, overlap, and unchanged D-pad focus behavior.

An emulator validates layout and interaction, not physical TV decoder contention,
HDR quality, or low-memory performance. Screenshots from the running app and
hardware measurements remain required before promoting this experiment.
