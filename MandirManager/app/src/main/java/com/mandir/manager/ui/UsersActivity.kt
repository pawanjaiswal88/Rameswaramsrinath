package com.mandir.manager.ui

import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mandir.manager.R
import com.mandir.manager.data.UserEntity
import com.mandir.manager.databinding.ActivityListBinding
import com.mandir.manager.util.Constants
import com.mandir.manager.util.Session
import com.mandir.manager.util.toast
import kotlinx.coroutines.launch

class UsersActivity : BaseActivity() {

    private lateinit var b: ActivityListBinding

    private val adapter = RowAdapter<UserEntity>(
        idOf = { it.uid },
        bind = { u, r ->
            r.tvTitle.text = u.fullName + if (u.uid == Session.user?.uid) "  (you)" else ""
            r.tvSubtitle.text = "@${u.username} · ${u.role}"
            r.tvMeta.text = if (u.isApprover) "✋ Deletion approver" else "Not an approver"
            r.tvAmount.text = if (u.uid == prefs.biometricUserUid) "👆" else ""
            r.tvAmount.setTextColor(getColor(R.color.ink_muted))
        },
        onClick = { showOptions(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard(adminOnly = true)) return
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        b.tilSearch.isVisible = false
        b.rv.layoutManager = LinearLayoutManager(this)
        b.rv.adapter = adapter
        b.fab.text = "Add user"
        b.fab.setOnClickListener { startActivity(Intent(this, AddUserActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.userDao().observeActive().collect { list ->
                    adapter.submitList(list)
                    val approvers = list.count { it.isApprover }
                    b.tvSummary.text = "${list.size} users · $approvers of ${Constants.MAX_APPROVERS} approver places used · " +
                        "${Constants.REQUIRED_APPROVALS} approvals needed to delete a donation"
                }
            }
        }
    }

    private fun showOptions(u: UserEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(u.fullName)
            .setItems(arrayOf("Edit details or reset password", "Remove user")) { _, which ->
                if (which == 0) startActivity(Intent(this, AddUserActivity::class.java).putExtra(AddUserActivity.EXTRA_UID, u.uid))
                else confirmRemove(u)
            }
            .show()
    }

    private fun confirmRemove(u: UserEntity) {
        lifecycleScope.launch {
            if (u.uid == Session.user?.uid) { toast("You cannot remove yourself"); return@launch }
            if (u.role == Constants.ROLE_ADMIN && db.userDao().adminCount() <= 1) {
                toast("Keep at least one admin"); return@launch
            }
            MaterialAlertDialogBuilder(this@UsersActivity)
                .setTitle("Remove ${u.fullName}?")
                .setMessage("They will no longer be able to log in on any phone. Their name stays on entries they made.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove") { _, _ ->
                    lifecycleScope.launch {
                        db.userDao().upsert(u.copy(active = false, isApprover = false, updatedAt = System.currentTimeMillis()))
                        if (prefs.biometricUserUid == u.uid) prefs.biometricUserUid = null
                        toast("${u.fullName} removed")
                    }
                }
                .show()
        }
    }
}
