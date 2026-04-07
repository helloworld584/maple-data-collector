package com.helloworld584.mapledatacollector

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.GestureDetector
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val ACTION_REQUEST_MEDIA_PROJECTION =
            "com.helloworld584.mapledatacollector.REQUEST_MEDIA_PROJECTION"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "maple_collector_channel"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView:   View?        = null
    private var overlayButton: ImageButton? = null
    private var progressBar:   ProgressBar? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var mediaProjection:      MediaProjection?      = null
    private var screenCaptureManager: ScreenCaptureManager? = null

    private var isMinimized = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * GestureDetector handles all three interactions:
     *  - onDown()            : MUST return true — otherwise subsequent events are dropped
     *  - onSingleTapConfirmed: single tap (fires after double-tap timeout to avoid false positives)
     *  - onDoubleTap         : minimize / expand toggle
     *  - onScroll            : drag to move overlay (any movement that exceeds touch slop)
     */
    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(applicationContext, object : GestureDetector.SimpleOnGestureListener() {

            // Critical: return true so GestureDetector continues processing this gesture sequence
            override fun onDown(e: MotionEvent): Boolean = true

            // Single tap — show collection preview dialog
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val btn = overlayButton ?: return false
                if (!btn.isEnabled) return false
                showCollectionPreview()
                return true
            }

            // Double-tap — minimize or expand the button
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleMinimize()
                return true
            }

            // Any drag movement — reposition the overlay
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                overlayParams?.let { p ->
                    // Gravity.END: params.x = distance from RIGHT edge
                    //   distanceX > 0 means finger moved LEFT  → button follows → x increases
                    //   distanceX < 0 means finger moved RIGHT → button follows → x decreases
                    p.x = (p.x + distanceX).toInt().coerceAtLeast(0)
                    // Gravity.TOP: params.y = distance from TOP
                    //   distanceY > 0 means finger moved UP   → y decreases
                    //   distanceY < 0 means finger moved DOWN → y increases
                    p.y = (p.y - distanceY).toInt().coerceAtLeast(0)
                    overlayView?.let { windowManager.updateViewLayout(it, p) }
                }
                return true
            }
        })
    }

    // =========================================================================
    // Service lifecycle
    // =========================================================================

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        overlayView?.let { windowManager.removeView(it); overlayView = null }
        screenCaptureManager?.release()
    }

    // =========================================================================
    // 오버레이 버튼 표시
    // =========================================================================

    private fun showOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_FOCUSABLE: does not steal focus from the underlying app.
            // Do NOT add FLAG_NOT_TOUCHABLE — that would block all touch input.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }
        overlayParams = params

        val view   = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)
        val button = view.findViewById<ImageButton>(R.id.btn_collect)
        val prog   = view.findViewById<ProgressBar>(R.id.progress_indicator)
        overlayButton = button
        progressBar   = prog

        // Attach touch listener directly to the ImageButton — NOT the container.
        // If set on the FrameLayout root, the ImageButton child consumes touches first
        // and the listener on the parent is never called.
        button.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true   // consume every event so the button's default click mechanism doesn't interfere
        }

        overlayView = view
        windowManager.addView(view, params)
    }

    // =========================================================================
    // 더블탭: 축소 / 복원
    // =========================================================================

    private fun toggleMinimize() {
        isMinimized = !isMinimized
        val sizePx = dpToPx(if (isMinimized) 16 else 56)

        overlayButton?.let { btn ->
            val lp    = btn.layoutParams
            lp.width  = sizePx
            lp.height = sizePx
            btn.layoutParams = lp
            btn.alpha = if (isMinimized) 0.4f else 1.0f
            if (isMinimized) btn.setImageDrawable(null)
            else             btn.setImageResource(android.R.drawable.ic_menu_camera)
        }
        if (isMinimized) progressBar?.visibility = View.GONE
        overlayView?.let { windowManager.updateViewLayout(it, overlayParams) }
    }

    // =========================================================================
    // 단순 클릭: 수집 미리보기 팝업
    // =========================================================================

    private fun showCollectionPreview() {
        val dialog = AlertDialog.Builder(
            ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar)
        )
            .setTitle("수집 준비 완료")
            .setMessage(
                "화면에 표시된 거래 내역을 캡처합니다.\n" +
                "수집 데이터: 아이템명, 가격, 거래량, 날짜"
            )
            .setPositiveButton("수집 시작") { _, _ ->
                val btn  = overlayButton ?: return@setPositiveButton
                val prog = progressBar   ?: return@setPositiveButton
                startCollection(btn, prog)
            }
            .setNegativeButton("취소", null)
            .create()

        // Required to display dialog on top of other apps from a Service
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    // =========================================================================
    // 수집 실행
    // =========================================================================

    private fun startCollection(button: ImageButton, progress: ProgressBar) {
        val mp = mediaProjection ?: run {
            startActivity(
                Intent(this@OverlayService, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                    action = ACTION_REQUEST_MEDIA_PROJECTION
                }
            )
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

                val recordsJson = Json.encodeToString(priceRecords)
                startActivity(
                    Intent(this@OverlayService, ReviewActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(ReviewActivity.EXTRA_RECORDS_JSON, recordsJson)
                    }
                )
                toast("OCR 완료 ${priceRecords.size}건 – 확인 후 업로드하세요.")

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

    // =========================================================================
    // 유틸
    // =========================================================================

    private fun toast(msg: String) =
        Toast.makeText(this@OverlayService, msg, Toast.LENGTH_SHORT).show()

    private fun toItemId(name: String): String =
        name.trim().lowercase()
            .replace(Regex("[^a-z0-9가-힣]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

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
}
