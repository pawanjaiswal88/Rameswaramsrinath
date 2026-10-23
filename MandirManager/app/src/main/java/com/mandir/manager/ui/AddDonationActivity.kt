package com.mandir.manager.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mandir.manager.data.DonationEntity
import com.mandir.manager.data.MemberEntity
import com.mandir.manager.databinding.ActivityAddDonationBinding
import com.mandir.manager.util.Constants
import com.mandir.manager.util.Dates
import com.mandir.manager.util.Fmt
import com.mandir.manager.util.Numbering
import com.mandir.manager.util.Session
import com.mandir.manager.util.WhatsApp
import com.mandir.manager.util.setOptions
import com.mandir.manager.util.toast
import com.mandir.manager.util.value
import kotlinx.coroutines.launch
import java.util.UUID

class AddDonationActivity : BaseActivity() {

    companion object {
        const val EXTRA_MEMBER_UID = "member_uid"
        const val EXTRA_TYPE = "donation_type"
        const val EXTRA_PERIOD = "fee_period"
        private const val STATE_MEMBER = "selected_member"
    }

    private lateinit var b: ActivityAddDonationBinding
    private var selected: MemberEntity? = null
    private val monthKeys = Dates.recentMonths(back = 11, ahead = 2).toMutableList()
    private val yearKeys = Dates.yearOptions().toMutableList()
    private var presetPeriod: String? = null
    private var shownType = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard()) return
        b = ActivityAddDonationBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        presetPeriod = intent.getStringExtra(EXTRA_PERIOD)?.also { p ->
            if (p.length == 7 && p !in monthKeys) monthKeys.add(p)
            if (p.length == 4 && p !in yearKeys) yearKeys.add(p)
        }

        b.actType.setOptions(Constants.DONATION_TYPES, intent.getStringExtra(EXTRA_TYPE) ?: Constants.TYPE_GENERAL)
        b.actMode.setOptions(Constants.PAYMENT_MODES, Constants.MODE_CASH)
        b.etCollectedBy.setText(Session.name)

        b.actType.setOnItemClickListener { _, _, _, _ -> updateTypeUi(); prefillFee() }
        b.actMode.setOnItemClickListener { _, _, _, _ -> updateModeUi() }
        b.etDonor.setOnItemClickListener { parent, _, position, _ ->
            (parent.getItemAtPosition(position) as? MemberEntity)?.let { selectMember(it) }
        }
        b.etDonor.doAfterTextChanged { text ->
            val s = selected
            if (s != null && text.toString() != s.fullName) {
                selected = null
                showMemberInfo()
            }
        }
        b.btnSaveWhatsApp.setOnClickListener { save(sendWhatsApp = true) }
        b.btnSaveOnly.setOnClickListener { save(sendWhatsApp = false) }
        updateTypeUi()
        updateModeUi()

        val restoreUid = savedInstanceState?.getString(STATE_MEMBER) ?: intent.getStringExtra(EXTRA_MEMBER_UID)
        lifecycleScope.launch {
            val members = db.memberDao().getAll()
            b.etDonor.setAdapter(ArrayAdapter(this@AddDonationActivity, android.R.layout.simple_dropdown_item_1line, members))
            val festivals = (db.donationDao().festivalNames() + db.expenseDao().festivalNames())
                .distinctBy { it.lowercase() }.sorted()
            b.etFestival.setAdapter(ArrayAdapter(this@AddDonationActivity, android.R.layout.simple_dropdown_item_1line, festivals))
            members.firstOrNull { it.uid == restoreUid }?.let { selectMember(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MEMBER, selected?.uid)
    }

    private fun typeText() = b.actType.text.toString()

    private fun selectMember(m: MemberEntity) {
        selected = m
        b.etDonor.setText(m.fullName, false)
        b.etDonor.setSelection(m.fullName.length)
        b.etMobile.setText(m.mobileNumber)
        b.tilDonor.error = null
        // If a fee is being recorded, switch to this member's own plan (monthly or yearly).
        val planType = Constants.feeTypeFor(m.feePlan)
        if (Constants.isFeeType(typeText()) && planType != null && planType != typeText()) {
            b.actType.setText(planType, false)
            updateTypeUi()
        }
        showMemberInfo()
        prefillFee()
    }

    private fun showMemberInfo() {
        val m = selected
        b.tvDonorInfo.isVisible = m != null
        if (m != null) {
            val fee = when (m.feePlan) {
                Constants.PLAN_MONTHLY -> "${Fmt.money(m.feeAmount)}/month"
                Constants.PLAN_YEARLY -> "${Fmt.money(m.feeAmount)}/year"
                else -> null
            }
            b.tvDonorInfo.text = "✓ " + listOfNotNull(
                m.recordId, m.designation ?: m.category,
                m.houseNo?.takeIf { it.isNotBlank() }?.let { "House $it" }, fee
            ).joinToString(" · ")
        }
    }

    private fun prefillFee() {
        val m = selected ?: return
        if (Constants.feeTypeFor(m.feePlan) == typeText() && m.feeAmount > 0 && b.etAmount.value().isEmpty()) {
            b.etAmount.setText(Fmt.plain(m.feeAmount))
        }
    }

    private fun updateTypeUi() {
        val type = typeText()
        val monthly = type == Constants.TYPE_MONTHLY
        val yearly = type == Constants.TYPE_YEARLY
        b.tilPeriod.isVisible = monthly || yearly
        b.tilFestival.isVisible = !monthly && !yearly
        if (type != shownType) {
            if (monthly) {
                b.tilPeriod.hint = "Fee for month"
                b.actPeriod.setOptions(
                    monthKeys.map { Fmt.monthLabel(it) },
                    Fmt.monthLabel(presetPeriod?.takeIf { it.length == 7 } ?: Fmt.monthKey())
                )
            } else if (yearly) {
                b.tilPeriod.hint = "Fee for year"
                b.actPeriod.setOptions(yearKeys, presetPeriod?.takeIf { it.length == 4 } ?: Dates.year().toString())
            }
            shownType = type
        }
        b.tilFestival.hint = if (type == Constants.TYPE_FESTIVAL) "Festival / event name" else "Festival / event (optional)"
        b.tilDonor.hint = when {
            type == Constants.TYPE_HUNDI -> "Donor name (optional)"
            monthly || yearly -> "Member – search by name, mobile or house"
            else -> "Donor – search member or type a name"
        }
    }

    private fun selectedPeriod(): String? {
        val text = b.actPeriod.text.toString()
        return when (typeText()) {
            Constants.TYPE_MONTHLY -> monthKeys.firstOrNull { Fmt.monthLabel(it) == text }
            Constants.TYPE_YEARLY -> text.takeIf { it in yearKeys }
            else -> null
        }
    }

    private fun updateModeUi() {
        val mode = b.actMode.text.toString()
        b.tilTxn.isVisible = mode != Constants.MODE_CASH
        b.tilTxn.hint = when (mode) {
            Constants.MODE_UPI -> "UPI reference / UTR no."
            Constants.MODE_CHEQUE -> "Cheque number"
            else -> "Bank transaction ID"
        }
    }

    private fun save(sendWhatsApp: Boolean) {
        listOf(b.tilDonor, b.tilMobile, b.tilFestival, b.tilAmount, b.tilCollectedBy, b.tilPeriod).forEach { it.error = null }

        val type = typeText()
        val mode = b.actMode.text.toString()
        var name = b.etDonor.value()
        val mobile = WhatsApp.localDigits(b.etMobile.value())
        val amount = b.etAmount.value().toDoubleOrNull()
        val festival = if (b.tilFestival.isVisible) b.etFestival.value().ifBlank { null } else null
        val txn = if (mode != Constants.MODE_CASH) b.etTxn.value().ifBlank { null } else null
        val collectedBy = b.etCollectedBy.value()
        val period = selectedPeriod()
        val member = selected
        val isFee = Constants.isFeeType(type)

        if (type == Constants.TYPE_HUNDI && name.isEmpty()) name = Constants.TYPE_HUNDI

        var ok = true
        if (name.isEmpty()) { b.tilDonor.error = "Enter the donor's name"; ok = false }
        if (isFee && member == null) { b.tilDonor.error = "Pick a registered member from the list to record a fee"; ok = false }
        if (isFee && period == null) { b.tilPeriod.error = "Choose the fee period"; ok = false }
        if (type == Constants.TYPE_FESTIVAL && festival == null) { b.tilFestival.error = "Enter the festival or event name"; ok = false }
        if (amount == null || amount <= 0) { b.tilAmount.error = "Enter an amount greater than zero"; ok = false }
        if (mobile.isNotEmpty() && mobile.length != 10) { b.tilMobile.error = "Enter a 10-digit mobile number"; ok = false }
        if (sendWhatsApp && mobile.isEmpty()) { b.tilMobile.error = "A mobile number is needed to send the WhatsApp receipt"; ok = false }
        if (collectedBy.isEmpty()) { b.tilCollectedBy.error = "Enter who collected this amount"; ok = false }
        if (!ok || amount == null) return

        val now = System.currentTimeMillis()
        val draft = DonationEntity(
            uid = UUID.randomUUID().toString(),
            receiptNumber = "",
            timestamp = now,
            donorUid = member?.uid,
            donorName = name,
            donorMobile = mobile.ifEmpty { null },
            donationType = type,
            festivalName = festival,
            feePeriod = if (isFee) period else null,
            amount = amount,
            paymentMode = mode,
            transactionId = txn,
            collectedBy = collectedBy,
            enteredBy = Session.username,
            updatedAt = now
        )

        lifecycleScope.launch {
            if (member != null && isFee && period != null) {
                val already = db.donationDao().feePaidFor(member.uid, period, type)
                if (already > 0) {
                    MaterialAlertDialogBuilder(this@AddDonationActivity)
                        .setTitle("Fee already recorded")
                        .setMessage("${Fmt.money(already)} has already been received from ${member.fullName} for ${Fmt.periodLabel(period)}. Save this as an additional payment?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save anyway") { _, _ -> persist(draft, sendWhatsApp) }
                        .show()
                    return@launch
                }
            }
            persist(draft, sendWhatsApp)
        }
    }

    private fun persist(draft: DonationEntity, sendWhatsApp: Boolean) {
        b.btnSaveWhatsApp.isEnabled = false
        b.btnSaveOnly.isEnabled = false
        lifecycleScope.launch {
            val receiptNo = Numbering.nextReceipt(db, prefs)
            val donation = draft.copy(receiptNumber = receiptNo)
            db.donationDao().insert(donation)
            toast("Saved · $receiptNo")
            val mobile = donation.donorMobile
            if (sendWhatsApp && mobile != null) {
                WhatsApp.send(this@AddDonationActivity, mobile, WhatsApp.receiptMessage(prefs, donation))
            }
            finish()
        }
    }
}
