package com.mandir.manager.ui

import android.os.Bundle
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mandir.manager.R
import com.mandir.manager.data.DeletionLogEntity
import com.mandir.manager.databinding.ActivityListBinding
import com.mandir.manager.util.DeletionService
import com.mandir.manager.util.Fmt
import com.mandir.manager.util.value
import kotlinx.coroutines.launch

/** Read-only. There is deliberately no delete or edit action anywhere for this history. */
class DeletionLogActivity : BaseActivity() {

    private lateinit var b: ActivityListBinding
    private var all: List<DeletionLogEntity> = emptyList()

    private val adapter = RowAdapter<DeletionLogEntity>(
        idOf = { it.uid },
        bind = { l, r ->
            r.tvTitle.text = "${l.receiptNumber} · ${l.donorName}"
            r.tvSubtitle.text = "Reason: ${l.reason}"
            r.tvMeta.text = "Deleted ${Fmt.dateTime(l.deletedAt)} · approved by " +
                DeletionService.approvals(l.approvalsJson).joinToString(", ") { it.name }
            r.tvAmount.text = Fmt.money(l.amount)
            r.tvAmount.setTextColor(getColor(R.color.expense))
        },
        onClick = { showDetails(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard()) return
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        b.fab.isVisible = false
        b.tilSearch.hint = "Search receipt, donor, reason or approver"
        b.tvEmpty.text = "No donation has been deleted."
        b.rv.layoutManager = LinearLayoutManager(this)
        b.rv.adapter = adapter
        b.etSearch.doAfterTextChanged { applyFilter() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.deletionDao().observeLog().collect { all = it; applyFilter() }
            }
        }
    }

    private fun applyFilter() {
        val q = b.etSearch.value().lowercase()
        val list = if (q.isEmpty()) all else all.filter { l ->
            (listOf(l.receiptNumber, l.donorName, l.reason, l.requestedBy) +
                DeletionService.approvals(l.approvalsJson).map { it.name })
                .any { it.lowercase().contains(q) }
        }
        adapter.submitList(list)
        b.tvSummary.text = "${list.size} deleted entries · ${Fmt.money(list.sumOf { it.amount })} · permanent record, cannot be removed"
        b.tvEmpty.isVisible = list.isEmpty()
    }

    private fun showDetails(l: DeletionLogEntity) {
        val approvals = DeletionService.approvals(l.approvalsJson)
        val text = "ORIGINAL ENTRY\n" + DeletionService.describeDonation(l.donationJson) +
            "\n\nReason: ${l.reason}" +
            "\nRequested by ${l.requestedBy} on ${Fmt.dateTime(l.requestedAt)}" +
            "\n\nApproved by:" + approvals.joinToString("") {
                "\n• ${it.name} — ${Fmt.dateTime(it.at)}, phone ${it.deviceCode} (${it.method})"
            } +
            "\n\nDeleted on ${Fmt.dateTime(l.deletedAt)} (phone ${l.deletedOnDevice})"
        MaterialAlertDialogBuilder(this)
            .setTitle(l.receiptNumber)
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
    }
}
