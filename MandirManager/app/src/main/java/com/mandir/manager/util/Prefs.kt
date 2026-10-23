package com.mandir.manager.util

import android.content.Context
import java.util.UUID

/**
 * Two kinds of settings:
 *  - Committee settings (name, greeting, fees, logo...) are shared with every phone through sync.
 *  - Phone settings (device id/code, sync status, fingerprint login) stay on this phone.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mandir_prefs", Context.MODE_PRIVATE)

    private fun str(key: String, def: String): String = sp.getString(key, def) ?: def
    private fun put(key: String, value: String?) = sp.edit().putString(key, value).apply()

    // ---- Committee settings (synced) ----
    var mandirName: String
        get() = str("mandir_name", "Colony Mandir Committee")
        set(v) = put("mandir_name", v)

    var greeting: String
        get() = str("greeting", "Jai Shree Krishna")
        set(v) = put("greeting", v)

    var signatory: String
        get() = str("signatory", "Mandir Committee")
        set(v) = put("signatory", v)

    var upiId: String
        get() = str("upi_id", "")
        set(v) = put("upi_id", v)

    var defaultMonthlyFee: String
        get() = str("default_fee", "101")
        set(v) = put("default_fee", v)

    var defaultYearlyFee: String
        get() = str("default_yearly_fee", "1100")
        set(v) = put("default_yearly_fee", v)

    var settingsUpdatedAt: Long
        get() = sp.getLong("settings_updated", 0L)
        set(v) = sp.edit().putLong("settings_updated", v).apply()

    // ---- This phone only ----
    val deviceId: String
        get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().also { put("device_id", it) }

    /** Short code printed in receipt numbers so two phones never issue the same number. */
    var deviceCode: String
        get() = sp.getString("device_code", null) ?: run {
            val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"
            ("" + letters.random() + letters.random()).also { put("device_code", it) }
        }
        set(v) = put("device_code", v)

    var autoSync: Boolean
        get() = sp.getBoolean("auto_sync", true)
        set(v) = sp.edit().putBoolean("auto_sync", v).apply()

    var lastSyncAt: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) = sp.edit().putLong("last_sync", v).apply()

    var lastSyncMessage: String
        get() = str("last_sync_msg", "Not synced yet")
        set(v) = put("last_sync_msg", v)

    /** User allowed to unlock the app with this phone's fingerprint. */
    var biometricUserUid: String?
        get() = sp.getString("bio_user", null)
        set(v) = put("bio_user", v)

    fun counter(key: String): Int = sp.getInt("ctr_$key", 0)
    fun setCounter(key: String, value: Int) = sp.edit().putInt("ctr_$key", value).apply()
}
