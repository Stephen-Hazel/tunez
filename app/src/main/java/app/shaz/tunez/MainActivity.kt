// MainActivity.kt - tunez ui (most stuff happens in the service now)

package app.shaz.tunez

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import app.shaz.tunez.databinding.ActivityMainBinding
import app.shaz.tunez.databinding.DialogRateBinding


data class FNList (
   val dir: String,
   var fn:  MutableList<String>
)

data class FNTitle (
   val dir: String,
   val grp: String,
   val x:   String,
   val ttl: String
)


fun substr (s: String, b: Int, l: Int): String
// kotlin n java just don't do substr PROPerly if'n ya ask mee
{  return s.substring (b, b+l)
}


fun rawRating (fn: String): String
// whatever's after the last " - " (sans .mp3), rating or not
{  return fn.removeSuffix (".mp3").substringAfterLast (" - ").trim ()
}


fun isRated (fn: String): Boolean
// the rating always hangs off a " - " (spaces n all) - no " - " = unrated
{  return fn.removeSuffix (".mp3").contains (" - ")
}


fun splitfn (fn: String): FNTitle
// unrated files keep their last segment as title n go in UNRATED bucket
{ var s = fn
   s = substr (s, 0, s.length-4)
  var d = UNRATED
   if (isRated (fn)) {
      d = s.substringAfterLast (" - ").trim ()
      s = s.substringBeforeLast (" - ")
   }
   s = s.replace ("_", " ")
  val f = s.indexOf     ("-")
  val l = s.lastIndexOf ("-")
  var g: String
  var t: String
  var x: String
   if (f != -1) {
      g = substr (s, 0, f).trim ()
      t = s.substring (l+1).trim ()
      if (f == l)  x = ""
      else         x = substr (s, f+1, l-f-1).trim ()
   }
   else {
      g = ""
      t = s.trim ()
      x = ""
   }
   return FNTitle (d, g, x, t)
}


fun fmtfn (fn: String): SpannableString
// i just want some bold...
{ val fnt = splitfn (fn)
  val ss  = SpannableString ("${fnt.grp}  ${fnt.ttl}  ${fnt.x}  ${fnt.dir}")
  var b   = ss.indexOf (fnt.ttl)
   if (b != -1)  ss.setSpan (StyleSpan (Typeface.BOLD), b, b + fnt.ttl.length,
                             Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
   b = ss.indexOf (fnt.dir)
   if (b != -1)  ss.setSpan (StyleSpan (Typeface.BOLD), b, b + fnt.dir.length,
                             Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
   return ss
}


fun renameRating (fn: String, rating: String): String
// swap the rating suffix on a filename, keeping everything before it
// (unrated files just get the rating tacked on the end)
{  val base = fn.removeSuffix (".mp3")
   if (! isRated (fn))  return "${base.trimEnd ()} - $rating.mp3"
  val idx  = base.lastIndexOf (" - ")
   if (idx == -1)  return fn
  val prefix = base.substring (0, idx).trimEnd ()
   return "$prefix - $rating.mp3"
}


// ratings are just whatever the song filenames have - MusicService fills
val RATINGS = mutableSetOf<String> ()
const val UNRATED = "new"


fun sortedRatings (): List<String>
// alphabetized, but new always goes last
{  return RATINGS.filter { it != UNRATED }
                 .sortedWith (String.CASE_INSENSITIVE_ORDER) +
          RATINGS.filter { it == UNRATED }
}


class MainActivity: AppCompatActivity (), PlaybackCallback
{  private lateinit var b: ActivityMainBinding
   private var svc: MusicService? = null
   private var selRow: TableRow? = null
   private var sentToPerms = false
   private var rateDialog: AlertDialog? = null
   private var startPlay = false     // fresh launch? then get playin

   private val conn = object: ServiceConnection
   {  override fun onServiceConnected (name: ComponentName, binder: IBinder)
      {  svc = (binder as MusicService.MusicBinder).getService ()
         svc!!.setCallback (this@MainActivity)
         initUi ()
         onPlayState (svc!!.isPlaying ())
         if (svc!!.play.isEmpty () && svc!!.pick.isNotEmpty ())
            svc!!.rePlay ()
         else if (svc!!.play.isNotEmpty ()) {
            onPlaylistReady (svc!!.play)
            if (startPlay)  svc!!.resume ()
         }
         startPlay = false
      }
      override fun onServiceDisconnected (name: ComponentName) { svc = null }
   }


   override fun onPlaylistReady (play: List<String>)
   {  val ppos = svc?.ppos ?: 0
      runOnUiThread {
         selRow = null
         b.tvCount.text = "${play.size}"
         b.loTbl.removeAllViews ()
         var id = 0
         play.forEach { fn ->
           val tr = TableRow (this)
           val tv = TextView (this)
            tv.text = fmtfn (fn)
            tv.setPadding (dp (8), dp (6), dp (8), dp (6))
            tr.addView (tv)
            tr.isClickable = true
            tr.isFocusable = true
            tr.isFocusableInTouchMode = true
            if (id == ppos) {
               tr.requestFocus ()
               hilite (tr, true)
               selRow = tr
            }
            tr.id = id++
            tr.setOnClickListener { ctr ->
               svc?.next (ctr.id)
            }
            b.loTbl.addView (tr)
         }
      }
   }


   private fun dp (n: Int): Int
   {  return (n * resources.displayMetrics.density).toInt ()
   }


   private fun hilite (tr: TableRow, on: Boolean)
   // rounded theme container bg w a colorPrimary stripe down the left
   {  val tv = tr.getChildAt (0) as? TextView
      if (! on) {
         tr.background = null
         tv?.setTextColor (MaterialColors.getColor (
            tr, android.R.attr.textColorPrimary, Color.BLACK))
         return
      }
     val acc = MaterialColors.getColor (
        tr, com.google.android.material.R.attr.colorPrimary,
        0xFF3F51B5.toInt ())
     val bg  = MaterialColors.getColor (
        tr, com.google.android.material.R.attr.colorPrimaryContainer,
        0xFFC5CAE9.toInt ())
     val fg  = MaterialColors.getColor (
        tr, com.google.android.material.R.attr.colorOnPrimaryContainer,
        Color.BLACK)
     val rad = dp (8).toFloat ()
     val bar = GradientDrawable ()
      bar.cornerRadius = rad
      bar.setColor (acc)
     val box = GradientDrawable ()
      box.cornerRadius = rad
      box.setColor (bg)
     val ld  = LayerDrawable (arrayOf (bar, box))
      ld.setLayerInset (1, dp (4), 0, 0, 0)
      tr.background = ld
      tv?.setTextColor (fg)
   }


   override fun onPlayState (playing: Boolean)
   // fab shows what a tap would do
   {  runOnUiThread {
         b.fab.setImageResource (if (playing)
                                    R.drawable.ic_pause_24
                                 else
                                    R.drawable.ic_play_24)
      }
   }


   override fun onAlbumArtChanged (art: Bitmap?)
   {  runOnUiThread {
         if (art != null) {
            b.ivArt.setImageBitmap (art)
            b.ivArt.visibility = View.VISIBLE
         } else {
            b.ivArt.visibility = View.GONE
         }
      }
   }


   override fun onSongChanged (removedPos: Int, newPpos: Int)
   {  runOnUiThread {
         if (removedPos >= 0) {
            selRow = null
            b.loTbl.removeViews (removedPos, 1)
            for (i in removedPos until b.loTbl.childCount)
               b.loTbl.getChildAt (i).id = i
         }
         else {
            selRow?.let { hilite (it, false) }
         }
        val tr = b.loTbl.getChildAt (newPpos) as? TableRow
                                                     ?: return@runOnUiThread
         hilite (tr, true)
         tr.requestFocus ()
         selRow = tr
         rateDialog?.dismiss ()
      }
   }


   private fun initUi ()
   {  val s = svc ?: return
      b.cbShuf.isChecked = s.shuf
      b.cbShuf.setOnCheckedChangeListener { _, chk ->
         s.setShuf (chk)
         s.rePlay (false)
      }
      fillCheckRow ()
      b.btnLyr.setOnClickListener { s.lyricsSearch () }
      b.btnRate.setOnClickListener { showRateDialog () }
   // a space's width between shuf's label n the rate button
     val lp = b.btnRate.layoutParams as ViewGroup.MarginLayoutParams
      lp.marginStart = b.cbShuf.paint.measureText (" ").toInt ()
      b.btnRate.layoutParams = lp
      try {
         CastButtonFactory.setUpMediaRouteButton (this, b.btnCast)
      }
      catch (e: Exception) { }
      b.fab.setOnClickListener { onPlayState (s.togglePlayPause ()) }
   }


   private fun fillCheckRow ()
   // a checkbox per rating, alphabetized, wrapping to new lines as needed
   {  val s = svc ?: return
      b.loPick.removeAllViews ()
      sortedRatings ().forEach { d ->
        val cb = CheckBox (this)
         cb.text = d
         cb.id = View.generateViewId ()
         cb.isChecked = s.pick.contains (d)
         cb.setOnCheckedChangeListener { cb, chk ->
            if (chk)  s.addPick    (cb.text as String)
            else      s.removePick (cb.text as String)
            s.rePlay (false)
         }
         b.loPick.addView (cb)
      }
   // a space's width between one label n the next checkbox
     val cb0 = b.loPick.getChildAt (0) as? CheckBox ?: return
      b.loPick.chipSpacingHorizontal = cb0.paint.measureText (" ").toInt ()
   }


   private fun rateTo (r: String)
   // rerate the playing song n redo its row
   {  val s = svc ?: return
     val newFn = s.rateSong (r)
      if (newFn != null) {
        val tr = b.loTbl.getChildAt (s.ppos) as? TableRow
        val tv = tr?.getChildAt (0) as? TextView
         tv?.text = fmtfn (newFn)
         fillCheckRow ()
         rateDialog?.dismiss ()
      }
   }


   private fun fillRatingList (list: ViewGroup, cur: String)
   // ratings wrapped in as many rows as needed, current one grayed out
   {  list.removeAllViews ()
     val fg = MaterialColors.getColor (
        list, com.google.android.material.R.attr.colorOnPrimaryContainer,
        Color.BLACK)
      sortedRatings ().forEach { r ->
        val tv = TextView (this)
         tv.text = r
         tv.textSize = 20f
         tv.setPadding (dp (12), dp (8), dp (12), dp (8))
         if (r == cur) {
            tv.setTextColor (Color.GRAY)
            tv.isClickable = false
            tv.isFocusable = false
         }
         else {
            tv.setTextColor (fg)
            tv.isClickable = true
            tv.isFocusable = true
            tv.setOnClickListener { rateTo (r) }
         }
         list.addView (tv)
      }
   }


   private fun showRateDialog ()
   // "rate" button pops the rating list plus a delete button
   {  val s = svc ?: return
      if (s.song.isEmpty ())  return
     val dlgB = DialogRateBinding.inflate (layoutInflater)
      fillRatingList (dlgB.loRate, splitfn (s.song).dir)
      dlgB.btnClose.setOnClickListener { rateDialog?.dismiss () }
      dlgB.btnDelete.setOnClickListener {
         rateDialog?.dismiss ()
         s.deleteSong ()
         fillCheckRow ()
      }
      rateDialog = AlertDialog.Builder (this)
         .setView (dlgB.root)
         .setOnDismissListener { rateDialog = null }
         .show ()
   // blue rounded bg instead of the stock gray
     val bg = GradientDrawable ()
      bg.cornerRadius = dp (16).toFloat ()
      bg.setColor (MaterialColors.getColor (
         dlgB.root, com.google.android.material.R.attr.colorPrimaryContainer,
         0xFFD1E4FF.toInt ()))
      rateDialog?.window?.setBackgroundDrawable (
         InsetDrawable (bg, dp (16)))
   }


   private fun checkPerms ()
   // holy god android (pretty glad i got ai now)
   { val need = mutableListOf<String> ()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
         if (ContextCompat.checkSelfPermission (this,
                                         Manifest.permission.POST_NOTIFICATIONS)
             != PackageManager.PERMISSION_GRANTED)
            need.add (Manifest.permission.POST_NOTIFICATIONS)
         if (ContextCompat.checkSelfPermission (this,
                                           Manifest.permission.READ_MEDIA_AUDIO)
             != PackageManager.PERMISSION_GRANTED)
            need.add (Manifest.permission.READ_MEDIA_AUDIO)
      }
      else {
         if (ContextCompat.checkSelfPermission (this,
                                      Manifest.permission.READ_EXTERNAL_STORAGE)
             != PackageManager.PERMISSION_GRANTED)
            need.add (Manifest.permission.READ_EXTERNAL_STORAGE)
         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
             ContextCompat.checkSelfPermission (this,
                                     Manifest.permission.WRITE_EXTERNAL_STORAGE)
             != PackageManager.PERMISSION_GRANTED)
            need.add (Manifest.permission.WRITE_EXTERNAL_STORAGE)
      }
      if (need.isNotEmpty ())
         ActivityCompat.requestPermissions (this, need.toTypedArray (), 1)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
          ! Environment.isExternalStorageManager ()) {
        val uri = Uri.parse ("package:$packageName")
         sentToPerms = true
         startActivity (Intent (
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
      }

   // let android know it's ok to keep runnin us in the background so
   // doze/app-standby don't kill playback when the screen locks
     val pm = getSystemService (POWER_SERVICE) as PowerManager
      if (! pm.isIgnoringBatteryOptimizations (packageName))
         startActivity (Intent (
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse ("package:$packageName")))
   }


   private fun restartService ()
   {  sentToPerms = false
      svc?.setCallback (null)
      svc = null
      unbindService (conn)
     val intent = Intent (this, MusicService::class.java)
      stopService   (intent)
      startService  (intent)
      bindService   (intent, conn, BIND_AUTO_CREATE)
   }


   override fun onResume ()
   {  super.onResume ()
      if (sentToPerms &&
          (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
           Environment.isExternalStorageManager ()))
         restartService ()
   }


   override fun onRequestPermissionsResult (
      req: Int, perms: Array<out String>, results: IntArray)
   {  super.onRequestPermissionsResult (req, perms, results)
      if (req == 1 && results.any {
               it == PackageManager.PERMISSION_GRANTED })
         restartService ()
   }


   override fun onCastVolumeChanged (vol: Double)
   {  runOnUiThread {
         try {
            Snackbar.make (b.root, "Vol: ${(vol * 100).toInt ()}%",
                           Snackbar.LENGTH_SHORT).show ()
         }
         catch (e: Exception) { }
      }
   }


   override fun onCreate (state: Bundle?)
   {  super.onCreate (state)
      startPlay = (state == null)    // not on a rotate n such
      b = ActivityMainBinding.inflate (layoutInflater)
      setContentView (b.root)
      checkPerms ()

      try { CastContext.getSharedInstance (this) } catch (e: Exception) { }

     val intent = Intent (this, MusicService::class.java)
      startService (intent)
      bindService  (intent, conn, BIND_AUTO_CREATE)
   }


   override fun onDestroy ()
   {  super.onDestroy ()
      svc?.setCallback (null)
      unbindService (conn)
   }
}
