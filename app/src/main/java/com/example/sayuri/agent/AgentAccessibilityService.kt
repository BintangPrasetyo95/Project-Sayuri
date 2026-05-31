/**
 * AgentAccessibilityService.kt
 *
 * Gives the AI physical control over the Android screen:
 *   - Take screenshots (Android 11+)
 *   - Tap at any (x, y) coordinate
 *   - Type text into focused fields
 *   - Press Back / Home / Recents
 *   - Scroll up or down
 */

package com.example.sayuri.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── Screenshot ─────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreen(): ByteArray? {
        var result: ByteArray? = null
        val latch = CountDownLatch(1)
        val displayId = try {
            display?.displayId ?: 0
        } catch (e: Exception) {
            Log.w("AgentAccessibilityService", "accessing display failed: ${e.message}")
            0
        }

        try {
            takeScreenshot(
                displayId,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val bmp = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer, screenshot.colorSpace
                            )
                            val baos = ByteArrayOutputStream()
                            bmp?.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                            result = baos.toByteArray()
                        } catch (e: Exception) {
                            Log.w("AgentAccessibilityService", "Failed to process screenshot: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            Log.w("AgentAccessibilityService", "takeScreenshot failed (displayId=$displayId): ${e.message}")
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    // ── Tap ────────────────────────────────────────────────────────────────

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var success = false
        val latch = CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                success = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                latch.countDown()
            }
        }, null)

        latch.await(2, TimeUnit.SECONDS)
        Thread.sleep(600)
        return success
    }

    // ── Type text ──────────────────────────────────────────────────────────

    fun typeText(text: String): Boolean {
        val node = findFocus(FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        )
    }

    // ── Scroll ─────────────────────────────────────────────────────────────

    fun scroll(direction: String): Boolean {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        val dist = metrics.heightPixels * 0.4f

        val (startY, endY) = when (direction.lowercase()) {
            "up" -> Pair(cy - dist, cy + dist)
            "down" -> Pair(cy + dist, cy - dist)
            else -> return false
        }

        val path = Path().apply { moveTo(cx, startY); lineTo(cx, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var success = false
        val latch = CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) {
                success = true
                latch.countDown()
            }

            override fun onCancelled(g: GestureDescription) {
                latch.countDown()
            }
        }, null)

        latch.await(2, TimeUnit.SECONDS)
        Thread.sleep(500)
        return success
    }

    // ── System buttons ─────────────────────────────────────────────────────

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    companion object {
        var instance: AgentAccessibilityService? = null

        fun isRunning() = instance != null
    }
}
