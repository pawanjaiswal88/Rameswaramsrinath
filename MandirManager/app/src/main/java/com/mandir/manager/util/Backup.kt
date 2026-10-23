package com.mandir.manager.util

import android.content.Context
import androidx.room.withTransaction
import com.mandir.manager.data.AppDatabase
import com.mandir.manager.data.DeletionLogEntity
import com.mandir.manager.data.DeletionRequestEntity
import com.mandir.manager.data.DonationEntity
import com.mandir.manager.data.ExpenseEntity
import com.mandir.manager.data.MemberEntity
import com.mandir.manager.data.UserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable
data class SharedSettings(
    val mandirName: String,
    val greeting: String,
    val signatory: String,
    val upiId: String,
    val defaultMonthlyFee: String,
    val defaultYearlyFee: String,
    val logoBase64: String? = null,
    val updatedAt: Long
)

@Serializable
data class Snapshot(
    val format: Int = 2,
    val deviceId: String,
    val deviceCode: String,
    val exportedAt: Long,
    val settings: SharedSettings,
    val users: List<UserEntity> = emptyList(),
    val members: List<MemberEntity> = emptyList(),
    val donations: List<DonationEntity> = emptyList(),
    val expenses: List<ExpenseEntity> = emptyList(),
    val deletionRequests: List<DeletionRequestEntity> = emptyList(),
    val deletionLog: List<DeletionLogEntity> = emptyList()
)

/**
 * Backup file (.mbk) = gzip-compressed JSON snapshot of the whole database.
 * The same format is uploaded to Google Drive by each phone.
 *
 * Importing a backup or syncing always MERGES; it never replaces. So restoring an old backup
 * can never erase newer entries or the permanent deletion history.
 */
object Backup {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun snapshot(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val p = Prefs(context)
        Snapshot(
            deviceId = p.deviceId,
            deviceCode = p.deviceCode,
            exportedAt = System.currentTimeMillis(),
            settings = SharedSettings(
                p.mandirName, p.greeting, p.signatory, p.upiId, p.defaultMonthlyFee, p.defaultYearlyFee,
                Logo.base64(context), p.settingsUpdatedAt
            ),
            users = db.userDao().all(),
            members = db.memberDao().all(),
            donations = db.donationDao().getAll(),
            expenses = db.expenseDao().all(),
            deletionRequests = db.deletionDao().allRequests(),
            deletionLog = db.deletionDao().allLog()
        )
    }

    fun encode(s: Snapshot): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.encodeToString(Snapshot.serializer(), s).toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): Snapshot {
        val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)
        return json.decodeFromString(Snapshot.serializer(), text)
    }

    /** Merges [s] into this phone's database. Returns how many records were added or updated. */
    suspend fun merge(context: Context, s: Snapshot): Int = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        var changed = 0
        db.withTransaction {
            val del = db.deletionDao()
            s.deletionLog.forEach { if (del.insertLog(it) != -1L) changed++ }

            val users = db.userDao()
            s.users.forEach { u ->
                val local = users.byUid(u.uid)
                if (local == null || u.updatedAt > local.updatedAt) { users.upsert(u); changed++ }
            }

            val members = db.memberDao()
            s.members.forEach { m ->
                val local = members.byUid(m.uid)
                if (local == null || m.updatedAt > local.updatedAt) { members.upsert(m); changed++ }
            }

            val expenses = db.expenseDao()
            s.expenses.forEach { e ->
                val local = expenses.byUid(e.uid)
                if (local == null || e.updatedAt > local.updatedAt) {
                    // Bill photos live on the phone that took them; keep our own path if we have one.
                    val path = local?.billImagePath ?: e.billImagePath?.takeIf { File(it).exists() }
                    expenses.upsert(e.copy(billImagePath = path)); changed++
                }
            }

            val donations = db.donationDao()
            val logged = del.loggedDonationUids().toHashSet()
            s.donations.forEach { d ->
                if (d.uid in logged) return@forEach
                val local = donations.byUid(d.uid)
                if (local == null || d.updatedAt > local.updatedAt) { donations.upsert(d); changed++ }
            }
            donations.purgeLogged()

            s.deletionRequests.forEach { r ->
                val local = del.request(r.uid)
                val merged = if (local == null) r else DeletionService.mergeRequests(local, r)
                if (merged != local) { del.upsertRequest(merged); changed++ }
            }
        }

        val p = Prefs(context)
        if (s.settings.updatedAt > p.settingsUpdatedAt) {
            p.mandirName = s.settings.mandirName
            p.greeting = s.settings.greeting
            p.signatory = s.settings.signatory
            p.upiId = s.settings.upiId
            p.defaultMonthlyFee = s.settings.defaultMonthlyFee
            p.defaultYearlyFee = s.settings.defaultYearlyFee
            Logo.fromBase64(context, s.settings.logoBase64)
            p.settingsUpdatedAt = s.settings.updatedAt
            changed++
        }

        DeletionService.executeReady(context)
        changed
    }
}
