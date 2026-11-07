# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Workflow Rules
- **PLAN FIRST:** Before modifying any code, you must create a detailed implementation plan (`PLAN.md` or within the chat).
- **NO CODING:** Do not generate code, edit files, or run commands until I approve your plan.
- **STRUCTURE:** Plans must include: 1) Files to be edited, 2) Specific changes, 3) Potential risks, 4) Testing steps [1, 5, 11].
- **CLAUDE CODE:** If a task involves multiple files or ambiguity, initiate Plan Mode (Shift+Tab) [1, 14].

## What This App Is

SHazTunez (tunez, app id `app.shaz.tunez`) is a personal Android music
player for two users (Annie and Steve).  It plays mp3/flac/ogg/wav files
from one folder the user picks (normally `Music/tunez`).  Each song's
rating is the last field of its filename; checkboxes pick which ratings
play, shuffle never repeats a song till every picked song has played,
and it casts to Chromecast.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Build and install on connected device
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (requires device)
./gradlew lint                   # Lint analysis
./gradlew clean build            # Full clean build
./gradlew bundleRelease          # Play upload .aab - see Releasing
```

There's no system JDK: use the one gradle downloaded,
`JAVA_HOME=~/.gradle/jdks/jetbrains_s_r_o_-21-amd64-linux.2`.
adb is `~/Android/Sdk/platform-tools/adb`.

## Releasing

- Release builds are signed with the upload key `~/src/keys/tunez-upload.jks`
  (password in `~/src/keys/tunez-upload.txt`), found thru
  `keystore.properties` in the project root - git ignored, never commit it.
  No file = release builds come out unsigned.
- `./gradlew bundleRelease` ->
  `app/build/outputs/bundle/release/app-release.aab` is what gets uploaded
  to Play (it carries the R8 mapping file too).
- Versions come from the build date: `versionName` = `yyyymmdd`, or
  `yyyymmdda`, `b`, ... when today already has a `v<yyyymmdd...>` git tag;
  `versionCode` = `yyyymmdd * 100 + n` (plain = 0, a = 1, ...).  After
  every Play upload tag the release commit (`git tag v20261107` and push
  the tag) or the next build reuses that version and Play rejects it.
- R8 + resource shrinking are on for release.  Anything found by name at
  runtime needs a keep rule in `app/proguard-rules.pro` - e.g.
  `CastOptionsProvider` (named in the manifest's cast meta-data).
- Release builds aren't debuggable (no `adb run-as`); `tunez_debug.log`
  in `Music/tunez` still works.
- Store material (icon, feature graphic, screenshots, listing text, form
  notes) lives in `docs/store/`; `art.py` / `launcher.py` there redraw the
  icons.  The website n privacy policy are `https://shaz.app/tunez/` -
  source in `~/web/pc/shaz.app/tunez/`, published by pushing that repo
  then `sshp` + `cd public_html && git pull`.

## Architecture

| file | role |
|---|---|
| `MainActivity.kt` | the UI (rating checkboxes, song table, rate dialog, pick-folder panel + picker, play fab) plus the top level filename helpers (`splitfn`, `tickName`, `renameRating`, `SONG_EXTS`, `RATINGS` ...) |
| `MusicService.kt` | foreground `mediaPlayback` service that owns all playback state: `MediaPlayer`, MediaSession + notification, playlist / done / picks, ratings, casting, bt / noisy-pause watchdog, audio focus, the `Dbg` log.  Talks to the UI thru `PlaybackCallback` |
| `SongStore.kt` | the picked folder via the Storage Access Framework |
| `LocalHttpServer.kt` | serves songs out of `SongStore` to the cast device on port 8765 (range requests ok) |
| `CastOptionsProvider.kt` | cast sdk setup - kept from R8 by name |

**Folder access:** no storage permissions - the app uses the Storage
Access Framework. On first run (or if access is lost) `MainActivity`
opens the folder picker at `Music/tunez`; the grant is persisted and its
tree uri kept in prefs (`tree`). `SongStore.kt` wraps it: `scan ()` (one
`DocumentsContract` query per dir) builds a relative path -> document uri
map, plus `rename`/`delete`/`openFd`/`readText`/`writeText`. Playback,
album art, the cast http server, renames n deletes all go thru it.
`done.txt`, `skip.txt` and `tunez_debug.log` live in `Music/tunez` too.

**Music files on device** (`/Music/tunez/`): songs (`.mp3`, `.wav`,
`.flac`, `.ogg` - `SONG_EXTS`) sit in this directory or any (non-hidden)
subdirectory of it; songs are tracked as paths relative to it
(`song.mp3`, `sub/song.flac`). Folders are just for organizing - each
file's rating tag is suffixed on the filename, so re-rating a song is
just a rename in place (never moves folders, keeps the extension). The
filename helpers only look at the part after the last `/`. Fields are
split on `` ` `` (`SEP`) and trimmed: the last is always the rating, the
one before it the title, the first the group, anything between is the
middle (`x`) - 3 fields = no middle. `_` shows as a space. `splitfn`
appends the subdir path onto `x` with a leading `/` (`1976 High Voltage
/Live/Encores`). Old style names (`group-middle-title - rating.mp3`, or
no ` - rating` = `new`) found at startup get renamed on disk to the
`` ` `` style by `tickName`/`tickFile` (done.txt updated to match).
Re-rating replaces the last field; unrated songs are written as
`` ` new``. There's no fixed rating list: `RATINGS` is rebuilt by
`MusicService.reRate ()` from the distinct suffixes on the files (plus
`new` only if unrated songs exist), after the scan and after every
rerate/delete, so a rating vanishes with its last song. New ratings
come from renaming files outside the app. The top of the main screen
shows one checkbox per rating, alphabetized, in a wrapping `ChipGroup`;
the rate popup lists them the same way (`new` always last). The rate
popup has a top-right close button.

**Play/pause:** opening the app starts playing (`rePlay ()` on a cold
start, `resume ()` if the service already had a playlist). Toggling a
rating checkbox or shuf calls `rePlay (false)` - list rebuilt, first song
loaded but paused. The fab icon flips play/pause via `onPlayState`.
If no picks are saved at startup, every rating gets picked.

**Filename format** (required):
``group ` year album song# ` title ` rating.mp3`` (or `.wav`/`.flac`/`.ogg`)

**State n where it lives:**
- `pick` (checked ratings), `shuf` - prefs `"prf"`, saved right away
- `tree` - prefs, the picked folder's uri (its grant is persisted)
- `done` - `done.txt` in the folder; songs played this round.  Cleared
  only when the picks have songs n they've all been played
- `skip.txt` - append-only log of skipped songs, nothing reads it
- `play`, `ppos`, `song` - in memory only (the service's playlist)

**Data flow:**
1. `MainActivity` starts n binds `MusicService`; no saved folder = the
   pick-folder panel, then the picker, then the service gets restarted
2. service `onCreate` -> `SongStore.saved` -> `scan` -> `tickFile` (old
   style renames) -> `reRate` -> picks / done loaded -> `rePlay`
3. the service drives the UI thru `PlaybackCallback`: `onPlaylistReady`,
   `onSongChanged`, `onPlayState`, `onAlbumArtChanged`,
   `onCastVolumeChanged`
4. song end, skip, media keys -> `next ()`: marks done, drops the song
   from `play`, loads the next one (or advances the cast queue).  A row
   tap is `next (row)`: jumps there, logs a skip, drops nothing

**Casting:** `loadCastQueue` hands the receiver the current song + the
next 2 as a real queue, so "Hey Google, skip" spoken to the speaker
works.  The receiver advancing is the source of truth
(`handleCastQueueAdvance` keeps `play`/`ppos`/`song`/done in sync).  A
wifi lock is held while casting (needs `WAKE_LOCK`), and a dropped
session gets retried by `tryReconnectCast`.

**Parsing/display helpers** (top of `MainActivity.kt`, unit tested in
`SongNameTest`):
- `splitfn ()` - filename -> `FNTitle (dir = rating, grp, x, ttl)`
- `fmtfn ()` - display row, title n rating bolded by position
- `tickName ()` / `tickParts ()` - old style -> `` ` `` names / the fields
- `renameRating ()`, `songExt ()`, `songMime ()`, `isSong ()`

**Data classes:**
```kotlin
data class FNList(val dir: String, var fn: MutableList<String>)
data class FNTitle(val dir: String, val grp: String, val x: String,
                   val ttl: String)
```

## Debugging

- `Music/tunez/tunez_debug.log` (the `Dbg` object, trimmed at 1MB) -
  tags `TunezSkip` (playback / next), `TunezCast`, `HTTP`, `TunezRate`,
  `TunezLocal`, `TunezFocus`, `TunezStore` (scan time), `TunezBT`, and
  `TunezCrash` (uncaught exceptions, with stack)
- handy adb: `input keyevent KEYCODE_MEDIA_NEXT` (/ `_PAUSE` / `_PLAY`),
  `dumpsys media_session` (playing state + title), `dumpsys deviceidle
  force-idle` (doze test), `screenrecord` (stops while the screen's off)
- song names have backticks: single quote them in a shell
- release builds can't `run-as`, but the log still works

## Known Incomplete Areas

- Play Store: dev account made, waiting on Google's identity check;
  internal testing release next (see `docs/store/listing.md`)
- local Apache (`/var/www/html/sh` symlink) gives 403 for `~/web` pages
