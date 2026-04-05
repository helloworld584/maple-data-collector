package com.helloworld584.mapledatacollector

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class TradeRecord(
    val itemName: String,
    val price: Double,
    val volume: Int,
    val date: String   // ISO format: YYYY-MM-DD
)

class OcrManager(private val apiKey: String) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun extractTradeRecords(bitmap: Bitmap): List<TradeRecord> = withContext(Dispatchers.IO) {
        val rawText = callVisionApi(bitmap) ?: return@withContext emptyList()
        parseTradeRecords(rawText)
    }

    private fun callVisionApi(bitmap: Bitmap): String? {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val imageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val bodyJson = buildJsonObject {
            putJsonArray("requests") {
                addJsonObject {
                    putJsonObject("image") {
                        put("content", imageBase64)
                    }
                    putJsonArray("features") {
                        addJsonObject {
                            put("type", "TEXT_DETECTION")
                            put("maxResults", 1)
                        }
                    }
                    putJsonObject("imageContext") {
                        putJsonArray("languageHints") {
                            add("ko")
                        }
                    }
                }
            }
        }.toString()

        val request = Request.Builder()
            .url("https://vision.googleapis.com/v1/images:annotate?key=$apiKey")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                json.parseToJsonElement(body).jsonObject
                    .get("responses")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("fullTextAnnotation")?.jsonObject
                    ?.get("text")?.jsonPrimitive?.content
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parseTradeRecords(text: String): List<TradeRecord> {
        val records = mutableListOf<TradeRecord>()
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        val priceRegex     = Regex("""([\d,]+)\s*메소""")
        val volumeRegex    = Regex("""(\d+)\s*개""")
        val dateFullRegex  = Regex("""(\d{4})[./](\d{1,2})[./](\d{1,2})""")
        val dateShortRegex = Regex("""(\d{1,2})/(\d{1,2})""")
        // Item name: 2–30 chars, Korean/English, no price or volume keywords
        val itemNameRegex  = Regex("""^[가-힣a-zA-Z0-9\s\+\-\[\]()]{2,30}$""")

        val currentYear = LocalDate.now().year

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            if (itemNameRegex.matches(line)
                && !priceRegex.containsMatchIn(line)
                && !volumeRegex.containsMatchIn(line)
            ) {
                var price: Double? = null
                var volume: Int?   = null
                var dateStr: String? = null

                val end = minOf(i + 6, lines.size - 1)
                for (j in (i + 1)..end) {
                    val next = lines[j]

                    if (price == null) {
                        priceRegex.find(next)?.let {
                            price = it.groupValues[1].replace(",", "").toDoubleOrNull()
                        }
                    }
                    if (volume == null) {
                        volumeRegex.find(next)?.let {
                            volume = it.groupValues[1].toIntOrNull()
                        }
                    }
                    if (dateStr == null) {
                        dateFullRegex.find(next)?.let { m ->
                            val y  = m.groupValues[1]
                            val mo = m.groupValues[2].padStart(2, '0')
                            val d  = m.groupValues[3].padStart(2, '0')
                            dateStr = "$y-$mo-$d"
                        }
                    }
                    if (dateStr == null) {
                        dateShortRegex.find(next)?.let { m ->
                            val mo = m.groupValues[1].padStart(2, '0')
                            val d  = m.groupValues[2].padStart(2, '0')
                            dateStr = "$currentYear-$mo-$d"
                        }
                    }
                }

                if (price != null && volume != null && dateStr != null) {
                    records.add(TradeRecord(line, price!!, volume!!, dateStr!!))
                }
            }
            i++
        }

        return records
    }
}
