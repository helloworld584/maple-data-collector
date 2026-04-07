package com.helloworld584.mapledatacollector

import android.app.Activity
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
import android.widget.TextView
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

        /** MainActivity 로그 창으로 메시지를 전달하는 브로드캐스트 액션 */
        const val ACTION_LOG        = "com.helloworld584.mapledatacollector.LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "maple_collector_channel"

        /**
         * MainActivity가 백그라운드일 때 브로드캐스트를 못 받으므로,
         * 수집 중 발생한 로그를 여기에 버퍼링해 두었다가
         * MainActivity.onResume() 에서 일괄 소비한다.
         */
        val pendingLogs = ArrayDeque<String>()
    }

    private lateinit var windowManager: WindowManager
    private var overlayView:   View?        = null
    private var overlayButton: ImageButton? = null
    private var progressBar:   ProgressBar? = null
    private var statusText:    TextView?    = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var mediaProjection:      MediaProjection?      = null
    private var screenCaptureManager: ScreenCaptureManager? = null

    private var isMinimized = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val gestureDetector: GestureDetector by lazy {
        GestureDetector(applicationContext, object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val btn = overlayButton ?: return false
                if (!btn.isEnabled) return false
                showCollectionPreview()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleMinimize()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                overlayParams?.let { p ->
                    p.x = (p.x + distanceX).toInt().coerceAtLeast(0)
                    p.y = (p.y - distanceY).toInt().coerceAtLeast(0)
                    overlayView?.let { windowManager.updateViewLayout(it, p) }
                }
                return true
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
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
            log("화면 캡처 권한 획득 완료")
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
        mediaProjection?.stop()
        mediaProjection = null
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
        val status = view.findViewById<TextView>(R.id.tv_status)
        overlayButton = button
        progressBar   = prog
        statusText    = status

        button.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        overlayView = view
        windowManager.addView(view, params)
        log("오버레이 버튼 표시됨 (탭: 수집 시작, 더블탭: 최소화, 드래그: 이동)")
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

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    // =========================================================================
    // 수집 실행
    // =========================================================================

    private fun startCollection(button: ImageButton, progress: ProgressBar) {
        val mp = mediaProjection ?: run {
            log("화면 캡처 권한 없음 → 권한 재요청")
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
        setStatus("준비 중...")

        serviceScope.launch {
            var succeeded = false
            try {
                log("=== 수집 시작 ===")

                // ── 사전 점검 ────────────────────────────────────────────────
                val prefs = PreferencesManager(this@OverlayService)
                log("Supabase URL  : ${if (prefs.supabaseUrl.isNotEmpty()) "✓ (${prefs.supabaseUrl.take(30)}...)" else "✗ 미설정"}")
                log("Supabase Key  : ${if (prefs.supabaseKey.isNotEmpty()) "✓ (${prefs.supabaseKey.length}자)" else "✗ 미설정"}")
                log("Vision API Key: ${if (prefs.visionApiKey.isNotEmpty()) "✓ (${prefs.visionApiKey.length}자)" else "✗ 미설정"}")
                val a11y = MapleAccessibilityService.instance
                log("접근성 서비스 : ${if (a11y != null) "✓ 활성화 (자동 스크롤 가능)" else "✗ 비활성화 → 현재 화면 1장만 캡처"}")
                if (a11y == null) {
                    setStatus("⚠ 접근성 서비스 꺼짐")
                    toast("접근성 서비스가 비활성화 상태입니다.\n설정 > 접근성에서 활성화하면 자동 스크롤이 가능합니다.", long = true)
                }

                if (prefs.supabaseUrl.isEmpty() || prefs.supabaseKey.isEmpty() || prefs.visionApiKey.isEmpty()) {
                    log("오류: API 키 미설정 → 수집 중단")
                    setStatus("✗ API 키 미설정")
                    toast("앱 설정에서 API 키를 먼저 입력해주세요.", long = true)
                    return@launch
                }

                // ── 화면 캡처 ────────────────────────────────────────────────
                setStatus("캡처 준비...")
                log("VirtualDisplay 초기화 중...")
                val captureManager = ScreenCaptureManager(this@OverlayService, mp).also {
                    it.setup()
                    screenCaptureManager = it
                }
                log("VirtualDisplay 준비 완료")

                setStatus("화면 캡처 중...")
                log("화면 캡처 시작 (최대 20회 스크롤)")
                val bitmaps = captureManager.captureWithScroll(
                    onProgress = { count, scroll ->
                        setStatus("캡처 ${count}장 (스크롤 ${scroll})")
                        log("캡처 ${count}장 완료 (스크롤 ${scroll}회)")
                    }
                )
                log("캡처 종료: 총 ${bitmaps.size}장")

                if (bitmaps.isEmpty()) {
                    log("오류: 캡처된 화면 없음")
                    setStatus("✗ 캡처 실패")
                    toast("캡처된 화면이 없습니다.\n접근성 서비스 활성화 여부를 확인하세요.", long = true)
                    return@launch
                }

                // ── OCR ──────────────────────────────────────────────────────
                val ocrManager = OcrManager(prefs.visionApiKey)
                val allRecords = mutableListOf<TradeRecord>()
                bitmaps.forEachIndexed { i, bmp ->
                    val step = "${i + 1}/${bitmaps.size}"
                    setStatus("OCR 분석 중... ($step)")
                    log("OCR 요청 중 ($step)...")
                    val records = ocrManager.extractTradeRecords(bmp)
                    log("OCR 완료 ($step): ${records.size}건 파싱")
                    allRecords.addAll(records)
                    bmp.recycle()
                }

                val unique = allRecords.distinctBy { Triple(it.itemName, it.price, it.date) }
                log("중복 제거 후: ${unique.size}건 (전체 ${allRecords.size}건)")

                if (unique.isEmpty()) {
                    log("OCR 결과 없음 — 거래 목록 화면인지, Vision API 키가 올바른지 확인")
                    setStatus("✗ OCR 결과 없음")
                    toast("거래 내역을 찾지 못했습니다.\n수집 앱 로그를 확인하세요.", long = true)
                    return@launch
                }

                // ── ReviewActivity 전달 ────────────────────────────────────
                log("ReviewActivity 시작 (${unique.size}건)")
                setStatus("완료 ${unique.size}건 → 앱 확인")

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
                succeeded = true

            } catch (e: Exception) {
                log("예외: ${e.javaClass.simpleName}: ${e.message}")
                setStatus("✗ 오류: ${e.javaClass.simpleName}")
                toast("오류: ${e.message}", long = true)
            } finally {
                button.isEnabled    = true
                progress.visibility = View.GONE
                // 성공 시에만 상태 텍스트 지움.
                // 실패/오류 시에는 원인 메시지를 유지해 사용자가 읽을 수 있게 함.
                if (succeeded) setStatus(null)
                screenCaptureManager?.release()
                screenCaptureManager = null
                log("=== 수집 종료 (${if (succeeded) "성공" else "실패"}) ===")
            }
        }
    }

    // =========================================================================
    // 유틸
    // =========================================================================

    /**
     * 오버레이 상태 텍스트 + MainActivity 로그 창에 동시 전달.
     * null 전달 시 오버레이 텍스트만 숨김.
     */
    private fun log(msg: String) {
        pendingLogs.addLast(msg)
        if (pendingLogs.size > 200) pendingLogs.removeFirst()
        sendBroadcast(Intent(ACTION_LOG).putExtra(EXTRA_LOG_MESSAGE, msg))
    }

    private fun setStatus(msg: String?) {
        statusText?.let {
            if (msg == null) {
                it.visibility = View.GONE
            } else {
                it.text       = msg
                it.visibility = View.VISIBLE
            }
        }
        // WindowManager must be notified to recalculate WRAP_CONTENT size
        // whenever child view visibility or text changes.
        overlayView?.let { v -> overlayParams?.let { p -> windowManager.updateViewLayout(v, p) } }
    }

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this@OverlayService, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

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
