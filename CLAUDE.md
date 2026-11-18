# Spool: project notes for Claude

Personal, offline Android player for audio/video downloaded with Seal (yt-dlp). Replaces "Simple ABP".
Owner's phone: Samsung S24 on Android 17. Installed by sideloading the APK; not on the Play Store.

## Status (handoff from the first session)

- All code is written but has **never been compiled**. First job: get `assembleRelease` green, then install and test on the phone.
- GitHub Actions can't run at the moment: the GitHub account is locked for billing. Build locally until that's fixed.
- Repo is public. `signing/` is gitignored; `signing/spool.jks` is present locally (the owner keeps a backup).

## Build (Windows, PowerShell)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew assembleRelease
# APK: app\build\outputs\apk\release\app-release.apk
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
```

If Android Studio / the SDK isn't installed yet, the owner installs it first (default setup accepts SDK licenses).
AGP downloads missing SDK platforms itself.

## Stack and pinned versions

AGP 9.1.1 (built-in Kotlin) · Gradle 9.3.1 (wrapper) · Kotlin 2.3.10 (pinned via buildscript classpath to match the Compose plugin)
· compileSdk 37, targetSdk 36, minSdk 31 · Compose BOM 2026.08.00 · Media3 1.11.1 · plain SQLite (no Room/KSP) · no DI framework, no icon library.
These versions were chosen from release notes, not verified by a build: adjust if the build disagrees.

Likely first-build trouble spots (APIs written from memory):
- `PlaybackService`: `CommandButton.Builder(ICON_SKIP_BACK_15)`, `.setSlots(...)`, `MediaSession.Builder.setMediaButtonPreferences(...)`
- `PlayerScreen`: `androidx.media3.ui.compose.PlayerSurface(player = ..., modifier = ...)`
- `TagReader`: `MetadataRetriever.retrieveMetadata(context, mediaItem)` (may be deprecated/replaced by a builder API)
- AGP 9 DSL details in `app/build.gradle.kts`

## How it works

- `data/Library`: reads MediaStore (audio + video) under the library folder (default `Download/Seal`, set in Settings).
  Channel = artist tag only (yt-dlp writes the channel name). Owner uses a flat folder, **no subfolder grouping** (removed on request).
  If MediaStore lacks the artist (some video containers), it's read from the file via `TagReader` and cached in prefs.
  Unindexed files under the folder are passed to MediaScanner.
- `data/ProgressStore`: per-file progress (key = relative path + file name), in memory + SQLite. Finished = within 15s of the end.
- `data/Thumbnails`: `ContentResolver.loadThumbnail`, falling back to embedded art read via `TagReader` (Opus) with a disk cache.
- `data/ChannelArt`: channel pictures. Auto-fetched silently at startup and on library changes (failures retried after 24h);
  manual fetch in Settings and per channel; or pick from gallery. YouTube flow: tag text -> video link -> oEmbed `author_url`
  -> channel page `og:image`. Only network use in the app. Twitter/other sources: not yet; depends on what metadata the downloader writes.
- `data/FileOps`: rename (MediaStore update, falls back to File.renameTo) and delete; progress follows renames.
  App uses MANAGE_EXTERNAL_STORAGE ("All files access").
- `playback/PlaybackService`: Media3 MediaSessionService + ExoPlayer; 15s back / 30s forward; speed persisted; saves position every 5s and on pause.
- UI (Compose, Material You dynamic color): Home (search, filter chips, Continue row, channel grid), Channel (2-col 16:9 grid, sort),
  Player (same screen for audio and video; landscape video goes fullscreen), mini player, long-press sheets, Settings, permission screen.

## Owner's preferences

- As simple as possible, offline-first, big controls, per-file progress (like Simple ABP). Feel native, modern, very fast.
- Seal output template: `%(title).200B.%(ext)s` (no `[id]`), "Embed metadata" and "Embed thumbnail" on, everything in `Download/Seal`.

## Next up after a green build

Test on the phone: Opus covers, artist grouping for video files, background playback/notification, resume, rename/delete, channel pictures (needs VPN in China).
Later ideas: folder picker instead of a text field, auto-play next in channel (off by default), sleep timer, baseline profile.
