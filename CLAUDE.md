# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Workflow Rules
- **PLAN FIRST:** Before modifying any code, you must create a detailed implementation plan (`PLAN.md` or within the chat).
- **NO CODING:** Do not generate code, edit files, or run commands until I approve your plan.
- **STRUCTURE:** Plans must include: 1) Files to be edited, 2) Specific changes, 3) Potential risks, 4) Testing steps [1, 5, 11].
- **CLAUDE CODE:** If a task involves multiple files or ambiguity, initiate Plan Mode (Shift+Tab) [1, 14].

## What This App Is

SHazTunez (tunez) is a personal Android music player for two users (Annie and Steve). It reads MP3s from a structured directory on the device, supports shuffle with skip-history tracking (so recently played songs aren't replayed), and lets users select which music directories are active.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Build and install on connected device
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (requires device)
./gradlew lint                   # Lint analysis
./gradlew clean build            # Full clean build
```

## Architecture

This is a **single-Activity, single-file app**. Nearly all logic lives in `app/src/main/java/app/shaz/tunez/MainActivity.kt`. There are no fragments, ViewModels, repositories, or services — everything is inline.

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
`` ` new``. There's no fixed rating list: `RATINGS` is rebuilt by `MusicService.reRate ()` from the distinct
suffixes on the files (plus `new` only if unrated songs exist), after the
scan and after every rerate/delete, so a rating vanishes with its last
song. New ratings come from renaming files outside the app. The top of
the main screen shows one checkbox per rating, alphabetized, in a
wrapping `ChipGroup`; the rate popup lists them the same way (`new`
always last). The rate popup has a top-right close button.

**Play/pause:** opening the app starts playing (`rePlay ()` on a cold
start, `resume ()` if the service already had a playlist). Toggling a
rating checkbox or shuf calls `rePlay (false)` - list rebuilt, first song
loaded but paused. The fab icon flips play/pause via `onPlayState`.
If no picks are saved at startup, every rating gets picked.

**Filename format** (required): ``group ` year album song# ` title ` rating.mp3`` (or `.wav`/`.flac`/`.ogg`)

**Key in-memory state:**
- `pick` — which dirs are selected (checkboxes)
- `play` — current playlist (ordered or shuffled)
- `done` — songs completed this rotation (cleared when all played)
- `skip` — manually skipped songs
- `shuf` — shuffle toggle

All state persists to `SharedPreferences` under the key `"prf"`.

**Data flow:**
1. `onCreate` → load prefs, scan `/Music/tunez/`, build playlist via `rePlay()`
2. `rePlay()` → filter by selected dirs, shuffle or sort, populate `TableLayout` with clickable rows
3. Row click or song completion → `next()` → advances playback, updates `done`/`skip`, highlights current row
4. `onDestroy` → save all state, release `MediaPlayer`

**Parsing/display helpers:**
- `splitfn()` — parses a filename string into `FNTitle(dir, grp, x, ttl)`
- `fmtfn()` — formats an `FNTitle` for display (bold title, directory label)

**Data classes:**
```kotlin
data class FNList(val dir: String, var fn: MutableList<String>)
data class FNTitle(val dir: String, val grp: String, val x: String, val ttl: String)
```

## Known Incomplete Areas

- **ExoPlayer** is imported and initialized but never used; `MediaPlayer` is the actual playback engine.
- **`lyr()`** is a stubbed lyrics popup — blocked pending foreground service implementation.
- `MediaSession` integration is a known TODO (see `docs/do.txt`).
