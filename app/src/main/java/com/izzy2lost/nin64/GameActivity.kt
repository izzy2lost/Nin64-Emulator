package com.izzy2lost.nin64

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.roundToInt

class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private val blitRect = RectF()

    @Volatile private var running = false
    private var emulationThread: Thread? = null
    private var keyButtonMask = 0
    private var axisButtonMask = 0
    private var analogStickX = 0
    private var analogStickY = 0

    private val rootPath: String get() = intent.getStringExtra(EXTRA_ROOT_PATH) ?: ""
    private val romPath: String get() = intent.getStringExtra(EXTRA_ROM_PATH) ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.RGBX_8888)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
        setContentView(surfaceView)
        surfaceView.holder.addCallback(this)
        pushControllerState()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (rootPath.isEmpty() || romPath.isEmpty()) {
            finish()
            return
        }
        startEmulation(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopEmulation()
    }

    override fun onResume() {
        super.onResume()
        surfaceView.requestFocus()
    }

    private fun startEmulation(holder: SurfaceHolder) {
        running = true
        emulationThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            NativeBridge.bootRomForPlay(rootPath, romPath)
            emulationLoop(holder)
        }.apply {
            name = "Nin64-Emu"
            start()
        }
    }

    private fun stopEmulation() {
        running = false
        emulationThread?.join(3000)
        emulationThread = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val button = mapGamepadKeyToN64Button(event.keyCode)
        if (button == 0 || !isGamepadSource(event.source)) {
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyButtonMask = keyButtonMask or button
            KeyEvent.ACTION_UP -> keyButtonMask = keyButtonMask and button.inv()
            else -> return super.dispatchKeyEvent(event)
        }

        pushControllerState()
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE || !isGamepadSource(event.source)) {
            return super.dispatchGenericMotionEvent(event)
        }

        val device = event.device ?: return super.dispatchGenericMotionEvent(event)

        analogStickX = scaleStickAxis(event, device, MotionEvent.AXIS_X, positiveUp = false)
        analogStickY = scaleStickAxis(event, device, MotionEvent.AXIS_Y, positiveUp = true)

        val rightX = getAxisValue(event, device, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)
        val rightY = getAxisValue(event, device, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)
        val hatX = getAxisValue(event, device, MotionEvent.AXIS_HAT_X)
        val hatY = getAxisValue(event, device, MotionEvent.AXIS_HAT_Y)
        val leftTrigger = maxTriggerValue(event, device, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)

        axisButtonMask = 0
        if (hatX <= -HAT_THRESHOLD) axisButtonMask = axisButtonMask or N64_DPAD_LEFT
        if (hatX >= HAT_THRESHOLD) axisButtonMask = axisButtonMask or N64_DPAD_RIGHT
        if (hatY <= -HAT_THRESHOLD) axisButtonMask = axisButtonMask or N64_DPAD_UP
        if (hatY >= HAT_THRESHOLD) axisButtonMask = axisButtonMask or N64_DPAD_DOWN

        if (rightX <= -C_BUTTON_THRESHOLD) axisButtonMask = axisButtonMask or N64_C_LEFT
        if (rightX >= C_BUTTON_THRESHOLD) axisButtonMask = axisButtonMask or N64_C_RIGHT
        if (rightY <= -C_BUTTON_THRESHOLD) axisButtonMask = axisButtonMask or N64_C_UP
        if (rightY >= C_BUTTON_THRESHOLD) axisButtonMask = axisButtonMask or N64_C_DOWN

        if (leftTrigger >= TRIGGER_THRESHOLD) axisButtonMask = axisButtonMask or N64_Z_TRIGGER

        pushControllerState()
        return true
    }

    private fun emulationLoop(holder: SurfaceHolder) {
        val paint = Paint().apply { isFilterBitmap = false }
        var frameBitmap: Bitmap? = null
        var framePixels: IntArray? = null
        var lastSwap = NativeBridge.getSwapCount()
        var lastPresentAtMs = 0L

        while (running) {
            NativeBridge.runFrame(OPS_PER_CHUNK)

            val swap = NativeBridge.getSwapCount()
            if (swap == lastSwap) continue
            lastSwap = swap

            val w = NativeBridge.getFrameWidth()
            val h = NativeBridge.getFrameHeight()
            if (w <= 0 || h <= 0) continue

            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastPresentAtMs < MIN_PRESENT_INTERVAL_MS) {
                continue
            }
            lastPresentAtMs = nowMs

            val totalPixels = w * h
            if (framePixels == null || framePixels!!.size < totalPixels) {
                framePixels = IntArray(totalPixels)
            }
            val pixels = framePixels!!
            val copiedPixels = NativeBridge.copyFrameBufferArgbInto(pixels)
            if (copiedPixels < totalPixels) continue

            if (frameBitmap == null || frameBitmap!!.width != w || frameBitmap!!.height != h) {
                frameBitmap?.recycle()
                frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            frameBitmap!!.setPixels(pixels, 0, w, 0, 0, w, h)

            val canvas: Canvas = holder.lockCanvas() ?: continue
            try {
                blit(canvas, frameBitmap!!, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        frameBitmap?.recycle()
    }

    private fun blit(canvas: Canvas, bitmap: Bitmap, paint: Paint) {
        canvas.drawColor(Color.BLACK)

        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstAspect = canvas.width.toFloat() / canvas.height.toFloat()

        if (srcAspect > dstAspect) {
            val h = canvas.width / srcAspect
            blitRect.set(0f, (canvas.height - h) / 2f, canvas.width.toFloat(), (canvas.height + h) / 2f)
        } else {
            val w = canvas.height * srcAspect
            blitRect.set((canvas.width - w) / 2f, 0f, (canvas.width + w) / 2f, canvas.height.toFloat())
        }

        canvas.drawBitmap(bitmap, null, blitRect, paint)
    }

    private fun pushControllerState() {
        NativeBridge.setControllerState(
            keyButtonMask or axisButtonMask,
            analogStickX,
            analogStickY
        )
    }

    private fun mapGamepadKeyToN64Button(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> N64_DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> N64_DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> N64_DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> N64_DPAD_RIGHT
        KeyEvent.KEYCODE_BUTTON_A -> N64_A_BUTTON
        KeyEvent.KEYCODE_BUTTON_B -> N64_B_BUTTON
        KeyEvent.KEYCODE_BUTTON_X -> N64_C_UP
        KeyEvent.KEYCODE_BUTTON_Y -> N64_C_LEFT
        KeyEvent.KEYCODE_BUTTON_START -> N64_START
        KeyEvent.KEYCODE_BUTTON_L1 -> N64_L_TRIGGER
        KeyEvent.KEYCODE_BUTTON_R1 -> N64_R_TRIGGER
        KeyEvent.KEYCODE_BUTTON_L2 -> N64_Z_TRIGGER
        KeyEvent.KEYCODE_BUTTON_R2 -> N64_C_RIGHT
        KeyEvent.KEYCODE_BUTTON_THUMBR -> N64_C_DOWN
        KeyEvent.KEYCODE_BUTTON_Z -> N64_Z_TRIGGER
        else -> 0
    }

    private fun isGamepadSource(source: Int): Boolean {
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun scaleStickAxis(
        event: MotionEvent,
        device: InputDevice,
        axis: Int,
        positiveUp: Boolean
    ): Int {
        val value = getAxisValue(event, device, axis)
        val scaled = (if (positiveUp) -value else value) * STICK_MAX
        return scaled.roundToInt().coerceIn(-STICK_MAX, STICK_MAX)
    }

    private fun getAxisValue(event: MotionEvent, device: InputDevice, vararg axes: Int): Float {
        var bestValue = 0f
        var bestMagnitude = 0f

        for (axis in axes) {
            val range = getMotionRange(device, axis, event.source) ?: continue
            val value = event.getAxisValue(axis)
            val centeredValue = if (abs(value) > range.flat) value else 0f
            val magnitude = abs(centeredValue)
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestValue = centeredValue
            }
        }

        return bestValue
    }

    private fun maxTriggerValue(event: MotionEvent, device: InputDevice, vararg axes: Int): Float {
        var maxValue = 0f
        for (axis in axes) {
            val range = getMotionRange(device, axis, event.source) ?: continue
            val value = event.getAxisValue(axis)
            if (value > range.flat && value > maxValue) {
                maxValue = value
            }
        }
        return maxValue
    }

    private fun getMotionRange(device: InputDevice, axis: Int, source: Int): InputDevice.MotionRange? {
        return device.getMotionRange(axis, source)
            ?: device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            ?: device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
            ?: device.getMotionRange(axis)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        stopEmulation()
        super.onBackPressed()
    }

    override fun onDestroy() {
        keyButtonMask = 0
        axisButtonMask = 0
        analogStickX = 0
        analogStickY = 0
        pushControllerState()
        stopEmulation()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ROOT_PATH = "extra_root_path"
        private const val EXTRA_ROM_PATH = "extra_rom_path"
        private const val OPS_PER_CHUNK = 2_000_000
        private const val MIN_PRESENT_INTERVAL_MS = 33L
        private const val STICK_MAX = 80
        private const val C_BUTTON_THRESHOLD = 0.5f
        private const val HAT_THRESHOLD = 0.5f
        private const val TRIGGER_THRESHOLD = 0.35f

        private const val N64_A_BUTTON = 0x0080
        private const val N64_B_BUTTON = 0x0040
        private const val N64_Z_TRIGGER = 0x0020
        private const val N64_START = 0x0010
        private const val N64_DPAD_UP = 0x0008
        private const val N64_DPAD_DOWN = 0x0004
        private const val N64_DPAD_LEFT = 0x0002
        private const val N64_DPAD_RIGHT = 0x0001
        private const val N64_L_TRIGGER = 0x2000
        private const val N64_R_TRIGGER = 0x1000
        private const val N64_C_UP = 0x0800
        private const val N64_C_DOWN = 0x0400
        private const val N64_C_LEFT = 0x0200
        private const val N64_C_RIGHT = 0x0100

        fun launch(context: Context, rootPath: String, romPath: String) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_ROOT_PATH, rootPath)
                    .putExtra(EXTRA_ROM_PATH, romPath)
            )
        }
    }
}
