package com.mandir.manager.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mandir.manager.databinding.ActivitySyncBinding
import com.mandir.manager.util.Backup
import com.mandir.manager.util.CsvExport
import com.mandir.manager.util.DriveAuthorizer
import com.mandir.manager.util.Fmt
import com.mandir.manager.util.Session
import com.mandir.manager.util.Share
import com.mandir.manager.util.SyncManager
import com.mandir.manager.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SyncActivity : BaseActivity() {

    private lateinit var b: ActivitySyncBinding

    private val drive = DriveAuthorizer(this) { token -> runSync(token) }

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) export(uri)
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) import(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard()) return
        b = ActivitySyncBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        b.swAuto.isChecked = prefs.autoSync
        b.swAuto.setOnCheckedChangeListener { _, on -> prefs.autoSync = on }
        b.btnSync.setOnClickListener { drive.start() }
        b.btnExport.setOnClickListener { exportFile.launch("mandir-backup-${Fmt.fileStamp()}.mbk") }
        b.btnImport.isVisible = Session.isAdmin
        b.btnImport.setOnClickListener { importFile.launch(arrayOf("*/*")) }
        b.btnCsv.setOnClickListener {
            lifecycleScope.launch {
                Share.files(this@SyncActivity, CsvExport.create(this@SyncActivity), "text/csv", "${prefs.mandirName} accounts")
            }
        }
        showStatus()
    }

    private fun showStatus() {
        b.tvStatus.text = prefs.lastSyncMessage
        b.tvDevice.text = "This phone's code: ${prefs.deviceCode}"
    }

    private fun busy(on: Boolean) {
        b.progress.isVisible = on
        b.btnSync.isEnabled = !on
        b.btnImport.isEnabled = !on
    }

    private fun runSync(token: String) {
        busy(true)
        lifecycleScope.launch {
            val result = runCatching { SyncManager.sync(applicationContext, token) }
            busy(false)
            result.onSuccess { toast("Sync complete") }.onFailure { toast("Sync failed: ${it.message}") }
            showStatus()
        }
    }

    private fun export(uri: Uri) {
        lifecycleScope.launch {
            val ok = runCatching {
                val bytes = Backup.encode(Backup.snapshot(applicationContext))
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot write")
                }
            }.isSuccess
            toast(if (ok) "Backup saved" else "Could not save the backup")
        }
    }

    private fun import(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Import this backup?")
            .setMessage("Its entries are merged into this phone. Nothing newer is overwritten and deletion history is kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import") { _, _ ->
                busy(true)
                lifecycleScope.launch {
                    val result = runCatching {
                        val bytes = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
                        }
                        Backup.merge(applicationContext, Backup.decode(bytes))
                    }
                    busy(false)
                    result.onSuccess { toast("Imported · $it update(s)") }
                        .onFailure { toast("This file is not a Mandir Manager backup") }
                }
            }
            .show()
    }
}
