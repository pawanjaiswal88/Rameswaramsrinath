package com.mandir.manager.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.mandir.manager.data.ExpenseEntity
import com.mandir.manager.databinding.ActivityAddExpenseBinding
import com.mandir.manager.util.Constants
import com.mandir.manager.util.Numbering
import com.mandir.manager.util.Session
import com.mandir.manager.util.Fmt
import com.mandir.manager.util.Images
import com.mandir.manager.util.Share
import com.mandir.manager.util.setOptions
import com.mandir.manager.util.toast
import com.mandir.manager.util.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

class AddExpenseActivity : BaseActivity() {

    private lateinit var b: ActivityAddExpenseBinding
    private var time = System.currentTimeMillis()
    private var billPath: String? = null
    private var cameraFile: File? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) importBill(uri)
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val f = cameraFile
        if (saved && f != null && f.length() > 0) setBill(f) else f?.delete()
        cameraFile = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard()) return
        b = ActivityAddExpenseBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        savedInstanceState?.let { s ->
            time = s.getLong("time", time)
            s.getString("camera")?.let { cameraFile = File(it) }
            s.getString("bill")?.let { setBill(File(it)) }
        }

        b.actCategory.setOptions(Constants.EXPENSE_CATEGORIES, Constants.EXPENSE_CATEGORIES.first())
        b.actMode.setOptions(Constants.PAYMENT_MODES, Constants.MODE_CASH)
        b.etDate.setText(Fmt.date(time))
        b.etDate.setOnClickListener { pickDate() }
        b.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        b.btnCamera.setOnClickListener { openCamera() }
        b.btnRemoveBill.setOnClickListener { clearBill() }
        b.btnSave.setOnClickListener { save() }

        lifecycleScope.launch {
            val festivals = (db.donationDao().festivalNames() + db.expenseDao().festivalNames())
                .distinctBy { it.lowercase() }.sorted()
            b.etFestival.setAdapter(ArrayAdapter(this@AddExpenseActivity, android.R.layout.simple_dropdown_item_1line, festivals))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("time", time)
        outState.putString("camera", cameraFile?.absolutePath)
        outState.putString("bill", billPath)
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Expense date")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        picker.addOnPositiveButtonClickListener { utc ->
            val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
            time = Calendar.getInstance().apply {
                set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
            b.etDate.setText(Fmt.date(time))
        }
        picker.show(supportFragmentManager, "date")
    }

    private fun newBillFile(): File =
        File(File(filesDir, "bills").apply { mkdirs() }, "bill_${System.currentTimeMillis()}.jpg")

    private fun openCamera() {
        val f = newBillFile()
        cameraFile = f
        try {
            takePhoto.launch(Share.uri(this, f))
        } catch (e: ActivityNotFoundException) {
            cameraFile = null
            toast("No camera app found. Use From gallery instead.")
        }
    }

    private fun importBill(uri: Uri) {
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) {
                val out = newBillFile()
                contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                out
            }
            if (f.length() > 0) setBill(f) else { f.delete(); toast("Could not read that image") }
        }
    }

    private fun setBill(f: File) {
        billPath?.takeIf { it != f.absolutePath }?.let { File(it).delete() }
        billPath = f.absolutePath
        b.ivBill.setImageBitmap(Images.thumb(f.absolutePath))
        b.ivBill.isVisible = true
        b.btnRemoveBill.isVisible = true
    }

    private fun clearBill() {
        billPath?.let { File(it).delete() }
        billPath = null
        b.ivBill.setImageDrawable(null)
        b.ivBill.isVisible = false
        b.btnRemoveBill.isVisible = false
    }

    private fun save() {
        listOf(b.tilDescription, b.tilAmount, b.tilPaidTo).forEach { it.error = null }
        val category = b.actCategory.text.toString()
        val description = b.etDescription.value().ifEmpty { category }
        val amount = b.etAmount.value().toDoubleOrNull()
        val paidTo = b.etPaidTo.value()

        var ok = true
        if (amount == null || amount <= 0) { b.tilAmount.error = "Enter an amount greater than zero"; ok = false }
        if (paidTo.isEmpty()) { b.tilPaidTo.error = "Enter who was paid"; ok = false }
        if (!ok || amount == null) return

        b.btnSave.isEnabled = false
        lifecycleScope.launch {
            val voucher = Numbering.nextVoucher(db, prefs)
            db.expenseDao().upsert(
                ExpenseEntity(
                    uid = UUID.randomUUID().toString(),
                    voucherNumber = voucher,
                    timestamp = time,
                    category = category,
                    description = description,
                    amountPaid = amount,
                    paidTo = paidTo,
                    paymentMode = b.actMode.text.toString(),
                    festivalName = b.etFestival.value().ifBlank { null },
                    billImagePath = billPath,
                    enteredBy = Session.username,
                    updatedAt = System.currentTimeMillis()
                )
            )
            billPath = null // now owned by the saved record
            toast("Expense saved · $voucher")
            finish()
        }
    }
}
