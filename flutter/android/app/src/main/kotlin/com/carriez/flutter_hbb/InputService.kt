package com.carriez.flutter_hbb

/**
 * Handle remote input and dispatch android gesture
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.util.LinkedList
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs
import kotlin.math.max

const val LIFT_DOWN = 9
const val LIFT_MOVE = 8
const val LIFT_UP = 10
const val RIGHT_DOWN = 17
const val RIGHT_UP = 18
const val WHEEL_BUTTON_DOWN = 33
const val WHEEL_BUTTON_UP = 34
const val WHEEL_DOWN = 523331
const val WHEEL_UP = 963

const val WHEEL_STEP = 120
const val WHEEL_DURATION = 50L
const val LONG_TAP_DELAY = 200L
const val TAP_DELAY = 120L

@RequiresApi(Build.VERSION_CODES.O)
class InputService : AccessibilityService() {

    companion object {
        var ctx: InputService? = null
        val isOpen: Boolean
            get() = ctx != null
    }

    private val logTag = "input service"
    private var leftIsDown = false
    private var touchPath = Path()
    private var touchGestureBuilder = GestureDescription.Builder()
    private var lastTouchGestureStartTime = 0L
    private var mouseX = 0
    private var mouseY = 0
    private var lastMouseX = 0
    private var lastMouseY = 0
    private var timer = Timer()
    private var recentActionTask: TimerTask? = null

    private val wheelActionsQueue = LinkedList<GestureDescription>()
    private var isWheelActionsPolling = false
    private var isWaitingLongPress = false

    private var performClickTimer: Timer? = null
    private var performClickTask: TimerTask? = null
    private var clicked = false

    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        Log.d(logTag, "mask:$mask x:$_x y:$_y")
        val x = max(0, _x)
        val y = max(0, _y)

        if (mask == 0 || mask == LIFT_MOVE) {
            Log.d(logTag, "LIFT_MOVE or mask == 0")
            Log.d(logTag, "last----lastMouseX:$lastMouseX lastMouseY:$lastMouseY")
            Log.d(logTag, "old----mouseX:$mouseX mouseY:$mouseY")
            val oldX = mouseX
            val oldY = mouseY
            mouseX = x * SCREEN_INFO.scale
            mouseY = y * SCREEN_INFO.scale
            lastMouseX = oldX
            lastMouseY = oldY
            Log.d(logTag, "new----mouseX:$mouseX mouseY:$mouseY")
            if (isWaitingLongPress) {
                Log.d(logTag, "isWaitingLongPress")
                val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
                Log.d(logTag, "delta:$delta")
                if (delta > 8) {
                    isWaitingLongPress = false
                    performClickTimer?.cancel()
                }
            }
            if (performClickTimer == null && lastMouseX != mouseX && lastMouseY != mouseY) {
                Log.d(logTag, "start performClickTimer")
                performClickTimer = Timer()
                clicked = false
                lastTouchGestureStartTime = System.currentTimeMillis()
                performClickTask = object : TimerTask() {
                    override fun run() {
                        tap(mouseX, mouseY)
                        clicked = true
                    }
                }
                performClickTimer?.schedule(performClickTask, TAP_DELAY)
            }
        }

        // left button down ,was up
        if (mask == LIFT_DOWN) {
            Log.d(logTag, "LIFT_DOWN")
            if (clicked) {
                return
            }
            performClickTimer?.cancel()
            isWaitingLongPress = true
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (isWaitingLongPress) {
                        isWaitingLongPress = false
                        leftIsDown = false
                        endGesture(mouseX, mouseY)
                    }
                }
            }, LONG_TAP_DELAY * 4)

            leftIsDown = true
            startGesture(mouseX, mouseY)
            return
        }

        // left up ,was down
        if (mask == LIFT_UP) {
            Log.d(logTag, "LIFT_UP")
            performClickTimer?.cancel()
            performClickTimer = null
            if (leftIsDown) {
                Log.d(logTag, "leftIsDown")
                leftIsDown = false
                isWaitingLongPress = false
                endGesture(mouseX, mouseY)
                return
            }
        }

        if (mask == RIGHT_DOWN) {
            Log.d(logTag, "RIGHT_DOWN")
            return
        }

        if (mask == RIGHT_UP) {
            Log.d(logTag, "RIGHT_UP")
            return
        }

        if (isWaitingLongPress) {
            return
        }

        // left down ,was down
        if (leftIsDown) {
            Log.d(logTag, "leftIsDown")
            continueGesture(mouseX, mouseY)
        }

        // long WHEEL_BUTTON_DOWN -> GLOBAL_ACTION_RECENTS
        if (mask == WHEEL_BUTTON_DOWN) {
            timer.purge()
            recentActionTask = object : TimerTask() {
                override fun run() {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    recentActionTask = null
                }
            }
            timer.schedule(recentActionTask, LONG_TAP_DELAY)
        }

        // wheel button up
        if (mask == WHEEL_BUTTON_UP) {
            if (recentActionTask != null) {
                recentActionTask!!.cancel()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        if (mask == WHEEL_DOWN) {
//            if (mouseY < WHEEL_STEP) {
//                return
//            }
//            val path = Path()
//            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
//            path.lineTo(mouseX.toFloat(), (mouseY - WHEEL_STEP).toFloat())
//            val stroke = GestureDescription.StrokeDescription(
//                path,
//                0,
//                WHEEL_DURATION
//            )
//            val builder = GestureDescription.Builder()
//            builder.addStroke(stroke)
//            wheelActionsQueue.offer(builder.build())
//            consumeWheelActions()
            performGestureZoomOut(x.toFloat(), y.toFloat())
        }

        if (mask == WHEEL_UP) {
//            if (mouseY < WHEEL_STEP) {
//                return
//            }
//            val path = Path()
//            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
//            path.lineTo(mouseX.toFloat(), (mouseY + WHEEL_STEP).toFloat())
//            val stroke = GestureDescription.StrokeDescription(
//                path,
//                0,
//                WHEEL_DURATION
//            )
//            val builder = GestureDescription.Builder()
//            builder.addStroke(stroke)
//            wheelActionsQueue.offer(builder.build())
//            consumeWheelActions()
//            performGlobalAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            performGestureZoomIn(x.toFloat(), y.toFloat())
        }
    }

    private fun consumeWheelActions() {
        if (isWheelActionsPolling) {
            return
        } else {
            isWheelActionsPolling = true
        }
        wheelActionsQueue.poll()?.let {
            dispatchGesture(it, null, null)
            timer.purge()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    isWheelActionsPolling = false
                    consumeWheelActions()
                }
            }, WHEEL_DURATION + 10)
        } ?: let {
            isWheelActionsPolling = false
            return
        }
    }

    private fun startGesture(x: Int, y: Int) {
        Log.d(logTag, "start gesture x:$x y:$y")
        touchPath = Path()
        touchPath.moveTo(x.toFloat(), y.toFloat())
        val strokeDescription = GestureDescription.StrokeDescription(
            touchPath, 0, 1, true
        ) // 设置willContinue为true

        //构建并发送手势
        touchGestureBuilder = GestureDescription.Builder()
        touchGestureBuilder.addStroke(strokeDescription)
        lastTouchGestureStartTime = System.currentTimeMillis()
        dispatchGesture(touchGestureBuilder.build(), null, null)
    }

    private fun continueGesture(x: Int, y: Int) {
        Log.d(logTag, "continue gesture x:$x y:$y")

        //构建手势路径
        touchPath.lineTo(x.toFloat(), y.toFloat())
        var duration = System.currentTimeMillis() - lastTouchGestureStartTime
        Log.d(logTag, "duration:$duration")
        if (duration <= 0) {
            duration = 1
        }
        val strokeDescription = GestureDescription.StrokeDescription(
            touchPath, 0, duration, true
        ) // 设置willContinue为true

        //构建并发送手势
        touchGestureBuilder = GestureDescription.Builder()
        touchGestureBuilder.addStroke(strokeDescription)
        lastTouchGestureStartTime = System.currentTimeMillis()
        dispatchGesture(touchGestureBuilder.build(), null, null)

        //重置手势路径
        touchPath = Path()
        touchPath.moveTo(x.toFloat(), y.toFloat())
    }

    /**
     * 模拟放大手势
     */
    private fun performGestureZoomIn(x: Float, y: Float) {
        // 创建一个手势路径
        val path1 = Path()
        path1.moveTo(x - 50, y) // 第一个手指初始位置
        path1.lineTo(x - 150, y) // 第一个手指滑动位置
        val path2 = Path()
        path2.moveTo(x + 50, y) // 第二个手指初始位置
        path2.lineTo(x + 150, y) // 第二个手指滑动位置

        // 创建手势描述
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path1, 0, 500))
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path2, 0, 500))


        // 发送手势事件
        val gestureDescription = gestureBuilder.build()
        dispatchGesture(gestureDescription, null, null)
    }

    /**
     * 模拟缩小手势
     */
    private fun performGestureZoomOut(x: Float, y: Float) {
        // 创建一个手势路径
        val path = Path()
        path.moveTo(x - 150, y) // 第一个手指初始位置
        path.lineTo(x - 50, y) // 第一个手指滑动位置
        path.moveTo(x + 150, y) // 第二个手指初始位置
        path.lineTo(x + 50, y) // 第二个手指滑动位置

        // 创建手势描述
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 500))

        // 发送手势事件
        val gestureDescription = gestureBuilder.build()
        dispatchGesture(gestureDescription, null, null)
    }

    private fun endGesture(x: Int, y: Int) {
        if (mouseX == lastMouseX && mouseY == lastMouseY) {
            //防止二次点击
            return
        }
        try {
            touchPath.moveTo(lastMouseX.toFloat(), lastMouseY.toFloat())
            touchPath.lineTo(x.toFloat(), y.toFloat())
            var duration = System.currentTimeMillis() - lastTouchGestureStartTime
            Log.d(logTag, "end duration:$duration")
            if (duration <= 0) {
                duration = 1
            }
            val stroke = GestureDescription.StrokeDescription(
                touchPath, 0, duration * 3, false
            )
            touchGestureBuilder.addStroke(stroke)
            Log.d(logTag, "end gesture x:$x y:$y time:$duration")
            dispatchGesture(touchGestureBuilder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "endGesture error:$e")
        }
    }

    private fun tap(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        path.lineTo(x.toFloat(), y.toFloat())
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            1
        )
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ctx = this
        Log.d(logTag, "onServiceConnected!")
    }

    override fun onDestroy() {
        ctx = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}
