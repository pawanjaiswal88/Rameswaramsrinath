package com.mandir.manager.ui

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.mandir.manager.data.UserEntity
import com.mandir.manager.databinding.ActivityAddUserBinding
import com.mandir.manager.util.Constants
import com.mandir.manager.util.Passwords
import com.mandir.manager.util.Session
import com.mandir.manager.util.setOptions
import com.mandir.manager.util.toast
import com.mandir.manager.util.value
import kotlinx.coroutines.launch
import java.util.UUID

class AddUserActivity : BaseActivity() {

    companion object { const val EXTRA_UID = "user_uid" }

    private lateinit var b: ActivityAddUserBinding
    private var editing: UserEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard(adminOnly = true)) return
        b = ActivityAddUserBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        b.actRole.setOptions(Constants.ROLES, Constants.ROLE_COLLECTOR)
        b.btnSave.setOnClickListener { save() }

        intent.getStringExtra(EXTRA_UID)?.let { uid ->
            title = "Edit user"
            b.btnSave.text = "Save changes"
            b.tilPassword.hint = "New password (leave blank to keep)"
            b.etUsername.isEnabled = false
            lifecycleScope.launch {
                editing = db.userDao().byUid(uid)
                if (savedInstanceState == null) editing?.let { u ->
                    b.etFullName.setText(u.fullName)
                    b.etUsername.setText(u.username)
                    b.actRole.setText(u.role, false)
                    b.swApprover.isChecked = u.isApprover
                }
            }
        }
    }

    private fun save() {
        listOf(b.tilFullName, b.tilUsername, b.tilPassword, b.tilConfirm).forEach { it.error = null }
        val e = editing
        val name = b.etFullName.value()
        val username = b.etUsername.value().lowercase()
        val role = b.actRole.text.toString()
        val approver = b.swApprover.isChecked
        val password = b.etPassword.text?.toString().orEmpty()

        var ok = true
        if (name.isEmpty()) { b.tilFullName.error = "Enter the full name"; ok = false }
        if (e == null && !Passwords.validUsername(username)) { b.tilUsername.error = "Use 3–20 letters, numbers, . or _"; ok = false }
        if ((e == null || password.isNotEmpty()) && password.length < 6) { b.tilPassword.error = "Use at least 6 characters"; ok = false }
        if (password != b.etConfirm.text?.toString().orEmpty()) { b.tilConfirm.error = "Passwords do not match"; ok = false }
        if (!ok) return

        b.btnSave.isEnabled = false
        lifecycleScope.launch {
            val dao = db.userDao()
            val fail = { msg: String -> toast(msg); b.btnSave.isEnabled = true }

            if (approver && e?.isApprover != true && dao.approvers().size >= Constants.MAX_APPROVERS) {
                fail("Already ${Constants.MAX_APPROVERS} approvers. Remove one before adding another."); return@launch
            }
            if (e != null && e.role == Constants.ROLE_ADMIN && role != Constants.ROLE_ADMIN && dao.adminCount() <= 1) {
                fail("Keep at least one admin"); return@launch
            }

            val now = System.currentTimeMillis()
            val (hash, salt) = if (password.isNotEmpty()) Passwords.create(password)
            else (e?.passwordHash ?: "") to (e?.salt ?: "")

            if (e == null) {
                val existing = dao.byUsername(username)
                if (existing != null && existing.active) { b.tilUsername.error = "This username is taken"; b.btnSave.isEnabled = true; return@launch }
                // A removed user with the same username is brought back instead of duplicated.
                dao.upsert(
                    UserEntity(
                        uid = existing?.uid ?: UUID.randomUUID().toString(), username = username, fullName = name,
                        passwordHash = hash, salt = salt, role = role, isApprover = approver, active = true, updatedAt = now
                    )
                )
                toast("User $username added")
            } else {
                val updated = e.copy(fullName = name, role = role, isApprover = approver, passwordHash = hash, salt = salt, updatedAt = now)
                dao.upsert(updated)
                if (updated.uid == Session.user?.uid) Session.login(updated)
                toast("User updated")
            }
            finish()
        }
    }
}
