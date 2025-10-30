package com.example.attendancemanagementsystem

import android.content.Context
import java.util.*

/**
 * Simple Undo helper: removes the most recent attendance record for today.
 * It uses AttendanceStorage/AttendanceManager to read/write today's attendance file.
 *
 * NOTE: This is intentionally simple (last-in list). If you want an undo stack
 * across app restarts, we can store a small undo file separately.
 */
object UndoHelper {

    /**
     * Remove the last attendance record for today (if any).
     * Returns true if a record was removed.
     */
    fun removeLastRecord(context: Context): Boolean {
        val today = Date()
        val list = AttendanceStorage.load(context, today)
        if (list.isEmpty()) return false
        // remove the last record (most recent)
        val removed = list.removeAt(list.size - 1)
        AttendanceStorage.save(context, today, list)
        return true
    }

    /**
     * Optionally add an entry back (useful if you store a short-lived undo buffer).
     * Not used by the app by default but provided for completeness.
     */
    fun pushRecordBack(context: Context, record: AttendanceRecord): Boolean {
        val today = Date()
        val list = AttendanceStorage.load(context, today)
        list.add(record)
        AttendanceStorage.save(context, today, list)
        return true
    }
}
