package com.mandir.manager.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mandir.manager.R
import com.mandir.manager.data.DonationEntity
import com.mandir.manager.databinding.ActivityListBinding
import com.mandir.manager.util.Constants
import com.mandir.manager.util.DeletionService
import com.mandir.manager.util.Fmt
import com.mandir.manager.util.ReceiptPdf
import com.mandir.manager.util.Session
import com.mandir.manager.util.Share
import com.mandir.manager.util.WhatsApp
import com.mandir.manager.util.purpose
import com.mandir.manager.util.toast
import com.mandir.manager.util.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DonationsActivity : BaseActivity() {

    private lateinit var b: ActivityListBinding
    private var all: List<DonationEntity> = emptyList()

    private val adapter = RowAdapter<DonationEntity>(
        idOf = { it.uid },
        bind = { d, r ->
            r.tvTitle.text = d.donorName
            r.tvSubtitle.text = d.purpose()
            r.tvMeta.text = "${d.receiptNumber} · ${Fmt.dateTime(d.timestamp)} · ${d.paymentMode} · by ${d.collectedBy}"
            r.tvAmount.text = Fmt.money(d.amount)
            r.tvAmount.setTextColor(getColor(R.color.income))
        },
        onClick = { showOptions(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard()) return
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        b.tilSearch.hint = "Search name, receipt no., festival or mobile"
        b.tvEmpty.text = "No donations recorded yet.\nTap New donation to add the first one."
        b.rv.layoutManager = LinearLayoutManager(this)
        b.rv.adapter = adapter
        b.fab.text = "New donation"
        b.fab.setOnClickListener { startActivity(Intent(this, AddDonationActivity::class.java)) }
        b.etSearch.doAfterTextChanged { applyFilter() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.donationDao().observeAll().collect { all = it; applyFilter() }
            }
        }
    }

    private fun applyFilter() {
        val q = b.etSearch.value().lowercase()
        val list = if (q.isEmpty()) all else all.filter { d ->
            listOf(d.donorName, d.receiptNumber, d.festivalName, d.donorMobile, d.donationType, d.transactionId)
                .any { it?.lowercase()?.contains(q) == true }
        }
        adapter.submitList(list)
        b.tvSummary.text = "${list.size} entries · ${Fmt.money(list.sumOf { it.amount })}"
        b.tvEmpty.isVisible = list.isEmpty()
    }

    private fun showOptions(d: DonationEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("${d.receiptNumber} · ${Fmt.money(d.amount)}")
            .setItems(arrayOf("Resend WhatsApp receipt", "Share PDF receipt", "Request deletion")) { _, which ->
                when (which) {
                    0 -> {
                        val mobile = d.donorMobile
                        if (mobile == null) toast("No mobile number was saved for this donor")
                        else WhatsApp.send(this, mobile, WhatsApp.receiptMessage(prefs, d))
                    }
                    1 -> sharePdf(d)
                    2 -> requestDeletion(d)
                }
            }
            .show()
    }

    private fun sharePdf(d: DonationEntity) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { ReceiptPdf.create(this@DonationsActivity, prefs, d) }
            Share.files(this@DonationsActivity, listOf(file), "application/pdf", "Receipt ${d.receiptNumber}")
        }
    }

    private fun requestDeletion(d: DonationEntity) {
        lifecycleScope.launch {
            val existing = db.deletionDao().pendingFor(d.uid)
            if (existing != null) {
                toast("Deletion already requested: ${DeletionService.approvals(existing).size} of ${Constants.REQUIRED_APPROVALS} approvals")
                startActivity(Intent(this@DonationsActivity, DeletionRequestsActivity::class.java))
                return@launch
            }
            val input = TextInputEditText(this@DonationsActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            val til = TextInputLayout(this@DonationsActivity).apply {
                hint = "Reason (e.g. wrong amount entered)"
                addView(input)
            }
            val box = FrameLayout(this@DonationsActivity).apply {
                setPadding(64, 16, 64, 0)
                addView(til)
            }
            MaterialAlertDialogBuilder(this@DonationsActivity)
                .setTitle("Request deletion of ${d.receiptNumber}?")
                .setMessage("${Fmt.money(d.amount)} from ${d.donorName}. It is removed only after ${Constants.REQUIRED_APPROVALS} approvers confirm with password and fingerprint. The deletion is kept in permanent history.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send request") { _, _ ->
                    val reason = input.text?.toString()?.trim().orEmpty()
                    if (reason.length < 5) {
                        toast("Write a reason of at least 5 characters")
                        return@setPositiveButton
                    }
                    lifecycleScope.launch {
                        DeletionService.request(this@DonationsActivity, d, reason, Session.user ?: return@launch)
                        toast("Deletion request created. ${Constants.REQUIRED_APPROVALS} approvals needed.")
                        startActivity(Intent(this@DonationsActivity, DeletionRequestsActivity::class.java))
                    }
                }
                .show()
        }
    }
}
