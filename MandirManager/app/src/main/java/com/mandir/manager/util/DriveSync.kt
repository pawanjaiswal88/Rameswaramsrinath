package com.mandir.manager.util

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal Google Drive v3 REST client (no extra libraries). */
class DriveApi(private val token: String) {

    data class DriveFile(val id: String, val name: String, val modifiedTime: String)

    private companion object {
        const val BASE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }

    private fun open(url: String, method: String = "GET") =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun read(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
            throw IOException("Google Drive error $code ${err.take(200)}")
        }
        return c.inputStream.use { it.readBytes() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun findOrCreateFolder(name: String): String {
        val q = "name = '$name' and mimeType = '$FOLDER_MIME' and trashed = false"
        val found = JSONObject(read(open("$BASE/files?q=${enc(q)}&fields=${enc("files(id,name)")}")).toString(Charsets.UTF_8))
            .getJSONArray("files")
        if (found.length() > 0) return found.getJSONObject(0).getString("id")

        val c = open("$BASE/files", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        c.outputStream.use { it.write(JSONObject().put("name", name).put("mimeType", FOLDER_MIME).toString().toByteArray()) }
        return JSONObject(read(c).toString(Charsets.UTF_8)).getString("id")
    }

    fun list(folderId: String): List<DriveFile> {
        val q = "'$folderId' in parents and trashed = false"
        val arr = JSONObject(
            read(open("$BASE/files?q=${enc(q)}&pageSize=200&fields=${enc("files(id,name,modifiedTime)")}")).toString(Charsets.UTF_8)
        ).getJSONArray("files")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            DriveFile(o.getString("id"), o.getString("name"), o.optString("modifiedTime"))
        }
    }

    fun download(id: String): ByteArray = read(open("$BASE/files/$id?alt=media"))

    fun create(folderId: String, name: String, bytes: ByteArray) {
        val boundary = "mandir${System.nanoTime()}"
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(folderId)).toString()
        val c = open("$UPLOAD/files?uploadType=multipart", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        }
        c.outputStream.use { o ->
            o.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
            o.write("--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            o.write(bytes)
            o.write("\r\n--$boundary--\r\n".toByteArray())
        }
        read(c)
    }

    fun delete(id: String) {
        val c = open("$BASE/files/$id", "DELETE")
        if (c.responseCode !in 200..299 && c.responseCode != 404) throw IOException("Delete failed ${c.responseCode}")
    }
}

/**
 * Sync through one shared committee Google account:
 *  every phone uploads its own snapshot to the folder "Mandir Manager Sync",
 *  downloads the other phones' snapshots and merges them. Nothing is overwritten blindly.
 */
object SyncManager {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private val lock = Mutex()

    fun authRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Token without showing any screen; null if the user has not connected Drive yet. */
    suspend fun silentToken(context: Context): String? = runCatching {
        val r = Identity.getAuthorizationClient(context).authorize(authRequest()).await()
        if (r.hasResolution()) null else r.accessToken
    }.getOrNull()

    suspend fun sync(context: Context, token: String): String = lock.withLock {
        withContext(Dispatchers.IO) {
            val prefs = Prefs(context)
            val api = DriveApi(token)
            val folder = api.findOrCreateFolder(Constants.DRIVE_FOLDER)
            val files = api.list(folder)
            val ownName = "snapshot-${prefs.deviceId}.mbk"

            val others = files.filter { it.name.startsWith("snapshot-") && it.name != ownName }
            var changed = 0
            others.forEach { f -> changed += Backup.merge(context, Backup.decode(api.download(f.id))) }

            api.create(folder, ownName, Backup.encode(Backup.snapshot(context)))
            files.filter { it.name == ownName }.forEach { runCatching { api.delete(it.id) } }

            val msg = "Last sync ${Fmt.dateTime(System.currentTimeMillis())} · ${others.size} other phone(s) · $changed update(s) received"
            prefs.lastSyncAt = System.currentTimeMillis()
            prefs.lastSyncMessage = msg
            msg
        }
    }
}

/** Handles the one-time "Allow access to Google Drive" screen. Create it as an Activity property. */
class DriveAuthorizer(private val activity: ComponentActivity, private val onToken: (String) -> Unit) {

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val token = runCatching {
            Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(res.data).accessToken
        }.getOrNull()
        if (token != null) onToken(token) else activity.toast("Google Drive access was not granted")
    }

    fun start() {
        Identity.getAuthorizationClient(activity).authorize(SyncManager.authRequest())
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) {
                    launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    r.accessToken?.let(onToken) ?: activity.toast("Google Drive access was not granted")
                }
            }
            .addOnFailureListener { e -> activity.toast("Google sign-in failed: ${e.message}") }
    }
}
