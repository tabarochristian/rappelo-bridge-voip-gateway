package com.gateway.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gateway.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : ListAdapter<LogEntry, LogAdapter.LogViewHolder>(LogDiffCallback()) {

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timestampText: TextView = itemView.findViewById(R.id.logTimestamp)
        private val levelText: TextView = itemView.findViewById(R.id.logLevel)
        private val messageText: TextView = itemView.findViewById(R.id.logMessage)

        fun bind(entry: LogEntry) {
            timestampText.text = dateFormat.format(Date(entry.timestamp))
            levelText.text = entry.level
            messageText.text = entry.message

            val levelColor = when (entry.level) {
                "ERROR" -> R.color.log_error
                "WARN" -> R.color.log_warning
                "INFO" -> R.color.log_info
                "DEBUG" -> R.color.log_debug
                else -> R.color.log_verbose
            }
            levelText.setTextColor(itemView.context.getColor(levelColor))
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem == newItem
        }
    }
}
