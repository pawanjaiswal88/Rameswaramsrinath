package com.mandir.manager.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Every record uses a random UUID as its key so that records created on different
 * phones never clash when they are merged through Google Drive or a backup file.
 * updatedAt decides which copy wins when the same record was edited on two phones.
 */

@Serializable
@Entity(tableName = "users", indices = [Index(value = ["username"], unique = true)])
data class UserEntity(
    @PrimaryKey val uid: String,
    val username: String,          // stored lowercase
    val fullName: String,
    val passwordHash: String,      // PBKDF2-SHA256, never the plain password
    val salt: String,
    val role: String,              // Admin / Collector
    val isApprover: Boolean,       // may approve donation deletions (max 5)
    val active: Boolean = true,    // false = removed (kept so history still shows the name)
    val updatedAt: Long
)

@Serializable
@Entity(tableName = "members", indices = [Index(value = ["recordId"], unique = true)])
data class MemberEntity(
    @PrimaryKey val uid: String,
    val recordId: String,          // e.g. M-A-001
    val fullName: String,
    val category: String,          // Committee Member / Colony Resident / External Devotee
    val designation: String?,
    val mobileNumber: String,
    val houseNo: String?,
    val familyCount: Int,
    val feePlan: String,           // No fee / Monthly / Yearly
    val feeAmount: Double,         // custom amount per month or per year
    val deleted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
) {
    override fun toString(): String =
        listOfNotNull(fullName, mobileNumber, houseNo?.takeIf { it.isNotBlank() }?.let { "House $it" })
            .joinToString(" · ")
}

@Serializable
@Entity(
    tableName = "donations",
    indices = [Index(value = ["receiptNumber"], unique = true), Index("donorUid"), Index("timestamp")]
)
data class DonationEntity(
    @PrimaryKey val uid: String,
    val receiptNumber: String,     // REC-2026-A-0001 (restarts every year)
    val timestamp: Long,
    val donorUid: String?,
    val donorName: String,
    val donorMobile: String?,
    val donationType: String,
    val festivalName: String?,
    val feePeriod: String?,        // "2026-10" for a monthly fee, "2026" for a yearly fee
    val amount: Double,
    val paymentMode: String,
    val transactionId: String?,
    val collectedBy: String,
    val enteredBy: String,         // username of the logged-in user
    val updatedAt: Long
)

@Serializable
@Entity(tableName = "expenses", indices = [Index(value = ["voucherNumber"], unique = true), Index("timestamp")])
data class ExpenseEntity(
    @PrimaryKey val uid: String,
    val voucherNumber: String,     // V-2026-A-0001
    val timestamp: Long,
    val category: String,
    val description: String,
    val amountPaid: Double,
    val paidTo: String,
    val paymentMode: String,
    val festivalName: String?,
    val billImagePath: String?,    // photo stays on the phone that captured it
    val enteredBy: String,
    val deleted: Boolean = false,
    val updatedAt: Long
)

@Serializable
data class Approval(
    val userUid: String,
    val name: String,
    val at: Long,
    val deviceCode: String,
    val method: String
)

@Serializable
@Entity(tableName = "deletion_requests", indices = [Index("donationUid")])
data class DeletionRequestEntity(
    @PrimaryKey val uid: String,
    val donationUid: String,
    val receiptNumber: String,
    val donorName: String,
    val amount: Double,
    val donationJson: String,      // full copy of the donation at request time
    val reason: String,
    val requestedBy: String,
    val requestedByUid: String,
    val requestedAt: Long,
    val approvalsJson: String,     // JSON list of Approval
    val status: String,            // Pending / Deleted / Cancelled
    val updatedAt: Long
)

/** Permanent history. The database refuses UPDATE and DELETE on this table (see AppDatabase). */
@Serializable
@Entity(tableName = "deletion_log")
data class DeletionLogEntity(
    @PrimaryKey val uid: String,   // "del-<donationUid>" so the same deletion never appears twice
    val donationUid: String,
    val receiptNumber: String,
    val donorName: String,
    val amount: Double,
    val donationJson: String,
    val reason: String,
    val requestedBy: String,
    val requestedAt: Long,
    val approvalsJson: String,
    val deletedAt: Long,
    val deletedOnDevice: String
)

data class LabelTotal(val label: String?, val total: Double)

data class PendingFee(
    @Embedded val member: MemberEntity,
    val paid: Double
) {
    val due: Double get() = (member.feeAmount - paid).coerceAtLeast(0.0)
}
