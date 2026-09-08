package com.silverbullet.checker.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.silverbullet.checker.R
import com.silverbullet.checker.models.Account
import com.silverbullet.checker.models.AccountStatus

class AccountAdapter : ListAdapter<Account, AccountAdapter.AccountViewHolder>(AccountDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccountViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_account, parent, false)
        return AccountViewHolder(view)
    }

    override fun onBindViewHolder(holder: AccountViewHolder, position: Int) {
        val account = getItem(position)
        holder.bind(account)
    }

    class AccountViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvEmail: TextView = itemView.findViewById(R.id.tvEmail)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvDetails: TextView = itemView.findViewById(R.id.tvDetails)
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)

        fun bind(account: Account) {
            tvEmail.text = account.email
            tvStatus.text = account.status.label
            tvDetails.text = account.details

            tvStatus.setTextColor(account.status.color)
            statusIndicator.setBackgroundColor(account.status.color)
        }
    }

    class AccountDiffCallback : DiffUtil.ItemCallback<Account>() {
        override fun areItemsTheSame(oldItem: Account, newItem: Account): Boolean {
            return oldItem.email == newItem.email && oldItem.password == newItem.password
        }

        override fun areContentsTheSame(oldItem: Account, newItem: Account): Boolean {
            return oldItem == newItem
        }
    }
}
