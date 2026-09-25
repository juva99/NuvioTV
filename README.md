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

## License

[GNU General Public License v3.0](./LICENSE)
