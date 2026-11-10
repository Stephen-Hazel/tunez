// SongStore.kt - our Music/tunez folder thru the storage access framework
// (a folder the user picked once) instead of all-files access.  songs n
// files are still named by their path relative to the folder

package app.shaz.tunez

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SongStore (ctx: Context, val tree: Uri)
{  private val cr     = ctx.contentResolver
   private val rootId = DocumentsContract.getTreeDocumentId (tree)
// the folder's path on its drive - "primary:Music/tunez" -> Music/tunez
// (empty if they picked a whole drive)
   val where = rootId.substringAfter (':')
// relative path -> document uri, files only.  the http server reads it
// from its own threads so it's a concurrent map
   private val docs   = ConcurrentHashMap<String, Uri> ()

   companion object {
      const val PREF = "tree"

   // where the picker starts out
      val HINT: Uri = DocumentsContract.buildDocumentUri (
         "com.android.externalstorage.documents", "primary:Music/tunez")

      fun saved (ctx: Context): SongStore?
      // the folder we were given before, if we still have access to it
      {  val s = ctx.getSharedPreferences ("prf", Context.MODE_PRIVATE)
                    .getString (PREF, null) ?: return null
        val u = Uri.parse (s)
        val ok = ctx.contentResolver.persistedUriPermissions.any {
                    it.uri == u && it.isReadPermission && it.isWritePermission }
         return if (ok)  SongStore (ctx, u)  else  null
      }

      fun make ()
      // make Music/tunez if it's not there so the picker can land on it.
      // android 11+ lets us mkdir in Music without storage permission -
      // older ones don't, so they make it in the picker (New folder)
      {  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)  return
        val d = File (Environment.getExternalStoragePublicDirectory (
                         Environment.DIRECTORY_MUSIC), "tunez")
         try {
            if (d.isDirectory)  return
            Dbg.log ("TunezStore", "make $d: ${d.mkdirs ()}")
         }
         catch (e: Exception) { Dbg.log ("TunezStore", "make $d: $e") }
      }

      fun keep (ctx: Context, u: Uri)
      // hang onto a freshly picked folder for good
      {  ctx.contentResolver.takePersistableUriPermission (u,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
         ctx.getSharedPreferences ("prf", Context.MODE_PRIVATE).edit ()
            .putString (PREF, u.toString ()).apply ()
      }
   }


   fun scan (): List<String>
   // every file under the folder (skipping hidden dirs) - one query per dir
   {  docs.clear ()
     val todo = ArrayDeque<Pair<String, String>> ()     // doc id, rel dir
      todo.add (Pair (rootId, ""))
      while (todo.isNotEmpty ()) {
        val (id, rel) = todo.removeFirst ()
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree (tree, id)
         try {
            cr.query (kids, arrayOf (Document.COLUMN_DOCUMENT_ID,
                                     Document.COLUMN_DISPLAY_NAME,
                                     Document.COLUMN_MIME_TYPE),
                      null, null, null)?.use { c ->
               while (c.moveToNext ()) {
                 val kid  = c.getString (0)
                 val name = c.getString (1)
                 val rp   = if (rel.isEmpty ())  name  else  "$rel/$name"
                  if (c.getString (2) == Document.MIME_TYPE_DIR) {
                     if (! name.startsWith ("."))  todo.add (Pair (kid, rp))
                  }
                  else  docs [rp] =
                     DocumentsContract.buildDocumentUriUsingTree (tree, kid)
               }
            }
         }
         catch (e: Exception) { Dbg.log ("TunezStore", "scan $rel: $e") }
      }
      return docs.keys.toList ()
   }


   fun uri    (rel: String): Uri?     { return docs [rel] }
   fun exists (rel: String): Boolean  { return docs.containsKey (rel) }


   fun rename (rel: String, newRel: String): Boolean
   // newRel has to be in the same dir - only the file name changes
   {  val u = docs [rel] ?: return false
      if (docs.containsKey (newRel))  return false
     val nu = try {
         DocumentsContract.renameDocument (cr, u, baseName (newRel))
      }
      catch (e: Exception) {
         Dbg.log ("TunezStore", "rename $rel: $e")
         null
      } ?: return false
      docs.remove (rel)
      docs [newRel] = nu
      return true
   }


   fun delete (rel: String): Boolean
   {  val u = docs [rel] ?: return false
     val ok = try { DocumentsContract.deleteDocument (cr, u) }
              catch (e: Exception) { false }
      if (ok)  docs.remove (rel)
      return ok
   }


   fun openFd (rel: String): ParcelFileDescriptor?
   {  val u = docs [rel] ?: return null
      return try { cr.openFileDescriptor (u, "r") }
             catch (e: Exception) { null }
   }


// small text files sittin in the folder itself (done.txt n such)

   fun readText (name: String): String?
   {  val u = docs [name] ?: return null
      return try {
         cr.openInputStream (u)?.use {
            it.readBytes ().toString (Charsets.UTF_8) }
      }
      catch (e: Exception) { null }
   }


   fun writeText (name: String, text: String, append: Boolean = false)
   {  val u = docs [name] ?: create (name) ?: return
      try {
         cr.openOutputStream (u, if (append)  "wa"  else  "wt")?.use {
            it.write (text.toByteArray ())
         }
      }
      catch (e: Exception) { }
   }


   private fun create (name: String): Uri?
   // octet-stream so the provider doesn't go tackin on its own extension
   {  val dir = DocumentsContract.buildDocumentUriUsingTree (tree, rootId)
     val u = try {
         DocumentsContract.createDocument (cr, dir,
                                           "application/octet-stream", name)
      }
      catch (e: Exception) { null } ?: return null
      docs [name] = u
      return u
   }
}
