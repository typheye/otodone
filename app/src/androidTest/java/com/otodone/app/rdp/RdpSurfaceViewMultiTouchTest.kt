package com.otodone.app.rdp

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 多点触控「按键状态泄漏」回归测试。
 *
 * adb 无法向同一虚拟设备注入真正两根手指（第二次 DOWN 会被 InputDispatcher 丢掉），
 * 所以这里直接构造多指 [MotionEvent] 派发给真实的 [RdpSurfaceView]，
 * 用 [RecordingListener] 断言送给远端的指针事件序列。
 *
 * 视口 1000x1000、位图 800x600：fit 缩放 = 1.25，图像占满整宽、上下居中，
 * 于是 (500,500) -> 远端 (400,300)；(500,50) 落在图像外（y < 125）返回 null。
 */
@RunWith(AndroidJUnit4::class)
class RdpSurfaceViewMultiTouchTest {

    private class RecordingListener : RdpSurfaceView.Listener {
        data class Ptr(val x: Int, val y: Int, val flags: Int)

        val pointers = mutableListOf<Ptr>()
        override fun onPointer(x: Int, y: Int, flags: Int) {
            pointers += Ptr(x, y, flags)
        }

        override fun onVirtualKey(virtualKey: Int, down: Boolean) = Unit
        override fun onUnicode(codePoint: Int) = Unit
        override fun onText(text: String) = Unit
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun createView(): Pair<RdpSurfaceView, RecordingListener> {
        val listener = RecordingListener()
        lateinit var view: RdpSurfaceView
        instrumentation.runOnMainSync {
            view = RdpSurfaceView(instrumentation.targetContext)
            view.listener = listener
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 1000, 1000)
            view.setFrame(Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888), 1L)
        }
        return view to listener
    }

    private fun event(
        action: Int,
        points: List<Pair<Float, Float>>,
        downTime: Long,
        eventTime: Long,
        actionIndex: Int = 0,
    ): MotionEvent {
        val props = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].first
                y = points[i].second
                pressure = 1f
                size = 1f
            }
        }
        val masked = if (action == MotionEvent.ACTION_POINTER_DOWN ||
            action == MotionEvent.ACTION_POINTER_UP
        ) {
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            action
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            masked,
            points.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    private fun dispatch(view: RdpSurfaceView, event: MotionEvent) {
        instrumentation.runOnMainSync { view.dispatchTouchEvent(event) }
        event.recycle()
    }

    private fun hasButtonDown(flags: Int): Boolean =
        flags and RdpKeys.PTR_FLAGS_DOWN != 0 &&
            flags and (RdpKeys.PTR_FLAGS_BUTTON1 or RdpKeys.PTR_FLAGS_BUTTON2) != 0

    private fun hasAnyButton(flags: Int): Boolean =
        flags and (RdpKeys.PTR_FLAGS_BUTTON1 or RdpKeys.PTR_FLAGS_BUTTON2) != 0

    // ---- 核心回归：单指 -> 双指 必须先补发一次按键释放 ----

    @Test
    fun secondFingerDown_releasesLeftButtonAtLastRemotePosition() {
        val (view, listener) = createView()
        val t0 = SystemClock.uptimeMillis()

        dispatch(view, event(MotionEvent.ACTION_DOWN, listOf(500f to 500f), t0, t0))
        assertEquals(1, listener.pointers.size)
        val press = listener.pointers.single()
        assertTrue("单指按下应发 BUTTON1+DOWN", press.flags and RdpKeys.PTR_FLAGS_BUTTON1 != 0)
        assertTrue(press.flags and RdpKeys.PTR_FLAGS_DOWN != 0)
        assertEquals(400, press.x)
        assertEquals(300, press.y)

        listener.pointers.clear()
        // 第二根手指进来（真实设备上这就是 ACTION_POINTER_DOWN）
        dispatch(
            view,
            event(
                MotionEvent.ACTION_POINTER_DOWN,
                listOf(500f to 500f, 700f to 700f),
                t0,
                t0 + 20,
                actionIndex = 1,
            ),
        )

        assertEquals("进入双指必须补发恰好一次释放", 1, listener.pointers.size)
        val release = listener.pointers.single()
        assertTrue("释放必须带 BUTTON1", release.flags and RdpKeys.PTR_FLAGS_BUTTON1 != 0)
        assertFalse("释放不能带 DOWN", release.flags and RdpKeys.PTR_FLAGS_DOWN != 0)
        assertEquals("释放要用最后已知远端坐标 x", 400, release.x)
        assertEquals("释放要用最后已知远端坐标 y", 300, release.y)
    }

    @Test
    fun rightClickArmed_secondFingerDown_releasesButton2() {
        val (view, listener) = createView()
        instrumentation.runOnMainSync { view.rightClickArmed = true }
        val t0 = SystemClock.uptimeMillis()

        dispatch(view, event(MotionEvent.ACTION_DOWN, listOf(500f to 500f), t0, t0))
        assertTrue(
            "armed 时单指按下应发 BUTTON2",
            listener.pointers.single().flags and RdpKeys.PTR_FLAGS_BUTTON2 != 0,
        )

        listener.pointers.clear()
        dispatch(
            view,
            event(
                MotionEvent.ACTION_POINTER_DOWN,
                listOf(500f to 500f, 700f to 700f),
                t0,
                t0 + 20,
                actionIndex = 1,
            ),
        )
        val release = listener.pointers.single()
        assertTrue("释放的应是按下时记录的 BUTTON2", release.flags and RdpKeys.PTR_FLAGS_BUTTON2 != 0)
        assertFalse(release.flags and RdpKeys.PTR_FLAGS_DOWN != 0)
        assertFalse("不能误发 BUTTON1", release.flags and RdpKeys.PTR_FLAGS_BUTTON1 != 0)
    }

    @Test
    fun movingBothFingers_sendsNoButtonDownEvents() {
        val (view, listener) = createView()
        val t0 = SystemClock.uptimeMillis()

        dispatch(view, event(MotionEvent.ACTION_DOWN, listOf(500f to 500f), t0, t0))
        dispatch(
            view,
            event(
                MotionEvent.ACTION_POINTER_DOWN,
                listOf(400f to 500f, 600f to 500f),
                t0,
                t0 + 10,
                actionIndex = 1,
            ),
        )
        listener.pointers.clear()
        // 两指张开 -> 缩放判定；不得产生任何按键 DOWN
        dispatch(
            view,
            event(
                MotionEvent.ACTION_MOVE,
                listOf(300f to 500f, 700f to 500f),
                t0,
                t0 + 60,
            ),
        )
        dispatch(
            view,
            event(
                MotionEvent.ACTION_POINTER_UP,
                listOf(300f to 500f, 700f to 500f),
                t0,
                t0 + 100,
                actionIndex = 1,
            ),
        )
        dispatch(view, event(MotionEvent.ACTION_UP, listOf(300f to 500f), t0, t0 + 120))

        assertTrue(
            "双指手势过程中不得出现按键 DOWN",
            listener.pointers.none { hasButtonDown(it.flags) },
        )
    }

    @Test
    fun pinchEnd_doesNotEmitStrayClick() {
        val (view, listener) = createView()
        val t0 = SystemClock.uptimeMillis()

        dispatch(view, event(MotionEvent.ACTION_DOWN, listOf(400f to 500f), t0, t0))
        dispatch(
            view,
            event(
                MotionEvent.ACTION_POINTER_DOWN,
                listOf(400f to 500f, 600f to 500f),
                t0,
                t0 + 10,
                actionIndex = 1,
            ),
        )
        dispatch(
            view,
            event(
                MotionEvent.ACTION_MOVE,
                listOf(300f to 500f, 700f to 500f),
                t0,
                t0 + 40,
            ),
        )
        dispatch(
            view,
            event(
                MotionEvent.ACTION_POINTER_UP,
                listOf(300f to 500f, 700f to 500f),
                t0,
                t0 + 80,
                actionIndex = 1,
            ),
        )
        listener.pointers.clear()
        // 快速抬起最后一根手指：sawMultiTouch 应抑制缩放后的"轻点=点击"分支
        dispatch(view, event(MotionEvent.ACTION_UP, listOf(300f to 500f), t0, t0 + 120))

        assertTrue(
            "捏合结束不能补出误点击",
            listener.pointers.none { hasButtonDown(it.flags) },
        )
    }

    // ---- 其他释放路径 ----

    @Test
    fun actionCancel_releasesButton() {
        val (view, listener) = createView()
        val t0 = SystemClock.uptimeMillis()

        dispatch(view, event(MotionEvent.ACTION_DOWN, listOf(500f to 500f), t0, t0))
        listener.pointers.clear()
        dispatch(view, event(MotionEvent.ACTION_CANCEL, listOf(500f to 500f), t0, t0 + 30))

        val release = listener.pointers.single()
        assertTrue(release.flags and RdpKeys.PTR_FLAGS_BUTTON1 != 0)
        assertFalse(release.flags and RdpKeys.PTR_FLAGS_DOWN != 0)
    }

    @Test
    fun upOutsideImage_releasesAtLastKnownRemotePosition() {
        val (view, listener) = createView()
        val t0 = SystemClock.uptimeMillis()

        dispatch(view, event(MotionEvent.ACTION_DOWN, listOf(500f to 500f), t0, t0))
        dispatch(view, event(MotionEvent.ACTION_MOVE, listOf(500f to 500f), t0, t0 + 20))
        listener.pointers.clear()
        // (500,50) 在图像外，toRemote 返回 null；仍必须在最后已知坐标释放
        dispatch(view, event(MotionEvent.ACTION_UP, listOf(500f to 50f), t0, t0 + 40))

        val release = listener.pointers.single()
        assertTrue(release.flags and RdpKeys.PTR_FLAGS_BUTTON1 != 0)
        assertFalse(release.flags and RdpKeys.PTR_FLAGS_DOWN != 0)
        assertEquals(400, release.x)
        assertEquals(300, release.y)
    }

    @Test
    fun pointerUpThenRemainingFingerUp_doesNotLeakButton() {
        val (view, listener) = createView()
        val t0 = SystemClock.uptimeMillis()

        dispatch(view, event(MotionEvent.ACTION_DOWN, listOf(500f to 500f), t0, t0))
        dispatch(
            view,
            event(
                MotionEvent.ACTION_POINTER_DOWN,
                listOf(500f to 500f, 700f to 700f),
                t0,
                t0 + 10,
                actionIndex = 1,
            ),
        )
        listener.pointers.clear()
        // 抬起第二根手指，剩第一根
        dispatch(
            view,
            event(
                MotionEvent.ACTION_POINTER_UP,
                listOf(500f to 500f, 700f to 700f),
                t0,
                t0 + 40,
                actionIndex = 1,
            ),
        )
        dispatch(view, event(MotionEvent.ACTION_UP, listOf(500f to 500f), t0, t0 + 80))

        assertTrue(
            "双指 -> 单指 -> 全部抬起，不得再有任何按键 DOWN",
            listener.pointers.none { hasButtonDown(it.flags) },
        )
    }

    @Test
    fun singleFingerDrag_stillSendsMoveAndRelease() {
        val (view, listener) = createView()
        val t0 = SystemClock.uptimeMillis()

        dispatch(view, event(MotionEvent.ACTION_DOWN, listOf(500f to 500f), t0, t0))
        dispatch(view, event(MotionEvent.ACTION_MOVE, listOf(520f to 540f), t0, t0 + 20))
        dispatch(view, event(MotionEvent.ACTION_UP, listOf(520f to 540f), t0, t0 + 40))

        assertEquals(3, listener.pointers.size)
        val press = listener.pointers[0]
        assertTrue(press.flags and RdpKeys.PTR_FLAGS_BUTTON1 != 0)
        assertTrue(press.flags and RdpKeys.PTR_FLAGS_DOWN != 0)
        val move = listener.pointers[1]
        assertTrue(move.flags and RdpKeys.PTR_FLAGS_BUTTON1 != 0)
        assertFalse(move.flags and RdpKeys.PTR_FLAGS_DOWN != 0)
        val release = listener.pointers[2]
        assertTrue(release.flags and RdpKeys.PTR_FLAGS_BUTTON1 != 0)
        assertFalse(release.flags and RdpKeys.PTR_FLAGS_DOWN != 0)
        assertTrue("普通拖动也必须带按键标志", hasAnyButton(move.flags))
    }
}
