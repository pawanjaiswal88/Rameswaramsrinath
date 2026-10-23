package com.mandir.manager.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mandir.manager.data.AppDatabase
import com.mandir.manager.databinding.ItemRowBinding
import com.mandir.manager.util.Prefs
import com.mandir.manager.util.Session
import com.mandir.manager.util.toast

open class BaseActivity : AppCompatActivity() {
    protected val db: AppDatabase by lazy { AppDatabase.get(this) }
    protected val prefs: Prefs by lazy { Prefs(this) }

    /**
     * Call right after super.onCreate(). Returns false (and leaves the screen) when nobody is
     * logged in, or when [adminOnly] is set and the user is not an admin.
     */
    protected fun guard(adminOnly: Boolean = false): Boolean {
        if (Session.user == null) { goLogin(); return false }
        if (adminOnly && !Session.isAdmin) {
            toast("Only an admin can open this screen")
            finish()
            return false
        }
        return true
    }

    protected fun goLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (this is LoginActivity) return
        if (Session.user == null || Session.isExpired()) {
            Session.logout()
            goLogin()
        } else {
            Session.touch()
        }
    }

    override fun onPause() {
        super.onPause()
        if (Session.user != null) Session.touch()
    }

    protected fun enableUp() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

/** One list-row adapter reused by every list screen. */
class RowAdapter<T : Any>(
    idOf: (T) -> String,
    private val bind: (T, ItemRowBinding) -> Unit,
    private val onClick: (T) -> Unit
) : ListAdapter<T, RowAdapter.VH>(Diff(idOf)) {

    class VH(val b: ItemRowBinding) : RecyclerView.ViewHolder(b.root)

    class Diff<T : Any>(val idOf: (T) -> String) : DiffUtil.ItemCallback<T>() {
        override fun areItemsTheSame(oldItem: T, newItem: T) = idOf(oldItem) == idOf(newItem)
        @android.annotation.SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: T, newItem: T) = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        bind(item, holder.b)
        holder.b.root.setOnClickListener { onClick(item) }
    }
}
