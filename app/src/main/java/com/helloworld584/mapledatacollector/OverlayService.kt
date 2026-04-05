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
        /** MainActivity가 이 Action을 받으면 MediaProjection 권한 요청 다이얼로그를 띄운다 */
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

    private var mediaProjection:    MediaProjection?    = null
    private var screenCaptureManager: ScreenCaptureManager? = null

    // ── 드래그 상태 (long-press 후 활성화) ───────────────────────────────────
    private var isDragMode  = false
    private var dragInitX   = 0;   private var dragInitY   = 0
    private var dragInitTX  = 0f;  private var dragInitTY  = 0f
    private var lastRawX    = 0f;  private var lastRawY    = 0f

    // ── 최소화 상태 (double-tap 토글) ─────────────────────────────────────────
    private var isMinimized = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // GestureDetector: single-tap / double-tap / long-press 분기
    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(applicationContext, object : GestureDetector.SimpleOnGestureListener() {

            /** 단순 클릭 → 미리보기 팝업 */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val btn = overlayButton ?: return false
                if (!btn.isEnabled) return false
                showCollectionPreview()
                return true
            }

            /** 더블탭 → 축소 / 복원 토글 */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleMinimize()
                return true
            }

            /** 길게 누름 → 드래그 모드 진입 */
            override fun onLongPress(e: MotionEvent) {
                isDragMode  = true
                dragInitX   = overlayParams?.x ?: 0
                dragInitY   = overlayParams?.y ?: 0
                dragInitTX  = lastRawX
                dragInitTY  = lastRawY
            }
        })
    }

    // =========================================================================
    // 서비스 생명주기
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

        view.setOnTouchListener { _, event ->
            // 항상 현재 위치를 기록 (long-press 드래그 시작점에 사용)
            lastRawX = event.rawX
            lastRawY = event.rawY

            // GestureDetector에 모든 이벤트 전달 (single/double/longpress 판별)
            gestureDetector.onTouchEvent(event)

            // long-press 이후 드래그 처리
            if (isDragMode) {
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        params.x = dragInitX + (dragInitTX - event.rawX).toInt()
                        params.y = dragInitY + (event.rawY  - dragInitTY).toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> isDragMode = false
                }
            }
            true
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
            val lp     = btn.layoutParams
            lp.width   = sizePx
            lp.height  = sizePx
            btn.layoutParams = lp
            btn.alpha  = if (isMinimized) 0.4f else 1.0f
            if (isMinimized) btn.setImageDrawable(null)
            else             btn.setImageResource(android.R.drawable.ic_menu_camera)
        }
        if (isMinimized) progressBar?.visibility = View.GONE

        // WRAP_CONTENT 윈도우가 새 크기를 반영하도록 강제 갱신
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
                "아이템: 주문의 흔적\n" +
                "예상 데이터: 가격, 거래량, 날짜"
            )
            .setPositiveButton("수집 시작") { _, _ ->
                val btn  = overlayButton ?: return@setPositiveButton
                val prog = progressBar   ?: return@setPositiveButton
                startCollection(btn, prog)
            }
            .setNegativeButton("취소", null)
            .create()

        // 다른 앱 위에 표시하기 위해 TYPE_APPLICATION_OVERLAY 지정
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    // =========================================================================
    // 수집 실행
    // =========================================================================

    private fun startCollection(button: ImageButton, progress: ProgressBar) {
        // MediaProjection이 없으면 MainActivity를 통해 권한을 재요청하고 리턴
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
