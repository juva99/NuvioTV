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

## Phone approval for locked profiles

In **Settings → Profiles → Manage Profiles**, open a profile's options and choose
**Phone approval**. After the profile PIN check (when enabled), scan the QR code
with the phone camera and subscribe to the topic in ntfy or its web/PWA app.
Selecting **Approve with phone** on the TV sends a two-minute unlock request
with **Yes** and **No** actions; the normal PIN unlock always remains available.
The topic is a random bearer capability shared by the same authenticated account
and profile across TVs. It is stored in the existing authenticated Supabase
profile-settings blob, so equal numeric profile IDs in different accounts do not
share a topic. Copying settings to another profile deliberately excludes the
pairing. Signing out or resetting local account data only clears the local cache;
it does not remotely revoke the shared pairing. Use the PIN-gated **Disable** or
**Regenerate QR** actions to revoke or rotate it.

Direct ntfy transport is a bearer capability: anyone who obtains the QR code or
topic can subscribe and approve or deny requests for that profile. Nuvio does
not provide an ACL through ntfy, so treat the QR/topic like a secret and
regenerate it if it is exposed. Each TV request includes a bounded device name
and uses a fresh response topic/token; only the first matching response is
accepted. The TV revalidates the shared pairing before unlocking and fails
closed on remote failure, cancellation, backgrounding, profile changes, or
timeout. Pairings are refreshed for all profiles on the selection screen and
kept out of automatic local-settings uploads and profile copies. Settings writes
preserve the latest remote pairing rather than a cached topic. Initial setup and
pairing changes are read back for confirmation, but the settings RPCs have no
compare-and-swap: simultaneous setup or settings writes can still race and may
require retrying. Atomic pairing updates require server support.
The QR URL is intended for ntfy subscription in a phone app or web/PWA.
HTTP actions are POST-only (no Safari GET approvals). An iOS proof of concept
has been reported, but native iOS behavior has not been device-verified and is
not asserted as compatible.

## Minimal fork

`juva99/NuvioTV` is aligned with upstream's published `1.1.0-beta.5` tag.
Subtitle AutoSync, its settings, and its player integration come directly from
upstream. The fork's old subtitle alignment engines, reference scanner,
GitHub sync-failure reporting, and associated authorization UI have been removed.

The remaining fork changes are permanent signing and in-app updates for the
existing `com.nuvio.tv.debug` installation, release automation, IntroDB and avatar
URL defaults, RTL subtitle punctuation, app-language playback messages, and
protection against next-episode skipping from stale playback samples, and
phone approval for locked profiles.
The RTL checks use small synthetic cues rather than the old sync fixtures.

## Fork release automation

**Follow Upstream Stable Releases** checks `NuvioMedia/NuvioTV` hourly (GitHub may
delay scheduled runs). The initial migration uses `1.1.0-beta.5`, but subsequent
automatic updates follow **stable releases only**, published after that baseline.
Published tags are resolved to pinned commits; unreleased `dev` changes and beta
releases are not automatically imported.

Each release is merged into `dev` while retaining only the documented fork
changes. **Minimal Fork Release** then runs upstream AutoSync and focused
player/updater/profile-approval tests, builds all five benchmark APKs, and checks their application
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
For an explicitly requested fork-only update to an already published baseline,
use a unique prerelease tag such as `1.1.0-beta.5.fork.1` (or `1.1.0-fork.1`
for a stable baseline). Increment the fork revision and Android version code;
never reuse the upstream tag. The dotted suffix on beta/RC baselines preserves
updater ordering below the next upstream beta/RC and final release. This does
not import unreleased upstream changes or enable automatic beta tracking.

## License

[GNU General Public License v3.0](./LICENSE)
