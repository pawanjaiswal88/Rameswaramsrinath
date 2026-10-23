package com.mandir.manager.util

import com.mandir.manager.data.AppDatabase
import java.util.Locale

/**
 * Sequential numbers that restart every year and include the phone code:
 *   REC-2026-A-0001, REC-2026-A-0002 ... REC-2027-A-0001
 * Numbers of deleted receipts are never reused (they are looked up in the deletion history too).
 */
object Numbering {

    private fun maxSeq(numbers: List<String>, prefix: String): Int =
        numbers.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: 0

    private fun next(prefs: Prefs, prefix: String, used: List<String>, width: Int): String {
        val seq = maxOf(maxSeq(used, prefix), prefs.counter(prefix)) + 1
        prefs.setCounter(prefix, seq)
        return prefix + String.format(Locale.US, "%0${width}d", seq)
    }

    suspend fun nextReceipt(db: AppDatabase, prefs: Prefs): String {
        val prefix = "REC-${Dates.year()}-${prefs.deviceCode}-"
        val used = db.donationDao().numbersLike(prefix) + db.deletionDao().logReceipts(prefix)
        return next(prefs, prefix, used, 4)
    }

    suspend fun nextVoucher(db: AppDatabase, prefs: Prefs): String {
        val prefix = "V-${Dates.year()}-${prefs.deviceCode}-"
        return next(prefs, prefix, db.expenseDao().numbersLike(prefix), 4)
    }

    suspend fun nextMemberId(db: AppDatabase, prefs: Prefs): String {
        val prefix = "M-${prefs.deviceCode}-"
        return next(prefs, prefix, db.memberDao().numbersLike(prefix), 3)
    }
}
