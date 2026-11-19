# Spool: project notes for Claude

Personal, offline Android player for audio/video downloaded with Seal (yt-dlp). Replaces "Simple ABP".
Owner's phone: Samsung S24 on Android 17. Installed by sideloading the APK; not on the Play Store.

## Status

- Builds locally with `assembleRelease` and runs on the owner's phone.
- GitHub Actions can't run at the moment: the GitHub account is locked for billing. Build locally until that's fixed.
- Repo is public. `signing/` is gitignored; `signing/spool.jks` is present locally (the owner keeps a backup).

## Build

Needs a JDK 17+ on `JAVA_HOME` (this machine: Microsoft JDK 21) and the Android SDK at `%LOCALAPPDATA%\Android\Sdk`
(SDK only; Android Studio isn't installed). AGP downloads missing SDK platforms itself.
`signing/spool.jks` signs both build types when present; without it the release APK is unsigned.

Git Bash (Claude's shell):

```bash
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew :app:compileReleaseKotlin   # quick compile check
./gradlew :app:testDebugUnitTest      # JVM unit tests (app/src/test)
./gradlew assembleRelease             # APK: app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/platform-tools/adb.exe" install -r app/build/outputs/apk/release/app-release.apk
```

PowerShell (owner):

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew assembleRelease
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
```

Install needs the phone connected over USB or wireless debugging; otherwise the owner copies the APK over.

## Stack and pinned versions

AGP 9.1.1 (built-in Kotlin) · Gradle 9.3.1 (wrapper) · Kotlin 2.3.10 (pinned via buildscript classpath to match the Compose plugin)
· compileSdk 37, targetSdk 36, minSdk 31 · Compose BOM 2026.09.00 · Media3 1.11.1 · plain SQLite (no Room/KSP) · no DI framework, no icon library.
No sources jars in the Gradle cache: to check a Media3 API, unzip `classes.jar` from the .aar under
`~/.gradle/caches/modules-2/files-2.1/androidx.media3/` and use `javap`.

## How it works

- `data/Library`: reads MediaStore (audio + video) under the library folder (default `Download/Seal`, set in Settings).
  Channel = artist tag only (yt-dlp writes the channel name). Owner uses a flat folder, **no subfolder grouping** (removed on request).
  If MediaStore lacks the artist, it's read from the file via `TagReader` and cached in prefs (`artist_cache_v2`; bump the
  name to force a re-read). MediaStore and Media3 both ignore Matroska tags, so `.webm`/`.mkv` go through `data/MatroskaTags`
  (small EBML reader, header area only).
  Unindexed files under the folder are passed to MediaScanner.
- `data/ProgressStore`: per-file progress (key = relative path + file name), in memory + SQLite. Finished = within 15s of the end.
- `data/Thumbnails`: `ContentResolver.loadThumbnail`, falling back to embedded art read via `TagReader` (Opus) with a disk cache.
- `data/ChannelArt`: channel pictures. Auto-fetched silently at startup and on library changes (failures retried after 24h);
  manual fetch in Settings and per channel; or pick from gallery. YouTube flow: tag text -> video link -> oEmbed `author_url`
  -> channel page `og:image`. Only network use in the app. Twitter/other sources: not yet; depends on what metadata the downloader writes.
- `data/FileOps`: rename (MediaStore update, falls back to File.renameTo) and delete; progress follows renames.
  App uses MANAGE_EXTERNAL_STORAGE ("All files access").
- `playback/PlaybackService`: Media3 MediaSessionService + ExoPlayer; 15s back / 30s forward; speed persisted; saves position every 5s and on pause.
  The session wraps the player in `SpoolPlayer` (ForwardingSimpleBasePlayer): next/previous from headphones/Bluetooth/car = forward/back.
  Needed because the queue is one item and Media3 hides skip actions when SLOT_BACK/SLOT_FORWARD buttons are set.
  `onPlaybackResumption` + `MediaButtonReceiver` in the manifest: headset "play" resumes the last unfinished file.
- `ui/MainActivity`: one MediaController for the activity's whole life (onCreate..onDestroy, reconnects if the session drops it).
  Deliberately not onStart/onStop: the UI's Media3 state holders would briefly show a released controller's empty state.
- Player UI state comes from media3-ui-compose state holders (`rememberPlayPauseButtonState`, `rememberProgressStateWithTickInterval`,
  `rememberPlaybackSpeedState`, seek back/forward states, `rememberCurrentMediaItemState`, `rememberPresentationState`); no polling of our own.
- Home lists/counts are keyed on `PlayStatus` (via `Map<String, Progress>.statuses()`), not on the progress map, which changes every 5 s while playing.
- No disk work in constructors that run in `Application.onCreate` (ChannelArt/Thumbnails/Library load lazily or on IO).
- Screens and the mini player pad for horizontal `safeDrawing` insets (landscape side nav bar, cutout).
- Media3's `@UnstableApi` needs `@file:androidx.annotation.OptIn(UnstableApi::class)`; Kotlin's `OptIn` has no effect on it.
- UI (Compose, Material You dynamic color): Home (search, filter chips, Continue row, channel grid), Channel (16:9 grid, newest first),
  Player (same screen for audio and video; landscape video goes fullscreen; info button opens a sheet with all file details and tags),
  mini player, long-press sheets, Settings, permission screen.

## Owner's preferences

- As simple as possible, offline-first, per-file progress (like Simple ABP). Feel native, modern, very fast.
- Normal-size controls and text (an earlier "big controls" preference was dropped).
- Titles are never cut off with an ellipsis: wrap them, or use `basicMarquee()` where there's only one line (mini player, top bar).
- Cover art keeps its own aspect ratio on the player and mini player (`CoverArt`): no forced square, no letterbox around it.
- Channel screen top bar: back, name, item count only (no picture button, no sort). Channel pictures are managed by long-pressing a channel on Home.
- Use current Android/Jetpack/Media3 APIs wherever applicable.
- Seal output template: `%(title).200B.%(ext)s` (no `[id]`), "Embed metadata" and "Embed thumbnail" on, everything in `Download/Seal`.

## Next up

Open bug: switching between files of the same channel sometimes doesn't work until the owner plays another channel's file
and comes back. Cause not found yet; get exact steps and what the screen shows.

Test on the phone: Seal videos grouped by channel (.webm/.mkv), Bluetooth play/pause/next/previous, cover shapes, info sheet. Also: background playback/notification, resume, rename/delete, channel pictures (needs VPN in China).
Later ideas: folder picker instead of a text field, auto-play next in channel (off by default), sleep timer, baseline profile.

## Git

Don't use branches except main.

When your change is done, commit to main.
test and stage your files/hunks only.
use standard commit subjects, not the declarative sentences you may see in git history.

You are only responsible for your own changes. parallel sessions own their work under this same rule.

Git stores text as LF (`.gitattributes`: `text=auto`, `.bat` stays CRLF); the Windows working tree is CRLF and is
normalized on commit. Never commit `signing/` or anything derived from the keystore (such as a base64 copy for a CI
secret): the repo is public.
