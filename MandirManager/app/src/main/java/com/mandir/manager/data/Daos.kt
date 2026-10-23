package com.mandir.manager.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(user: UserEntity)

    @Query("SELECT * FROM users WHERE active = 1 ORDER BY fullName COLLATE NOCASE")
    fun observeActive(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users") suspend fun all(): List<UserEntity>
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1") suspend fun byUsername(username: String): UserEntity?
    @Query("SELECT * FROM users WHERE uid = :uid") suspend fun byUid(uid: String): UserEntity?
    @Query("SELECT COUNT(*) FROM users WHERE active = 1") suspend fun activeCount(): Int
    @Query("SELECT COUNT(*) FROM users WHERE active = 1 AND role = 'Admin'") suspend fun adminCount(): Int

    @Query("SELECT * FROM users WHERE active = 1 AND isApprover = 1 ORDER BY fullName COLLATE NOCASE")
    suspend fun approvers(): List<UserEntity>
}

@Dao
interface MemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(member: MemberEntity)

    @Query("SELECT * FROM members WHERE deleted = 0 ORDER BY fullName COLLATE NOCASE")
    fun observeAll(): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE deleted = 0 ORDER BY fullName COLLATE NOCASE")
    suspend fun getAll(): List<MemberEntity>

    @Query("SELECT * FROM members") suspend fun all(): List<MemberEntity>
    @Query("SELECT * FROM members WHERE uid = :uid") suspend fun byUid(uid: String): MemberEntity?

    @Query("SELECT recordId FROM members WHERE recordId LIKE :prefix || '%'")
    suspend fun numbersLike(prefix: String): List<String>

    @Query("SELECT COUNT(*) FROM members WHERE deleted = 0 AND mobileNumber = :mobile AND uid != :excludeUid")
    suspend fun countWithMobile(mobile: String, excludeUid: String): Int

    /** Members on [plan] whose payments of [feeType] for [period] are below their fee. */
    @Query(
        """
        SELECT * FROM (
            SELECT m.*, (
                SELECT COALESCE(SUM(d.amount), 0) FROM donations d
                WHERE d.donorUid = m.uid AND d.donationType = :feeType AND d.feePeriod = :period
            ) AS paid
            FROM members m
            WHERE m.deleted = 0 AND m.feePlan = :plan AND m.feeAmount > 0
        )
        WHERE paid < feeAmount
        ORDER BY houseNo COLLATE NOCASE, fullName COLLATE NOCASE
        """
    )
    suspend fun pendingFees(plan: String, feeType: String, period: String): List<PendingFee>
}

@Dao
interface DonationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(donation: DonationEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(donation: DonationEntity)

    @Query("DELETE FROM donations WHERE uid = :uid") suspend fun deleteByUid(uid: String)

    /** Removes donations that another phone has already deleted (found in the permanent log). */
    @Query("DELETE FROM donations WHERE uid IN (SELECT donationUid FROM deletion_log)")
    suspend fun purgeLogged()

    @Query("SELECT * FROM donations ORDER BY timestamp DESC") fun observeAll(): Flow<List<DonationEntity>>
    @Query("SELECT * FROM donations ORDER BY timestamp DESC") suspend fun getAll(): List<DonationEntity>
    @Query("SELECT * FROM donations WHERE uid = :uid") suspend fun byUid(uid: String): DonationEntity?

    @Query("SELECT receiptNumber FROM donations WHERE receiptNumber LIKE :prefix || '%'")
    suspend fun numbersLike(prefix: String): List<String>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM donations WHERE timestamp BETWEEN :fromMs AND :toMs")
    suspend fun totalBetween(fromMs: Long, toMs: Long): Double

    @Query("SELECT COALESCE(SUM(amount), 0) FROM donations WHERE paymentMode = :mode AND timestamp BETWEEN :fromMs AND :toMs")
    suspend fun totalByModeBetween(mode: String, fromMs: Long, toMs: Long): Double

    @Query("SELECT paymentMode AS label, SUM(amount) AS total FROM donations WHERE timestamp BETWEEN :fromMs AND :toMs GROUP BY paymentMode ORDER BY total DESC")
    suspend fun modeTotals(fromMs: Long, toMs: Long): List<LabelTotal>

    @Query("SELECT donationType AS label, SUM(amount) AS total FROM donations WHERE timestamp BETWEEN :fromMs AND :toMs GROUP BY donationType ORDER BY total DESC")
    suspend fun typeTotals(fromMs: Long, toMs: Long): List<LabelTotal>

    @Query("SELECT festivalName AS label, SUM(amount) AS total FROM donations WHERE festivalName IS NOT NULL AND festivalName != '' GROUP BY festivalName COLLATE NOCASE ORDER BY total DESC")
    suspend fun festivalTotals(): List<LabelTotal>

    @Query("SELECT DISTINCT festivalName FROM donations WHERE festivalName IS NOT NULL AND festivalName != ''")
    suspend fun festivalNames(): List<String>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM donations WHERE donorUid = :memberUid AND feePeriod = :period AND donationType = :feeType")
    suspend fun feePaidFor(memberUid: String, period: String, feeType: String): Double
}

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(expense: ExpenseEntity)

    @Query("SELECT * FROM expenses WHERE deleted = 0 ORDER BY timestamp DESC") fun observeAll(): Flow<List<ExpenseEntity>>
    @Query("SELECT * FROM expenses WHERE deleted = 0 ORDER BY timestamp DESC") suspend fun getAll(): List<ExpenseEntity>
    @Query("SELECT * FROM expenses") suspend fun all(): List<ExpenseEntity>
    @Query("SELECT * FROM expenses WHERE uid = :uid") suspend fun byUid(uid: String): ExpenseEntity?

    @Query("SELECT voucherNumber FROM expenses WHERE voucherNumber LIKE :prefix || '%'")
    suspend fun numbersLike(prefix: String): List<String>

    @Query("SELECT COALESCE(SUM(amountPaid), 0) FROM expenses WHERE deleted = 0 AND timestamp BETWEEN :fromMs AND :toMs")
    suspend fun totalBetween(fromMs: Long, toMs: Long): Double

    @Query("SELECT COALESCE(SUM(amountPaid), 0) FROM expenses WHERE deleted = 0 AND paymentMode = :mode AND timestamp BETWEEN :fromMs AND :toMs")
    suspend fun totalByModeBetween(mode: String, fromMs: Long, toMs: Long): Double

    @Query("SELECT category AS label, SUM(amountPaid) AS total FROM expenses WHERE deleted = 0 AND timestamp BETWEEN :fromMs AND :toMs GROUP BY category ORDER BY total DESC")
    suspend fun categoryTotals(fromMs: Long, toMs: Long): List<LabelTotal>

    @Query("SELECT festivalName AS label, SUM(amountPaid) AS total FROM expenses WHERE deleted = 0 AND festivalName IS NOT NULL AND festivalName != '' GROUP BY festivalName COLLATE NOCASE")
    suspend fun festivalTotals(): List<LabelTotal>

    @Query("SELECT DISTINCT festivalName FROM expenses WHERE festivalName IS NOT NULL AND festivalName != ''")
    suspend fun festivalNames(): List<String>
}

@Dao
interface DeletionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRequest(request: DeletionRequestEntity)

    @Query("SELECT * FROM deletion_requests ORDER BY requestedAt DESC") fun observeRequests(): Flow<List<DeletionRequestEntity>>
    @Query("SELECT * FROM deletion_requests") suspend fun allRequests(): List<DeletionRequestEntity>
    @Query("SELECT * FROM deletion_requests WHERE uid = :uid") suspend fun request(uid: String): DeletionRequestEntity?

    @Query("SELECT * FROM deletion_requests WHERE donationUid = :donationUid AND status = 'Pending' LIMIT 1")
    suspend fun pendingFor(donationUid: String): DeletionRequestEntity?

    @Query("SELECT COUNT(*) FROM deletion_requests WHERE status = 'Pending'") suspend fun pendingCount(): Int

    /** History is insert-only. IGNORE keeps the first copy if the same deletion arrives again via sync. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLog(entry: DeletionLogEntity): Long

    @Query("SELECT * FROM deletion_log ORDER BY deletedAt DESC") fun observeLog(): Flow<List<DeletionLogEntity>>
    @Query("SELECT * FROM deletion_log ORDER BY deletedAt DESC") suspend fun allLog(): List<DeletionLogEntity>
    @Query("SELECT donationUid FROM deletion_log") suspend fun loggedDonationUids(): List<String>

    @Query("SELECT receiptNumber FROM deletion_log WHERE receiptNumber LIKE :prefix || '%'")
    suspend fun logReceipts(prefix: String): List<String>
}
