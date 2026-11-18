# Spool

A simple, fast, offline player for the audio and video you download with Seal (or anything else).

- Opens straight to a grid of channels, grouped by the artist tag yt-dlp writes (the channel name)
- Channel pictures: fetched quietly from YouTube in the background (failures retried daily), or picked from your gallery
- Remembers your position in every file
- Search, filters (audio / video / unplayed / in progress), rename, delete
- Audio and video in the same app: only the player screen changes
- Big controls, 15s back / 30s forward, playback speed, lock-screen and Bluetooth controls

## Seal setup

Output template: `%(title).200B.%(ext)s`, and turn on "Embed metadata" and "Embed thumbnail".
Spool groups by the embedded metadata, so no subfolders are needed.
Point Spool at your Seal folder (default `Download/Seal`).

## Install

Every push to `main` builds an APK with GitHub Actions and publishes it under **Releases**.
Open the latest release on your phone and install the APK. Updates install over the old version and keep your progress.

CI signs the APK with a key stored in the repo secret `SPOOL_KEYSTORE_BASE64` (base64 of a PKCS12 keystore, alias `spool`).
Keep a backup of that keystore: an APK signed with a different key can't update the installed app.
