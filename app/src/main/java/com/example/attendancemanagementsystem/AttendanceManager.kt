package com.example.attendancemanagementsystem

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AttendanceManager {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Mark if not already present today.
     * id is expected to be something like "<roll>_<name>".
     * Returns true if newly added.
     */
    fun markIfNotMarked(context: Context, id: String, name: String, roll: String): Boolean {
        val today = Date()
        val list = AttendanceStorage.load(context, today)
        // Avoid duplicate by roll for today
        if (list.any { it.roll == roll }) return false
        val rec = AttendanceRecord(roll, name, System.currentTimeMillis(), "Present")
        val newList = list.toMutableList()
        newList.add(rec)
        AttendanceStorage.save(context, today, newList)
        return true
    }

    fun getTodayRecords(context: Context): List<AttendanceRecord> {
        return AttendanceStorage.load(context, Date())
    }

    fun getRecordsForDate(context: Context, date: Date): List<AttendanceRecord> {
        return AttendanceStorage.load(context, date)
    }

    /**
     * Remove a specific record for the date (match on timestamp + roll)
     */
    fun removeRecord(context: Context, date: Date, record: AttendanceRecord): Boolean {
        val list = AttendanceStorage.load(context, date)
        val idx = list.indexOfFirst { it.timestamp == record.timestamp && it.roll == record.roll }
        if (idx < 0) return false
        list.removeAt(idx)
        AttendanceStorage.save(context, date, list)
        return true
    }

    /**
     * Export records for a date to CSV, returns created File (in cache directory)
     */
    fun exportCsv(context: Context, date: Date): File {
        val list = AttendanceStorage.load(context, date)
        val csv = StringBuilder()
        csv.append("roll,name,timestamp,status\n")
        for (r in list) {
            csv.append("${escape(r.roll)},${escape(r.name)},${r.timestamp},${escape(r.status)}\n")
        }
        val fname = "attendance_${dayFormat.format(date)}.csv"
        val out = File(context.cacheDir, fname)
        out.writeText(csv.toString())
        return out
    }

    private fun escape(s: String): String {
        // simple CSV escape
        return "\"${s.replace("\"", "\"\"")}\""
    }
}
