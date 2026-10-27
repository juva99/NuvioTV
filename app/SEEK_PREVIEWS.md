# Experimental seek previews

Enable **Settings → Playback → Player Interface → Seek previews (experimental)**,
then hold left/right on the seek bar or while the player controls are hidden.
Releasing the direction key commits the seek exactly as before.

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

- No native decoder/network client starts until an eligible seek is held for 180 ms.
- One process-wide background decoder thread, even across player instances.
- One operation in flight; only the latest target is retained. No key-repeat queue.
- Ten-second position buckets and an 8 MiB session LRU cache.
- 320 × 180 maximum requested output, nearest sync frame, no exact-frame decoding.
- At least 750 ms between completed extractions and subsequent uncached requests.
- Each extraction's range reader has a 6 MiB payload budget and a 2.5-second
  deadline, with 1-second connection/read timeouts. Its 128 KiB read window bounds
  transient network memory. Cookies and request headers are preserved.
- Invalid/ignored ranges are rejected without downloading the whole response.
  Standard TLS verification is required; the playback client's separate TLS
  fallback is not used because it would escape the preview request budget.
- At least five seconds buffered ahead of the **real** playhead, or essentially
  the rest of the file buffered. The UI preview timeline is not a buffer signal.
- Buffer pressure aborts network reads and imposes a 15-second cooldown. Failed
  or slower-than-1.5-second extractions also cool down; three such results disable
  further extraction for that source session. Cache hits remain available.
- Commit, backgrounding, player release, source changes, and disabling the feature
  abort reads. Session/revision checks reject late results.

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

For diagnosis, `seekPreviewLocalBaseline=true` first downloads the supplied fixture
(up to 32 MiB by declared size) to a temporary app-cache file and checks the same
platform retriever without the HTTP adapter. The file is deleted afterward. This
distinguishes device/clip decoding limitations from range-reader failures.

Initial emulator validation: the full debug APK and instrumentation APK build,
and the app installs and launches on an Android TV API 36 x86_64 AVD. The reference
trailer smoke test did **not** pass: the platform retriever returned no frame even
for the local-file baseline. Actual thumbnail extraction and the player overlay
therefore remain unvalidated on this image; physical-TV codec/performance testing
is still required. Do not treat successful unit tests as native decoder validation.

Before enabling by default or declaring production readiness, test on both a TV
emulator and lower-powered physical TVs:

1. Enable the setting, play a seekable HTTP MP4/MKV, wait for a healthy buffer.
2. Verify quick taps don't fetch previews and long holds do, in both overlays.
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
