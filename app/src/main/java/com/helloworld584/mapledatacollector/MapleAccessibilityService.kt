package com.helloworld584.mapledatacollector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class MapleAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MapleAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * 화면을 위로 스와이프 → 아래 내용 표시 (스크롤 다운 효과)
     */
    fun scrollDown() {
        val metrics = resources.displayMetrics
        val cx      = metrics.widthPixels  / 2f
        val startY  = metrics.heightPixels * 0.70f
        val endY    = metrics.heightPixels * 0.30f

        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, 400L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
