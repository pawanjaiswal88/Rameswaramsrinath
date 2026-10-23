package com.mandir.manager.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mandir.manager.R
import com.mandir.manager.data.MemberEntity
import com.mandir.manager.databinding.ActivityListBinding
import com.mandir.manager.util.Constants
import com.mandir.manager.util.Fmt
import com.mandir.manager.util.Session
import com.mandir.manager.util.WhatsApp
import com.mandir.manager.util.toast
import com.mandir.manager.util.value
import kotlinx.coroutines.launch

class MembersActivity : BaseActivity() {

    private lateinit var b: ActivityListBinding
    private var all: List<MemberEntity> = emptyList()

    private val adapter = RowAdapter<MemberEntity>(
        idOf = { it.uid },
        bind = { m, r ->
            r.tvTitle.text = m.fullName
            r.tvSubtitle.text = listOfNotNull(
                m.designation ?: m.category,
                m.houseNo?.takeIf { it.isNotBlank() }?.let { "House $it" },
                "Family of ${m.familyCount}"
            ).joinToString(" · ")
            r.tvMeta.text = "${m.recordId} · ${m.mobileNumber}"
            r.tvAmount.text = feeLabel(m)
            r.tvAmount.setTextColor(getColor(R.color.ink_muted))
        },
        onClick = { showOptions(it) }
    )

    private fun feeLabel(m: MemberEntity) = when (m.feePlan) {
        Constants.PLAN_MONTHLY -> "${Fmt.money(m.feeAmount)}/mo"
        Constants.PLAN_YEARLY -> "${Fmt.money(m.feeAmount)}/yr"
        else -> ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard()) return
        b = ActivityListBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        b.tilSearch.hint = "Search name, mobile or house no."
        b.tvEmpty.text = "No members yet.\nAdd committee members and colony residents to start tracking fees."
        b.rv.layoutManager = LinearLayoutManager(this)
        b.rv.adapter = adapter
        b.fab.text = "Add member"
        b.fab.setOnClickListener { startActivity(Intent(this, AddMemberActivity::class.java)) }
        b.etSearch.doAfterTextChanged { applyFilter() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.memberDao().observeAll().collect { all = it; applyFilter() }
            }
        }
    }

    private fun applyFilter() {
        val q = b.etSearch.value().lowercase()
        val list = if (q.isEmpty()) all else all.filter { m ->
            listOf(m.fullName, m.mobileNumber, m.houseNo, m.recordId, m.category, m.designation)
                .any { it?.lowercase()?.contains(q) == true }
        }
        adapter.submitList(list)
        val monthly = list.count { it.feePlan == Constants.PLAN_MONTHLY }
        val yearly = list.count { it.feePlan == Constants.PLAN_YEARLY }
        b.tvSummary.text = "${list.size} members · $monthly monthly · $yearly yearly · ${list.sumOf { it.familyCount }} people"
        b.tvEmpty.isVisible = list.isEmpty()
    }

    private fun showOptions(m: MemberEntity) {
        val options = mutableListOf("Record a donation", "Edit details", "Call", "Open WhatsApp chat")
        if (Session.isAdmin) options += "Remove member"
        MaterialAlertDialogBuilder(this)
            .setTitle(m.fullName)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, AddDonationActivity::class.java)
                            .putExtra(AddDonationActivity.EXTRA_MEMBER_UID, m.uid)
                            .putExtra(AddDonationActivity.EXTRA_TYPE, Constants.feeTypeFor(m.feePlan) ?: Constants.TYPE_GENERAL)
                    )
                    1 -> startActivity(Intent(this, AddMemberActivity::class.java).putExtra(AddMemberActivity.EXTRA_UID, m.uid))
                    2 -> startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${m.mobileNumber}")))
                    3 -> WhatsApp.send(this, m.mobileNumber, "")
                    4 -> confirmRemove(m)
                }
            }
            .show()
    }

    private fun confirmRemove(m: MemberEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove ${m.fullName}?")
            .setMessage("They will no longer appear in lists or pending fees. Their past donations stay in the records.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    db.memberDao().upsert(m.copy(deleted = true, updatedAt = System.currentTimeMillis()))
                    toast("${m.fullName} removed")
                }
            }
            .show()
    }
}
