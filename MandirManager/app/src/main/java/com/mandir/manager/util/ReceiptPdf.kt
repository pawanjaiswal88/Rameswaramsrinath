package com.mandir.manager.util

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.mandir.manager.data.DonationEntity
import java.io.File
import java.io.FileOutputStream

/** Draws an A5 receipt with Android's built-in PdfDocument API (no extra library needed). */
object ReceiptPdf {

    fun create(context: Context, prefs: Prefs, d: DonationEntity): File {
        val w = 420f
        val h = 595f
        val saffron = Color.rgb(230, 81, 0)
        val marigold = Color.rgb(255, 243, 224)

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(420, 595, 1).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)

        // Header band
        c.drawRect(0f, 0f, w, 96f, Paint().apply { color = saffron })
        val logo = Logo.bitmap(context)
        if (logo != null) {
            val size = 64f
            val scale = size / maxOf(logo.width, logo.height)
            val lw = logo.width * scale
            val lh = logo.height * scale
            c.drawCircle(52f, 48f, 38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawBitmap(logo, null, RectF(52f - lw / 2, 48f - lh / 2, 52f + lw / 2, 48f + lh / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val titleWidth = if (logo != null) w - 200f else w - 40f
        val title = text(Color.WHITE, 20f, bold = true, center = true).also { fitSize(it, prefs.mandirName, titleWidth) }
        c.drawText(prefs.mandirName, w / 2, 46f, title)
        c.drawText(prefs.greeting, w / 2, 72f, text(marigold, 12f, center = true))

        c.drawText("Donation Receipt", w / 2, 128f, text(saffron, 15f, bold = true, center = true))
        c.drawLine(36f, 142f, w - 36f, 142f, Paint().apply { color = Color.rgb(255, 204, 128); strokeWidth = 1f })

        // Detail rows
        val labelP = text(Color.GRAY, 11f)
        val valueP = text(Color.BLACK, 12f, bold = true)
        var y = 170f
        fun row(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            c.drawText(label, 36f, y, labelP)
            c.drawText(ellipsize(valueP, value, w - 36f - 140f), 140f, y, valueP)
            y += 24f
        }
        row("Receipt no.", d.receiptNumber)
        row("Date", Fmt.dateTime(d.timestamp))
        row("Received from", d.donorName)
        row("Mobile", d.donorMobile)
        row("Purpose", d.purpose())
        row("Payment mode", d.paymentMode)
        row("Reference", d.transactionId)
        row("Collected by", d.collectedBy)

        // Amount box
        y += 6f
        val box = RectF(30f, y, w - 30f, y + 76f)
        c.drawRoundRect(box, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = marigold })
        c.drawRoundRect(box, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = saffron; style = Paint.Style.STROKE; strokeWidth = 1.5f
        })
        c.drawText(Fmt.money(d.amount), w / 2, y + 38f, text(saffron, 26f, bold = true, center = true))
        val words = Fmt.amountInWords(d.amount)
        val wordsP = text(Color.DKGRAY, 10f, center = true).also { fitSize(it, words, w - 80f) }
        c.drawText(words, w / 2, y + 60f, wordsP)

        y += 112f
        c.drawText("Thank you for your generous contribution.", w / 2, y, text(Color.DKGRAY, 12f, center = true))
        c.drawText("Your seva helps sustain daily puja and mandir activities.", w / 2, y + 18f, text(Color.GRAY, 10f, center = true))

        // Signature
        val sy = h - 66f
        c.drawLine(w - 176f, sy, w - 36f, sy, Paint().apply { color = Color.GRAY })
        c.drawText("Authorised signatory", w - 106f, sy + 15f, text(Color.GRAY, 10f, center = true))
        val signP = text(Color.DKGRAY, 10f, bold = true, center = true)
        c.drawText(ellipsize(signP, prefs.signatory, 140f), w - 106f, sy + 29f, signP)
        c.drawText("Computer-generated receipt", 36f, h - 34f, text(Color.LTGRAY, 9f))

        doc.finishPage(page)
        val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
        val file = File(dir, "${d.receiptNumber}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun text(color: Int, size: Float, bold: Boolean = false, center: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        }

    private fun fitSize(p: Paint, s: String, max: Float) {
        while (p.measureText(s) > max && p.textSize > 7f) p.textSize -= 0.5f
    }

    private fun ellipsize(p: Paint, s: String, max: Float): String {
        if (p.measureText(s) <= max) return s
        val n = p.breakText(s, true, max - p.measureText("…"), null)
        return s.take(n) + "…"
    }
}
