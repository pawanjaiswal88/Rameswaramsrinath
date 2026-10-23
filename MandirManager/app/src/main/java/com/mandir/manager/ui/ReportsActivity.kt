package com.mandir.manager.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.mandir.manager.R
import com.mandir.manager.data.LabelTotal
import com.mandir.manager.data.PendingFee
import com.mandir.manager.databinding.ActivityReportsBinding
import com.mandir.manager.databinding.ItemPendingBinding
import com.mandir.manager.databinding.ItemReportRowBinding
import com.mandir.manager.util.Constants
import com.mandir.manager.util.CsvExport
import com.mandir.manager.util.Dates
import com.mandir.manager.util.Fmt
import com.mandir.manager.util.Share
import com.mandir.manager.util.WhatsApp
import kotlinx.coroutines.launch

class ReportsActivity : BaseActivity() {

    private lateinit var b: ActivityReportsBinding
    private var month = Fmt.monthKey()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard()) return
        b = ActivityReportsBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        month = savedInstanceState?.getString("month") ?: month
        b.btnPrev.setOnClickListener { month = Dates.shiftMonth(month, -1); refresh() }
        b.btnNext.setOnClickListener { month = Dates.shiftMonth(month, 1); refresh() }
        b.btnExport.setOnClickListener {
            lifecycleScope.launch {
                val files = CsvExport.create(this@ReportsActivity)
                Share.files(this@ReportsActivity, files, "text/csv", "${prefs.mandirName} accounts")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("month", month)
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) refresh()
    }

    private fun refresh() {
        b.tvMonth.text = Fmt.monthLabel(month)
        val year = month.take(4)
        lifecycleScope.launch {
            val dd = db.donationDao()
            val ed = db.expenseDao()
            val (start, end) = Dates.monthRange(month)

            val income = dd.totalBetween(start, end)
            val spent = ed.totalBetween(start, end)
            b.llSummary.removeAllViews()
            row(b.llSummary, "Total collection", Fmt.money(income), R.color.income)
            row(b.llSummary, "Total expenses", Fmt.money(spent), R.color.expense)
            row(
                b.llSummary, if (income >= spent) "Surplus" else "Deficit", Fmt.money(income - spent),
                if (income >= spent) R.color.income else R.color.expense, bold = true
            )
            fill(b.llTypes, dd.typeTotals(start, end))
            fill(b.llModes, dd.modeTotals(start, end))
            fill(b.llExpenseCats, ed.categoryTotals(start, end))

            val monthly = db.memberDao().pendingFees(Constants.PLAN_MONTHLY, Constants.TYPE_MONTHLY, month)
            b.tvPendingTitle.text = "Monthly fees pending for ${Fmt.monthLabel(month)} · ${monthly.size} (${Fmt.money(monthly.sumOf { it.due })})"
            pendingList(b.llPending, monthly, month, Constants.TYPE_MONTHLY)

            val yearly = db.memberDao().pendingFees(Constants.PLAN_YEARLY, Constants.TYPE_YEARLY, year)
            b.tvPendingYearlyTitle.text = "Yearly fees pending for $year · ${yearly.size} (${Fmt.money(yearly.sumOf { it.due })})"
            pendingList(b.llPendingYearly, yearly, year, Constants.TYPE_YEARLY)

            val (dayStart, dayEnd) = Dates.dayRange()
            b.llToday.removeAllViews()
            val todayModes = dd.modeTotals(dayStart, dayEnd)
            if (todayModes.isEmpty()) row(b.llToday, "No collection yet today", "—")
            todayModes.forEach { row(b.llToday, it.label ?: "—", Fmt.money(it.total)) }
            row(b.llToday, "Spent today", Fmt.money(ed.totalBetween(dayStart, dayEnd)), R.color.expense)

            val collected = dd.festivalTotals().associateBy { it.label.orEmpty().lowercase() }
            val spentBy = ed.festivalTotals().associateBy { it.label.orEmpty().lowercase() }
            b.llFestivals.removeAllViews()
            val keys = (collected.keys + spentBy.keys).distinct()
            if (keys.isEmpty()) row(b.llFestivals, "Add a festival name to donations or expenses to see it here", "")
            keys.sortedByDescending { collected[it]?.total ?: 0.0 }.forEach { k ->
                val inc = collected[k]?.total ?: 0.0
                val out = spentBy[k]?.total ?: 0.0
                val name = collected[k]?.label ?: spentBy[k]?.label ?: k
                row(
                    b.llFestivals, "$name\nCollected ${Fmt.money(inc)} · Spent ${Fmt.money(out)}",
                    Fmt.money(inc - out), if (inc >= out) R.color.income else R.color.expense, bold = true
                )
            }
        }
    }

    private fun pendingList(container: LinearLayout, list: List<PendingFee>, period: String, feeType: String) {
        container.removeAllViews()
        if (list.isEmpty()) row(container, "Everyone has paid for ${Fmt.periodLabel(period)} 🙏", "")
        list.forEach { p ->
            val r = ItemPendingBinding.inflate(layoutInflater, container, true)
            r.tvName.text = p.member.fullName
            r.tvDetail.text = listOfNotNull(
                p.member.houseNo?.takeIf { it.isNotBlank() }?.let { "House $it" },
                p.member.designation,
                p.member.mobileNumber,
                if (p.paid > 0) "Paid ${Fmt.money(p.paid)} of ${Fmt.money(p.member.feeAmount)}" else "Due ${Fmt.money(p.due)}"
            ).joinToString(" · ")
            r.btnRemind.setOnClickListener {
                WhatsApp.send(this, p.member.mobileNumber, WhatsApp.reminderMessage(prefs, p, period))
            }
            r.btnCollect.setOnClickListener {
                startActivity(
                    Intent(this, AddDonationActivity::class.java)
                        .putExtra(AddDonationActivity.EXTRA_MEMBER_UID, p.member.uid)
                        .putExtra(AddDonationActivity.EXTRA_TYPE, feeType)
                        .putExtra(AddDonationActivity.EXTRA_PERIOD, period)
                )
            }
        }
    }

    private fun fill(container: LinearLayout, items: List<LabelTotal>) {
        container.removeAllViews()
        if (items.isEmpty()) row(container, "No entries", "—")
        items.forEach { row(container, it.label ?: "—", Fmt.money(it.total)) }
    }

    private fun row(container: LinearLayout, label: String, value: String, colorRes: Int? = null, bold: Boolean = false) {
        val r = ItemReportRowBinding.inflate(layoutInflater, container, true)
        r.tvLabel.text = label
        r.tvValue.text = value
        colorRes?.let { r.tvValue.setTextColor(getColor(it)) }
        if (bold) r.tvValue.setTypeface(r.tvValue.typeface, android.graphics.Typeface.BOLD)
    }
}
