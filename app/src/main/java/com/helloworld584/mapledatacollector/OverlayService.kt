package com.helloworld584.mapledatacollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "maple_collector_channel"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var mediaProjection: MediaProjection? = null
    private var screenCaptureManager: ScreenCaptureManager? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != -1 && resultData != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        }
        showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        val view   = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)
        val button = view.findViewById<ImageButton>(R.id.btn_collect)
        val prog   = view.findViewById<ProgressBar>(R.id.progress_indicator)

        // 드래그 지원
        var initX = 0;   var initY  = 0
        var initTX = 0f; var initTY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTX = event.rawX; initTY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initTX - event.rawX).toInt()
                    val dy = (event.rawY - initTY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) dragging = true
                    params.x = initX + dx
                    params.y = initY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) button.performClick()
                    true
                }
                else -> false
            }
        }

        button.setOnClickListener {
            if (!button.isEnabled) return@setOnClickListener
            startCollection(button, prog)
        }

        overlayView = view
        windowManager.addView(view, params)
    }

    private fun startCollection(button: ImageButton, progress: ProgressBar) {
        val mp = mediaProjection ?: run {
            toast("화면 캡처 권한이 없습니다. 앱을 재시작해주세요.")
            return
        }
        button.isEnabled    = false
        progress.visibility = View.VISIBLE

        serviceScope.launch {
            try {
                val prefs = PreferencesManager(this@OverlayService)

                if (prefs.supabaseUrl.isEmpty() || prefs.supabaseKey.isEmpty() || prefs.visionApiKey.isEmpty()) {
                    toast("앱 설정에서 API 키를 먼저 입력해주세요.")
                    return@launch
                }

                val captureManager = ScreenCaptureManager(this@OverlayService, mp).also {
                    it.setup()
                    screenCaptureManager = it
                }

                toast("수집 시작...")
                val bitmaps = captureManager.captureWithScroll()

                if (bitmaps.isEmpty()) {
                    toast("캡처된 화면이 없습니다.")
                    return@launch
                }

                val ocrManager = OcrManager(prefs.visionApiKey)
                val allRecords = mutableListOf<TradeRecord>()
                for (bmp in bitmaps) {
                    allRecords.addAll(ocrManager.extractTradeRecords(bmp))
                    bmp.recycle()
                }

                val unique = allRecords.distinctBy { Triple(it.itemName, it.price, it.date) }
                if (unique.isEmpty()) {
                    toast("파싱된 거래 내역이 없습니다.")
                    return@launch
                }

                val priceRecords = unique.map { tr ->
                    PriceHistoryRecord(
                        item_id   = toItemId(tr.itemName),
                        item_name = tr.itemName,
                        date      = tr.date,
                        price     = tr.price,
                        volume    = tr.volume
                    )
                }

                val supabase    = SupabaseManager(prefs.supabaseUrl, prefs.supabaseKey)
                val existingIds = withContext(Dispatchers.IO) { supabase.getExistingItemIds() }

                val newMeta = priceRecords
                    .filter { it.item_id !in existingIds }
                    .distinctBy { it.item_id }
                    .map { ItemMetaRecord(item_id = it.item_id, item_name = it.item_name) }

                if (newMeta.isNotEmpty()) {
                    withContext(Dispatchers.IO) { supabase.upsertItemMeta(newMeta) }
                }

                val result = withContext(Dispatchers.IO) { supabase.upsertPriceHistory(priceRecords) }
                result.fold(
                    onSuccess = { count -> toast("저장 완료 ${count}건") },
                    onFailure = { e    -> toast("저장 실패: ${e.message}") }
                )

            } catch (e: Exception) {
                toast("오류 발생: ${e.message}")
            } finally {
                button.isEnabled    = true
                progress.visibility = View.GONE
                screenCaptureManager?.release()
                screenCaptureManager = null
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this@OverlayService, msg, Toast.LENGTH_SHORT).show()

    private fun toItemId(name: String): String =
        name.trim().lowercase()
            .replace(Regex("[^a-z0-9가-힣]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "메이플 데이터 수집", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "데이터 수집 서비스 실행 중" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("메이플 데이터 수집기")
            .setContentText("오버레이 서비스 실행 중")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        overlayView?.let { windowManager.removeView(it); overlayView = null }
        screenCaptureManager?.release()
    }
}
