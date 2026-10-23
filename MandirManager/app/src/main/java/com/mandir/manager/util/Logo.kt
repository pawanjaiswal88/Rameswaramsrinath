package com.mandir.manager.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

/** Mandir logo: stored as a 512px PNG, shown on the home screen and receipts, shared via sync. */
object Logo {
    private fun file(context: Context) = File(context.filesDir, "logo.png")

    fun bitmap(context: Context): Bitmap? =
        file(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    fun save(context: Context, uri: Uri): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        val src = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val scale = minOf(1f, 512f / maxOf(src.width, src.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true
        ) else src
        FileOutputStream(file(context)).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return true
    }

    fun remove(context: Context) { file(context).delete() }

    fun base64(context: Context): String? =
        file(context).takeIf { it.exists() }?.readBytes()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    fun fromBase64(context: Context, b64: String?) {
        if (b64.isNullOrEmpty()) remove(context)
        else file(context).writeBytes(Base64.decode(b64, Base64.NO_WRAP))
    }
}
