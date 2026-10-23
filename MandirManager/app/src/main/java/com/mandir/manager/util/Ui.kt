package com.mandir.manager.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.Filter
import android.widget.Toast

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun EditText.value(): String = text?.toString()?.trim().orEmpty()

/** Fills an exposed-dropdown field; the list never gets filtered down to one item. */
fun AutoCompleteTextView.setOptions(items: List<String>, selected: String? = items.firstOrNull()) {
    setAdapter(NoFilterAdapter(context, items))
    if (selected != null) setText(selected, false)
}

class NoFilterAdapter(context: Context, private val items: List<String>) :
    ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {
    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) =
            FilterResults().apply { values = items; count = items.size }
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
    }
}

object Images {
    /** Decodes a down-sampled preview so large camera photos don't exhaust memory. */
    fun thumb(path: String, reqWidth: Int = 800): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
