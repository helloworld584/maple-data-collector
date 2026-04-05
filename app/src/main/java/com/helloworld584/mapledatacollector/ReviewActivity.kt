package com.helloworld584.mapledatacollector

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class ReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORDS_JSON = "extra_records_json"
    }

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // View references for each editable row
    private data class RowView(
        val checkBox:   CheckBox,
        val etItemName: EditText,
        val etPrice:    EditText,
        val etVolume:   EditText,
        val etDate:     EditText
    )

    private val rows = mutableListOf<RowView>()
    private lateinit var btnUpload: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)

        val json = intent.getStringExtra(EXTRA_RECORDS_JSON) ?: run {
            Toast.makeText(this, "전달된 데이터가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val records: List<PriceHistoryRecord> = Json.decodeFromString(json)

        val container = findViewById<LinearLayout>(R.id.container_rows)
        btnUpload     = findViewById(R.id.btn_upload)
        val btnCancel = findViewById<Button>(R.id.btn_cancel)

        records.forEach { record -> addRow(container, record) }
        updateUploadButton()

        btnUpload.setOnClickListener { uploadChecked() }
        btnCancel.setOnClickListener { finish() }
    }

    private fun addRow(container: LinearLayout, record: PriceHistoryRecord) {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_review_row, container, false)

        val rv = RowView(
            checkBox   = row.findViewById(R.id.cb_include),
            etItemName = row.findViewById(R.id.et_item_name),
            etPrice    = row.findViewById(R.id.et_price),
            etVolume   = row.findViewById(R.id.et_volume),
            etDate     = row.findViewById(R.id.et_date)
        )

        rv.checkBox.isChecked = true
        rv.etItemName.setText(record.item_name)
        rv.etPrice.setText(record.price.toLong().toString())
        rv.etVolume.setText(record.volume.toString())
        rv.etDate.setText(record.date)

        rv.checkBox.setOnCheckedChangeListener { _, _ -> updateUploadButton() }

        rows.add(rv)
        container.addView(row)
    }

    private fun updateUploadButton() {
        val count = rows.count { it.checkBox.isChecked }
        btnUpload.text      = "업로드 (${count}건)"
        btnUpload.isEnabled = count > 0
    }

    private fun uploadChecked() {
        val prefs = PreferencesManager(this)

        if (prefs.supabaseUrl.isEmpty() || prefs.supabaseKey.isEmpty()) {
            Toast.makeText(this, "Supabase 설정을 먼저 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val toUpload: List<PriceHistoryRecord> = rows
            .filter { it.checkBox.isChecked }
            .mapNotNull { rv ->
                val name   = rv.etItemName.text.toString().trim()
                val price  = rv.etPrice.text.toString().toDoubleOrNull()
                val volume = rv.etVolume.text.toString().toIntOrNull()
                val date   = rv.etDate.text.toString().trim()

                if (name.isEmpty() || price == null || volume == null || date.isEmpty()) {
                    null
                } else {
                    PriceHistoryRecord(
                        item_id   = toItemId(name),
                        item_name = name,
                        date      = date,
                        price     = price,
                        volume    = volume
                    )
                }
            }

        if (toUpload.isEmpty()) {
            Toast.makeText(this, "유효한 항목이 없습니다. 필드를 확인해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        btnUpload.isEnabled = false
        btnUpload.text      = "업로드 중..."

        activityScope.launch {
            try {
                val supabase    = SupabaseManager(prefs.supabaseUrl, prefs.supabaseKey)
                val existingIds = withContext(Dispatchers.IO) { supabase.getExistingItemIds() }

                val newMeta = toUpload
                    .filter { it.item_id !in existingIds }
                    .distinctBy { it.item_id }
                    .map { ItemMetaRecord(item_id = it.item_id, item_name = it.item_name) }

                if (newMeta.isNotEmpty()) {
                    withContext(Dispatchers.IO) { supabase.upsertItemMeta(newMeta) }
                }

                val result = withContext(Dispatchers.IO) { supabase.upsertPriceHistory(toUpload) }

                result.fold(
                    onSuccess = { count ->
                        Toast.makeText(this@ReviewActivity, "저장 완료 ${count}건", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onFailure = { e ->
                        Toast.makeText(this@ReviewActivity, "저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
                        btnUpload.isEnabled = true
                        updateUploadButton()
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(this, "오류: ${e.message}", Toast.LENGTH_LONG).show()
                btnUpload.isEnabled = true
                updateUploadButton()
            }
        }
    }

    private fun toItemId(name: String): String =
        name.trim().lowercase()
            .replace(Regex("[^a-z0-9가-힣]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
