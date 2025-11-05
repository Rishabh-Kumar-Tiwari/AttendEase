package com.example.attendancemanagementsystem

import android.content.Context
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

object AttendanceStorage {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val pctFmt = DecimalFormat("0")

    private fun safeClassFileName(classNameOrId: String): String {
        val tmp = classNameOrId.trim()
        if (tmp.isEmpty()) return "global"
        return tmp.replace("\\s+".toRegex(), "_")
            .replace("[^A-Za-z0-9_\\-]".toRegex(), "")
    }

    private fun isSummaryName(name: String?): Boolean {
        if (name == null) return false
        val n = name.trim().lowercase(Locale.ROOT)
        return n == "total present" || n == "total absent"
    }


    fun getMasterCsvFile(context: Context, classId: String): File {
        val room = ClassStorage.getClass(context, classId)
        val base = room?.name ?: classId
        val safe = safeClassFileName(base)
        return File(context.filesDir, "attendance-$safe.csv")
    }


    private fun fileForDate(context: Context, classId: String, date: Date): File {
        val safeClass = if (classId.isBlank()) "global" else classId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val name = "attendance_${safeClass}_${dayFormat.format(date)}.json"
        return File(context.filesDir, name)
    }

    fun load(context: Context, classId: String, date: Date): MutableList<AttendanceRecord> {
        val file = fileForDate(context, classId, date)
        if (!file.exists()) return mutableListOf()

        val txt = file.readText()
        val arr = org.json.JSONArray(txt)
        val out = mutableListOf<AttendanceRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val roll = o.optString("roll", "")
            val name = o.optString("name", "")
            val ts = o.optLong("timestamp", 0L)
            val status = o.optString("status", "Present")
            val sid = o.optString("studentId", "")
            val cid = o.optString("classId", "")
            out.add(AttendanceRecord(roll, name, ts, status, sid, cid))
        }
        return out
    }

    fun save(context: Context, classId: String, date: Date, list: List<AttendanceRecord>, studentIds: List<String>? = null) {
        val file = fileForDate(context, classId, date)
        val arr = org.json.JSONArray()
        for ((index, r) in list.withIndex()) {
            val o = org.json.JSONObject()
            o.put("roll", r.roll)
            o.put("name", r.name)
            o.put("timestamp", r.timestamp)
            o.put("status", r.status)
            if (r.studentId.isNotEmpty()) o.put("studentId", r.studentId)
            if (r.classId.isNotEmpty()) o.put("classId", r.classId)
            if (studentIds != null && index < studentIds.size) {
                o.put("studentId", studentIds[index])
            }
            arr.put(o)
        }
        file.writeText(arr.toString())
    }


    fun ensureMasterCsvHasRoster(context: Context, classId: String) {
        try {
            val csvFile = getMasterCsvFile(context, classId)
            val room = ClassStorage.getClass(context, classId)
            val enrolledIds: List<String> = room?.studentIds ?: emptyList()
            val enrolled = enrolledIds.map { id ->
                val (roll, name) = parseId(id)
                roll to name
            }

            if (!csvFile.exists()) {
                FileWriter(csvFile, false).use { fw ->
                    fw.appendLine("S.No.,Roll_No.,Full_Name,Attendance %")
                    var i = 1
                    for ((roll, name) in enrolled) {
                        fw.appendLine("${i},${escapeCsv(roll)},${escapeCsv(name)},")
                        i++
                    }
                }
                return
            }

            val lines = csvFile.readLines().toMutableList()
            if (lines.isEmpty()) {
                FileWriter(csvFile, false).use { fw ->
                    fw.appendLine("S.No.,Roll_No.,Full_Name,Attendance %")
                    var i = 1
                    for ((roll, name) in enrolled) {
                        fw.appendLine("${i},${escapeCsv(roll)},${escapeCsv(name)},")
                        i++
                    }
                }
                return
            }

            val existing = LinkedHashSet<Pair<String, String>>()
            for (i in 1 until lines.size) {
                val parts = splitCsvLine(lines[i])
                if (parts.size >= 3) {
                    val roll = parts[1]
                    val name = parts[2]
                    if (!isSummaryName(name) && (roll.trim().isNotEmpty() || name.trim().isNotEmpty())) existing.add(roll to name)
                }
            }

            var changed = false
            val outRows = mutableListOf<List<String>>()
            for (i in 1 until lines.size) {
                val parts = splitCsvLine(lines[i])
                val name = parts.getOrNull(2) ?: ""
                if (isSummaryName(name)) continue
                val roll = parts.getOrNull(1) ?: ""
                if (roll.trim().isEmpty() && name.trim().isEmpty()) continue
                outRows.add(parts)
            }

            for ((roll, name) in enrolled) {
                val key = roll to name
                if (!existing.contains(key)) {
                    val row = mutableListOf<String>()
                    row.add("") // S.No.
                    row.add(roll)
                    row.add(name)
                    val header = splitCsvLine(lines[0])
                    for (k in 3 until header.size) row.add("")
                    outRows.add(row)
                    changed = true
                }
            }

            if (changed) {
                val header = splitCsvLine(lines[0])
                FileWriter(csvFile, false).use { fw ->
                    fw.appendLine(header.joinToString(",") { escapeCsv(it) })
                    var serial = 1
                    for (r in outRows) {
                        val row = r.toMutableList()
                        while (row.size < header.size) row.add("")
                        row[0] = serial.toString()
                        val line = row.mapIndexed { idx, v ->
                            if (idx == 0) v else escapeCsv(v)
                        }.joinToString(",")
                        fw.appendLine(line)
                        serial++
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AttendanceStorage", "ensureMasterCsvHasRoster failed: ${e.message}")
        }
    }

    fun updateMasterCsv(context: Context, classId: String, date: Date) {
        try {
            val csvFile = getMasterCsvFile(context, classId)
            val dateStr = dayFormat.format(date)

            // 1) get enrolled students
            val room = ClassStorage.getClass(context, classId)
            val enrolledIds: List<String> = room?.studentIds ?: emptyList()
            data class S(val id: String, val roll: String, val name: String)
            val enrolled = enrolledIds.map { id ->
                val (roll, name) = parseId(id)
                S(id, roll, name)
            }

            // 2) load today's attendance present set from per-date JSON
            val todays = load(context, classId, date)
            val presentSet: Set<Pair<String, String>> = todays.map { it.roll to it.name }.toSet()

            // 3) read existing CSV header+rows if exists
            val headers = mutableListOf("S.No.", "Roll_No.", "Full_Name")
            val rowMap = linkedMapOf<Pair<String, String>, MutableList<String>>()

            if (csvFile.exists()) {
                FileReader(csvFile).use { fr ->
                    val lines = fr.readLines()
                    if (lines.isNotEmpty()) {
                        val hdr = splitCsvLine(lines[0])
                        val mappedHdr = hdr.map {
                            when (it.trim()) {
                                "Roll" -> "Roll_No."
                                "Name" -> "Full_Name"
                                else -> it
                            }
                        }.toMutableList()
                        headers.clear()
                        headers.addAll(mappedHdr)
                        for (i in 1 until lines.size) {
                            val parts = splitCsvLine(lines[i]).toMutableList()
                            if (parts.size < 3) continue
                            val name = parts[2]
                            val roll = parts.getOrNull(1) ?: ""
                            if (isSummaryName(name)) continue
                            if (roll.trim().isEmpty() && name.trim().isEmpty()) continue
                            while (parts.size < headers.size) parts.add("")
                            val key = parts[1] to parts[2]
                            rowMap[key] = parts
                        }
                    }
                }
            } else {
                headers.clear()
                headers.addAll(listOf("S.No.", "Roll_No.", "Full_Name"))
            }

            // Ensure Attendance % column exists at the end
            if (!headers.contains("Attendance %")) {
                headers.add("Attendance %")
            }

            // Determine current date columns
            val dateIndices = mutableListOf<Int>()
            for (i in headers.indices) {
                val h = headers[i]
                if (h.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) dateIndices.add(i)
            }

            // If today's date not present, insert before "Attendance %"
            if (!headers.contains(dateStr)) {
                val insertAt = headers.indexOf("Attendance %").let { if (it >= 0) it else headers.size }
                headers.add(insertAt, dateStr)
            }

            // refresh dateIndices
            dateIndices.clear()
            for (i in headers.indices) {
                val h = headers[i]
                if (h.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) dateIndices.add(i)
            }

            // 4) ensure every enrolled student exists, set today's cell
            for (s in enrolled) {
                val key = s.roll to s.name
                val isPresent = presentSet.contains(key)
                if (rowMap.containsKey(key)) {
                    val row = rowMap[key]!!
                    while (row.size < headers.size) row.add("")
                    val idx = headers.indexOf(dateStr)
                    if (idx >= 0) row[idx] = if (isPresent) "P" else "A"
                } else {
                    val newRow = MutableList(headers.size) { "" }
                    newRow[1] = s.roll
                    newRow[2] = s.name
                    for (di in dateIndices) newRow[di] = "A"
                    val idx = headers.indexOf(dateStr)
                    if (idx >= 0) newRow[idx] = if (isPresent) "P" else "A"
                    rowMap[key] = newRow
                }
            }

            // 5) ensure existing non-enrolled rows have today's column (preserve history)
            val keys = rowMap.keys.toList()
            for (k in keys) {
                val r = rowMap[k]!!
                while (r.size < headers.size) r.add("")
                val idx = headers.indexOf(dateStr)
                if (idx >= 0 && r[idx].isBlank()) r[idx] = "A"
            }

            // 6) collect student rows (exclude summary and blank rows)
            val studentRows = mutableListOf<MutableList<String>>()
            for ((_, v) in rowMap) {
                val roll = v.getOrNull(1) ?: ""
                val name = v.getOrNull(2) ?: ""
                if (isSummaryName(name)) continue
                if (roll.trim().isEmpty() && name.trim().isEmpty()) continue
                while (v.size < headers.size) v.add("")
                studentRows.add(v)
            }

            // 7) unique by roll+name (keep first)
            val unique = linkedMapOf<Pair<String, String>, MutableList<String>>()
            for (r in studentRows) {
                val key = (r.getOrNull(1) ?: "") to (r.getOrNull(2) ?: "")
                if (!unique.containsKey(key)) unique[key] = r
            }
            val uniqueRows = unique.values.toMutableList()

            // 8) sort by roll numeric if possible
            uniqueRows.sortWith(Comparator { a, b ->
                val ra = a.getOrNull(1) ?: ""
                val rb = b.getOrNull(1) ?: ""
                val na = ra.toIntOrNull()
                val nb = rb.toIntOrNull()
                when {
                    na != null && nb != null -> na.compareTo(nb)
                    na != null && nb == null -> -1
                    na == null && nb != null -> 1
                    else -> ra.compareTo(rb, ignoreCase = true)
                }
            })

            // 9) recompute S.No.
            var serial = 1
            for (r in uniqueRows) {
                if (r.size >= 1) r[0] = serial.toString() else {
                    while (r.size < headers.size) r.add("")
                    r[0] = serial.toString()
                }
                serial++
            }

            // 10) compute Attendance % per student
            val totalDateCols = dateIndices.size
            for (r in uniqueRows) {
                if (totalDateCols <= 0) {
                    val pctIdx = headers.indexOf("Attendance %")
                    if (pctIdx >= 0) r[pctIdx] = ""
                    continue
                }
                var pcount = 0
                for (di in dateIndices) {
                    val cell = r.getOrNull(di) ?: ""
                    if (cell.trim().equals("P", ignoreCase = true)) pcount++
                }
                val pct = if (totalDateCols > 0) (pcount * 100.0 / totalDateCols) else 0.0
                val pctStr = pctFmt.format(pct)
                val pctIdx = headers.indexOf("Attendance %")
                if (pctIdx >= 0) {
                    while (r.size <= pctIdx) r.add("")
                    r[pctIdx] = pctStr
                }
            }

            // 11) compute totals
            val totalPresentRow = MutableList(headers.size) { "" }
            val totalAbsentRow = MutableList(headers.size) { "" }
            totalPresentRow[2] = "Total Present"
            totalAbsentRow[2] = "Total Absent"
            for (di in dateIndices) {
                var cntP = 0
                for (r in uniqueRows) {
                    val cell = r.getOrNull(di) ?: ""
                    if (cell.trim().equals("P", ignoreCase = true)) cntP++
                }
                val totalStudents = uniqueRows.size
                val cntA = totalStudents - cntP
                if (totalPresentRow.size <= di) while (totalPresentRow.size <= di) totalPresentRow.add("")
                if (totalAbsentRow.size <= di) while (totalAbsentRow.size <= di) totalAbsentRow.add("")
                totalPresentRow[di] = cntP.toString()
                totalAbsentRow[di] = cntA.toString()
            }
            val pctIndex = headers.indexOf("Attendance %")
            if (pctIndex >= 0) {
                if (totalPresentRow.size <= pctIndex) while (totalPresentRow.size <= pctIndex) totalPresentRow.add("")
                if (totalAbsentRow.size <= pctIndex) while (totalAbsentRow.size <= pctIndex) totalAbsentRow.add("")
                totalPresentRow[pctIndex] = ""
                totalAbsentRow[pctIndex] = ""
            }

            // 12) Write header, student rows, blank line, totals
            FileWriter(csvFile, false).use { fw ->
                fw.appendLine(headers.joinToString(",") { escapeCsv(it) })
                for (r in uniqueRows) {
                    val line = r.mapIndexed { idx, v ->
                        if (idx == 0) v else escapeCsv(v)
                    }.joinToString(",")
                    fw.appendLine(line)
                }
                fw.appendLine("")
                val tp = totalPresentRow.mapIndexed { idx, v -> if (idx == 0) "" else escapeCsv(v) }.joinToString(",")
                val ta = totalAbsentRow.mapIndexed { idx, v -> if (idx == 0) "" else escapeCsv(v) }.joinToString(",")
                fw.appendLine(tp)
                fw.appendLine(ta)
            }
        } catch (e: Exception) {
            android.util.Log.w("AttendanceStorage", "updateMasterCsv failed: ${e.message}")
        }
    }

    fun removeStudentFromMasterCsv(context: Context, classId: String, studentId: String) {
        try {
            val csvFile = getMasterCsvFile(context, classId)
            if (!csvFile.exists()) return

            val (roll, name) = parseId(studentId)
            val lines = csvFile.readLines()
            if (lines.isEmpty()) return

            val header = lines[0]
            val preserved = mutableListOf<String>()
            preserved.add(header)
            for (i in 1 until lines.size) {
                val parts = splitCsvLine(lines[i])
                if (parts.size >= 3) {
                    val key = parts[1] to parts[2]
                    if (key.first == roll && key.second == name) {
                        continue
                    }
                }
                preserved.add(lines[i])
            }

            FileWriter(csvFile, false).use { fw ->
                fw.appendLine(preserved[0])
                for (i in 1 until preserved.size) {
                    val parts = splitCsvLine(preserved[i])
                    if (parts.size >= 3 && isSummaryName(parts[2])) {
                        continue
                    }
                    val rollVal = parts.getOrNull(1) ?: ""
                    val nameVal = parts.getOrNull(2) ?: ""
                    if (rollVal.trim().isEmpty() && nameVal.trim().isEmpty()) continue
                    fw.appendLine(preserved[i])
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AttendanceStorage", "removeStudentFromMasterCsv failed: ${e.message}")
        }
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    cur.append('"'); i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString())
                cur = StringBuilder()
            } else {
                cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out.map { v ->
            var vv = v.trim()
            if (vv.length >= 2 && vv.startsWith("\"") && vv.endsWith("\"")) vv = vv.substring(1, vv.length - 1)
            vv.replace("\"\"", "\"")
        }
    }

    private fun escapeCsv(v: String): String {
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\""
        }
        return v
    }

    private fun parseId(studentId: String): Pair<String, String> {
        val idx = studentId.indexOf('_')
        return if (idx <= 0) Pair("", studentId)
        else Pair(studentId.substring(0, idx), studentId.substring(idx + 1).replace("_", " "))
    }
}
