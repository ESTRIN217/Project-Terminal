package com.termux.view

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Scroller
import androidx.annotation.Nullable
import androidx.annotation.RequiresApi
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.textselection.TextSelectionCursorController

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private var TERMINAL_VIEW_KEY_LOGGING_ENABLED = false
        const val TERMINAL_CURSOR_BLINK_RATE_MIN = 100
        const val TERMINAL_CURSOR_BLINK_RATE_MAX = 2000
        val KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD
        val KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0
        private const val LOG_TAG = "TerminalView"
    }

    @JvmField
    var mTermSession: TerminalSession? = null

    @JvmField
    var mEmulator: TerminalEmulator? = null

    @JvmField
    var mRenderer: TerminalRenderer? = null

    @JvmField
    var mClient: TerminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) = Unit
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String, message: String) = Unit
        override fun logWarn(tag: String, message: String) = Unit
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
        override fun logStackTrace(tag: String, e: Exception) = Unit
    }

    private var mTextSelectionCursorController: TextSelectionCursorController? = null

    private var mTerminalCursorBlinkerHandler: Handler? = null
    private var mTerminalCursorBlinkerRunnable: TerminalCursorBlinkerRunnable? = null
    private var mTerminalCursorBlinkerRate = 0

    var mTopRow = 0
    var mDefaultSelectors = intArrayOf(-1, -1, -1, -1)

    var mScaleFactor = 1f
    val mGestureRecognizer: GestureAndScaleRecognizer

    private var mMouseScrollStartX = -1
    private var mMouseScrollStartY = -1
    private var mMouseStartDownTime = -1L

    val mScroller: Scroller
    var mScrollRemainder = 0f
    var mCombiningAccent = 0

    @RequiresApi(Build.VERSION_CODES.O)
    private var mAutoFillType = AUTOFILL_TYPE_NONE

    @RequiresApi(Build.VERSION_CODES.O)
    private var mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO

    @RequiresApi(Build.VERSION_CODES.O)
    private var mAutoFillHints = arrayOf<String>()

    private val mAccessibilityEnabled: Boolean

    private val mShowFloatingToolbar = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getTextSelectionActionMode()?.hide(0)
        }
    }

    init {
        mGestureRecognizer = GestureAndScaleRecognizer(context, object : GestureAndScaleRecognizer.Listener {
            var scrolledWithFinger = false

            override fun onUp(event: MotionEvent): Boolean {
                mScrollRemainder = 0.0f
                if (mEmulator != null && mEmulator!!.isMouseTrackingActive &&
                    !event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                    !isSelectingText() && !scrolledWithFinger
                ) {
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, true)
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, false)
                    return true
                }
                scrolledWithFinger = false
                return false
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (mEmulator == null) return true
                if (isSelectingText()) {
                    stopTextSelectionMode()
                    return true
                }
                requestFocus()
                mClient.onSingleTapUp(event)
                return true
            }

            override fun onScroll(e: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (mEmulator == null) return true
                if (mEmulator!!.isMouseTrackingActive && e.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                } else {
                    scrolledWithFinger = true
                    var dy = distanceY + mScrollRemainder
                    val deltaRows = (dy / mRenderer!!.mFontLineSpacing).toInt()
                    mScrollRemainder = dy - deltaRows * mRenderer!!.mFontLineSpacing
                    doScroll(e, deltaRows)
                }
                return true
            }

            override fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean {
                if (mEmulator == null || isSelectingText()) return true
                mScaleFactor *= scale
                mScaleFactor = mClient.onScale(mScaleFactor)
                return true
            }

            override fun onFling(e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (mEmulator == null) return true
                if (!mScroller.isFinished) return true

                val mouseTrackingAtStartOfFling = mEmulator!!.isMouseTrackingActive
                val SCALE = 0.25f
                if (mouseTrackingAtStartOfFling) {
                    mScroller.fling(0, 0, 0, -(velocityY * SCALE).toInt(), 0, 0, -mEmulator!!.mRows / 2, mEmulator!!.mRows / 2)
                } else {
                    mScroller.fling(0, mTopRow, 0, -(velocityY * SCALE).toInt(), 0, 0, -mEmulator!!.screen.activeTranscriptRows, 0)
                }

                post(object : Runnable {
                    private var mLastY = 0

                    override fun run() {
                        if (mouseTrackingAtStartOfFling != mEmulator!!.isMouseTrackingActive) {
                            mScroller.abortAnimation()
                            return
                        }
                        if (mScroller.isFinished) return
                        val more = mScroller.computeScrollOffset()
                        val newY = mScroller.currY
                        val diff = if (mouseTrackingAtStartOfFling) newY - mLastY else newY - mTopRow
                        doScroll(e2, diff)
                        mLastY = newY
                        if (more) post(this)
                    }
                })
                return true
            }

            override fun onDown(x: Float, y: Float): Boolean = false

            override fun onDoubleTap(event: MotionEvent): Boolean = false

            override fun onLongPress(event: MotionEvent) {
                if (mGestureRecognizer.isInProgress()) return
                if (mClient.onLongPress(event)) return
                if (!isSelectingText()) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    startTextSelectionMode(event)
                }
            }
        })

        mScroller = Scroller(context)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        mAccessibilityEnabled = am.isEnabled
    }

    fun setTerminalViewClient(client: TerminalViewClient) {
        mClient = client
    }

    fun setIsTerminalViewKeyLoggingEnabled(value: Boolean) {
        TERMINAL_VIEW_KEY_LOGGING_ENABLED = value
    }

    fun attachSession(session: TerminalSession): Boolean {
        if (session == mTermSession) return false
        mTopRow = 0
        mTermSession = session
        mEmulator = null
        mCombiningAccent = 0
        updateSize()
        isVerticalScrollBarEnabled = true
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        if (mClient.isTerminalViewSelected()) {
            if (mClient.shouldEnforceCharBasedInput()) {
                outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                outAttrs.inputType = InputType.TYPE_NULL
            }
        } else {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, true) {
            override fun finishComposingText(): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "IME: finishComposingText()")
                super.finishComposingText()
                sendTextToTerminal(editable!!)
                editable!!.clear()
                return true
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: commitText(\"$text\", $newCursorPosition)")
                }
                super.commitText(text, newCursorPosition)
                if (mEmulator == null) return true
                val content = editable
                sendTextToTerminal(content!!)
                content!!.clear()
                return true
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: deleteSurroundingText($leftLength, $rightLength)")
                }
                val deleteKey = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                for (i in 0 until leftLength) sendKeyEvent(deleteKey)
                return super.deleteSurroundingText(leftLength, rightLength)
            }

            private fun sendTextToTerminal(text: CharSequence) {
                stopTextSelectionMode()
                val textLengthInChars = text.length
                var i = 0
                while (i < textLengthInChars) {
                    val firstChar = text[i]
                    val codePoint: Int
                    if (Character.isHighSurrogate(firstChar)) {
                        if (++i < textLengthInChars) {
                            codePoint = Character.toCodePoint(firstChar, text[i])
                        } else {
                            codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR
                        }
                    } else {
                        codePoint = firstChar.code
                    }

                    var cp = codePoint
                    if (mClient.readShiftKey())
                        cp = Character.toUpperCase(cp)

                    var ctrlHeld = false
                    if (cp <= 31 && cp != 27) {
                        if (cp == '\n'.code) {
                            cp = '\r'.code
                        }
                        ctrlHeld = true
                        when (cp) {
                            31 -> cp = '_'.code
                            30 -> cp = '^'.code
                            29 -> cp = ']'.code
                            28 -> cp = '\\'.code
                            else -> cp += 96
                        }
                    }

                    inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, cp, ctrlHeld, false)
                    i++
                }
            }
        }
    }

    override fun computeVerticalScrollRange(): Int {
        return mEmulator?.screen?.activeRows ?: 1
    }

    override fun computeVerticalScrollExtent(): Int {
        return mEmulator?.mRows ?: 1
    }

    override fun computeVerticalScrollOffset(): Int {
        val emu = mEmulator ?: return 1
        return emu.screen.activeRows + mTopRow - emu.mRows
    }

    fun onScreenUpdated() {
        onScreenUpdated(false)
    }

    fun onScreenUpdated(skipScrolling: Boolean) {
        val emu = mEmulator ?: return
        var skip = skipScrolling
        val rowsInHistory = emu.screen.activeTranscriptRows
        if (mTopRow < -rowsInHistory) mTopRow = -rowsInHistory

        if (isSelectingText() || emu.isAutoScrollDisabled) {
            val rowShift = emu.scrollCounter
            if (-mTopRow + rowShift > rowsInHistory) {
                if (isSelectingText()) stopTextSelectionMode()
                if (emu.isAutoScrollDisabled) {
                    mTopRow = -rowsInHistory
                    skip = true
                }
            } else {
                skip = true
                mTopRow -= rowShift
                decrementYTextSelectionCursors(rowShift)
            }
        }

        if (!skip && mTopRow != 0) {
            if (mTopRow < -3) {
                awakenScrollBars()
            }
            mTopRow = 0
        }

        emu.clearScrollCounter()
        invalidate()
        if (mAccessibilityEnabled) setContentDescription(text)
    }

    fun onContextMenuClosed(menu: Menu) {
        unsetStoredSelectedText()
    }

    fun setTextSize(textSize: Int) {
        mRenderer = TerminalRenderer(textSize, mRenderer?.mTypeface ?: Typeface.MONOSPACE)
        updateSize()
    }

    fun setTypeface(newTypeface: Typeface) {
        mRenderer = TerminalRenderer(mRenderer!!.mTextSize, newTypeface)
        updateSize()
        invalidate()
    }

    override fun onCheckIsTextEditor(): Boolean = true
    override fun isOpaque(): Boolean = true

    fun getColumnAndRow(event: MotionEvent, relativeToScroll: Boolean): IntArray {
        val column = (event.x / mRenderer!!.mFontWidth).toInt()
        val row = ((event.y - mRenderer!!.mFontLineSpacingAndAscent) / mRenderer!!.mFontLineSpacing).toInt()
        return intArrayOf(column, if (relativeToScroll) row + mTopRow else row)
    }

    fun sendMouseEventCode(e: MotionEvent, button: Int, pressed: Boolean) {
        val columnAndRow = getColumnAndRow(e, false)
        var x = columnAndRow[0] + 1
        var y = columnAndRow[1] + 1
        if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
            if (mMouseStartDownTime == e.downTime) {
                x = mMouseScrollStartX
                y = mMouseScrollStartY
            } else {
                mMouseStartDownTime = e.downTime
                mMouseScrollStartX = x
                mMouseScrollStartY = y
            }
        }
        mEmulator?.sendMouseEvent(button, x, y, pressed)
    }

    fun doScroll(event: MotionEvent, rowsDown: Int) {
        val up = rowsDown < 0
        val amount = Math.abs(rowsDown)
        for (i in 0 until amount) {
            if (mEmulator!!.isMouseTrackingActive) {
                sendMouseEventCode(event, if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, true)
            } else if (mEmulator!!.isAlternateBufferActive) {
                handleKeyCode(if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0)
            } else {
                mTopRow = Math.min(0, Math.max(-(mEmulator!!.screen.activeTranscriptRows), mTopRow + if (up) -1 else 1))
                if (!awakenScrollBars()) invalidate()
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.action == MotionEvent.ACTION_SCROLL) {
            val up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f
            doScroll(event, if (up) -3 else 3)
            return true
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    @TargetApi(23)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mEmulator == null) return true
        val action = event.action

        if (isSelectingText()) {
            updateFloatingToolbarVisibility(event)
            mGestureRecognizer.onTouchEvent(event)
            return true
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) showContextMenu()
                return true
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipData = clipboardManager?.primaryClip
                if (clipData != null) {
                    val clipItem = clipData.getItemAt(0)
                    if (clipItem != null) {
                        val text = clipItem.coerceToText(context)
                        if (!TextUtils.isEmpty(text)) mEmulator?.paste(text.toString())
                    }
                }
            } else if (mEmulator!!.isMouseTrackingActive) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP ->
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, event.action == MotionEvent.ACTION_DOWN)
                    MotionEvent.ACTION_MOVE ->
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                }
            }
        }

        mGestureRecognizer.onTouchEvent(event)
        return true
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyPreIme(keyCode=$keyCode, event=$event)")
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelRequestAutoFill()
            if (isSelectingText()) {
                stopTextSelectionMode()
                return true
            } else if (mClient.shouldBackButtonBeMappedToEscape()) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> return onKeyDown(keyCode, event)
                    KeyEvent.ACTION_UP -> return onKeyUp(keyCode, event)
                }
            }
        } else if (mClient.shouldUseCtrlSpaceWorkaround() && keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) {
            return onKeyDown(keyCode, event)
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyDown(keyCode=$keyCode, isSystem()=${event.isSystem()}, event=$event)")
        if (mEmulator == null) return true
        if (isSelectingText()) stopTextSelectionMode()

        if (mClient.onKeyDown(keyCode, event, mTermSession!!)) {
            invalidate()
            return true
        } else if (event.isSystem() && (!mClient.shouldBackButtonBeMappedToEscape() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return super.onKeyDown(keyCode, event)
        } else if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            mTermSession!!.write(event.characters)
            return true
        } else if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            return super.onKeyDown(keyCode, event)
        }

        val metaState = event.metaState
        val controlDown = event.isCtrlPressed || mClient.readControlKey()
        val leftAltDown = metaState and KeyEvent.META_ALT_LEFT_ON != 0 || mClient.readAltKey()
        val shiftDown = event.isShiftPressed || mClient.readShiftKey()
        val rightAltDownFromEvent = metaState and KeyEvent.META_ALT_RIGHT_ON != 0

        var keyMod = 0
        if (controlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || leftAltDown) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (shiftDown) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK
        if (!event.isFunctionPressed && handleKeyCode(keyCode, keyMod)) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "handleKeyCode() took key event")
            return true
        }

        var bitsToClear = KeyEvent.META_CTRL_MASK
        if (!rightAltDownFromEvent) {
            bitsToClear = bitsToClear or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        }
        var effectiveMetaState = event.metaState and bitsToClear.inv()

        if (shiftDown) effectiveMetaState = effectiveMetaState or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        if (mClient.readFnKey()) effectiveMetaState = effectiveMetaState or KeyEvent.META_FUNCTION_ON

        val result = event.getUnicodeChar(effectiveMetaState)
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "KeyEvent#getUnicodeChar($effectiveMetaState) returned: $result")
        if (result == 0) return false

        val oldCombiningAccent = mCombiningAccent
        if (result and KeyCharacterMap.COMBINING_ACCENT != 0) {
            if (mCombiningAccent != 0)
                inputCodePoint(event.deviceId, mCombiningAccent, controlDown, leftAltDown)
            mCombiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
        } else {
            var cp = result
            if (mCombiningAccent != 0) {
                val combinedChar = KeyCharacterMap.getDeadChar(mCombiningAccent, cp)
                if (combinedChar > 0) cp = combinedChar
                mCombiningAccent = 0
            }
            inputCodePoint(event.deviceId, cp, controlDown, leftAltDown)
        }

        if (mCombiningAccent != oldCombiningAccent) invalidate()
        return true
    }

    fun inputCodePoint(eventSource: Int, codePoint: Int, controlDownFromEvent: Boolean, leftAltDownFromEvent: Boolean) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient.logInfo(LOG_TAG, "inputCodePoint(eventSource=$eventSource, codePoint=$codePoint, controlDownFromEvent=$controlDownFromEvent, leftAltDownFromEvent=$leftAltDownFromEvent)")
        }
        if (mTermSession == null) return
        mEmulator?.setCursorBlinkState(true)

        val controlDown = controlDownFromEvent || mClient.readControlKey()
        val altDown = leftAltDownFromEvent || mClient.readAltKey()
        var cp = codePoint

        if (mClient.onCodePoint(cp, controlDown, mTermSession!!)) return

        if (controlDown) {
            when {
                cp in 'a'.code..'z'.code -> cp = cp - 'a'.code + 1
                cp in 'A'.code..'Z'.code -> cp = cp - 'A'.code + 1
                cp == ' '.code || cp == '2'.code -> cp = 0
                cp == '['.code || cp == '3'.code -> cp = 27
                cp == '\\'.code || cp == '4'.code -> cp = 28
                cp == ']'.code || cp == '5'.code -> cp = 29
                cp == '^'.code || cp == '6'.code -> cp = 30
                cp == '_'.code || cp == '7'.code || cp == '/'.code -> cp = 31
                cp == '8'.code -> cp = 127
            }
        }

        if (cp > -1) {
            if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
                when (cp) {
                    0x02DC -> cp = 0x007E
                    0x02CB -> cp = 0x0060
                    0x02C6 -> cp = 0x005E
                }
            }
            mTermSession!!.writeCodePoint(altDown, cp)
        }
    }

    fun handleKeyCode(keyCode: Int, keyMod: Int): Boolean {
        mEmulator?.setCursorBlinkState(true)
        if (handleKeyCodeAction(keyCode, keyMod)) return true

        val term = mTermSession!!.emulator
        val code = KeyHandler.getCode(keyCode, keyMod, term.isCursorKeysApplicationMode, term.isKeypadApplicationMode)
        if (code == null) return false
        mTermSession!!.write(code)
        return true
    }

    fun handleKeyCodeAction(keyCode: Int, keyMod: Int): Boolean {
        val shiftDown = keyMod and KeyHandler.KEYMOD_SHIFT != 0
        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (shiftDown) {
                    val time = SystemClock.uptimeMillis()
                    val motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
                    doScroll(motionEvent, if (keyCode == KeyEvent.KEYCODE_PAGE_UP) -mEmulator!!.mRows else mEmulator!!.mRows)
                    motionEvent.recycle()
                    return true
                }
            }
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyUp(keyCode=$keyCode, event=$event)")
        if (mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true

        if (mClient.onKeyUp(keyCode, event)) {
            invalidate()
            return true
        } else if (event.isSystem()) {
            return super.onKeyUp(keyCode, event)
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateSize()
    }

    fun updateSize() {
        val viewWidth = width
        val viewHeight = height
        if (viewWidth == 0 || viewHeight == 0 || mTermSession == null) return

        val newColumns = Math.max(4, (viewWidth / mRenderer!!.mFontWidth).toInt())
        val newRows = Math.max(4, ((viewHeight - mRenderer!!.mFontLineSpacingAndAscent) / mRenderer!!.mFontLineSpacing).toInt())

        if (mEmulator == null || newColumns != mEmulator!!.mColumns || newRows != mEmulator!!.mRows) {
            mTermSession!!.updateSize(newColumns, newRows, mRenderer!!.mFontWidth.toInt(), mRenderer!!.mFontLineSpacing)
            mEmulator = mTermSession!!.emulator
            mClient.onEmulatorSet()

            mTerminalCursorBlinkerRunnable?.setEmulator(mEmulator!!)

            mTopRow = 0
            scrollTo(0, 0)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (mEmulator == null) {
            canvas.drawColor(0XFF000000.toInt())
        } else {
            val sel = mDefaultSelectors
            mTextSelectionCursorController?.getSelectors(sel)
            mRenderer!!.render(mEmulator!!, canvas, mTopRow, sel[0], sel[1], sel[2], sel[3])
            renderTextSelection()
        }
    }

    fun getCurrentSession(): TerminalSession? = mTermSession

    private val text: CharSequence
        get() = mEmulator!!.screen.getSelectedText(0, mTopRow, mEmulator!!.mColumns, mTopRow + mEmulator!!.mRows)

    fun getCursorX(x: Float): Int = (x / mRenderer!!.mFontWidth).toInt()

    fun getCursorY(y: Float): Int {
        return (((y - 40) / mRenderer!!.mFontLineSpacing).toInt()) + mTopRow
    }

    fun getPointX(cx: Int): Int {
        var col = cx
        if (col > mEmulator!!.mColumns) col = mEmulator!!.mColumns
        return Math.round(col * mRenderer!!.mFontWidth)
    }

    fun getPointY(cy: Int): Int {
        return Math.round((cy - mTopRow) * mRenderer!!.mFontLineSpacing.toFloat())
    }

    // AutoFill API
    @RequiresApi(Build.VERSION_CODES.O)
    override fun autofill(value: AutofillValue) {
        if (value.isText) {
            mTermSession?.write(value.textValue.toString())
        }
        resetAutoFill()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillType(): Int = mAutoFillType

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillHints(): Array<String> = mAutoFillHints

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getAutofillValue(): AutofillValue = AutofillValue.forText("")

    @RequiresApi(Build.VERSION_CODES.O)
    override fun getImportantForAutofill(): Int = mAutoFillImportance

    @RequiresApi(Build.VERSION_CODES.O)
    private fun resetAutoFill() {
        mAutoFillType = AUTOFILL_TYPE_NONE
        mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO
        mAutoFillHints = arrayOf()
    }

    fun getAutoFillManagerService(): AutofillManager? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return try {
            context?.getSystemService(AutofillManager::class.java)
        } catch (e: Exception) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to get AutofillManager service", e)
            null
        }
    }

    fun isAutoFillEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val autofillManager = getAutoFillManagerService()
            autofillManager != null && autofillManager.isEnabled
        } catch (e: Exception) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to check if Autofill is enabled", e)
            false
        }
    }

    @Synchronized fun requestAutoFillUsername() {
        requestAutoFill(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) arrayOf(View.AUTOFILL_HINT_USERNAME) else null)
    }

    @Synchronized fun requestAutoFillPassword() {
        requestAutoFill(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) arrayOf(View.AUTOFILL_HINT_PASSWORD) else null)
    }

    @Synchronized fun requestAutoFill(autoFillHints: Array<String>?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || autoFillHints == null || autoFillHints.size < 1) return
        try {
            val autofillManager = getAutoFillManagerService()
            if (autofillManager != null && autofillManager.isEnabled) {
                mAutoFillType = AUTOFILL_TYPE_TEXT
                mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_YES
                mAutoFillHints = autoFillHints
                autofillManager.requestAutofill(this)
            }
        } catch (e: Exception) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to request Autofill", e)
        }
    }

    @Synchronized fun cancelRequestAutoFill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (mAutoFillType == AUTOFILL_TYPE_NONE) return
        try {
            val autofillManager = getAutoFillManagerService()
            if (autofillManager != null && autofillManager.isEnabled) {
                resetAutoFill()
                autofillManager.cancel()
            }
        } catch (e: Exception) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to cancel Autofill request", e)
        }
    }

    // Cursor blinking
    @Synchronized fun setTerminalCursorBlinkerRate(blinkRate: Int): Boolean {
        val result: Boolean
        if (blinkRate != 0 && (blinkRate < TERMINAL_CURSOR_BLINK_RATE_MIN || blinkRate > TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            mClient.logError(LOG_TAG, "The cursor blink rate must be in between $TERMINAL_CURSOR_BLINK_RATE_MIN-$TERMINAL_CURSOR_BLINK_RATE_MAX: $blinkRate")
            mTerminalCursorBlinkerRate = 0
            result = false
        } else {
            mClient.logVerbose(LOG_TAG, "Setting cursor blinker rate to $blinkRate")
            mTerminalCursorBlinkerRate = blinkRate
            result = true
        }

        if (mTerminalCursorBlinkerRate == 0) {
            mClient.logVerbose(LOG_TAG, "Cursor blinker disabled")
            stopTerminalCursorBlinker()
        }
        return result
    }

    @Synchronized fun setTerminalCursorBlinkerState(start: Boolean, startOnlyIfCursorEnabled: Boolean) {
        stopTerminalCursorBlinker()
        if (mEmulator == null) return
        mEmulator!!.setCursorBlinkingEnabled(false)

        if (start) {
            if (mTerminalCursorBlinkerRate < TERMINAL_CURSOR_BLINK_RATE_MIN || mTerminalCursorBlinkerRate > TERMINAL_CURSOR_BLINK_RATE_MAX)
                return
            else if (startOnlyIfCursorEnabled && !mEmulator!!.isCursorEnabled) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                    mClient.logVerbose(LOG_TAG, "Ignoring call to start cursor blinker since cursor is not enabled")
                return
            }

            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logVerbose(LOG_TAG, "Starting cursor blinker with the blink rate $mTerminalCursorBlinkerRate")
            if (mTerminalCursorBlinkerHandler == null)
                mTerminalCursorBlinkerHandler = Handler(Looper.getMainLooper())
            mTerminalCursorBlinkerRunnable = TerminalCursorBlinkerRunnable(mEmulator!!, mTerminalCursorBlinkerRate)
            mEmulator!!.setCursorBlinkingEnabled(true)
            mTerminalCursorBlinkerRunnable!!.run()
        }
    }

    private fun stopTerminalCursorBlinker() {
        if (mTerminalCursorBlinkerHandler != null && mTerminalCursorBlinkerRunnable != null) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logVerbose(LOG_TAG, "Stopping cursor blinker")
            mTerminalCursorBlinkerHandler!!.removeCallbacks(mTerminalCursorBlinkerRunnable!!)
        }
    }

    private inner class TerminalCursorBlinkerRunnable(
        private var mEmulator: TerminalEmulator,
        private val mBlinkRate: Int
    ) : Runnable {

        private var mCursorVisible = false

        fun setEmulator(emulator: TerminalEmulator) {
            mEmulator = emulator
        }

        override fun run() {
            try {
                mCursorVisible = !mCursorVisible
                mEmulator.setCursorBlinkState(mCursorVisible)
                invalidate()
            } finally {
                mTerminalCursorBlinkerHandler!!.postDelayed(this, mBlinkRate.toLong())
            }
        }
    }

    // Text selection
    private fun getTextSelectionCursorController(): TextSelectionCursorController {
        if (mTextSelectionCursorController == null) {
            mTextSelectionCursorController = TextSelectionCursorController(this)
            val observer = viewTreeObserver
            observer?.addOnTouchModeChangeListener(mTextSelectionCursorController!!)
        }
        return mTextSelectionCursorController!!
    }

    private fun showTextSelectionCursors(event: MotionEvent) {
        getTextSelectionCursorController().show(event)
    }

    private fun hideTextSelectionCursors(): Boolean {
        return getTextSelectionCursorController().hide()
    }

    private fun renderTextSelection() {
        mTextSelectionCursorController?.render()
    }

    fun isSelectingText(): Boolean {
        return mTextSelectionCursorController?.isActive() ?: false
    }

    fun getSelectedText(): String? {
        return if (isSelectingText() && mTextSelectionCursorController != null)
            mTextSelectionCursorController!!.selectedText
        else null
    }

    @Nullable
    fun getStoredSelectedText(): String? {
        return mTextSelectionCursorController?.storedSelectedText
    }

    fun unsetStoredSelectedText() {
        mTextSelectionCursorController?.unsetStoredSelectedText()
    }

    private fun getTextSelectionActionMode(): android.view.ActionMode? {
        return mTextSelectionCursorController?.getActionMode()
    }

    fun startTextSelectionMode(event: MotionEvent) {
        if (!requestFocus()) return
        showTextSelectionCursors(event)
        mClient.copyModeChanged(isSelectingText())
        invalidate()
    }

    fun stopTextSelectionMode() {
        if (hideTextSelectionCursors()) {
            mClient.copyModeChanged(isSelectingText())
            invalidate()
        }
    }

    private fun decrementYTextSelectionCursors(decrement: Int) {
        mTextSelectionCursorController?.decrementYTextSelectionCursors(decrement)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mTextSelectionCursorController != null) {
            viewTreeObserver.addOnTouchModeChangeListener(mTextSelectionCursorController!!)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (mTextSelectionCursorController != null) {
            stopTextSelectionMode()
            viewTreeObserver.removeOnTouchModeChangeListener(mTextSelectionCursorController!!)
            mTextSelectionCursorController!!.onDetached()
        }
    }

    // Floating toolbar
    @RequiresApi(Build.VERSION_CODES.M)
    private fun showFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            val delay = ViewConfiguration.getDoubleTapTimeout()
            postDelayed(mShowFloatingToolbar, delay.toLong())
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun hideFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            removeCallbacks(mShowFloatingToolbar)
            getTextSelectionActionMode()!!.hide(-1)
        }
    }

    fun updateFloatingToolbarVisibility(event: MotionEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getTextSelectionActionMode() != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> hideFloatingToolbar()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> showFloatingToolbar()
            }
        }
    }
}
