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

## Minimal fork

`juva99/NuvioTV` is aligned with upstream's published `1.1.0-beta.5` tag.
Subtitle AutoSync, its settings, and its player integration come directly from
upstream. The fork's old subtitle alignment engines, reference scanner,
GitHub sync-failure reporting, and associated authorization UI have been removed.

The remaining fork changes are permanent signing and in-app updates for the
existing `com.nuvio.tv.debug` installation, release automation, IntroDB and avatar
URL defaults, RTL subtitle punctuation, app-language playback messages, and
protection against next-episode skipping from stale playback samples.
The RTL checks use small synthetic cues rather than the old sync fixtures.

## Fork release automation

**Follow Upstream Stable Releases** checks `NuvioMedia/NuvioTV` hourly (GitHub may
delay scheduled runs). The initial migration uses `1.1.0-beta.5`, but subsequent
automatic updates follow **stable releases only**, published after that baseline.
Published tags are resolved to pinned commits; unreleased `dev` changes and beta
releases are not automatically imported.

Each release is merged into `dev` while retaining only the documented fork
changes. **Minimal Fork Release** then runs upstream AutoSync and focused
player/updater tests, builds all five benchmark APKs, and checks their application
ID, version code, permanent signing certificate, and Dolby Vision native library.
Fork releases use the upstream stable tag and are published as stable releases,
so migrated apps can use the standard updater without a special subtitle-sync channel.
For old installed apps, the workflow also publishes compatibility prereleases
under every historical `v0.7.17`, `v0.8.4`, and `v0.9.0` subtitle-sync tag prefix,
including prefixes whose releases were deleted but whose Git tags remain.
Each contains the same newly signed universal APK, not the old sync algorithm.
Its installed version uses the upstream version, so subsequent updates use
the standard fork updater. These entries do not import upstream beta releases.
Builds predating the in-app updater still require a manual APK update.

Android version codes continue above the old fork builds (`2024` was the last
legacy release). The release notes record `nuvio-upstream-release` and
`nuvio-fork-version-code` markers to prevent duplicate publication and preserve
upgrade-safe numbering. Do not remove these markers. Manual releases must also
use an increasing version code; the release workflow checks it and records it.
The existing `NUVIO_SUBTITLE_BETA_*` signing secrets keep their names and key to
allow installed fork APKs to upgrade without uninstalling.
Compatibility entries publish before the stable release. If publication fails
partway through, the next run retries with a newer version code; compatibility
notes never mark the upstream stable release as completed.

`TMDB_API_KEY` is supplied through a GitHub Actions secret and read from the build
environment (or `local.properties` for local builds). Release publication stops
if the secret is missing. Never commit the key or expose it to pull-request builds.

Merge conflicts create one open resolution PR into `dev`; subsequent runs wait
for it to be merged. Resolve conflicts without reintroducing the old sync stack.
Other failed checks stop publication. A failed build may leave the merge on
`dev`; the next run retries unpublished upstream releases. Missed releases are
processed oldest first, one per run. Publication also refuses a build if `dev`
advances before it finishes.

To pause automation, disable **Follow Upstream Stable Releases** in Actions.
It can also be run manually. The repository must allow Actions to create PRs.
An explicitly requested manual release can publish a numbered upstream beta or
release candidate through **Minimal Fork Release**, with the correct prerelease
flag and the same legacy upgrade entries. This does not enable automatic beta
tracking.

## License

[GNU General Public License v3.0](./LICENSE)
