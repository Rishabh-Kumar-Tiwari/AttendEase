package com.example.attendancemanagementsystem

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.attendancemanagementsystem.databinding.ItemAttendanceBinding
import java.text.SimpleDateFormat
import java.util.*

class AttendanceAdapter : ListAdapter<AttendanceRecord, AttendanceAdapter.VH>(DIFF) {
    var onDeleteClick: ((Int, AttendanceRecord) -> Unit)? = null
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAttendanceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        holder.bind(r)
    }

    inner class VH(private val b: ItemAttendanceBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(r: AttendanceRecord) {
            b.tvName.text = r.name
            b.tvRoll.text = r.roll
            b.tvTs.text = sdf.format(Date(r.timestamp))
            b.btnDelete.setOnClickListener { onDeleteClick?.invoke(bindingAdapterPosition, r) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AttendanceRecord>() {
            override fun areItemsTheSame(oldItem: AttendanceRecord, newItem: AttendanceRecord): Boolean {
                return oldItem.timestamp == newItem.timestamp && oldItem.roll == newItem.roll
            }

            override fun areContentsTheSame(oldItem: AttendanceRecord, newItem: AttendanceRecord): Boolean {
                return oldItem == newItem
            }
        }
    }
}
