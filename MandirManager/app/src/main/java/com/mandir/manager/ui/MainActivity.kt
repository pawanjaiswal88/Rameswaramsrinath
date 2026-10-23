package com.mandir.manager.ui

import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.mandir.manager.databinding.ActivityMainBinding
import com.mandir.manager.util.Constants
import com.mandir.manager.util.Dates
import com.mandir.manager.util.Fmt
import com.mandir.manager.util.Logo
import com.mandir.manager.util.Session
import com.mandir.manager.util.SyncManager
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard()) return
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnNewDonation.setOnClickListener { open(AddDonationActivity::class.java) }
        b.btnAddExpense.setOnClickListener { open(AddExpenseActivity::class.java) }
        b.btnMembers.setOnClickListener { open(MembersActivity::class.java) }
        b.btnDonations.setOnClickListener { open(DonationsActivity::class.java) }
        b.btnExpenses.setOnClickListener { open(ExpensesActivity::class.java) }
        b.btnReports.setOnClickListener { open(ReportsActivity::class.java) }
        b.btnRequests.setOnClickListener { open(DeletionRequestsActivity::class.java) }
        b.btnHistory.setOnClickListener { open(DeletionLogActivity::class.java) }
        b.btnSync.setOnClickListener { open(SyncActivity::class.java) }
        b.btnUsers.setOnClickListener { open(UsersActivity::class.java) }
        b.btnSettings.setOnClickListener { open(SettingsActivity::class.java) }
        b.cardPending.setOnClickListener { open(ReportsActivity::class.java) }
        b.btnLogout.setOnClickListener {
            Session.logout()
            goLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Session.user == null || !::b.isInitialized) return
        refresh()
        autoSync()
    }

    private fun open(target: Class<*>) = startActivity(Intent(this, target))

    private fun autoSync() {
        if (!prefs.autoSync || System.currentTimeMillis() - prefs.lastSyncAt < Constants.AUTO_SYNC_INTERVAL_MS) return
        lifecycleScope.launch {
            val token = SyncManager.silentToken(applicationContext) ?: return@launch
            if (runCatching { SyncManager.sync(applicationContext, token) }.isSuccess) refresh()
        }
    }

    private fun refresh() {
        b.tvMandirName.text = prefs.mandirName
        b.tvToday.text = Fmt.date(System.currentTimeMillis())
        val logo = Logo.bitmap(this)
        b.ivLogo.isVisible = logo != null
        logo?.let { b.ivLogo.setImageBitmap(it) }
        b.rowAdmin.isVisible = Session.isAdmin
        b.tvUser.text = "Logged in as ${Session.name} (${Session.user?.role}) · phone ${prefs.deviceCode}"

        lifecycleScope.launch {
            val donations = db.donationDao()
            val expenses = db.expenseDao()
            val (dayStart, dayEnd) = Dates.dayRange()
            val month = Fmt.monthKey()
            val year = Dates.year().toString()
            val (monthStart, monthEnd) = Dates.monthRange(month)

            b.tvTodayTotal.text = Fmt.money(donations.totalBetween(dayStart, dayEnd))
            val modes = donations.modeTotals(dayStart, dayEnd)
            b.tvTodayModes.text = if (modes.isEmpty()) "No entries yet today"
            else modes.joinToString("    ") { "${it.label}: ${Fmt.money(it.total)}" }

            b.tvMonthIncome.text = Fmt.money(donations.totalBetween(monthStart, monthEnd))
            b.tvMonthExpense.text = Fmt.money(expenses.totalBetween(monthStart, monthEnd))

            val balance = donations.totalBetween(0L, Long.MAX_VALUE) - expenses.totalBetween(0L, Long.MAX_VALUE)
            val cash = donations.totalByModeBetween(Constants.MODE_CASH, 0L, Long.MAX_VALUE) -
                expenses.totalByModeBetween(Constants.MODE_CASH, 0L, Long.MAX_VALUE)
            b.tvBalance.text = Fmt.money(balance)
            b.tvCashInHand.text = "Cash in hand: ${Fmt.money(cash)}   ·   In bank / UPI: ${Fmt.money(balance - cash)}"

            val monthly = db.memberDao().pendingFees(Constants.PLAN_MONTHLY, Constants.TYPE_MONTHLY, month)
            val yearly = db.memberDao().pendingFees(Constants.PLAN_YEARLY, Constants.TYPE_YEARLY, year)
            b.tvPending.text = if (monthly.isEmpty() && yearly.isEmpty()) "All fees received 🙏"
            else "${monthly.size} monthly (${Fmt.monthLabel(month)}) · ${yearly.size} yearly ($year) · " +
                Fmt.money(monthly.sumOf { it.due } + yearly.sumOf { it.due }) + " due"

            val requests = db.deletionDao().pendingCount()
            b.btnRequests.text = if (requests > 0) "✋  Delete requests ($requests)" else "✋  Delete requests"
        }
    }
}
