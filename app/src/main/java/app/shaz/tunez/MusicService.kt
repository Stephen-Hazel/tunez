// MusicService.kt - need a "foreground service" so we can pop lyrics and
//    do, uhh, a bunch of stuff while music =still= plays

package app.shaz.tunez

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.VolumeProviderCompat
import androidx.media.session.MediaButtonReceiver
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata  as CastMeta
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit


// setup play/pause/skip api
const val ACTION_PLAY_PAUSE = "app.shaz.tunez.PLAY_PAUSE"
const val ACTION_NEXT       = "app.shaz.tunez.NEXT"
const val CHANNEL_ID        = "tunez_playback"
const val NOTIF_ID          = 1
const val BT_WATCHDOG_POLL_MS    = 500L
const val BT_WATCHDOG_TIMEOUT_MS = 5000L
const val CAST_QUEUE_LOAD_DEBOUNCE_MS = 400L
// a natural end-of-track always reports streamPosition=0 in the
// LOADING status right before the queue item changes - any interrupted
// (skipped) item still shows its mid-song position there. threshold
// gives slack for jitter, real gap observed is 0 vs tens of thousands
const val CAST_SKIP_POS_THRESHOLD_MS = 1500L


object Dbg
// permanent on-device log for chasing intermittent bugs (bt/assistant
// skip weirdness etc) - a plain file survives days between adb
// sessions, unlike the logcat ring buffer
{  private var store: SongStore? = null
   private val fmt = SimpleDateFormat ("MM-dd HH:mm:ss", Locale.US)
   private const val LOG        = "tunez_debug.log"
   private const val MAX_BYTES  = 1_000_000
   private const val KEEP_BYTES = 500_000

   fun init (s: SongStore)
   // it lives in our Music/tunez folder
   {  val t = s.readText (LOG)
      if (t != null && t.length > MAX_BYTES)
         s.writeText (LOG, t.takeLast (KEEP_BYTES))
      store = s
   }

   fun log (tag: String, msg: String)
   {  Log.d (tag, msg)
     val s = store ?: return
      synchronized (this) {
         s.writeText (LOG, "${fmt.format (Date ())} $tag: $msg\n",
                      append = true)
      }
   }

   fun installCrashLogger ()
   // catch crashes we didn't think to log for ourselves - a dead
   // service mid-drive should always leave a trace
   {  val prev = Thread.getDefaultUncaughtExceptionHandler ()
      Thread.setDefaultUncaughtExceptionHandler { thread, e ->
         log ("TunezCrash", "uncaught in ${thread.name}: " +
                            Log.getStackTraceString (e))
         prev?.uncaughtException (thread, e)
      }
   }
}


interface PlaybackCallback {
   fun onPlaylistReady    (play: List<String>)
   fun onSongChanged      (removedPos: Int, newPpos: Int)
   fun onAlbumArtChanged  (art: Bitmap?)
   fun onPlayState        (playing: Boolean)
   fun onCastVolumeChanged (vol: Double)
}


class MusicService: Service ()
{  inner class MusicBinder: Binder () {
      fun getService (): MusicService = this@MusicService
   }

   private val binder = MusicBinder ()
   private var callback: PlaybackCallback? = null

   private var mplay: MediaPlayer? = null
   var mp3 = mutableListOf<FNList> ()     // all our songs (wav,flac,ogg too)
       private set
   var store: SongStore? = null     // our picked Music/tunez folder
       private set
   var shuf:  Boolean = true
       private set
   var pick = mutableListOf<String> ()
       private set
   private var done = mutableListOf<String> ()
   var play = mutableListOf<String> ()
       private set
   var song = ""
       private set
   var ppos = 0
       private set
   private var albumArt: Bitmap? = null
   private var castSession: CastSession? = null
   private var httpServer: LocalHttpServer? = null
   private var wifiLock: WifiManager.WifiLock? = null
   private var castCb: RemoteMediaClient.Callback? = null
   private var volumeProvider: VolumeProviderCompat? = null
   private var castErrorStreak = 0
   private var localErrorStreak = 0
   private var lastCastDeviceId: String? = null
   private var lastCastQueueSize = -1
   private var lastAdvancedItemId = -1
   private var castSkipInFlight = false
   private var castAdvanceWasSkip = false
   private var castPreAdvancePos = 0L
   private var castQueueLoadPending = false
   private var reconnectAttempts = 0
   private var noisyPauseGuard = false
   private var btReconnectedSinceNoisy = false
   private val reconnectHandler = Handler (Looper.getMainLooper ())
   private val btWatchdogHandler = Handler (Looper.getMainLooper ())
   private val castQueueLoadHandler = Handler (Looper.getMainLooper ())
   private val focusGainCheckHandler = Handler (Looper.getMainLooper ())

   private lateinit var mediaSession: MediaSessionCompat
   private var noFg = false        // android wouldn't let us start up

// if we get disco'd from bluetooth shuuut uuupp
   private lateinit var btDisco: BTDisco
   private val intentFilter = IntentFilter (
      AudioManager.ACTION_AUDIO_BECOMING_NOISY)

// audioFocusListener logs so we can see what's happening when "Hey
// Google, skip" (heard by the phone itself, not a cast device) goes
// weird, and on GAIN gives mplay a restart kick - see the comment at
// its GAIN branch for why. audioDeviceCallback also logs, but its
// onAudioDevicesAdded additionally arms btReconnectedSinceNoisy - see the
// NOTE above startBtResumeWatchdog() for why a real re-add event (not a
// getDevices() snapshot) is what the watchdog needs to trust
   private var audioFocusListener: AudioManager.OnAudioFocusChangeListener?
      = null
   private val audioDeviceCallback = object: AudioDeviceCallback ()
   {  override fun onAudioDevicesAdded (devices: Array<out AudioDeviceInfo>)
      {  devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .forEach {
               Dbg.log ("TunezBT", "device added: ${it.productName}")
               btReconnectedSinceNoisy = true
            }
      }
      override fun onAudioDevicesRemoved (
         devices: Array<out AudioDeviceInfo>)
      {  devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .forEach {
               Dbg.log ("TunezBT", "device removed: ${it.productName}")
            }
      }
   }

   fun setCallback (cb: PlaybackCallback?) { callback = cb }
   fun addPick    (dir: String)
   {  if (! pick.contains (dir))  pick.add (dir)
      savePrefs ()
   }
   fun removePick (dir: String) { pick.remove (dir);  savePrefs () }
   fun setShuf    (v: Boolean)  { shuf = v;           savePrefs () }


   private fun savePrefs ()
   // store shuf,dir picks right away so a process kill can't lose em
   {  getSharedPreferences ("prf", MODE_PRIVATE).edit ()
         .putBoolean   ("shuf", shuf)
         .putStringSet ("pick", pick.toSet ())
         .apply ()
   }

   fun isCasting () = castSession?.isConnected == true

   fun castVolume (): Double = castSession?.volume ?: 1.0

   fun setCastVolume (vol: Double)
   {  try { castSession?.setVolume (vol) }
      catch (e: Exception) { }
   }

   private fun buildVolumeProvider (): VolumeProviderCompat
   { val cur = (castVolume () * 50).toInt ()
      return object: VolumeProviderCompat (
         VOLUME_CONTROL_ABSOLUTE, 50, cur)
      {  override fun onSetVolumeTo (vol: Int)
         { val step = vol.coerceIn (0, 50)
           val v    = step / 50.0
            setCastVolume (v)
            currentVolume = step
            callback?.onCastVolumeChanged (v)
         }
         override fun onAdjustVolume (direction: Int)
         { val step = (currentVolume + direction).coerceIn (0, 50)
           val v    = step / 50.0
            setCastVolume (v)
            currentVolume = step
            callback?.onCastVolumeChanged (v)
         }
      }
   }

   private fun getLocalIp (): String
   {  try {
        val ifaces = NetworkInterface.getNetworkInterfaces ()?.toList ()
         if (ifaces == null) {
            Log.w ("TunezCast", "no network interfaces, using loopback")
            Dbg.log ("TunezCast", "no network interfaces, using loopback")
            return "127.0.0.1"
         }
      // prefer wlan so we don't hand the chromecast a cellular rmnet addr
         for (iface in ifaces.filter { it.name.startsWith ("wlan") })
            for (addr in iface.inetAddresses.toList ())
               if (! addr.isLoopbackAddress && addr is Inet4Address)
                  return addr.hostAddress ?: continue
      // fallback
         for (iface in ifaces)
            for (addr in iface.inetAddresses.toList ())
               if (! addr.isLoopbackAddress && addr is Inet4Address)
                  return addr.hostAddress ?: continue
      }
      catch (e: Exception) {
         Log.w ("TunezCast", "getLocalIp failed", e)
         Dbg.log ("TunezCast", "getLocalIp failed: $e")
      }
      Log.w ("TunezCast", "no usable ip found, using loopback")
      Dbg.log ("TunezCast", "no usable ip found, using loopback")
      return "127.0.0.1"
   }

   private fun buildCastQueueItem (songFile: String): MediaQueueItem
   { val encoded = songFile.split ("/").joinToString ("/") { Uri.encode (it) }
     val url     = "http://${getLocalIp ()}:8765/$encoded"
      Log.d ("TunezCast", "queueing $url")
      Dbg.log ("TunezCast", "queueing $url")
     val fnt     = splitfn (songFile)
     val meta    = CastMeta (CastMeta.MEDIA_TYPE_MUSIC_TRACK)
      meta.putString (CastMeta.KEY_TITLE,  fnt.ttl)
      meta.putString (CastMeta.KEY_ARTIST, fnt.grp)
     val mi  = MediaInfo.Builder (url)
                  .setStreamType  (MediaInfo.STREAM_TYPE_BUFFERED)
                  .setContentType (songMime (songFile))
                  .setMetadata    (meta)
                  .build ()
      return MediaQueueItem.Builder (mi).build ()
   }

   private fun logCastResult (
      op: String,
      pending: PendingResult<RemoteMediaClient.MediaChannelResult>?)
   // every queue* call is fire-and-forget by default - without this, a
   // failed queueNext()/queueLoad()/etc (dropped session, invalid item id
   // race, receiver said no) leaves zero trace, same blind spot that made
   // the original bt-skip bug hard to pin down
   {  pending?.setResultCallback { r ->
         if (! r.status.isSuccess)
            Dbg.log ("TunezCast",
               "$op failed: ${r.status.statusCode} ${r.status.statusMessage}")
      }
   }

   private fun loadCastQueue (autoplay: Boolean = true)
   // load current+2-ahead as a real cast queue (not a one-off load()) so
   // the receiver itself knows what "next" means - lets "Hey Google, skip"
   // spoken straight at the cast device (never reaches this app at all)
   // actually have something to skip to. 2 ahead rather than 1 gives a
   // couple of rapid-fire skips some slack instead of running the queue
   // dry the instant a queueAppendItem() round-trip lags behind
   { val cs    = castSession ?: return
     val items = mutableListOf (buildCastQueueItem (song))
      play.getOrNull (ppos + 1)?.let { items.add (buildCastQueueItem (it)) }
      play.getOrNull (ppos + 2)?.let { items.add (buildCastQueueItem (it)) }
   // rePlay() calls this on every dir-checkbox toggle - rapidly hitting a
   // few checkboxes fired one queueLoad() per click, which overlap and
   // race on the receiver (one loses with a 2103 REPLACED, and the
   // receiver can end up on a stale queue that no longer matches local
   // play/ppos/song). debounce so only the last click in a burst actually
   // reaches the receiver
      castQueueLoadHandler.removeCallbacksAndMessages (null)
      castQueueLoadHandler.postDelayed ({
      // the OLD queue can keep emitting status updates for a moment after
      // we issue queueLoad() - the receiver hasn't actually replaced it
      // yet. block handleCastQueueAdvance() from looking at ANY status
      // update until this load is confirmed, so a straggling update from
      // the queue we're about to replace can't get misread as an advance
      // (or wrongly absorbed as this load's baseline) and corrupt local
      // ppos/song against a queue that has nothing to do with it
         castQueueLoadPending = true
         Dbg.log ("TunezCast",
                  "loadCastQueue: ${items.size} item(s) song=$song")
        val pending = cs.remoteMediaClient?.queueLoad (
            items.toTypedArray (), 0, MediaStatus.REPEAT_MODE_REPEAT_OFF, null)
         if (pending == null)  castQueueLoadPending = false
         pending?.setResultCallback ({ r ->
            castQueueLoadPending = false
            if (r.status.isSuccess) {
            // queueLoad always starts the receiver - hold it if we're not
            // supposed to be playin yet (checkbox/shuf rePlay)
               if (! autoplay)  cs.remoteMediaClient?.pause ()
               lastAdvancedItemId = -1
               castSkipInFlight = false
               castAdvanceWasSkip = false
               castPreAdvancePos = 0L
            }
            else  Dbg.log ("TunezCast", "queueLoad failed: " +
               "${r.status.statusCode} ${r.status.statusMessage}")
         }, 10, TimeUnit.SECONDS)
      }, CAST_QUEUE_LOAD_DEBOUNCE_MS)
   }

   private fun handleCastQueueAdvance (ms: MediaStatus)
   // the receiver moved its currentItem on to something other than the
   // last one we knew about - happens whether we asked it to (queueNext()
   // below, from a button/phone-heard "skip") or the cast device did it on
   // its own ("Hey Google, skip" spoken to the speaker itself, or the
   // current track just finishing). this is the one place that keeps
   // play/ppos/song/done in sync with what's actually on the receiver,
   // and keeps a "next" item queued for the round after this one. whether
   // it counts as a skip (vs a natural finish) comes from either
   // castAdvanceWasSkip (we asked for it) or castPreAdvancePos (the
   // receiver cut something off on its own - see onStatusUpdated above)
   // keyed off itemId rather than queue position: the queue is never
   // pruned (see queueAppendItem below, no matching remove) so "current"
   // drifts to a higher index every skip - position was never a reliable
   // signal and comparing ids side-steps it entirely
   { if (castQueueLoadPending)  return
     val client = castSession?.remoteMediaClient ?: return
     val items  = ms.queueItems ?: return
      if (items.size != lastCastQueueSize) {
         Dbg.log ("TunezCast", "cast queue size now ${items.size}")
         lastCastQueueSize = items.size
      }
     val curId = ms.currentItemId
      if (curId == MediaQueueItem.INVALID_ITEM_ID)  return
      if (lastAdvancedItemId == -1) {
      // first status update since a fresh loadCastQueue() - this is just
      // that queue's starting item, not an advance
         lastAdvancedItemId = curId
         return
      }
      if (curId == lastAdvancedItemId)  return
      lastAdvancedItemId = curId
      castSkipInFlight = false
     val wasSkip = castAdvanceWasSkip ||
                   castPreAdvancePos > CAST_SKIP_POS_THRESHOLD_MS
      castAdvanceWasSkip = false
      castPreAdvancePos = 0L
      Dbg.log ("TunezCast",
               "receiver advanced to queue item $curId" +
               (if (wasSkip)  " (skip)" else ""))
      advanceLocal (wasSkip)
      if (ppos >= play.size)  return
      song = play [ppos]
      loadAlbumArt ()
      updateMediaSession ()
      postNotification ()
   // loadCastQueue() preloads current+2-ahead, so after this one-step
   // advance the receiver already has ppos+1 queued (it was "ahead2"
   // before this advance) - only ppos+2 is genuinely new. appending
   // ppos+1 here would re-queue a song already sitting in the queue as a
   // second, distinct item, which the receiver would eventually play as
   // an extra, silent-to-the-user advance - exactly the kind of gap that
   // desyncs the phone's idea of "current song" from what's really on air
      play.getOrNull (ppos + 2)?.let {
         logCastResult ("queueAppendItem",
            client.queueAppendItem (buildCastQueueItem (it), null))
      }
      callback?.onSongChanged (ppos, ppos)
   }

   private fun regCastCb ()
   { val client = castSession?.remoteMediaClient ?: return
      castCb = object : RemoteMediaClient.Callback ()
      {  override fun onStatusUpdated ()
         { val ms = castSession?.remoteMediaClient?.mediaStatus ?: return
            Dbg.log ("TunezCast",
               "status: state=${ms.playerState} idle=${ms.idleReason} " +
               "curId=${ms.currentItemId} qSize=${ms.queueItems?.size} " +
               "pos=${ms.streamPosition}")
         // a natural finish reports pos=0 here; an interrupted (skipped)
         // item - whether from our own queueNext() or "Hey Google, skip"
         // spoken straight to the receiver - still shows its mid-song
         // position. grab it here, before curId flips, so
         // handleCastQueueAdvance() below can use it to catch skips it
         // has no other way of seeing
            if (ms.playerState == MediaStatus.PLAYER_STATE_LOADING &&
                ms.currentItemId == lastAdvancedItemId)
               castPreAdvancePos = ms.streamPosition
            updateMediaSession ()
            handleCastQueueAdvance (ms)
            if (ms.playerState == MediaStatus.PLAYER_STATE_IDLE) {
               Log.d ("TunezCast", "idle reason=${ms.idleReason}")
               Dbg.log ("TunezCast", "idle reason=${ms.idleReason}")
               if (ms.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                  castErrorStreak = 0
                  next ()
               }
            // load/playback failed - skip the dead track n keep going,
            // but give up after a few in a row so a real outage doesn't
            // burn through the whole playlist
               else if (ms.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                  castErrorStreak++
                  Log.d ("TunezCast", "error streak=$castErrorStreak")
                  Dbg.log ("TunezCast", "error streak=$castErrorStreak")
                  if (castErrorStreak <= 3)  next ()
                  else  Dbg.log ("TunezCast",
                     "giving up after $castErrorStreak consecutive errors")
               }
            }
         }
      }
      client.registerCallback (castCb!!)
   }

   private fun handleHttpStreamError (uri: String)
   // the http server has its own failures (dropped connection, etc)
   // that never reach us via a cast status update - runs on the
   // server's handler thread, so hop back to main before touching
   // any playback state
   {  reconnectHandler.post {
        if (castSkipInFlight) {
            Dbg.log ("TunezCast",
               "ignoring stream reset for $uri - skip in flight")
            return@post
         }
        if (isCasting () && uri == song) {
            castErrorStreak++
            Log.d ("TunezCast", "http stream error streak=$castErrorStreak")
            Dbg.log ("TunezCast", "http stream error streak=$castErrorStreak")
            if (castErrorStreak <= 3)  next ()
            else  Dbg.log ("TunezCast",
               "giving up after $castErrorStreak consecutive errors")
         }
      }
   }

   private fun unregCastCb ()
   {  castCb?.let { castSession?.remoteMediaClient?.unregisterCallback (it) }
      castCb = null
   }

   @Suppress("DEPRECATION")
   private fun acquireWifiLock ()
   // wifi radio power-saves when the screen is off, which can stall
   // our http server for tens of seconds between beacon wakeups -
   // keep it awake for as long as we're serving to the cast device.
   // WIFI_MODE_FULL_HIGH_PERF is deprecated (the OS manages this
   // automatically since API 34) but minSdk is 24 and there's no
   // non-deprecated equivalent, so we still need it on older devices
   {  if (wifiLock?.isHeld == true)  return
     val wm = applicationContext
                 .getSystemService (Context.WIFI_SERVICE) as WifiManager
      wifiLock = wm.createWifiLock (WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                                    "tunez:cast")
      wifiLock?.acquire ()
   }

   private fun releaseWifiLock ()
   {  if (wifiLock?.isHeld == true)  wifiLock?.release ()
      wifiLock = null
   }

   private fun tryReconnectCast ()
   // an active cast session died unexpectedly - reselect the same
   // route to get the framework to reconnect us, rather than ever
   // falling back to the phone speaker while we think we're casting
   { val deviceId = lastCastDeviceId ?: return
     val router   = MediaRouter.getInstance (this)
     val route    = router.routes.firstOrNull {
        CastDevice.getFromBundle (it.extras)?.deviceId == deviceId
     }
      if (route != null) {
         Log.d ("TunezCast", "reconnecting to $deviceId")
         Dbg.log ("TunezCast", "reconnecting to $deviceId")
         router.selectRoute (route)
         return
      }
      reconnectAttempts++
      if (reconnectAttempts <= 5) {
         Log.d ("TunezCast",
                "device $deviceId not found, retry $reconnectAttempts/5")
         Dbg.log ("TunezCast",
                  "device $deviceId not found, retry $reconnectAttempts/5")
         reconnectHandler.postDelayed ({ tryReconnectCast () }, 3000)
      }
      else {
         Log.w ("TunezCast", "giving up reconnecting to $deviceId")
         Dbg.log ("TunezCast", "giving up reconnecting to $deviceId")
      }
   }

   private val castListener = object : SessionManagerListener<CastSession>
   {  override fun onSessionStarted (session: CastSession, id: String)
      {  Dbg.log ("TunezCast", "session started device=" +
                                  "${session.castDevice?.deviceId}")
         castSession = session
         castErrorStreak = 0
         reconnectAttempts = 0
         reconnectHandler.removeCallbacksAndMessages (null)
         lastCastDeviceId = session.castDevice?.deviceId
         httpServer  = store?.let { s -> LocalHttpServer (s).also {
            it.start ()
            it.onStreamError = { uri -> handleHttpStreamError (uri) }
         } }
         acquireWifiLock ()
         mplay?.pause ()
         if (song.isNotEmpty ())  loadCastQueue ()
         regCastCb ()
         volumeProvider = buildVolumeProvider ()
         mediaSession.setPlaybackToRemote (volumeProvider!!)
         updateMediaSession ()
         postNotification ()
      }

      override fun onSessionResumed (session: CastSession,
                                     wasSuspended: Boolean)
      {  Dbg.log ("TunezCast",
                  "session resumed wasSuspended=$wasSuspended " +
                  "device=${session.castDevice?.deviceId}")
         castSession = session
         reconnectAttempts = 0
         reconnectHandler.removeCallbacksAndMessages (null)
         lastCastDeviceId = session.castDevice?.deviceId
         if (httpServer == null)
            httpServer = store?.let { s -> LocalHttpServer (s).also {
               it.start ()
               it.onStreamError = { uri -> handleHttpStreamError (uri) }
            } }
         acquireWifiLock ()
         if (song.isNotEmpty ())  loadCastQueue ()
         regCastCb ()
         volumeProvider = buildVolumeProvider ()
         mediaSession.setPlaybackToRemote (volumeProvider!!)
         mplay?.pause ()
      }

      override fun onSessionEnded (session: CastSession, error: Int)
      {  Log.d ("TunezCast", "session ended error=$error")
         Dbg.log ("TunezCast", "session ended error=$error")
         unregCastCb ()
         castSession = null
         volumeProvider = null
         mediaSession.setPlaybackToLocal (AudioManager.STREAM_MUSIC)
         httpServer?.stop ()
         httpServer = null
         releaseWifiLock ()
      // a clean, user-requested disconnect falls back to the phone
      // speaker same as always. anything else (dropped wifi, etc) tries
      // to get back onto the same cast device instead of ever playing
      // out the phone speaker while we still think we're casting
         if (error == CastStatusCodes.SUCCESS) {
            if (song.isNotEmpty ()) {
               mplay?.reset ()
               try {
                  mplay?.setDataSource (this@MusicService, songUri ())
                  mplay?.prepare ()
                  mplay?.start ()
                  mplay?.setOnCompletionListener { localErrorStreak = 0; next () }
               }
               catch (e: Exception) {
                  Log.e ("TunezLocal", "fallback playback failed for $song", e)
                  Dbg.log ("TunezLocal",
                           "fallback playback failed for $song: $e")
               }
            }
         }
         else {
            reconnectAttempts = 0
            tryReconnectCast ()
         }
         updateMediaSession ()
         postNotification ()
      }

      override fun onSessionStarting    (s: CastSession)
      {  Dbg.log ("TunezCast", "session starting") }
      override fun onSessionStartFailed (s: CastSession, e: Int)
      {  Log.e ("TunezCast", "session start failed error=$e")
         Dbg.log ("TunezCast", "session start failed error=$e") }
      override fun onSessionEnding      (s: CastSession)
      {  Dbg.log ("TunezCast", "session ending") }
      override fun onSessionResuming    (s: CastSession, id: String)
      {  Dbg.log ("TunezCast", "session resuming id=$id") }
      override fun onSessionResumeFailed(s: CastSession, e: Int)
      {  Log.e ("TunezCast", "session resume failed error=$e")
         Dbg.log ("TunezCast", "session resume failed error=$e") }
      override fun onSessionSuspended   (s: CastSession, r: Int)
      {  Log.d ("TunezCast", "session suspended reason=$r")
         Dbg.log ("TunezCast", "session suspended reason=$r") }
   }

   fun isPlaying (): Boolean
   // while casting, mplay is intentionally paused - ask the remote player
   {  return if (isCasting ())
                castSession?.remoteMediaClient?.mediaStatus
                   ?.playerState == MediaStatus.PLAYER_STATE_PLAYING
             else  mplay?.isPlaying == true
   }


   fun resume ()
   // start playin if we aren't already (app just got opened)
   {  if (song.isNotEmpty () && ! isPlaying ())  togglePlayPause ()
   }


   fun togglePlayPause (): Boolean
   {  val wasPlaying: Boolean
      if (isCasting ()) {
        val client = castSession!!.remoteMediaClient ?: return false
         wasPlaying = client.mediaStatus?.playerState ==
                                            MediaStatus.PLAYER_STATE_PLAYING
         if (wasPlaying)  client.pause (null)
         else             client.play  (null)
      }
      else {
         wasPlaying = mplay?.isPlaying == true
         if (wasPlaying)  mplay?.pause ()
         else             mplay?.start ()
      }
      updateMediaSession ()
      postNotification ()
      return ! wasPlaying
   }


   override fun onCreate ()
   {  super.onCreate ()

   // must call startForeground() fast or Android kills us when started
   // from background (e.g. media button while screen is off)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel (CHANNEL_ID, "Playback",
                                      NotificationManager.IMPORTANCE_LOW)
         getSystemService (NotificationManager::class.java
                                                ).createNotificationChannel (ch)
      }
   // android 12+ won't let a service restarted in the background (say
   // after a crash) go foreground - just quietly quit, the app starts us
   // again properly when it's opened
      try {
         startForeground (NOTIF_ID,
            NotificationCompat.Builder (this, CHANNEL_ID)
               .setSmallIcon (R.drawable.outline_music_cast_24)
               .setContentTitle ("SHazTunez")
               .build ())
      }
      catch (e: IllegalStateException) {
         Log.w ("TunezSkip", "startForeground refused, stopping", e)
         noFg = true
         stopSelf ()
         return
      }

      mplay = newMediaPlayer ()
   // our songs live in the Music/tunez folder the user picked for us (no
   // folder yet?  then no songs til MainActivity gets one n restarts us)
      store = SongStore.saved (this)
     val t0  = SystemClock.elapsedRealtime ()
     val all = store?.scan () ?: emptyList ()
      store?.let { Dbg.init (it) }
      Dbg.installCrashLogger ()
      Dbg.log ("TunezStore", "scanned ${all.size} files in " +
               "${SystemClock.elapsedRealtime () - t0}ms")
      Dbg.log ("TunezSkip", "onCreate: service created")
   // load shuf,picked dirs from last time
     val p = getSharedPreferences ("prf", MODE_PRIVATE)
      shuf = p.getBoolean   ("shuf", true)
      pick = p.getStringSet ("pick", emptySet ())?.toMutableList () ?:
                                                                mutableListOf ()
   // and our done list so we don't hear ANY repeats
      done = store?.readText ("done.txt")?.lines ()
                ?.filter { it.isNotEmpty () }?.toMutableList ()
             ?: mutableListOf ()
   // ok, our songs (subdirs too, as relative paths) n bucket by rating
     val fns = all.filter { isSong (it) }.map { tickFile (it) }.sorted ()
      fns.groupBy { splitfn (it).dir }.toSortedMap ().forEach { (d, ls) ->
         mp3.add (FNList (d, ls.toMutableList ()))
      }
      reRate ()
   // nothing picked (fresh install, or everything unticked)? pick it all
      if (pick.isEmpty ()) {
         pick.addAll (RATINGS)
         savePrefs ()
      }
      mediaSession = MediaSessionCompat (this, "TunezSession").apply {
         setCallback (object: MediaSessionCompat.Callback ()
         {  override fun onPlay ()
            {  Dbg.log ("TunezSkip", "onPlay")
               btWatchdogHandler.removeCallbacksAndMessages (null)
               mplay?.start ()
               updateMediaSession ()
               postNotification ()
            }

            override fun onPause ()
            {  Dbg.log ("TunezSkip", "onPause")
               if (noisyPauseGuard) {
                  noisyPauseGuard = false
                  Dbg.log ("TunezSkip",
                           "onPause: noisy-pause guard held, " +
                           "watchdog kept armed")
               }
               else {
                  btWatchdogHandler.removeCallbacksAndMessages (null)
                  Dbg.log ("TunezSkip",
                           "onPause: watchdog cancelled (manual pause)")
               }
               mplay?.pause ()
               updateMediaSession ()
               postNotification ()
            }

            override fun onSkipToNext ()
            {  Dbg.log ("TunezSkip", "onSkipToNext")
               next (skipped = true)
            }

         // not implemented - logged so an assistant call landing here
         // instead of onSkipToNext shows up rather than vanishing
            override fun onStop ()
            {  Dbg.log ("TunezSkip", "onStop") }

            override fun onSkipToPrevious ()
            {  Dbg.log ("TunezSkip", "onSkipToPrevious") }

            override fun onSeekTo (pos: Long)
            {  Dbg.log ("TunezSkip", "onSeekTo pos=$pos") }

            override fun onFastForward ()
            {  Dbg.log ("TunezSkip", "onFastForward") }

            override fun onRewind ()
            {  Dbg.log ("TunezSkip", "onRewind") }

            override fun onSetRating (rating: RatingCompat)
            {  Dbg.log ("TunezRate", "onSetRating rating=$rating " +
                        "isThumbUp=${rating.isThumbUp}")
               setRating (rating.isThumbUp) }

            override fun onCustomAction (action: String, extras: Bundle?)
            {  Dbg.log ("TunezSkip", "onCustomAction action=$action " +
                        "extras=$extras") }

            override fun onPlayFromSearch (query: String?, extras: Bundle?)
            {  Dbg.log ("TunezSkip", "onPlayFromSearch query=$query " +
                        "extras=$extras") }

            override fun onPlayFromMediaId (mediaId: String?,
                                             extras: Bundle?)
            {  Dbg.log ("TunezSkip", "onPlayFromMediaId mediaId=$mediaId " +
                        "extras=$extras") }

            override fun onPlayFromUri (uri: Uri?, extras: Bundle?)
            {  Dbg.log ("TunezSkip", "onPlayFromUri uri=$uri " +
                        "extras=$extras") }

            override fun onPrepare ()
            {  Dbg.log ("TunezSkip", "onPrepare") }

            override fun onCommand (command: String, args: Bundle?,
                                     cb: ResultReceiver?)
            {  Dbg.log ("TunezSkip", "onCommand command=$command " +
                        "args=$args") }
         })
         setRatingType (RatingCompat.RATING_THUMB_UP_DOWN)
         isActive = true
      }

      btDisco = BTDisco ({ mplay }) { handleNoisyPause () }
      registerReceiver (btDisco, intentFilter)

    val am = getSystemService (Context.AUDIO_SERVICE) as AudioManager
      am.registerAudioDeviceCallback (audioDeviceCallback, null)
      audioFocusListener = AudioManager.OnAudioFocusChangeListener {
         focusChange ->
        val name = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN                     -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS                     -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT            ->
               "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK   ->
               "LOSS_TRANSIENT_CAN_DUCK"
            else                                              ->
               "UNKNOWN($focusChange)"
         }
         Dbg.log ("TunezFocus",
                  "onAudioFocusChange: $name isPlaying=${mplay?.isPlaying} " +
                  "btA2dp=${isBtA2dpConnected ()} mode=${am.mode} " +
                  "pos=${mplay?.currentPosition} routed=${routedDeviceStr ()} " +
                  "vol=${volumeStr ()}")
      // "Hey Google, skip" can call next()'s mplay.start() while
      // Assistant still holds focus for its own SCO listen/response - if
      // the BT stack doesn't cleanly hand A2DP back afterwards, mplay
      // keeps reporting isPlaying=true but produces no audio, and
      // nothing else ever kicks it again. tried a plain start() (no-op
      // on an already-Started player) and a pause/seekTo/start cycle -
      // neither fixed it despite routed/vol logging showing everything
      // normal, so this fully tears down and recreates mplay (a brand
      // new AudioTrack) instead of trying to revive the old one
         if (focusChange == AudioManager.AUDIOFOCUS_GAIN &&
             mplay?.isPlaying == true) {
           val posBefore = mplay?.currentPosition ?: -1
            Dbg.log ("TunezFocus",
                     "GAIN: full recreate, pos=$posBefore " +
                     "routed=${routedDeviceStr ()} vol=${volumeStr ()}")
            try {
               mplay?.release ()
               mplay = newMediaPlayer ()
               mplay?.setDataSource (this@MusicService, songUri ())
               mplay?.prepare ()
               mplay?.seekTo (posBefore)
               mplay?.start ()
               mplay?.setOnCompletionListener {
                  localErrorStreak = 0; next ()
               }
            }
            catch (e: Exception) {
               Dbg.log ("TunezLocal", "GAIN recreate failed: $e")
            }
            focusGainCheckHandler.postDelayed ({
               Dbg.log ("TunezFocus",
                        "GAIN: pos check +1500ms pos=" +
                        "${mplay?.currentPosition} " +
                        "isPlaying=${mplay?.isPlaying} " +
                        "routed=${routedDeviceStr ()} vol=${volumeStr ()}")
            }, 1500L)
         }
      }
    @Suppress ("DEPRECATION")
    val focusRes = am.requestAudioFocus (audioFocusListener,
         AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
      Dbg.log ("TunezFocus", "requestAudioFocus result=$focusRes")

      try {
        val cc = CastContext.getSharedInstance (this)
         cc.sessionManager.addSessionManagerListener (
            castListener, CastSession::class.java)
         castSession = cc.sessionManager.currentCastSession
         if (isCasting ()) {
            volumeProvider = buildVolumeProvider ()
            mediaSession.setPlaybackToRemote (volumeProvider!!)
         }
      }
      catch (e: Exception) { }
   }


// more foreground service silliness - glad i have AI now cuz this looks duuuumb
   override fun onBind (intent: Intent): IBinder = binder

   override fun onStartCommand (intent: Intent?, flags: Int, startId: Int): Int
   {  if (noFg)  return START_NOT_STICKY
     val key = intent?.let { IntentCompat.getParcelableExtra (
                  it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }
      Dbg.log ("TunezSkip",
               "onStartCommand action=${intent?.action} key=${key?.keyCode}")
      MediaButtonReceiver.handleIntent (mediaSession, intent)
      when (intent?.action) {
         ACTION_PLAY_PAUSE -> togglePlayPause ()
         ACTION_NEXT       -> next (skipped = true)
      }
      return START_STICKY
   }


   override fun onDestroy ()
   // shut it all down
   {  super.onDestroy ()
      if (noFg)  return               // never got set up
      Dbg.log ("TunezSkip", "onDestroy: service destroyed")
      unregisterReceiver (btDisco)
    val am = getSystemService (Context.AUDIO_SERVICE) as AudioManager
      am.unregisterAudioDeviceCallback (audioDeviceCallback)
      audioFocusListener?.let {
         @Suppress ("DEPRECATION")
         am.abandonAudioFocus (it)
      }
      reconnectHandler.removeCallbacksAndMessages (null)
      btWatchdogHandler.removeCallbacksAndMessages (null)
      castQueueLoadHandler.removeCallbacksAndMessages (null)
   // clear cast state before tearing anything else down - httpServer
   // .stop() below deliberately kills in-flight sockets, which fires
   // handleHttpStreamError()/onStatusUpdated() asynchronously; without
   // this, isCasting() still reads true and those callbacks keep
   // calling next() against a service that's already destroyed
      unregCastCb ()
      castSession = null
      volumeProvider = null
      if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.release ()
      mplay = null
      mediaSession.release ()

   // shuf,dir picks n done/skip are all saved as they happen
      try {
         CastContext.getSharedInstance (this)
            .sessionManager.removeSessionManagerListener (
               castListener, CastSession::class.java)
      }
      catch (ex: Exception) { }
      httpServer?.stop ()
      releaseWifiLock ()
   }


   override fun onTaskRemoved (rootIntent: Intent?)
   {  Dbg.log ("TunezSkip", "onTaskRemoved: app swiped away, stopping")
      stopForeground (STOP_FOREGROUND_REMOVE)
      stopSelf ()
   }


   fun lyricsSearch ()
   // boot chrome and pass google our song title,artist in hopes o gettin lyrics
   { val fnt   = splitfn (song)
     val query = Uri.encode ("${fnt.ttl} ${fnt.grp} lyrics")
     val uri   = Uri.parse ("https://www.google.com/search?q=$query")
     val i     = Intent (Intent.ACTION_VIEW, uri).addFlags (
                                                  Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
         startActivity (i.setPackage ("com.android.chrome"))
      }
      catch (e: Exception) {
         startActivity (i.setPackage (null))
      }
   }


   private fun songUri (): Uri
   // the playing song's document in our folder (EMPTY just fails to load)
   {  return store?.uri (song) ?: Uri.EMPTY
   }


   private fun loadAlbumArt ()
   // if our mp3 has an embedded bitmap show it
   { val mmr  = MediaMetadataRetriever ()
      albumArt = try {
         mmr.setDataSource (this, songUri ())
        val bytes = mmr.embeddedPicture
         if (bytes != null)
               BitmapFactory.decodeByteArray (bytes, 0, bytes.size)
         else  null
      }
      catch (e: Exception) { null }
      finally              { mmr.release () }
      callback?.onAlbumArtChanged (albumArt)
   }


   private fun pick2play ()
   // clicked a dir checkbox sooo redo play from mp3
   {  pick.forEach { p ->
         mp3.forEach { m ->
            if (p == m.dir)  m.fn.forEach { fn ->
               play.add (fn)
            }
         }
      }
   }


   fun rePlay (start: Boolean = true)
   // start false = load up the first song but sit paused
   {  if (mplay?.isPlaying == true)  mplay?.stop ()
      mplay?.reset ()
      play.clear ()
      if (pick.isEmpty ()) {
         callback?.onPlaylistReady (play)
         return
      }
      if (shuf) {
      // build a shuffled interleaved list per picked dir, minus done songs
        val buckets = pick.mapNotNull { p ->
               mp3.find { it.dir == p }?.fn
                  ?.filter { !done.contains (it) }
                  ?.shuffled ()
                  ?.toMutableList ()
                  ?.takeIf { it.isNotEmpty () }
            }.toMutableList ()
      // only start a new rotation if the picks actually HAVE songs n
      // they're all done - picks with no songs at all mustn't wipe done
        val anySongs = pick.any { p ->
               mp3.find { it.dir == p }?.fn?.isNotEmpty () == true }
         if (buckets.isEmpty () && anySongs) {
            done.clear ()
            saveDone ()
            pick.forEach { p ->
               mp3.find { it.dir == p }?.fn
                  ?.shuffled ()
                  ?.let { if (it.isNotEmpty ())
                             buckets.add (it.toMutableList ()) }
            }
         }
      // interleave round-robin across buckets
         while (buckets.isNotEmpty ()) {
           val it = buckets.iterator ()
            while (it.hasNext ()) {
              val bucket = it.next ()
               play.add (bucket.removeFirst ())
               if (bucket.isEmpty ())  it.remove ()
            }
         }
      }
      else {
         pick2play ()
         play.sortBy { fmtfn (it).toString () }
      }
      if (play.isEmpty ()) {
         callback?.onPlaylistReady (play)
         return
      }
      ppos = 0
      song = play [ppos]
   // ui gets the list BEFORE we try loadin song 1 - if that fails next ()
   // drops its row, n the row had better be there by then
      callback?.onPlaylistReady (play.toList ())
      loadAlbumArt ()
      if (isCasting ())  loadCastQueue (start)
      else {
         try {
            mplay?.setDataSource (this@MusicService, songUri ())
            mplay?.prepare ()
            if (start)  mplay?.start ()
            mplay?.setOnCompletionListener { localErrorStreak = 0; next () }
         }
         catch (e: Exception) {
            Log.e ("TunezLocal", "prepare failed for $song", e)
            Dbg.log ("TunezLocal", "prepare failed for $song: $e")
            localErrorStreak++
            if (localErrorStreak <= 3)  next ()
         }
      }
      updateMediaSession ()
      postNotification ()
   }


   private fun tickFile (fn: String): String
   // old style name?  rename it on disk to the ` style (n fix done to
   // match).  if that can't happen we just keep the old name
   {  val tn = tickName (fn)
      if (tn == fn)  return fn
      if (store?.rename (fn, tn) != true) {
         Dbg.log ("TunezRate", "tick rename failed: $fn -> $tn")
         return fn
      }
      Dbg.log ("TunezRate", "tick renamed: $fn -> $tn")
     val i = done.indexOf (fn)
      if (i >= 0) { done [i] = tn;  saveDone () }
      return tn
   }


   private fun saveDone ()
   // mirrors 'done' to disk right away so a mid-rotation process kill
   // doesn't lose which songs we've already heard this cycle
   {  store?.writeText ("done.txt", done.joinToString ("\n"))
   }


   private fun logSkip (name: String)
   // append-only record of explicitly skipped songs, separate from done
   {  store?.writeText ("skip.txt", "$name\n", append = true)
   }


   private fun advanceLocal (skipped: Boolean = false): Int
   // shared bookkeeping for a sequential (row==-1) advance: mark the
   // current song done and drop it from play. doesn't touch mplay/cast -
   // callers decide what (if anything) to load next
   { val removedPos = ppos
      done.add (song)
      saveDone ()
      if (skipped)  logSkip (song)
      play.removeAt (ppos)
      return removedPos
   }


   fun next (row: Int = -1, skipped: Boolean = false)
   // row set if song table got doubleclicked.  else itsa neeext
   {  Dbg.log ("TunezSkip",
               "next enter row=$row ppos=$ppos playSize=${play.size}")
   // a NEXT action (skip button, media key) can reach a just-created
   // service before the playlist is rebuilt - nothing to skip to yet
      if (row == -1 && ppos !in play.indices) {
         Dbg.log ("TunezSkip",
                  "next: ppos=$ppos out of range for playSize=" +
                  "${play.size}, ignoring")
         return
      }
      btWatchdogHandler.removeCallbacksAndMessages (null)
   // while casting, a sequential skip just tells the receiver to move to
   // whatever it already has queued as "next" (see loadCastQueue()) -
   // handleCastQueueAdvance() picks up the resulting currentItemId change
   // and does the play/ppos/song/done bookkeeping there, so this same path
   // and a "Hey Google, skip" heard by the cast device itself (which never
   // reaches this app directly) end up updating state in exactly one place
      if (row == -1 && isCasting ()) {
         Dbg.log ("TunezCast", "next: casting, sending queueNext")
      // the receiver drops its in-flight HTTP connection to the current
      // song the instant it acts on this - that's an expected side effect
      // of skipping, not a playback failure, so tell handleHttpStreamError
      // to ignore it til handleCastQueueAdvance() confirms the skip landed
         castSkipInFlight = true
         castAdvanceWasSkip = skipped
         castSession?.remoteMediaClient?.queueNext (null)?.setResultCallback {
            r ->
            if (! r.status.isSuccess) {
               Dbg.log ("TunezCast", "queueNext failed: " +
                  "${r.status.statusCode} ${r.status.statusMessage}")
               castSkipInFlight = false
            }
         }
         return
      }
      mplay?.stop ()
      mplay?.reset ()
     val removedPos: Int
      if (row == -1)  removedPos = advanceLocal (skipped)
      else {
         removedPos = -1
      // picking a different song off the list is itself a skip of
      // whatever was playing - unlike advanceLocal() this doesn't drop
      // the old song from play/done, it's still eligible to come up
      // again this rotation
         if (song.isNotEmpty ())  logSkip (song)
         ppos = row
      }
      if (ppos < play.size) {
         song = play [ppos]
         loadAlbumArt ()
         if (isCasting ())  loadCastQueue ()
         else {
            try {
               mplay?.setDataSource (this@MusicService, songUri ())
               mplay?.prepare ()
               mplay?.start ()
               mplay?.setOnCompletionListener { localErrorStreak = 0; next () }
            }
            catch (e: Exception) {
               Log.e ("TunezLocal", "prepare failed for $song", e)
               Dbg.log ("TunezLocal", "prepare failed for $song: $e")
               localErrorStreak++
               if (localErrorStreak <= 3)  next ()
            }
         }
         updateMediaSession ()
         postNotification ()
      }
      Dbg.log ("TunezSkip",
               "next exit ppos=$ppos playSize=${play.size} song=$song")
      callback?.onSongChanged (removedPos, ppos)
   }


   fun setRating (up: Boolean)
   // thumbs up promotes b -> a, thumbs down demotes back
   {  if (song.isEmpty ()) {
         Dbg.log ("TunezRate", "setRating: no song playing, ignored")
         return
      }
     val cur = splitfn (song).dir
     val bucket =
        if      (up   && cur == "b")  "a"
        else if (! up && cur == "a")  "b"
        else                          null
      if (bucket == null) {
         Dbg.log ("TunezRate", "setRating: no mapping for up=$up cur=$cur " +
                  "song=$song")
         return
      }
     val result = rateSong (bucket)
      Dbg.log ("TunezRate", "setRating: up=$up cur=$cur bucket=$bucket " +
               "result=$result")
   }


   private fun reRate ()
   // ratings are the distinct suffixes on our songs (new if any unrated)
   {  mp3.removeAll { it.fn.isEmpty () }
      RATINGS.clear ()
      RATINGS.addAll (mp3.map { it.dir })
   // n forget picks of ratings that don't exist anymore
      if (pick.retainAll { RATINGS.contains (it) })  savePrefs ()
   }


   fun rateSong (rating: String): String?
   // rename the playing song's rating suffix, keep it playing til next ()
   {  if (song.isEmpty ())  return null
     val oldFn     = song
     val oldRating = splitfn (oldFn).dir
     val newFn     = renameRating (oldFn, rating)
      if (newFn == oldFn)  return oldFn
     val s = store ?: return null
      if (s.exists (newFn)) {
         Log.w ("TunezRate", "rate skip, exists: $newFn")
         return null
      }
      if (! s.rename (oldFn, newFn)) {
         Log.w ("TunezRate", "rate rename failed: $oldFn -> $newFn")
         return null
      }
      play [ppos] = newFn
      song = newFn
      mp3.find { it.dir == oldRating }?.fn?.remove (oldFn)
     var bucket = mp3.find { it.dir == rating }
      if (bucket == null) {
         bucket = FNList (rating, mutableListOf ())
         mp3.add (bucket)
      }
      bucket.fn.add (newFn)
      reRate ()
      return newFn
   }


   fun deleteSong (): Boolean
   // permanently remove the current mp3 - unlike rateSong () this does
   // NOT keep it playing, moves on immediately like next ()
   {  if (song.isEmpty ())  return false
     val oldFn = song
      store?.delete (oldFn)
     val d = splitfn (oldFn).dir
      mp3.find { it.dir == d }?.fn?.remove (oldFn)
      reRate ()
      Dbg.log ("TunezRate", "deleted $oldFn")
      next ()
      return true
   }


// pause on a noisy-audio blip (bt route hiccup, headphones out, etc), then
// watch for the bt route coming right back so we don't just sit silent
// waiting on an external play/skip command that may take a while (or never
// arrive) - see docs/do.txt notes on the "Hey Google, next song" gap
// NOTE: transportControls.pause() dispatches to onPause() above, which
// (for a manual pause) cancels any watchdog left running from an
// earlier noisy event. Without noisyPauseGuard, that same cancel would
// immediately kill the watchdog we're about to arm right here - onPause()
// fires (sync or posted, doesn't matter) before the first poll ever gets
// a chance to run, so it looked like the watchdog never existed. The
// guard tells onPause() "this pause is mine, don't cancel."
   private fun handleNoisyPause ()
   {  noisyPauseGuard = true
      btReconnectedSinceNoisy = false
      Dbg.log ("TunezSkip", "handleNoisyPause: pausing, arming watchdog guard")
      mediaSession.controller.transportControls.pause ()
      startBtResumeWatchdog ()
   }

// shared setup for both onCreate's original mplay and the GAIN-time
// full recreate below - the two need identical listeners attached
   private fun newMediaPlayer (): MediaPlayer
   {  val mp = MediaPlayer ()
      mp.setOnErrorListener { _, what, extra ->
         Log.e ("TunezLocal", "player error what=$what extra=$extra")
         Dbg.log ("TunezLocal",
                  "player error what=$what extra=$extra song=$song")
         localErrorStreak++
         if (localErrorStreak <= 3)  next ()
         true
      }
   // event-driven, independent of the GAIN-triggered pos checks below -
   // fires the instant mplay's actual bound output device changes,
   // whether or not that lines up with any focus-change we saw
      mp.addOnRoutingChangedListener ({
         Dbg.log ("TunezFocus", "routing changed: routed=${routedDeviceStr ()}")
      }, Handler (Looper.getMainLooper ()))
      return mp
   }

   private fun isBtA2dpConnected (): Boolean =
      (getSystemService (Context.AUDIO_SERVICE) as AudioManager)
         .getDevices (AudioManager.GET_DEVICES_OUTPUTS)
         .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

   private fun outputDevicesDump (): String =
      (getSystemService (Context.AUDIO_SERVICE) as AudioManager)
         .getDevices (AudioManager.GET_DEVICES_OUTPUTS)
         .joinToString { "${it.type}:${it.productName}" }

// mplay's actual bound output (MediaPlayer implements AudioRouting) -
// unlike currentPosition, which keeps ticking even if the underlying
// AudioTrack's writes never reach a real DAC, this says what device
// we're really attached to right now
   private fun routedDeviceStr (): String =
      mplay?.routedDevice?.let { "${it.type}:${it.productName}" } ?: "none"

// STREAM_MUSIC volume as Android sees it - doesn't catch a BT device's
// own AVRCP-absolute-volume state going to 0 on its end, but rules out
// (or confirms) the phone-side half of that
   private fun volumeStr (): String
   {  val am = getSystemService (Context.AUDIO_SERVICE) as AudioManager
     val vol = am.getStreamVolume (AudioManager.STREAM_MUSIC)
     val max = am.getStreamMaxVolume (AudioManager.STREAM_MUSIC)
     val mut = am.isStreamMute (AudioManager.STREAM_MUSIC)
      return "$vol/$max muted=$mut"
   }

// getDevices() lags the real disconnect: on every real car-BT-off event
// seen in tunez_debug.log, the noisy broadcast fired, getDevices() still
// showed the A2DP device connected for another 500ms-2s, and only then
// did onAudioDevicesRemoved() actually fire. Polling isBtA2dpConnected()
// on a fixed timer reads that stale snapshot and "resumes" playback right
// as the car is disconnecting - which is what made the app start blaring
// audio at the exact moment the car turned off. Waiting on
// btReconnectedSinceNoisy instead means "a real add event happened after
// we armed," which can't be stale the same way.
   private fun startBtResumeWatchdog ()
   {  btWatchdogHandler.removeCallbacksAndMessages (null)
     val deadline = SystemClock.elapsedRealtime () + BT_WATCHDOG_TIMEOUT_MS
      Dbg.log ("TunezSkip",
               "bt watchdog: armed, timeout=${BT_WATCHDOG_TIMEOUT_MS}ms " +
               "poll=${BT_WATCHDOG_POLL_MS}ms")
      fun poll ()
      {  val remaining = deadline - SystemClock.elapsedRealtime ()
         if (btReconnectedSinceNoisy) {
            Dbg.log ("TunezSkip",
                     "bt watchdog: route back, resuming " +
                     "(remaining=${remaining}ms)")
            mediaSession.controller.transportControls.play ()
         }
         else if (remaining > 0) {
            Dbg.log ("TunezSkip",
                     "bt watchdog: poll, still no route, " +
                     "remaining=${remaining}ms")
            btWatchdogHandler.postDelayed ({ poll () }, BT_WATCHDOG_POLL_MS)
         }
         else
            Dbg.log ("TunezSkip",
                     "bt watchdog: gave up, staying paused, " +
                     "outputs=[${outputDevicesDump ()}]")
      }
      btWatchdogHandler.postDelayed ({ poll () }, BT_WATCHDOG_POLL_MS)
   }


   private fun updateMediaSession ()
   {  if (song.isEmpty ())  return
   // while casting report the remote player's actual state, or the
   // session always looks "paused" n the OS won't route volume keys to it
     val playing = isPlaying ()
      callback?.onPlayState (playing)
     val state = if (playing)  PlaybackStateCompat.STATE_PLAYING
                 else           PlaybackStateCompat.STATE_PAUSED
      mediaSession.setPlaybackState (
         PlaybackStateCompat.Builder ()
            .setState (state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions (PlaybackStateCompat.ACTION_PLAY          or
                         PlaybackStateCompat.ACTION_PAUSE         or
                         PlaybackStateCompat.ACTION_PLAY_PAUSE    or
                         PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            .build ()
      )
     val fnt = splitfn (song)
     val meta = MediaMetadataCompat.Builder ()
                .putString (MediaMetadataCompat.METADATA_KEY_TITLE,  fnt.ttl)
                .putString (MediaMetadataCompat.METADATA_KEY_ARTIST, fnt.grp)
                .putString (MediaMetadataCompat.METADATA_KEY_ALBUM,  fnt.x)
      if (albumArt != null)
         meta.putBitmap (MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
      mediaSession.setMetadata (meta.build ())
   }


   private fun postNotification ()
   {  if (song.isEmpty ())  return
     val isPlaying = mplay?.isPlaying == true
     val fnt       = splitfn (song)

     val piMain = PendingIntent.getActivity (
            this, 0,
            Intent (this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
     val piPlayPause = PendingIntent.getService (
            this, 1,
            Intent (this, MusicService::class.java).setAction (
                                                             ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE)
     val piNext = PendingIntent.getService (
            this, 2,
            Intent (this, MusicService::class.java).setAction (ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE)

     val ppIcon = if (isPlaying)  android.R.drawable.ic_media_pause
                  else            android.R.drawable.ic_media_play
     val ppLabel = if (isPlaying)  "Pause"  else  "Play"

     val notif = NotificationCompat.Builder (this, CHANNEL_ID)
        .setSmallIcon     (R.drawable.outline_music_cast_24)
        .setLargeIcon     (albumArt)
        .setContentTitle  (fnt.ttl)
        .setContentText   (fnt.grp)
        .setContentIntent (piMain)
        .setVisibility    (NotificationCompat.VISIBILITY_PUBLIC)
        .addAction        (ppIcon, ppLabel, piPlayPause)
        .addAction        (android.R.drawable.ic_media_next, "Next", piNext)
        .setStyle         (MediaStyle ()
                              .setMediaSession (mediaSession.sessionToken)
                              .setShowActionsInCompactView (0, 1))
        .build ()

   // service is already foreground from onCreate() - just update the
   // existing notification instead of re-requesting promotion, which
   // Android 12+ denies once the app has left the TOP state
      try {
         NotificationManagerCompat.from (this).notify (NOTIF_ID, notif)
      }
      catch (e: Exception) { }
   }
}


class BTDisco (private val mp: () -> MediaPlayer?,
               private val onNoisy: () -> Unit): BroadcastReceiver ()
// if bluetooth disconnects, don't keep playin !!
// mp is a supplier, not a fixed instance - mplay gets torn down and
// rebuilt on a GAIN full-recreate (see onAudioFocusChange), and a
// fixed reference here would go stale and throw on the released
// player the next time a noisy broadcast fires
{  override fun onReceive (context: Context, intent: Intent)
   {  if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
        val playing = mp ()?.isPlaying == true
         Dbg.log ("TunezSkip", "audio becoming noisy, isPlaying=$playing")
         if (playing)  onNoisy ()
      }
   }
}
