# SHazTunez (tunez) - android app to play ma tunes and junk

hopefully it asks for permissions properly now?

Music/tunez dir should be your main dir of songs (mp3,flac,ogg,wav).  
It can have subdirs under it.

song filename format is
artist`etc`songtitle`rating.mp3
   where etc can be something like year_album_trackNumber_whatever
	and your subfolder name will be tacked onto etc
	rating can be any string but will default to new (to re-rate later)

rating possiblities come from all rating suffixes - keep them short.
You add new ratings by tacking it onto one of your songs.

ours are
- a    (a songs that we both like and could listen to repeatedly)
- b    (b songs that we both like but once max please)
- aAn  (annie - my gf's songs - ones i don't like - rated A)
- bAn  (annie - my gf's songs - ones i don't like - rated B)
- aSt  (steve - my songs - ones she doesn't like)
- bSt  (steve - my songs - ones she doesn't like)

Keep your ratings short or they'll use up tons of space at the app top.

check the dirs you want to hear
check shuf or not

Shuf will order em randomly MINUS the songs you already heard
- Interleaving songs from each picked dir.
- Via a done list stored across sessions.  
- Upon hearing all of em, the done list is killed.
- or you can kill the tunez/done.txt file if you want to

play/pause is in bottom right

make sure to name your songs as:  
artist ` year album track# ` songtitle ` rating.mp3

- the middle part is optional
- no ` chars means no artist and everything is the song title

good luck

oh - open source mit license

[do - to do list](do.txt)

[did - release notes](did.txt)

sh@shaz.app
