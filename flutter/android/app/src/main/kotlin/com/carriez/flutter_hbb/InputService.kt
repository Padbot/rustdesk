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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.view.accessibility.AccessibilityEvent
import android.view.ViewGroup.LayoutParams
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import java.util.*
import java.lang.Character
import kotlin.math.abs
import kotlin.math.max
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import hbb.KeyEventConverter

const val LIFT_DOWN = 9
const val LIFT_MOVE = 8
const val LIFT_UP = 10
const val RIGHT_DOWN = 17
const val RIGHT_UP = 18
const val WHEEL_BUTTON_DOWN = 33
const val WHEEL_BUTTON_UP = 34
const val WHEEL_DOWN = 523331
const val WHEEL_UP = 963

const val TOUCH_SCALE_START = 1
const val TOUCH_SCALE = 2
const val TOUCH_SCALE_END = 3
const val TOUCH_PAN_START = 4
const val TOUCH_PAN_UPDATE = 5
const val TOUCH_PAN_END = 6

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
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var strokeQueue: LinkedList<GestureDescription.StrokeDescription> = LinkedList()
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

    private var fakeEditTextForTextStateCalculation: EditText? = null

    //防止双击
    private var lastClickUpTime = 0L

    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        Log.d(logTag, "mask:$mask x:$_x y:$_y")
        val x = max(0, _x)
        val y = max(0, _y)

        if (mask == 0 || mask == LIFT_MOVE) {
            Log.d(logTag, "LIFT_MOVE or mask == 0")
            Log.d(logTag, "last----($lastMouseX,$lastMouseY)")
            Log.d(logTag, "old----($mouseX,$mouseY)")
            val oldX = mouseX
            val oldY = mouseY
            mouseX = x * SCREEN_INFO.scale
            mouseY = y * SCREEN_INFO.scale
            lastMouseX = oldX
            lastMouseY = oldY
            Log.d(logTag, "new----($mouseX,$mouseY)")
            if (isWaitingLongPress) {
                Log.d(logTag, "isWaitingLongPress")
                val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
                Log.d(logTag, "delta:$delta")
                if (delta > 8) {
                    isWaitingLongPress = false
//                    performClickTimer?.cancel()
                }
            }
//            if (performClickTimer == null && lastMouseX != mouseX && lastMouseY != mouseY) {
//                Log.d(logTag, "start performClickTimer")
//                performClickTimer = Timer()
//                clicked = false
//                lastTouchGestureStartTime = System.currentTimeMillis()
//                performClickTask = object : TimerTask() {
//                    override fun run() {
//                        tap(mouseX, mouseY)
//                        clicked = true
//                    }
//                }
//                performClickTimer?.schedule(performClickTask, TAP_DELAY)
//            }
        }

        // left button down ,was up
        if (mask == LIFT_DOWN) {
            Log.d(logTag, "LIFT_DOWN")
            if (System.currentTimeMillis() - lastClickUpTime < 10) {
                Log.d(logTag, "double click blocked")
                return
            }
//            if (clicked) {
//                return
//            }
//            performClickTimer?.cancel()
            isWaitingLongPress = true
            leftIsDown = true
            startGesture(mouseX, mouseY)
            return
        }

        // left up ,was down
        if (mask == LIFT_UP) {
            Log.d(logTag, "LIFT_UP")
            lastClickUpTime = System.currentTimeMillis()
//            performClickTimer?.cancel()
//            performClickTimer = null
            if (leftIsDown) {
                Log.d(logTag, "LIFT_UP - leftIsDown")
                leftIsDown = false
                endGesture(mouseX, mouseY)
                isWaitingLongPress = false
                return
            }
        }

        if (mask == RIGHT_UP) {
            Log.d(logTag, "RIGHT_UP")
            if (!isWaitingLongPress) performGlobalAction(GLOBAL_ACTION_BACK)
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
            if (mouseY < WHEEL_STEP) {
                return
            }
            val path = Path()
            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
            path.lineTo(mouseX.toFloat(), (mouseY - WHEEL_STEP).toFloat())
            val stroke = GestureDescription.StrokeDescription(
                path,
                0,
                WHEEL_DURATION
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            wheelActionsQueue.offer(builder.build())
            consumeWheelActions()
//            performGestureZoomOut(x.toFloat(), y.toFloat())
        }

        if (mask == WHEEL_UP) {
            if (mouseY < WHEEL_STEP) {
                return
            }
            val path = Path()
            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
            path.lineTo(mouseX.toFloat(), (mouseY + WHEEL_STEP).toFloat())
            val stroke = GestureDescription.StrokeDescription(
                path,
                0,
                WHEEL_DURATION
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            wheelActionsQueue.offer(builder.build())
            consumeWheelActions()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onTouchInput(mask: Int, _x: Int, _y: Int) {
        when (mask) {
            TOUCH_PAN_UPDATE -> {
                mouseX -= _x * SCREEN_INFO.scale
                mouseY -= _y * SCREEN_INFO.scale
                mouseX = max(0, mouseX);
                mouseY = max(0, mouseY);
                continueGesture(mouseX, mouseY)
            }

            TOUCH_PAN_START -> {
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
                startGesture(mouseX, mouseY)
            }

            TOUCH_PAN_END -> {
                endGesture(mouseX, mouseY)
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
            }

            else -> {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
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

    private fun performGesture() {
        //构建并发送手势
        strokeQueue.peek()?.let {
            lastTouchGestureStartTime = System.currentTimeMillis()
            Log.d(logTag, "continue gesture ${it.path}, duration:${it.duration}")
            dispatchGesture(
                GestureDescription.Builder().addStroke(it).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        Log.d(logTag, "gesture completed")
                        strokeQueue.poll()
                        performGesture()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        Log.d(logTag, "gesture cancelled")
                        strokeQueue.poll()
                        performGesture()
                    }
                }, null
            )
        }
    }

    private fun startGesture(x: Int, y: Int) {
        Log.d(logTag, "start gesture ($x,$y)")
        touchPath = Path()
        touchPath.moveTo(x.toFloat(), y.toFloat())
        currentStroke = GestureDescription.StrokeDescription(
            touchPath, 0, 1, true
        ) // 设置willContinue为true

        strokeQueue.offer(currentStroke)
        performGesture()
    }

    private fun continueGesture(x: Int, y: Int) {
        //构建手势路径
        touchPath.lineTo(x.toFloat(), y.toFloat())
        var duration = System.currentTimeMillis() - lastTouchGestureStartTime
        if (duration <= 0) {
            duration = 1
        }
        currentStroke = currentStroke?.continueStroke(
            touchPath, 0, 1, true
        ) // 设置willContinue为true

        strokeQueue.offer(currentStroke)
        if (strokeQueue.size <= 1) {
            Log.d(logTag, "pending, no gesture performing")
            performGesture()
        }

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
        try {
            var duration = System.currentTimeMillis() - lastTouchGestureStartTime
            if (isWaitingLongPress) {
                currentStroke = currentStroke?.continueStroke(
                    touchPath, 0, 1, false
                )
                Log.d(
                    logTag,
                    "end gesture: from ($lastMouseX,$lastMouseY) to ($x,$y),duration:$duration"
                )
            } else {
                val extendX = x + (x - lastMouseX)
                val extendY = y + (y - lastMouseY)
                touchPath.lineTo(extendX.toFloat(), extendY.toFloat())
                if (duration <= 0) {
                    duration = 1
                }
                currentStroke = currentStroke?.continueStroke(
                    touchPath, 0, duration * 3, false
                )
                Log.d(
                    logTag,
                    "end gesture: from ($x,$y) to ($extendX,$extendY),duration:$duration"
                )
            }

            strokeQueue.offer(currentStroke)
            if (strokeQueue.size <= 1) {
                Log.d(logTag, "end gesture: no gesture performing")
                performGesture()
            }
        } catch (e: Exception) {
            Log.e(logTag, "endGesture error:$e")
        }
    }

    private fun tap(x: Int, y: Int) {
        Log.d(logTag, "perform tap at ($x,$y)")
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

    @RequiresApi(Build.VERSION_CODES.N)
    fun onKeyEvent(data: ByteArray) {
        val keyEvent = KeyEvent.parseFrom(data)
        val keyboardMode = keyEvent.getMode()

        var textToCommit: String? = null

        if (keyboardMode == KeyboardMode.Legacy) {
            if (keyEvent.hasChr() && keyEvent.getDown()) {
                val chr = keyEvent.getChr()
                if (chr != null) {
                    textToCommit = String(Character.toChars(chr))
                }
            }
        } else if (keyboardMode == KeyboardMode.Translate) {
            if (keyEvent.hasSeq() && keyEvent.getDown()) {
                val seq = keyEvent.getSeq()
                if (seq != null) {
                    textToCommit = seq
                }
            }
        }

        Log.d(logTag, "onKeyEvent $keyEvent textToCommit:$textToCommit")

        if (Build.VERSION.SDK_INT >= 33) {
            getInputMethod()?.let { inputMethod ->
                inputMethod.getCurrentInputConnection()?.let { inputConnection ->
                    if (textToCommit != null) {
                        textToCommit?.let { text ->
                            inputConnection.commitText(text, 1, null)
                        }
                    } else {
                        KeyEventConverter.toAndroidKeyEvent(keyEvent).let { event ->
                            inputConnection.sendKeyEvent(event)
                        }
                    }
                }
            }
        } else {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                KeyEventConverter.toAndroidKeyEvent(keyEvent)?.let { event ->
                    val possibleNodes = possibleAccessibiltyNodes()
                    Log.d(logTag, "possibleNodes:$possibleNodes")
                    for (item in possibleNodes) {
                        val success = trySendKeyEvent(event, item, textToCommit)
                        if (success) {
                            break
                        }
                    }
                }
            }
        }
    }

    private fun insertAccessibilityNode(list: LinkedList<AccessibilityNodeInfo>, node: AccessibilityNodeInfo) {
        if (node == null) {
            return
        }
        if (list.contains(node)) {
            return
        }
        list.add(node)
    }

    private fun findChildNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }
        if (node.isEditable() && node.isFocusable()) {
            return node
        }
        val childCount = node.getChildCount()
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (child.isEditable() && child.isFocusable()) {
                    return child
                }
                if (Build.VERSION.SDK_INT < 33) {
                    child.recycle()
                }
            }
        }
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findChildNode(child)
                if (Build.VERSION.SDK_INT < 33) {
                    if (child != result) {
                        child.recycle()
                    }
                }
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }

    private fun possibleAccessibiltyNodes(): LinkedList<AccessibilityNodeInfo> {
        val linkedList = LinkedList<AccessibilityNodeInfo>()
        val latestList = LinkedList<AccessibilityNodeInfo>()

        val focusInput = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var focusAccessibilityInput = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        val rootInActiveWindow = getRootInActiveWindow()

        Log.d(logTag, "focusInput:$focusInput focusAccessibilityInput:$focusAccessibilityInput rootInActiveWindow:$rootInActiveWindow")

        if (focusInput != null) {
            if (focusInput.isFocusable() && focusInput.isEditable()) {
                insertAccessibilityNode(linkedList, focusInput)
            } else {
                insertAccessibilityNode(latestList, focusInput)
            }
        }

        if (focusAccessibilityInput != null) {
            if (focusAccessibilityInput.isFocusable() && focusAccessibilityInput.isEditable()) {
                insertAccessibilityNode(linkedList, focusAccessibilityInput)
            } else {
                insertAccessibilityNode(latestList, focusAccessibilityInput)
            }
        }

        val childFromFocusInput = findChildNode(focusInput)
        Log.d(logTag, "childFromFocusInput:$childFromFocusInput")

        if (childFromFocusInput != null) {
            insertAccessibilityNode(linkedList, childFromFocusInput)
        }

        val childFromFocusAccessibilityInput = findChildNode(focusAccessibilityInput)
        if (childFromFocusAccessibilityInput != null) {
            insertAccessibilityNode(linkedList, childFromFocusAccessibilityInput)
        }
        Log.d(logTag, "childFromFocusAccessibilityInput:$childFromFocusAccessibilityInput")

        if (rootInActiveWindow != null) {
            insertAccessibilityNode(linkedList, rootInActiveWindow)
        }

        for (item in latestList) {
            insertAccessibilityNode(linkedList, item)
        }

        return linkedList
    }

    private fun trySendKeyEvent(event: android.view.KeyEvent, node: AccessibilityNodeInfo, textToCommit: String?): Boolean {
        node.refresh()
        this.fakeEditTextForTextStateCalculation?.setSelection(0,0)
        this.fakeEditTextForTextStateCalculation?.setText(null)

        val text = node.getText()
        var isShowingHint = false
        if (Build.VERSION.SDK_INT >= 26) {
            isShowingHint = node.isShowingHintText()
        }

        var textSelectionStart = node.textSelectionStart
        var textSelectionEnd = node.textSelectionEnd

        if (text != null) {
            if (textSelectionStart > text.length) {
                textSelectionStart = text.length
            }
            if (textSelectionEnd > text.length) {
                textSelectionEnd = text.length
            }
            if (textSelectionStart > textSelectionEnd) {
                textSelectionStart = textSelectionEnd
            }
        }

        var success = false

        Log.d(logTag, "existing text:$text textToCommit:$textToCommit textSelectionStart:$textSelectionStart textSelectionEnd:$textSelectionEnd")

        if (textToCommit != null) {
            if ((textSelectionStart == -1) || (textSelectionEnd == -1)) {
                val newText = textToCommit
                this.fakeEditTextForTextStateCalculation?.setText(newText)
                success = updateTextForAccessibilityNode(node)
            } else if (text != null) {
                this.fakeEditTextForTextStateCalculation?.setText(text)
                this.fakeEditTextForTextStateCalculation?.setSelection(
                    textSelectionStart,
                    textSelectionEnd
                )
                this.fakeEditTextForTextStateCalculation?.text?.insert(textSelectionStart, textToCommit)
                success = updateTextAndSelectionForAccessibiltyNode(node)
            }
        } else {
            if (isShowingHint) {
                this.fakeEditTextForTextStateCalculation?.setText(null)
            } else {
                this.fakeEditTextForTextStateCalculation?.setText(text)
            }
            if (textSelectionStart != -1 && textSelectionEnd != -1) {
                Log.d(logTag, "setting selection $textSelectionStart $textSelectionEnd")
                this.fakeEditTextForTextStateCalculation?.setSelection(
                    textSelectionStart,
                    textSelectionEnd
                )
            }

            this.fakeEditTextForTextStateCalculation?.let {
                // This is essiential to make sure layout object is created. OnKeyDown may not work if layout is not created.
                val rect = Rect()
                node.getBoundsInScreen(rect)

                it.layout(rect.left, rect.top, rect.right, rect.bottom)
                it.onPreDraw()
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    val succ = it.onKeyDown(event.getKeyCode(), event)
                    Log.d(logTag, "onKeyDown $succ")
                } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                    val success = it.onKeyUp(event.getKeyCode(), event)
                    Log.d(logTag, "keyup $success")
                } else {}
            }

            success = updateTextAndSelectionForAccessibiltyNode(node)
        }
        return success
    }

    fun updateTextForAccessibilityNode(node: AccessibilityNodeInfo): Boolean {
        var success = false
        this.fakeEditTextForTextStateCalculation?.text?.let {
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                it.toString()
            )
            success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return success
    }

    fun updateTextAndSelectionForAccessibiltyNode(node: AccessibilityNodeInfo): Boolean {
        var success = updateTextForAccessibilityNode(node)

        if (success) {
            val selectionStart = this.fakeEditTextForTextStateCalculation?.selectionStart
            val selectionEnd = this.fakeEditTextForTextStateCalculation?.selectionEnd

            if (selectionStart != null && selectionEnd != null) {
                val arguments = Bundle()
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    selectionStart
                )
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    selectionEnd
                )
                success = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
                Log.d(logTag, "Update selection to $selectionStart $selectionEnd success:$success")
            }
        }

        return success
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ctx = this
        val info = AccessibilityServiceInfo()
        if (Build.VERSION.SDK_INT >= 33) {
            info.flags = FLAG_INPUT_METHOD_EDITOR or FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        } else {
            info.flags = FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        setServiceInfo(info)
        fakeEditTextForTextStateCalculation = EditText(this)
        // Size here doesn't matter, we won't show this view.
        fakeEditTextForTextStateCalculation?.layoutParams = LayoutParams(100, 100)
        fakeEditTextForTextStateCalculation?.onPreDraw()
        val layout = fakeEditTextForTextStateCalculation?.getLayout()
        Log.d(logTag, "fakeEditTextForTextStateCalculation layout:$layout")
        Log.d(logTag, "onServiceConnected!")
    }

    override fun onDestroy() {
        ctx = null
        super.onDestroy()
    }

    override fun onInterrupt() {}
}