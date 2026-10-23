package com.mandir.manager.ui

import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mandir.manager.data.MemberEntity
import com.mandir.manager.databinding.ActivityAddMemberBinding
import com.mandir.manager.util.Constants
import com.mandir.manager.util.Fmt
import com.mandir.manager.util.Numbering
import com.mandir.manager.util.WhatsApp
import com.mandir.manager.util.setOptions
import com.mandir.manager.util.toast
import com.mandir.manager.util.value
import kotlinx.coroutines.launch
import java.util.UUID

class AddMemberActivity : BaseActivity() {

    companion object { const val EXTRA_UID = "member_uid" }

    private lateinit var b: ActivityAddMemberBinding
    private var editing: MemberEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard()) return
        b = ActivityAddMemberBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        b.actCategory.setOptions(Constants.MEMBER_CATEGORIES, Constants.CAT_RESIDENT)
        b.actDesignation.setOptions(Constants.DESIGNATIONS, null)
        b.actPlan.setOptions(Constants.FEE_PLANS, Constants.PLAN_MONTHLY)
        b.etFee.setText(prefs.defaultMonthlyFee)
        b.etFamily.setText("1")
        b.actCategory.setOnItemClickListener { _, _, _, _ -> onCategoryChanged() }
        b.actPlan.setOnItemClickListener { _, _, _, _ -> onPlanChanged() }
        b.btnSave.setOnClickListener { save() }
        updateDesignation()

        intent.getStringExtra(EXTRA_UID)?.let { uid ->
            title = "Edit member"
            b.btnSave.text = "Save changes"
            lifecycleScope.launch {
                editing = db.memberDao().byUid(uid)
                if (savedInstanceState == null) editing?.let { fill(it) }
            }
        }
    }

    private fun fill(m: MemberEntity) {
        b.etName.setText(m.fullName)
        b.actCategory.setText(m.category, false)
        b.actDesignation.setText(m.designation ?: "", false)
        b.etMobile.setText(m.mobileNumber)
        b.etHouse.setText(m.houseNo ?: "")
        b.etFamily.setText(m.familyCount.toString())
        b.actPlan.setText(m.feePlan, false)
        b.etFee.setText(Fmt.plain(m.feeAmount))
        updateDesignation()
        updateFeeField()
    }

    private fun updateDesignation() {
        b.tilDesignation.isVisible = b.actCategory.text.toString() == Constants.CAT_COMMITTEE
    }

    private fun updateFeeField() {
        b.tilFee.isEnabled = b.actPlan.text.toString() != Constants.PLAN_NONE
    }

    /** Sensible defaults: residents pay monthly, committee members yearly, outside devotees nothing. */
    private fun onCategoryChanged() {
        updateDesignation()
        val plan = when (b.actCategory.text.toString()) {
            Constants.CAT_COMMITTEE -> Constants.PLAN_YEARLY
            Constants.CAT_EXTERNAL -> Constants.PLAN_NONE
            else -> Constants.PLAN_MONTHLY
        }
        b.actPlan.setText(plan, false)
        onPlanChanged()
    }

    private fun onPlanChanged() {
        b.etFee.setText(
            when (b.actPlan.text.toString()) {
                Constants.PLAN_MONTHLY -> prefs.defaultMonthlyFee
                Constants.PLAN_YEARLY -> prefs.defaultYearlyFee
                else -> "0"
            }
        )
        updateFeeField()
    }

    private fun save() {
        listOf(b.tilName, b.tilMobile, b.tilFee).forEach { it.error = null }
        val name = b.etName.value()
        val category = b.actCategory.text.toString()
        val designation = if (category == Constants.CAT_COMMITTEE) b.actDesignation.text.toString().ifBlank { null } else null
        val mobile = WhatsApp.localDigits(b.etMobile.value())
        val house = b.etHouse.value().ifBlank { null }
        val family = b.etFamily.value().toIntOrNull()?.coerceAtLeast(1) ?: 1
        val plan = b.actPlan.text.toString()
        val fee = if (plan == Constants.PLAN_NONE) 0.0 else b.etFee.value().toDoubleOrNull()

        var ok = true
        if (name.isEmpty()) { b.tilName.error = "Enter the member's name"; ok = false }
        if (mobile.length != 10) { b.tilMobile.error = "Enter a 10-digit mobile number"; ok = false }
        if (fee == null || fee < 0 || (plan != Constants.PLAN_NONE && fee == 0.0)) {
            b.tilFee.error = "Enter the ${plan.lowercase()} fee amount"; ok = false
        }
        if (!ok || fee == null) return

        lifecycleScope.launch {
            val dao = db.memberDao()
            val write: suspend () -> Unit = {
                val now = System.currentTimeMillis()
                val e = editing
                if (e == null) {
                    val record = Numbering.nextMemberId(db, prefs)
                    dao.upsert(
                        MemberEntity(
                            uid = UUID.randomUUID().toString(), recordId = record, fullName = name,
                            category = category, designation = designation, mobileNumber = mobile,
                            houseNo = house, familyCount = family, feePlan = plan, feeAmount = fee,
                            createdAt = now, updatedAt = now
                        )
                    )
                    toast("Member added · $record")
                } else {
                    dao.upsert(
                        e.copy(
                            fullName = name, category = category, designation = designation,
                            mobileNumber = mobile, houseNo = house, familyCount = family,
                            feePlan = plan, feeAmount = fee, updatedAt = now
                        )
                    )
                    toast("Member updated")
                }
                finish()
            }

            if (dao.countWithMobile(mobile, editing?.uid ?: "") > 0) {
                MaterialAlertDialogBuilder(this@AddMemberActivity)
                    .setTitle("Mobile number already registered")
                    .setMessage("Another member uses $mobile. Save anyway (for example, two people in the same family)?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save anyway") { _, _ -> lifecycleScope.launch { write() } }
                    .show()
            } else {
                write()
            }
        }
    }
}
