package com.otodone.app.rdp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 显示远端桌面位图并采集输入的 View。
 *
 * 位图由 [RdpSession] 在 FreeRDP 回调线程上原地更新，这里只在 [setFrame] 时
 * invalidate()。坐标按 fit-center 映射回远端桌面坐标。
 */
class RdpSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onPointer(x: Int, y: Int, flags: Int)
        fun onVirtualKey(virtualKey: Int, down: Boolean)
        fun onUnicode(codePoint: Int)

        /** 输入法提交的整段文本（中文要走剪贴板通道，逐字符会丢）。 */
        fun onText(text: String)
    }

    var listener: Listener? = null

    private var bitmap: Bitmap? = null
    private var frameTick = -1L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destRect = Rect()

    /** 远端当前真正按下的按键 flags（0 = 未按下）；补发释放时用它，别和 armed 状态混淆。 */
    private var pressedButton = 0

    /** 最后一次成功映射到远端的坐标：补发按键释放时用它，避免指针跳到 (0,0)。 */
    private var lastRemoteX = 0
    private var lastRemoteY = 0

    /** 本次手势是否出现过两根及以上手指（用于抑制捏合结束时的误点击）。 */
    private var sawMultiTouch = false

    /**
     * 「右键待发」开关：会话页右上角的鼠标右键浮标被按住时为 true。
     * 此时"未缩放的普通单指点击"发送右键（PTR_FLAGS_BUTTON2），松开浮标即恢复左键。
     * 双指缩放/滚动与缩放后的单指平移完全不受影响。
     */
    var rightClickArmed: Boolean = false

    /** 粘性右键：一次右键点击发完之后回调，便于上层自动解除 armed。 */
    var onRightClickSent: (() -> Unit)? = null

    /** 单指点击实际要发送的鼠标按键：armed 时是右键，否则左键。 */
    private val activeButton: Int
        get() = if (rightClickArmed) RdpKeys.PTR_FLAGS_BUTTON2 else RdpKeys.PTR_FLAGS_BUTTON1

    /**
     * 若远端当前有鼠标键按下，补发一次按键释放（flags 不带 DOWN）。
     *
     * 用最后一次已知的远端坐标发送，绝不能用 (0,0)：否则远端指针会瞬间跳到左上角。
     * 释放哪一颗键以按下时记录的 [pressedButton] 为准，不受此后 armed 状态变化影响。
     * 幂等：没有按键按下时什么也不做。
     */
    private fun releasePressedButton() {
        val button = pressedButton
        if (button == 0) return
        pressedButton = 0
        listener?.onPointer(lastRemoteX, lastRemoteY, button)
        if (button == RdpKeys.PTR_FLAGS_BUTTON2) onRightClickSent?.invoke()
    }

    /** 双指滚动的基准 Y。 */
    private var lastScrollY = 0f

    /** 相对 fit 的缩放倍数（1f = 铺满适配）。 */
    private var zoom = 1f

    /** 缩放后的画面平移量（像素）。 */
    private var offsetX = 0f
    private var offsetY = 0f

    private val minZoom = 0.6f
    private val maxZoom = 4f

    /** 缩到小于这个值就自动回弹到原状。 */
    private val resetBelow = 0.8f

    // 双指手势意图：0=未判定 1=缩放 2=滚动（判定后本手势内不再改变，避免互相打架）
    private var gestureMode = 0
    private var gestureStartSpan = 0f
    private var gestureStartCx = 0f
    private var gestureStartCy = 0f
    private var zoomAtGestureStart = 1f

    // 单指：区分"轻点"（点击远端）和"拖动"（缩放后平移画面）
    private var downAt = 0L
    private var panning = false
    private var lastX = 0f
    private var lastY = 0f

    /** 当前实际缩放：fit 缩放 × 手势缩放。 */
    private fun currentScale(bmp: Bitmap): Float =
        minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height) * zoom

    private fun spanOf(event: MotionEvent): Float =
        if (event.pointerCount < 2) 0f
        else hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    /** 平移量限制在画面范围内，别把远端桌面拖出屏幕。 */
    private fun clampOffset() {
        val bmp = bitmap ?: return
        if (bmp.width <= 0 || bmp.height <= 0 || width <= 0 || height <= 0) return
        val scale = currentScale(bmp)
        val maxX = ((bmp.width * scale - width) / 2f).coerceAtLeast(0f)
        val maxY = ((bmp.height * scale - height) / 2f).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    /** 缩得过小就回到原状。 */
    private fun snapBackIfTooSmall() {
        if (zoom < resetBelow) {
            zoom = 1f
            offsetX = 0f
            offsetY = 0f
            invalidate()
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    /** 由 Compose 每帧调用；tick 变化时重绘。 */
    fun setFrame(bmp: Bitmap?, tick: Long) {
        bitmap = bmp
        if (tick != frameTick) {
            frameTick = tick
            invalidate()
        }
    }

    /**
     * 供会话页浮标调用：在视图坐标 ([x], [y]) 处补一次点击。
     *
     * 用于「按住右键浮标 → 同一根手指拖出浮标、进入画面后松手」这条交互：
     * 浮标手势吃掉了这根手指，画面收不到，于是由浮标在松手时主动补发。
     */
    fun clickButtonAt(x: Float, y: Float, button2: Boolean) {
        if (bitmap == null) return
        val (rx, ry) = toRemote(x, y) ?: return
        val button = if (button2) RdpKeys.PTR_FLAGS_BUTTON2 else RdpKeys.PTR_FLAGS_BUTTON1
        listener?.onPointer(rx, ry, RdpKeys.PTR_FLAGS_MOVE or button or RdpKeys.PTR_FLAGS_DOWN)
        listener?.onPointer(rx, ry, button)
    }

    override fun onDetachedFromWindow() {
        // 视图销毁（退出会话/切换页面）时若还有按键按着，补发释放，避免远端卡在拖拽态。
        releasePressedButton()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (bmp.width <= 0 || bmp.height <= 0 || width <= 0 || height <= 0) return
        val scale = currentScale(bmp)
        val dw = (bmp.width * scale).toInt()
        val dh = (bmp.height * scale).toInt()
        val left = ((width - dw) / 2f + offsetX).toInt()
        val top = ((height - dh) / 2f + offsetY).toInt()
        destRect.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(bmp, null, destRect, paint)
    }

    /**
     * 触摸点映射到远端桌面坐标。
     *
     * 关键：**落点不在实际显示的远端画面区域内时返回 null**（调用方直接丢弃该事件）。
     * 以前是把坐标 coerceIn 到图像范围，导致按到画面外的控件（例如浮标）时，
     * 指针会被硬压到图像最右/最上边缘，表现为"鼠标贴边"。
     */
    private fun toRemote(x: Float, y: Float): Pair<Int, Int>? {
        val bmp = bitmap ?: return null
        if (bmp.width <= 0 || bmp.height <= 0 || width <= 0 || height <= 0) return null
        val scale = currentScale(bmp)
        if (scale <= 0f) return null
        val imgW = bmp.width * scale
        val imgH = bmp.height * scale
        val left = (width - imgW) / 2f + offsetX
        val top = (height - imgH) / 2f + offsetY
        if (x < left || x > left + imgW || y < top || y > top + imgH) return null
        val rx = ((x - left) / scale).toInt().coerceIn(0, bmp.width - 1)
        val ry = ((y - top) / scale).toInt().coerceIn(0, bmp.height - 1)
        return rx to ry
    }

    /**
     * 双指手势：先判定用户意图再锁定，避免"缩放/滚动打架"。
     * 两指间距变化大 → 缩放；两指整体平移大 → 滚动。判定后本次手势不再切换。
     */
    private fun handleTwoFinger(event: MotionEvent) {
        val span = spanOf(event)
        val cx = if (event.pointerCount >= 2) (event.getX(0) + event.getX(1)) / 2f else event.x
        val cy = if (event.pointerCount >= 2) (event.getY(0) + event.getY(1)) / 2f else event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                gestureMode = 0
                gestureStartSpan = span
                gestureStartCx = cx
                gestureStartCy = cy
                zoomAtGestureStart = zoom
                lastScrollY = event.getY(0)
            }

            MotionEvent.ACTION_MOVE -> {
                if (gestureMode == 0) {
                    val dSpan = abs(span - gestureStartSpan)
                    val dMove = abs(cx - gestureStartCx) + abs(cy - gestureStartCy)
                    if (dSpan > 24f || dMove > 24f) {
                        gestureMode = if (dSpan > dMove) 1 else 2
                    }
                }
                when (gestureMode) {
                    1 -> {
                        if (gestureStartSpan > 0f) {
                            zoom = (zoomAtGestureStart * (span / gestureStartSpan))
                                .coerceIn(minZoom, maxZoom)
                            clampOffset()
                            invalidate()
                        }
                    }

                    2 -> {
                        val stepPx = 36f
                        val steps = ((event.getY(0) - lastScrollY) / stepPx).toInt()
                        if (steps != 0) {
                            repeat(abs(steps)) {
                                val flags = if (steps > 0) {
                                    RdpKeys.PTR_FLAGS_WHEEL or RdpKeys.WHEEL_DELTA_DOWN
                                } else {
                                    RdpKeys.PTR_FLAGS_WHEEL or
                                        RdpKeys.PTR_FLAGS_WHEEL_NEGATIVE or RdpKeys.WHEEL_DELTA_UP
                                }
                                listener?.onPointer(0, 0, flags)
                            }
                            lastScrollY += steps * stepPx
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                snapBackIfTooSmall()
                gestureMode = 0
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestFocus()

        // 双指 = 滚动远端（滚轮），不移动指针；单指 = 指针移动/点击。
        // 之前用 GestureDetector.onScroll 处理滚轮，会和单指拖动指针同时触发，已移除。
        if (event.pointerCount >= 2) {
            // 第一根手指按下时已经给远端发了按键 DOWN；第二根手指进来后本 View 不再转发
            // 单指移动，必须在这里补发一次释放，否则远端一直以为左键按着（拖拽态），
            // 双指缩放/滚动会全部错乱。releasePressedButton() 幂等，重复进入无副作用。
            sawMultiTouch = true
            releasePressedButton()
            handleTwoFinger(event)
            return true
        }

        val zoomed = zoom > 1.01f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 会话页父层（Compose 容器）不再拦截，双指事件必须完整送到本 View。
                parent?.requestDisallowInterceptTouchEvent(true)
                sawMultiTouch = false
                panning = false
                downAt = SystemClock.uptimeMillis()
                lastX = event.x
                lastY = event.y
                if (!zoomed) {
                    val (rx, ry) = toRemote(event.x, event.y) ?: return true
                    lastRemoteX = rx
                    lastRemoteY = ry
                    pressedButton = activeButton
                    listener?.onPointer(
                        rx,
                        ry,
                        RdpKeys.PTR_FLAGS_MOVE or pressedButton or RdpKeys.PTR_FLAGS_DOWN,
                    )
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (zoomed) {
                    // 缩放后：单指拖动 = 平移画面，不再映射成远端输入
                    panning = true
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    clampOffset()
                    invalidate()
                } else {
                    val (rx, ry) = toRemote(event.x, event.y) ?: return true
                    lastRemoteX = rx
                    lastRemoteY = ry
                    val flags = if (pressedButton != 0) {
                        RdpKeys.PTR_FLAGS_MOVE or pressedButton
                    } else {
                        RdpKeys.PTR_FLAGS_MOVE
                    }
                    listener?.onPointer(rx, ry, flags)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val quickTap = SystemClock.uptimeMillis() - downAt < 260L
                    if (zoomed) {
                        // 缩放后：只有"轻点"（时间短、没拖动、且本次手势没出现过第二根手指）
                        // 才当成远端左键单击，避免捏合/双指滚动结束时误点一下。
                        if (!panning && quickTap && !sawMultiTouch) {
                            toRemote(event.x, event.y)?.let { (rx, ry) ->
                                lastRemoteX = rx
                                lastRemoteY = ry
                                // 缩放后同样要尊重粘性右键：armed 时发右键而不是左键，
                                // 否则"放大后点不动右键"（之前这里硬编码了 BUTTON1）。
                                val button = activeButton
                                listener?.onPointer(
                                    rx,
                                    ry,
                                    RdpKeys.PTR_FLAGS_MOVE or button or RdpKeys.PTR_FLAGS_DOWN,
                                )
                                listener?.onPointer(rx, ry, button)
                                if (button == RdpKeys.PTR_FLAGS_BUTTON2) onRightClickSent?.invoke()
                            }
                        }
                    } else if (pressedButton != 0) {
                        // 松手点还能映射到画面就更新释放坐标；映射不到（拖出画面外松手）
                        // 也要在最后一次已知坐标上释放，绝不能把按键留在按下状态。
                        toRemote(event.x, event.y)?.let { (rx, ry) ->
                            lastRemoteX = rx
                            lastRemoteY = ry
                        }
                    }
                }
                // 无论 ACTION_UP 还是被系统抢占的 ACTION_CANCEL，都要补发按键释放。
                releasePressedButton()
                parent?.requestDisallowInterceptTouchEvent(false)
                panning = false
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (dispatchAndroidKey(keyCode, down = true, event = event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (dispatchAndroidKey(keyCode, down = false, event = event)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** 供 IME 的 InputConnection 复用。 */
    fun dispatchAndroidKey(keyCode: Int, down: Boolean, event: KeyEvent?): Boolean {
        val virtualKey = RdpKeys.androidToVirtualKey(keyCode)
        if (virtualKey != null) {
            listener?.onVirtualKey(virtualKey, down)
            return true
        }
        if (down && event != null) {
            val unicode = event.unicodeChar
            if (unicode != 0) {
                listener?.onUnicode(unicode)
                return true
            }
        }
        return false
    }

    fun dispatchUnicode(codePoint: Int) {
        listener?.onUnicode(codePoint)
    }

    /** 弹出软键盘：requestFocus 后 post 一帧再 showSoftInput，成功率高得多。 */
    fun showKeyboard() {
        requestFocus()
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // 必须是普通文本！用 TYPE_TEXT_VARIATION_VISIBLE_PASSWORD 会拉起系统的
        // 专用密码键盘，中文等字符根本打不出来。
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return RdpInputConnection(this)
    }

    /** 软键盘 -> unicode / 虚拟键。 */
    private class RdpInputConnection(private val host: RdpSurfaceView) :
        // fullEditor = true：中文等输入法需要一个可用的 Editable 才能正常合成/提交，
        // 传 false 时部分输入法（MIUI/搜狗等）会静默不 commit，表现为"打了没反应"。
        BaseInputConnection(host, true) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text?.toString() ?: return true
            if (value.isEmpty()) return true
            // 整段交给上层：纯 ASCII 走虚拟键，含中文等则走剪贴板 + Ctrl+V
            host.listener?.onText(value)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true

        override fun finishComposingText(): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) {
                host.dispatchAndroidKey(KeyEvent.KEYCODE_DEL, down = true, event = null)
                host.dispatchAndroidKey(KeyEvent.KEYCODE_DEL, down = false, event = null)
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean = when (event.action) {
            KeyEvent.ACTION_DOWN -> host.dispatchAndroidKey(event.keyCode, true, event)
            KeyEvent.ACTION_UP -> host.dispatchAndroidKey(event.keyCode, false, event)
            else -> false
        }
    }
}
