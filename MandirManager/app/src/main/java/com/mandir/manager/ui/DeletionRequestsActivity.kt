package com.mandir.manager.ui

import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mandir.manager.R
import com.mandir.manager.data.DeletionRequestEntity
import com.mandir.manager.data.UserEntity
import com.mandir.manager.databinding.ActivityListBinding
import com.mandir.manager.databinding.DialogApproveBinding
import com.mandir.manager.util.Biometric
import com.mandir.manager.util.Constants
import com.mandir.manager.util.DeletionService
import com.mandir.manager.util.Fmt
import com.mandir.manager.util.Passwords
import com.mandir.manager.util.Session
import com.mandir.manager.util.setOptions
import com.mandir.manager.util.toast
import kotlinx.coroutines.launch

class DeletionRequestsActivity : BaseActivity() {

    private lateinit var b: ActivityListBinding

    private val adapter = RowAdapter<DeletionRequestEntity>(
        idOf = { it.uid },
        bind = { r, row ->
            val approvals = DeletionService.approvals(r)
            row.tvTitle.text = "${r.receiptNumber} · ${r.donorName}"
            row.tvSubtitle.text = "Reason: ${r.reason}"
            row.tvMeta.text = "Requested by ${r.requestedBy}, ${Fmt.dateTime(r.requestedAt)}\n" +
                "Approvals ${approvals.size}/${Constants.REQUIRED_APPROVALS}" +
                if (approvals.isEmpty()) "" else ": " + approvals.joinToString(", ") { it.name }
            row.tvAmount.text = when (r.status) {
                Constants.STATUS_PENDING -> Fmt.money(r.amount)
                else -> r.status
            }
            row.tvAmount.setTextColor(
                getColor(
                    when (r.status) {
                        Constants.STATUS_DELETED -> R.color.expense
                        Constants.STATUS_CANCELLED -> R.color.ink_muted
                        else -> R.color.saffron_dark
                    }
                )
            )
        },
        onClick = { showOptions(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard()) return
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        b.tilSearch.isVisible = false
        b.fab.isVisible = false
        b.tvEmpty.text = "No deletion requests.\nTo remove a wrong entry, open Donations, tap it and choose Request deletion."
        b.rv.layoutManager = LinearLayoutManager(this)
        b.rv.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.deletionDao().observeRequests().collect { list ->
                    adapter.submitList(list)
                    val pending = list.count { it.status == Constants.STATUS_PENDING }
                    b.tvSummary.text = "$pending waiting for approval · each needs ${Constants.REQUIRED_APPROVALS} different approvers (password + fingerprint)"
                    b.tvEmpty.isVisible = list.isEmpty()
                }
            }
        }
    }

    private fun showOptions(r: DeletionRequestEntity) {
        if (r.status != Constants.STATUS_PENDING) {
            MaterialAlertDialogBuilder(this)
                .setTitle("${r.receiptNumber} · ${r.status}")
                .setMessage(details(r))
                .setPositiveButton("Close", null)
                .show()
            return
        }
        val canCancel = Session.isAdmin || Session.user?.uid == r.requestedByUid
        val options = mutableListOf("Approve with fingerprint", "View details")
        if (canCancel) options += "Cancel request"
        MaterialAlertDialogBuilder(this)
            .setTitle("${r.receiptNumber} · ${Fmt.money(r.amount)}")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Approve with fingerprint" -> startApproval(r)
                    "View details" -> MaterialAlertDialogBuilder(this).setTitle(r.receiptNumber)
                        .setMessage(details(r)).setPositiveButton("Close", null).show()
                    else -> MaterialAlertDialogBuilder(this)
                        .setTitle("Cancel this request?")
                        .setMessage("The donation will stay. Approvals given so far are discarded.")
                        .setNegativeButton("Keep request", null)
                        .setPositiveButton("Cancel request") { _, _ ->
                            lifecycleScope.launch { DeletionService.cancel(this@DeletionRequestsActivity, r) }
                        }
                        .show()
                }
            }
            .show()
    }

    private fun details(r: DeletionRequestEntity): String {
        val approvals = DeletionService.approvals(r)
        return DeletionService.describeDonation(r.donationJson) +
            "\n\nReason: ${r.reason}\nRequested by ${r.requestedBy} on ${Fmt.dateTime(r.requestedAt)}" +
            "\n\nApprovals (${approvals.size}/${Constants.REQUIRED_APPROVALS}):" +
            (if (approvals.isEmpty()) "\nNone yet" else approvals.joinToString("") {
                "\n• ${it.name} — ${Fmt.dateTime(it.at)}, phone ${it.deviceCode}"
            })
    }

    private fun label(u: UserEntity) = "${u.fullName} (@${u.username})"

    private fun startApproval(r: DeletionRequestEntity) {
        lifecycleScope.launch {
            if (!Biometric.available(this@DeletionRequestsActivity)) {
                MaterialAlertDialogBuilder(this@DeletionRequestsActivity)
                    .setTitle("Fingerprint not set up")
                    .setMessage("Approvals need a fingerprint scan. Add a fingerprint in this phone's Settings › Security, or approve from a phone that has one.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }
            val done = DeletionService.approvals(r).map { it.userUid }.toSet()
            val candidates = db.userDao().approvers().filter { it.uid !in done }
            if (candidates.isEmpty()) {
                toast("No approvers left. An admin can mark more users as approvers (up to ${Constants.MAX_APPROVERS}).")
                return@launch
            }
            val v = DialogApproveBinding.inflate(layoutInflater)
            val preselect = candidates.firstOrNull { it.uid == Session.user?.uid } ?: candidates.first()
            v.actApprover.setOptions(candidates.map { label(it) }, label(preselect))

            MaterialAlertDialogBuilder(this@DeletionRequestsActivity)
                .setTitle("Approve deleting ${r.receiptNumber}")
                .setView(v.root)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Next: scan fingerprint") { _, _ ->
                    val approver = candidates.firstOrNull { label(it) == v.actApprover.text.toString() }
                    val password = v.etPassword.text?.toString().orEmpty()
                    if (approver != null) verifyAndApprove(r, approver, password)
                }
                .show()
        }
    }

    private fun verifyAndApprove(r: DeletionRequestEntity, approver: UserEntity, password: String) {
        lifecycleScope.launch {
            if (!Passwords.verify(password, approver)) {
                toast("Wrong password for ${approver.fullName}. Approval not recorded.")
                return@launch
            }
            val scanned = Biometric.confirm(
                this@DeletionRequestsActivity,
                "Approve deletion of ${r.receiptNumber}",
                "${approver.fullName}, scan your fingerprint"
            )
            if (!scanned) {
                toast("Fingerprint not confirmed. Approval not recorded.")
                return@launch
            }
            val count = DeletionService.approve(
                this@DeletionRequestsActivity, r.uid, approver, "Password + fingerprint on phone ${prefs.deviceCode}"
            )
            toast(
                if (count >= Constants.REQUIRED_APPROVALS) "${r.receiptNumber} deleted. Saved permanently in deletion history."
                else "Approval $count of ${Constants.REQUIRED_APPROVALS} recorded for ${approver.fullName}"
            )
        }
    }
}
