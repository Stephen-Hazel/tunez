package app.shaz.tunez

import org.junit.Assert.assertEquals
import org.junit.Test

class SongNameTest {
   @Test fun tickNameConvertsOldStyle ()
   {  assertEquals ("AC_DC ` 1976_High_Voltage ` Live_Wire ` aSt.mp3",
         tickName ("AC_DC-1976_High_Voltage-Live_Wire - aSt.mp3"))
      assertEquals ("David_Bowie ` Changes ` aAn.mp3",
         tickName ("David_Bowie-Changes - aAn.mp3"))
      assertEquals ("Live/Band ` Song ` new.flac",
         tickName ("Live/Band-Song.flac"))
      assertEquals ("Song ` new.ogg", tickName ("Song.ogg"))
      assertEquals ("x ` y ` b.mp3", tickName ("x ` y ` b.mp3"))
   }

   @Test fun splitfnTrimsFields ()
   {  assertEquals (FNTitle ("aSt", "AC DC", "1976 High Voltage", "Live Wire"),
         splitfn ("AC_DC ` 1976_High_Voltage ` Live_Wire ` aSt.mp3"))
      assertEquals (FNTitle ("b", "Tone-Loc", "1989", "Funky Cold Medina"),
         splitfn ("Tone-Loc`1989  `Funky_Cold_Medina`  b.mp3"))
      assertEquals (FNTitle ("aAn", "David Bowie", "/Live", "Changes"),
         splitfn ("Live/David_Bowie ` Changes ` aAn.mp3"))
      assertEquals (FNTitle ("a", "", "", "Song"), splitfn ("Song ` a.WAV"))
   }

   @Test fun renameRatingSwapsLastField ()
   {  assertEquals ("Live/Band ` Mid ` Song ` b.flac",
         renameRating ("Live/Band ` Mid ` Song ` a.flac", "b"))
      assertEquals ("Band ` Song ` sing.mp3",
         renameRating ("Band-Song - a.mp3", "sing"))
   }
}
