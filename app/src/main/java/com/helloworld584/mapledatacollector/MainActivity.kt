package com.helloworld584.mapledatacollector

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OVERLAY          = 1001
        private const val REQUEST_MEDIA_PROJECTION = 1002
    }

    private lateinit var prefs: PreferencesManager

    private lateinit var etSupabaseUrl: EditText
    private lateinit var etSupabaseKey: EditText
    private lateinit var etVisionKey:   EditText
    private lateinit var btnSave:       Button
    private lateinit var btnOverlay:    Button
    private lateinit var btnStart:      Button
    private lateinit var btnStop:       Button
    private lateinit var tvLog:         TextView
    private lateinit var scrollLog:     ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PreferencesManager(this)

        etSupabaseUrl = findViewById(R.id.et_supabase_url)
        etSupabaseKey = findViewById(R.id.et_supabase_key)
        etVisionKey   = findViewById(R.id.et_vision_key)
        btnSave       = findViewById(R.id.btn_save_settings)
        btnOverlay    = findViewById(R.id.btn_request_overlay)
        btnStart      = findViewById(R.id.btn_start_service)
        btnStop       = findViewById(R.id.btn_stop_service)
        tvLog         = findViewById(R.id.tv_log)
        scrollLog     = findViewById(R.id.scroll_log)

        // 저장된 설정값 복원
        etSupabaseUrl.setText(prefs.supabaseUrl)
        etSupabaseKey.setText(prefs.supabaseKey)
        etVisionKey.setText(prefs.visionApiKey)

        btnSave.setOnClickListener {
            prefs.supabaseUrl  = etSupabaseUrl.text.toString().trim()
            prefs.supabaseKey  = etSupabaseKey.text.toString().trim()
            prefs.visionApiKey = etVisionKey.text.toString().trim()
            log("설정 저장 완료")
            Toast.makeText(this, "설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
        }

        btnOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                log("오버레이 권한이 이미 허용되어 있습니다.")
            } else {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    REQUEST_OVERLAY
                )
            }
        }

        btnStart.setOnClickListener {
            logApiKeyStatus()
            if (prefs.supabaseUrl.isEmpty() || prefs.supabaseKey.isEmpty() || prefs.visionApiKey.isEmpty()) {
                Toast.makeText(this, "모든 API 설정값을 입력 후 저장해주세요.", Toast.LENGTH_SHORT).show()
            } else {
                requestMediaProjection()
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            log("서비스 중지")
        }

        updateOverlayButton()

        // 앱 시작 시 오버레이 권한이 없으면 자동으로 요청 화면으로 이동
        if (!Settings.canDrawOverlays(this)) {
            log("오버레이 권한이 필요합니다. 허용 화면으로 이동합니다...")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQUEST_OVERLAY
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayButton()
    }

    /**
     * OverlayService가 ACTION_REQUEST_MEDIA_PROJECTION을 보내면 호출됨.
     * 앱 재시작 없이 MediaProjection 권한을 재요청한다.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == OverlayService.ACTION_REQUEST_MEDIA_PROJECTION) {
            log("화면 캡처 권한을 다시 요청합니다...")
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> {
                updateOverlayButton()
                log(if (Settings.canDrawOverlays(this)) "오버레이 권한 허용됨" else "오버레이 권한 거부됨")
            }
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForegroundService(
                        Intent(this, OverlayService::class.java).apply {
                            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                        }
                    )
                    log("오버레이 서비스 시작됨")
                } else {
                    log("화면 캡처 권한 거부됨")
                }
            }
        }
    }

    private fun updateOverlayButton() {
        val hasOverlay = Settings.canDrawOverlays(this)
        btnOverlay.text    = if (hasOverlay) "오버레이 권한: ✓ 허용됨" else "오버레이 권한 요청"
        btnStart.isEnabled = hasOverlay
    }

    /** 서비스 시작 전 API 키 설정 상태를 로그에 출력 (키 값은 노출하지 않음) */
    private fun logApiKeyStatus() {
        log("─── 설정 상태 확인 ───")
        log("Supabase URL  : ${if (prefs.supabaseUrl.isNotEmpty()) "✓ 설정됨 (${prefs.supabaseUrl.take(20)}...)" else "✗ 미설정"}")
        log("Supabase Key  : ${if (prefs.supabaseKey.isNotEmpty()) "✓ 설정됨 (${prefs.supabaseKey.length}자)" else "✗ 미설정"}")
        log("Vision API Key: ${if (prefs.visionApiKey.isNotEmpty()) "✓ 설정됨 (${prefs.visionApiKey.length}자)" else "✗ 미설정"}")
        log("오버레이 권한 : ${if (Settings.canDrawOverlays(this)) "✓ 허용됨" else "✗ 거부됨"}")
        log("──────────────────────")
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLog.append("[$time] $message\n")
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
