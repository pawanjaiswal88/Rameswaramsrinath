package com.mandir.manager.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mandir.manager.data.DonationEntity
import com.mandir.manager.data.PendingFee

object WhatsApp {

    /** Returns the 10-digit local number, stripping +91 / 0 prefixes and spaces. */
    fun localDigits(input: String): String {
        val d = input.filter { it.isDigit() }
        return when {
            d.length == 12 && d.startsWith("91") -> d.drop(2)
            d.length == 11 && d.startsWith("0") -> d.drop(1)
            else -> d
        }
    }

    private fun international(mobile: String): String {
        val local = localDigits(mobile)
        return if (local.length == 10) "91$local" else local
    }

    /** Opens a WhatsApp chat with [mobile] with [message] pre-filled. The user taps Send. */
    fun send(context: Context, mobile: String, message: String): Boolean {
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${international(mobile)}&text=${Uri.encode(message)}")
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (e: ActivityNotFoundException) {
            context.toast("WhatsApp is not installed on this device")
            false
        }
    }

    fun receiptMessage(prefs: Prefs, d: DonationEntity): String {
        val payment = d.paymentMode + (d.transactionId?.let { " (Ref: $it)" } ?: "")
        return buildString {
            appendLine("🛕 ${prefs.mandirName} 🛕")
            appendLine("🙏 ${prefs.greeting}, ${d.donorName} Ji")
            appendLine()
            appendLine("Thank you for your generous contribution. Your e-receipt details are below.")
            appendLine()
            appendLine("📄 Receipt No: ${d.receiptNumber}")
            appendLine("📅 Date: ${Fmt.dateTime(d.timestamp)}")
            appendLine("🪔 Purpose: ${d.purpose()}")
            appendLine("💰 Amount: ${Fmt.money(d.amount)}")
            appendLine("💳 Payment: $payment")
            appendLine("✍️ Collected by: ${d.collectedBy}")
            appendLine()
            appendLine("Your contribution helps our mandir sustain daily puja and seva.")
            appendLine()
            appendLine("Regards,")
            append(prefs.signatory)
        }
    }

    fun reminderMessage(prefs: Prefs, p: PendingFee, period: String): String = buildString {
        val yearly = p.member.feePlan == Constants.PLAN_YEARLY
        appendLine("🛕 ${prefs.mandirName} 🛕")
        appendLine("🙏 ${prefs.greeting}, ${p.member.fullName} Ji")
        appendLine()
        appendLine("This is a gentle reminder that your ${if (yearly) "yearly" else "monthly"} mandir contribution for ${Fmt.periodLabel(period)} is pending.")
        appendLine()
        appendLine("💰 ${if (yearly) "Yearly" else "Monthly"} fee: ${Fmt.money(p.member.feeAmount)}")
        if (p.paid > 0) appendLine("✅ Received so far: ${Fmt.money(p.paid)}")
        appendLine("⏳ Balance due: ${Fmt.money(p.due)}")
        if (prefs.upiId.isNotBlank()) appendLine("📲 UPI: ${prefs.upiId}")
        appendLine()
        appendLine("Please ignore this message if you have already paid.")
        appendLine()
        appendLine("Regards,")
        append(prefs.signatory)
    }
}
