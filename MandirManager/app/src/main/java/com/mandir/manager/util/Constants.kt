package com.mandir.manager.util

object Constants {
    const val ROLE_ADMIN = "Admin"
    const val ROLE_COLLECTOR = "Collector"
    val ROLES = listOf(ROLE_COLLECTOR, ROLE_ADMIN)

    const val CAT_COMMITTEE = "Committee Member"
    const val CAT_RESIDENT = "Colony Resident"
    const val CAT_EXTERNAL = "External Devotee"
    val MEMBER_CATEGORIES = listOf(CAT_RESIDENT, CAT_COMMITTEE, CAT_EXTERNAL)
    val DESIGNATIONS = listOf("President", "Vice President", "Secretary", "Treasurer", "Joint Secretary", "Member")

    const val PLAN_NONE = "No fee"
    const val PLAN_MONTHLY = "Monthly"
    const val PLAN_YEARLY = "Yearly"
    val FEE_PLANS = listOf(PLAN_MONTHLY, PLAN_YEARLY, PLAN_NONE)

    const val TYPE_GENERAL = "General Donation"
    const val TYPE_MONTHLY = "Monthly Fee"
    const val TYPE_YEARLY = "Yearly Fee"
    const val TYPE_FESTIVAL = "Festival Seva"
    const val TYPE_HUNDI = "Hundi / Daan Peti"
    val DONATION_TYPES = listOf(TYPE_GENERAL, TYPE_MONTHLY, TYPE_YEARLY, TYPE_FESTIVAL, TYPE_HUNDI)

    fun feeTypeFor(plan: String): String? = when (plan) {
        PLAN_MONTHLY -> TYPE_MONTHLY
        PLAN_YEARLY -> TYPE_YEARLY
        else -> null
    }
    fun isFeeType(type: String) = type == TYPE_MONTHLY || type == TYPE_YEARLY

    const val MODE_CASH = "Cash"
    const val MODE_UPI = "UPI"
    const val MODE_BANK = "Bank Transfer"
    const val MODE_CHEQUE = "Cheque"
    val PAYMENT_MODES = listOf(MODE_CASH, MODE_UPI, MODE_BANK, MODE_CHEQUE)

    val EXPENSE_CATEGORIES = listOf(
        "Puja Samagri", "Prasad / Bhog", "Pandit Ji Dakshina", "Flowers & Garlands",
        "Tent & Decoration", "Sound & Lighting", "Electricity", "Water",
        "Cleaning / Staff", "Repair & Maintenance", "Printing & Banners", "Other"
    )

    const val STATUS_PENDING = "Pending"
    const val STATUS_DELETED = "Deleted"
    const val STATUS_CANCELLED = "Cancelled"

    const val REQUIRED_APPROVALS = 3
    const val MAX_APPROVERS = 5

    const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L
    const val AUTO_SYNC_INTERVAL_MS = 10 * 60 * 1000L
    const val DRIVE_FOLDER = "Mandir Manager Sync"
}
