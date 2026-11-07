# SHazTunez - Play Store listing

## App name (30 max)

SHazTunez

## Short description (80 max)

Shuffle your music folder by your own ratings - no repeats till you hear it all

## Full description (4000 max)

SHazTunez plays the music files you already have - mp3, flac, ogg and wav -
straight from one folder on your phone. No accounts, no streaming service,
no ads.

RATE YOUR SONGS YOUR WAY
Every song carries a short rating at the end of its file name, like "a" for
favorites or "b" for once-in-a-while. Make up any ratings you like - they show
up as checkboxes at the top, and you pick which ones to hear. Re-rate the
playing song with one tap and the file gets renamed for you.

SHUFFLE WITHOUT REPEATS
Shuffle mixes songs from every rating you picked and remembers what you've
already heard, so nothing comes around again until you've heard everything.
Then a fresh round starts.

CAST TO YOUR SPEAKERS
Send the music to a Chromecast or other Google Cast speaker on your Wi-Fi.
Skips and song changes keep working, even from the lock screen.

MADE FOR YOUR OWN COLLECTION
- Songs can sit in subfolders - the folder name shows up next to the song
- Album art from your files shows while a song plays
- Lock screen and headphone controls
- Lyrics button looks up the playing song
- Delete songs you're done with right from the app

FILE NAMES
Name songs as:  artist ` year album track ` title ` rating.mp3
The middle part is optional. Files named any other way get tidied into that
form automatically, with the rating "new" so you can rate them later.

Open source, MIT license.

## Category

Music & Audio

## Contact

Email: sh@shaz.app
Privacy policy: (url where docs/store/privacy.html gets posted)

## Graphics (this folder)

- icon-512.png - 512x512 app icon
- feature-1024x500.png - feature graphic
- screen-1-list.png, screen-2-rate.png - phone screenshots (1080x2160)

## Notes for the Play Console forms

- Ads: no ads.
- App access: everything works without logging in; the only setup is picking
  the music folder on first launch.
- Content rating: a music player with no user-generated content shared, no
  chat, no web browsing in the app - should come out "Everyone".
- Target audience: 18+ (avoids the extra children's-app rules).
- Data safety: the app itself collects and shares nothing. The Google Cast
  SDK (Google Play services) is in the app - check Google's current data
  safety guidance for the Cast SDK before answering, in case it needs
  declaring.
- Foreground service (mediaPlayback): the service plays music the user
  started, keeps playing with the screen off, and shows a playback
  notification with pause/skip. Play wants a short video of that - record
  music playing, screen off, then the notification controls.
- Permissions used: notifications (playback controls), internet + network
  state + wifi state + wake lock (casting over the local Wi-Fi). No storage
  permissions - the folder comes from the system folder picker.
