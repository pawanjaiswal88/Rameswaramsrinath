package com.mandir.manager.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mandir.manager.R
import com.mandir.manager.data.UserEntity
import com.mandir.manager.databinding.ActivityLoginBinding
import com.mandir.manager.util.Backup
import com.mandir.manager.util.Biometric
import com.mandir.manager.util.Constants
import com.mandir.manager.util.DriveAuthorizer
import com.mandir.manager.util.Logo
import com.mandir.manager.util.Passwords
import com.mandir.manager.util.Session
import com.mandir.manager.util.SyncManager
import com.mandir.manager.util.toast
import com.mandir.manager.util.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class LoginActivity : BaseActivity() {

    private lateinit var b: ActivityLoginBinding
    private var setupMode = false
    private var bioUser: UserEntity? = null

    private val drive = DriveAuthorizer(this) { token -> pullFromDrive(token) }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) restoreFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.user != null && !Session.isExpired()) { openHome(); return }
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.hide()

        b.btnLogin.setOnClickListener { if (setupMode) createAdmin() else login() }
        b.btnFingerprint.setOnClickListener { fingerprintLogin() }
        b.btnRestoreDrive.setOnClickListener { drive.start() }
        b.btnRestoreFile.setOnClickListener { openBackup.launch(arrayOf("*/*")) }
        refreshMode()
    }

    private fun refreshMode() {
        b.tvMandir.text = prefs.mandirName
        Logo.bitmap(this)?.let { b.ivLogo.setImageBitmap(it) } ?: b.ivLogo.setImageResource(R.mipmap.ic_launcher)
        lifecycleScope.launch {
            setupMode = db.userDao().activeCount() == 0
            b.tilFullName.isVisible = setupMode
            b.tilConfirm.isVisible = setupMode
            b.setupBox.isVisible = setupMode
            b.tvTitle.text = if (setupMode) "First time setup: create the admin account" else "Log in to continue"
            b.btnLogin.text = if (setupMode) "Create admin and continue" else "Log in"

            bioUser = prefs.biometricUserUid?.let { db.userDao().byUid(it) }?.takeIf { it.active }
            val showBio = !setupMode && bioUser != null && Biometric.available(this@LoginActivity)
            b.btnFingerprint.isVisible = showBio
            if (showBio) b.btnFingerprint.text = "Unlock as ${bioUser?.fullName} with fingerprint"
        }
    }

    private fun busy(on: Boolean) {
        b.progress.isVisible = on
        b.btnLogin.isEnabled = !on
    }

    private fun login() {
        b.tilUsername.error = null
        b.tilPassword.error = null
        val username = b.etUsername.value().lowercase()
        val password = b.etPassword.text?.toString().orEmpty()
        if (username.isEmpty()) { b.tilUsername.error = "Enter your username"; return }
        if (password.isEmpty()) { b.tilPassword.error = "Enter your password"; return }
        busy(true)
        lifecycleScope.launch {
            val user = db.userDao().byUsername(username)
            val ok = user != null && user.active && Passwords.verify(password, user)
            busy(false)
            if (!ok || user == null) {
                b.tilPassword.error = "Wrong username or password"
                return@launch
            }
            onLoggedIn(user, offerFingerprint = true)
        }
    }

    private fun createAdmin() {
        listOf(b.tilFullName, b.tilUsername, b.tilPassword, b.tilConfirm).forEach { it.error = null }
        val name = b.etFullName.value()
        val username = b.etUsername.value().lowercase()
        val password = b.etPassword.text?.toString().orEmpty()
        var ok = true
        if (name.isEmpty()) { b.tilFullName.error = "Enter your name"; ok = false }
        if (!Passwords.validUsername(username)) { b.tilUsername.error = "Use 3–20 letters, numbers, . or _"; ok = false }
        if (password.length < 6) { b.tilPassword.error = "Use at least 6 characters"; ok = false }
        if (password != b.etConfirm.text?.toString()) { b.tilConfirm.error = "Passwords do not match"; ok = false }
        if (!ok) return
        busy(true)
        lifecycleScope.launch {
            val (hash, salt) = Passwords.create(password)
            val admin = UserEntity(
                uid = UUID.randomUUID().toString(), username = username, fullName = name,
                passwordHash = hash, salt = salt, role = Constants.ROLE_ADMIN, isApprover = true,
                updatedAt = System.currentTimeMillis()
            )
            db.userDao().upsert(admin)
            busy(false)
            onLoggedIn(admin, offerFingerprint = true)
        }
    }

    private fun fingerprintLogin() {
        val u = bioUser ?: return
        lifecycleScope.launch {
            if (Biometric.confirm(this@LoginActivity, "Unlock Mandir Manager", "Log in as ${u.fullName}")) {
                val fresh = db.userDao().byUid(u.uid)
                if (fresh != null && fresh.active) onLoggedIn(fresh, offerFingerprint = false)
                else toast("This user has been removed. Log in with a password.")
            }
        }
    }

    private fun onLoggedIn(user: UserEntity, offerFingerprint: Boolean) {
        Session.login(user)
        if (offerFingerprint && prefs.biometricUserUid != user.uid && Biometric.available(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Unlock with fingerprint?")
                .setMessage("Next time, ${user.fullName} can open the app on this phone with a fingerprint instead of the password.")
                .setNegativeButton("Not now") { _, _ -> openHome() }
                .setPositiveButton("Use fingerprint") { _, _ ->
                    prefs.biometricUserUid = user.uid
                    openHome()
                }
                .setCancelable(false)
                .show()
        } else {
            openHome()
        }
    }

    private fun openHome() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun pullFromDrive(token: String) {
        busy(true)
        lifecycleScope.launch {
            val result = runCatching { SyncManager.sync(applicationContext, token) }
            busy(false)
            result.onSuccess { toast("Committee data downloaded. Log in with your username.") }
                .onFailure { toast("Sync failed: ${it.message}") }
            refreshMode()
        }
    }

    private fun restoreFile(uri: Uri) {
        busy(true)
        lifecycleScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
                }
                Backup.merge(applicationContext, Backup.decode(bytes))
            }
            busy(false)
            result.onSuccess { toast("Backup restored. Log in with your username.") }
                .onFailure { toast("This file is not a Mandir Manager backup") }
            refreshMode()
        }
    }
}
