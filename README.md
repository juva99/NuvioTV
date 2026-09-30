<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioTV/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Get Nuvio TV

- [Android TV on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android TV APK](https://github.com/NuvioMedia/NuvioTV/releases/latest)

## Build from source

```bash
git clone https://github.com/NuvioMedia/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## Subtitle synchronization

Select an add-on SRT or WebVTT subtitle in the player and use **Automatic sync**
in the subtitle timing dialog. Matching now uses the AutoSync V2 delay-first,
activity-alignment, and grouped-cue retiming engine adapted from
[DavidVamaiotu/NuvioTV](https://github.com/DavidVamaiotu/NuvioTV).
The existing player controls, status messages, and synchronized subtitle
playback remain in place. When no usable indexed reference is available, the
existing reference scanner is retained as a fallback.

**Aggressive subtitle matching** in playback subtitle settings is off by
default. Turning it on also searches other same-language add-on subtitles and
may select a better match. Without it, only the selected subtitle is matched.

## Fork release automation

In `juva99/NuvioTV`, **Follow Upstream Stable Releases** checks
`NuvioMedia/NuvioTV` hourly (GitHub may delay scheduled runs). It follows stable
semantic-version releases published after the `1.0.0` baseline, not prereleases.
It merges each upstream release's pinned commit into `dev`, preserving fork
changes, then calls the existing subtitle-sync beta release workflow with the
merged commit. The build still runs subtitle tests and verifies all five APKs,
their signing certificate, and native libraries before publishing.

Merge conflicts create an open PR into `dev` with the conflicting files listed;
subsequent runs wait for that PR instead of creating duplicates. Resolve the
conflicts and merge the PR to resume automatic publication. Other failed checks
stop publication and appear as failed Actions runs. A failed build may leave the
upstream merge on `dev`; the next run retries unpublished upstream releases.
Successful releases record an upstream marker in their notes to prevent
duplicates. Do not remove that marker. Multiple missed releases are processed
oldest first, one per run.

Beta numbering retains the `v0.9.0-beta-subtitle-sync.N` updater channel and uses
Android version code `2000 + N`; use that convention for manual releases too.
Both release workflows serialize beta publication. To pause automation, disable
**Follow Upstream Stable Releases** in Actions. It can also be run manually.
The repository must allow GitHub Actions to create pull requests.
Publication verifies that the built commit is still the release branch's tip.
If the branch advances during a build, retry from its current commit rather than
publishing an older revision.

## License

[GNU General Public License v3.0](./LICENSE)
