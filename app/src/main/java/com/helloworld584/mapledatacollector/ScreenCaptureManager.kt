package com.helloworld584.mapledatacollector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ScreenCaptureManager(
    context: Context,
    private val mediaProjection: MediaProjection
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private val screenWidth:  Int
    private val screenHeight: Int
    private val screenDpi:    Int

    init {
        val metrics  = context.resources.displayMetrics
        screenWidth  = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDpi    = metrics.densityDpi
    }

    fun setup() {
        val ir = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        reader = ir
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "MapleDataCapture",
            screenWidth,
            screenHeight,
            screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface,
            null,
            null
        )
    }

    suspend fun captureScreen(): Bitmap? = suspendCancellableCoroutine { cont ->
        val r = reader ?: run { cont.resume(null); return@suspendCancellableCoroutine }

        r.setOnImageAvailableListener({ ir ->
            ir.setOnImageAvailableListener(null, null)
            val image: Image? = ir.acquireLatestImage()
            val bitmap = image?.let { img ->
                val plane      = img.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * screenWidth
                val raw = Bitmap.createBitmap(
                    screenWidth + rowPadding / plane.pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                raw.copyPixelsFromBuffer(plane.buffer)
                img.close()
                Bitmap.createBitmap(raw, 0, 0, screenWidth, screenHeight)
                    .also { raw.recycle() }
            }
            cont.resume(bitmap)
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * 자동 스크롤하며 연속 캡처. 화면 변화가 없으면 중단.
     */
    suspend fun captureWithScroll(
        maxScrolls: Int = 20,
        scrollDelayMs: Long = 1500L
    ): List<Bitmap> {
        val captures = mutableListOf<Bitmap>()
        var prevHash: Int? = null

        captureScreen()?.let {
            prevHash = sampleHash(it)
            captures.add(it)
        }

        repeat(maxScrolls) {
            MapleAccessibilityService.instance?.scrollDown()
            delay(scrollDelayMs)

            val bitmap = captureScreen() ?: return captures
            val hash   = sampleHash(bitmap)

            if (hash == prevHash) {
                bitmap.recycle()
                return captures
            }
            prevHash = hash
            captures.add(bitmap)
        }

        return captures
    }

    /** 16픽셀 샘플링으로 빠른 콘텐츠 해시 계산 */
    private fun sampleHash(bitmap: Bitmap): Int {
        var hash = 0
        val stepX = bitmap.width  / 4
        val stepY = bitmap.height / 4
        for (x in 1..4) {
            for (y in 1..4) {
                hash = hash * 31 + bitmap.getPixel(x * stepX - 1, y * stepY - 1)
            }
        }
        return hash
    }

    fun release() {
        virtualDisplay?.release()
        reader?.close()
        virtualDisplay = null
        reader         = null
        // Do NOT stop mediaProjection here. OverlayService owns its lifecycle
        // and will stop it in onDestroy(). This allows multiple collections
        // within the same service session without re-requesting permission.
    }
}
