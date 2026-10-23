package com.mandir.manager.util

import android.content.Context
import androidx.room.withTransaction
import com.mandir.manager.data.AppDatabase
import com.mandir.manager.data.Approval
import com.mandir.manager.data.DeletionLogEntity
import com.mandir.manager.data.DeletionRequestEntity
import com.mandir.manager.data.DonationEntity
import com.mandir.manager.data.UserEntity
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * A donation can only be deleted after [Constants.REQUIRED_APPROVALS] different approvers
 * each confirm with their own password AND a fingerprint scan. Approvals can be given on
 * any phone; they merge through sync. When the count is reached the donation is removed
 * and a permanent entry is written to the deletion history.
 */
object DeletionService {
    private val listSerializer = ListSerializer(Approval.serializer())

    fun approvals(r: DeletionRequestEntity): List<Approval> = approvals(r.approvalsJson)

    fun approvals(json: String): List<Approval> =
        runCatching { Backup.json.decodeFromString(listSerializer, json) }.getOrDefault(emptyList())

    private fun encode(list: List<Approval>) = Backup.json.encodeToString(listSerializer, list)

    suspend fun request(context: Context, d: DonationEntity, reason: String, by: UserEntity): DeletionRequestEntity {
        val dao = AppDatabase.get(context).deletionDao()
        dao.pendingFor(d.uid)?.let { return it }
        val now = System.currentTimeMillis()
        val r = DeletionRequestEntity(
            uid = UUID.randomUUID().toString(),
            donationUid = d.uid,
            receiptNumber = d.receiptNumber,
            donorName = d.donorName,
            amount = d.amount,
            donationJson = Backup.json.encodeToString(DonationEntity.serializer(), d),
            reason = reason,
            requestedBy = by.fullName,
            requestedByUid = by.uid,
            requestedAt = now,
            approvalsJson = "[]",
            status = Constants.STATUS_PENDING,
            updatedAt = now
        )
        dao.upsertRequest(r)
        return r
    }

    /** Adds one approval. Returns the number of distinct approvals now recorded. */
    suspend fun approve(context: Context, requestUid: String, approver: UserEntity, method: String): Int {
        val dao = AppDatabase.get(context).deletionDao()
        val r = dao.request(requestUid) ?: return 0
        val current = approvals(r)
        if (r.status != Constants.STATUS_PENDING || current.any { it.userUid == approver.uid }) return current.size
        val updated = current + Approval(approver.uid, approver.fullName, System.currentTimeMillis(), Prefs(context).deviceCode, method)
        val nr = r.copy(approvalsJson = encode(updated), updatedAt = System.currentTimeMillis())
        dao.upsertRequest(nr)
        if (updated.size >= Constants.REQUIRED_APPROVALS) execute(context, nr)
        return updated.size
    }

    suspend fun cancel(context: Context, r: DeletionRequestEntity) {
        AppDatabase.get(context).deletionDao()
            .upsertRequest(r.copy(status = Constants.STATUS_CANCELLED, updatedAt = System.currentTimeMillis()))
    }

    /** Runs after a sync: executes requests that reached enough approvals on other phones. */
    suspend fun executeReady(context: Context) {
        AppDatabase.get(context).deletionDao().allRequests()
            .filter { it.status == Constants.STATUS_PENDING && approvals(it).distinctBy { a -> a.userUid }.size >= Constants.REQUIRED_APPROVALS }
            .forEach { execute(context, it) }
    }

    private suspend fun execute(context: Context, r: DeletionRequestEntity) {
        val db = AppDatabase.get(context)
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.deletionDao().insertLog(
                DeletionLogEntity(
                    uid = "del-${r.donationUid}",
                    donationUid = r.donationUid,
                    receiptNumber = r.receiptNumber,
                    donorName = r.donorName,
                    amount = r.amount,
                    donationJson = r.donationJson,
                    reason = r.reason,
                    requestedBy = r.requestedBy,
                    requestedAt = r.requestedAt,
                    approvalsJson = r.approvalsJson,
                    deletedAt = now,
                    deletedOnDevice = Prefs(context).deviceCode
                )
            )
            db.donationDao().deleteByUid(r.donationUid)
            db.deletionDao().upsertRequest(r.copy(status = Constants.STATUS_DELETED, updatedAt = now))
        }
    }

    /** Combines two copies of the same request from different phones. */
    fun mergeRequests(a: DeletionRequestEntity, b: DeletionRequestEntity): DeletionRequestEntity {
        val all = (approvals(a) + approvals(b)).distinctBy { it.userUid }.sortedBy { it.at }
        val status = when {
            a.status == Constants.STATUS_DELETED || b.status == Constants.STATUS_DELETED -> Constants.STATUS_DELETED
            a.status == Constants.STATUS_CANCELLED || b.status == Constants.STATUS_CANCELLED -> Constants.STATUS_CANCELLED
            else -> Constants.STATUS_PENDING
        }
        return a.copy(approvalsJson = encode(all), status = status, updatedAt = maxOf(a.updatedAt, b.updatedAt))
    }

    fun describeDonation(json: String): String = runCatching {
        val d = Backup.json.decodeFromString(DonationEntity.serializer(), json)
        listOfNotNull(
            "Receipt: ${d.receiptNumber}",
            "Date: ${Fmt.dateTime(d.timestamp)}",
            "Donor: ${d.donorName}" + (d.donorMobile?.let { " ($it)" } ?: ""),
            "Purpose: ${d.purpose()}",
            "Amount: ${Fmt.money(d.amount)}",
            "Payment: ${d.paymentMode}" + (d.transactionId?.let { " · Ref $it" } ?: ""),
            "Collected by: ${d.collectedBy}",
            "Entered by: ${d.enteredBy}"
        ).joinToString("\n")
    }.getOrDefault("Original entry details unavailable")
}
