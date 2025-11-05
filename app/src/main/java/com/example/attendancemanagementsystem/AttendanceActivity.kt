package com.example.attendancemanagementsystem

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.attendancemanagementsystem.databinding.ActivityAttendanceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AttendanceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAttendanceBinding
    private val adapter = AttendanceAdapter()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var currentDate = Date()
    private var classId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarAttendance)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        classId = intent.getStringExtra("classId") ?: ""

        if (classId.isBlank()) {
            Toast.makeText(this, "No class specified — showing no records", Toast.LENGTH_LONG).show()
        }

        updateDateDisplay()
        loadRecordsFor(currentDate)

        binding.btnPickDate.setOnClickListener { showDatePicker() }

        // Export now directly shares the master CSV
        binding.btnExport.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val outFile: File = ClassAttendanceManager.getMasterCsvFile(this@AttendanceActivity, classId)
                    withContext(Dispatchers.Main) {
                        showToast("Master CSV ready: ${outFile.name}")
                        shareFile(outFile, "text/csv")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showToast("Export failed: ${e.localizedMessage}")
                    }
                }
            }
        }

        binding.btnRefresh.setOnClickListener { loadRecordsFor(currentDate) }

        adapter.onDeleteClick = { _, record ->
            lifecycleScope.launch(Dispatchers.IO) {
                val removed = ClassAttendanceManager.removeRecord(this@AttendanceActivity, currentDate, classId, record)
                withContext(Dispatchers.Main) {
                    if (removed) {
                        showToast("Record removed")
                        loadRecordsFor(currentDate)
                    } else showToast("Remove failed")
                }
            }
        }
    }

    private fun updateDateDisplay() {
        binding.txtDate.text = dayFormat.format(currentDate)
    }

    private fun showDatePicker() {
        val c = Calendar.getInstance().apply { time = currentDate }
        val dp = DatePickerDialog(
            this,
            { _, y, m, d ->
                val cal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m)
                    set(Calendar.DAY_OF_MONTH, d)
                }
                currentDate = cal.time
                updateDateDisplay()
                loadRecordsFor(currentDate)
            },
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH),
            c.get(Calendar.DAY_OF_MONTH)
        )
        dp.show()
    }

    private fun loadRecordsFor(date: Date) {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = ClassAttendanceManager.getRecordsForDate(this@AttendanceActivity, date, classId)
            withContext(Dispatchers.Main) {
                adapter.submitList(list)
                binding.txtCount.text = "Records: ${list.size}"
            }
        }
    }

    private fun shareFile(file: File, mime: String) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            file
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share CSV"))
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(this@AttendanceActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onResume() {
        super.onResume()
        loadRecordsFor(currentDate)
    }
}
