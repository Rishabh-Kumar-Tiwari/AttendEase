package com.example.attendancemanagementsystem

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

object AttendanceStorage {
    private val dayFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun fileForDate(context: Context, date: Date): File {
        val name = "attendance_${dayFormat.format(date)}.json"
        return File(context.filesDir, name)
    }

    fun load(context: Context, date: Date): MutableList<AttendanceRecord> {
        val file = fileForDate(context, date)
        if (!file.exists()) return mutableListOf()
        val txt = file.readText()
        val arr = JSONArray(txt)
        val out = mutableListOf<AttendanceRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val roll = o.optString("roll", "")
            val name = o.optString("name", "")
            val ts = o.optLong("timestamp", 0L)
            val status = o.optString("status", "Present")
            out.add(AttendanceRecord(roll, name, ts, status))
        }
        return out
    }

    fun save(context: Context, date: Date, list: List<AttendanceRecord>) {
        val file = fileForDate(context, date)
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("roll", r.roll)
            o.put("name", r.name)
            o.put("timestamp", r.timestamp)
            o.put("status", r.status)
            arr.put(o)
        }
        file.writeText(arr.toString())
    }
}
