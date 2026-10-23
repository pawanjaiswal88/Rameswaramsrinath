package com.mandir.manager.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.mandir.manager.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Share {
    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun files(context: Context, files: List<File>, mime: String, title: String) {
        val uris = ArrayList(files.map { uri(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = mime
        intent.putExtra(Intent.EXTRA_SUBJECT, title)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, title))
    }
}

object CsvExport {
    /** donations.csv, expenses.csv and deletion-history.csv (UTF-8 with BOM for Excel). */
    suspend fun create(context: Context): List<File> = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = Fmt.fileStamp()

        fun write(name: String, header: String, rows: List<List<Any?>>): File {
            val f = File(dir, "$name-$stamp.csv")
            f.bufferedWriter().use { w ->
                w.write("\uFEFF")
                w.appendLine(header)
                rows.forEach { r -> w.appendLine(r.joinToString(",") { cell(it) }) }
            }
            return f
        }

        val donations = write(
            "donations",
            "Receipt No,Date,Donor,Mobile,Type,Festival,Fee Period,Amount,Payment Mode,Reference,Collected By,Entered By",
            db.donationDao().getAll().map { d ->
                listOf(d.receiptNumber, Fmt.dateTime(d.timestamp), d.donorName, d.donorMobile, d.donationType,
                    d.festivalName, d.feePeriod, Fmt.plain(d.amount), d.paymentMode, d.transactionId, d.collectedBy, d.enteredBy)
            }
        )
        val expenses = write(
            "expenses",
            "Voucher No,Date,Category,Description,Amount,Paid To,Payment Mode,Festival,Entered By",
            db.expenseDao().getAll().map { e ->
                listOf(e.voucherNumber, Fmt.date(e.timestamp), e.category, e.description, Fmt.plain(e.amountPaid),
                    e.paidTo, e.paymentMode, e.festivalName, e.enteredBy)
            }
        )
        val history = write(
            "deletion-history",
            "Receipt No,Donor,Amount,Reason,Requested By,Requested On,Approved By,Deleted On",
            db.deletionDao().allLog().map { l ->
                listOf(l.receiptNumber, l.donorName, Fmt.plain(l.amount), l.reason, l.requestedBy,
                    Fmt.dateTime(l.requestedAt),
                    DeletionService.approvals(l.approvalsJson).joinToString(" | ") { "${it.name} (${Fmt.dateTime(it.at)})" },
                    Fmt.dateTime(l.deletedAt))
            }
        )
        listOf(donations, expenses, history)
    }

    private fun cell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
