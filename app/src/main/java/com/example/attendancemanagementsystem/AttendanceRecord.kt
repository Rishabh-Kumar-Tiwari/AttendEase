package com.example.attendancemanagementsystem

data class AttendanceRecord(
    val roll: String,
    val name: String,
    val timestamp: Long,
    val status: String = "Present",
    val studentId: String = "",
    val classId: String = ""
)
