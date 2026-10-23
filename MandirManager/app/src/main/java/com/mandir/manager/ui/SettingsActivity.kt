package com.mandir.manager.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.mandir.manager.R
import com.mandir.manager.databinding.ActivitySettingsBinding
import com.mandir.manager.util.Logo
import com.mandir.manager.util.toast
import com.mandir.manager.util.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : BaseActivity() {

    private lateinit var b: ActivitySettingsBinding

    private val pickLogo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Logo.save(this@SettingsActivity, uri) }.getOrDefault(false) }
            if (ok) {
                prefs.settingsUpdatedAt = System.currentTimeMillis()
                showLogo()
                toast("Logo updated")
            } else toast("Could not read that image")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!guard(adminOnly = true)) return
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        enableUp()

        if (savedInstanceState == null) {
            b.etMandirName.setText(prefs.mandirName)
            b.etGreeting.setText(prefs.greeting)
            b.etSignatory.setText(prefs.signatory)
            b.etUpiId.setText(prefs.upiId)
            b.etDefaultFee.setText(prefs.defaultMonthlyFee)
            b.etDefaultYearly.setText(prefs.defaultYearlyFee)
            b.etDeviceCode.setText(prefs.deviceCode)
        }
        showLogo()

        b.btnLogo.setOnClickListener { pickLogo.launch("image/*") }
        b.btnRemoveLogo.setOnClickListener {
            Logo.remove(this)
            prefs.settingsUpdatedAt = System.currentTimeMillis()
            showLogo()
        }
        b.btnSave.setOnClickListener { save() }
    }

    private fun showLogo() {
        Logo.bitmap(this)?.let { b.ivLogo.setImageBitmap(it) } ?: b.ivLogo.setImageResource(R.mipmap.ic_launcher)
    }

    private fun save() {
        b.tilDeviceCode.error = null
        val code = b.etDeviceCode.value().uppercase()
        if (!Regex("^[A-Z0-9]{1,3}$").matches(code)) {
            b.tilDeviceCode.error = "Use 1–3 letters or numbers"
            return
        }
        b.etMandirName.value().takeIf { it.isNotEmpty() }?.let { prefs.mandirName = it }
        b.etGreeting.value().takeIf { it.isNotEmpty() }?.let { prefs.greeting = it }
        b.etSignatory.value().takeIf { it.isNotEmpty() }?.let { prefs.signatory = it }
        prefs.upiId = b.etUpiId.value()
        prefs.defaultMonthlyFee = b.etDefaultFee.value().ifEmpty { "0" }
        prefs.defaultYearlyFee = b.etDefaultYearly.value().ifEmpty { "0" }
        prefs.deviceCode = code
        prefs.settingsUpdatedAt = System.currentTimeMillis()
        toast("Settings saved")
        finish()
    }
}
