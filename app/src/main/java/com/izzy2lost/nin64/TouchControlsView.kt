package com.izzy2lost.nin64

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class TouchControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var layout: TouchLayout = TouchLayout.default()
        set(value) {
            field = value
            if (selectedControlId != null && value.controls.none { it.id == selectedControlId }) {
                selectedControlId = null
            }
            invalidate()
        }

    var editorMode: Boolean = false
        set(value) {
            field = value
            clearPlayTouchState()
            invalidate()
        }

    var selectedControlId: String? = null
        private set

    var onTouchStateChanged: ((buttonMask: Int, stickX: Int, stickY: Int) -> Unit)? = null
    var onLayoutEdited: ((TouchLayout) -> Unit)? = null
    var onSelectionChanged: ((TouchControl) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val textBounds = Rect()
    private val activeControlIds = mutableSetOf<String>()

    private var editPointerId = MotionEvent.INVALID_POINTER_ID
    private var editControlId: String? = null
    private var activeStickPointerId = MotionEvent.INVALID_POINTER_ID
    private var activeStickControlId: String? = null
    private var activeStickX = 0
    private var activeStickY = 0

    init {
        isFocusable = true
        isClickable = true
    }

    fun selectControl(id: String) {
        val control = layout.controls.firstOrNull { it.id == id } ?: return
        selectedControlId = control.id
        onSelectionChanged?.invoke(control)
        invalidate()
    }

    fun replaceSelectedControl(transform: (TouchControl) -> TouchControl) {
        val selectedId = selectedControlId ?: return
        val updatedControls = layout.controls.map { control ->
            if (control.id == selectedId) transform(control) else control
        }
        layout = TouchLayout(updatedControls)
        updatedControls.firstOrNull { it.id == selectedId }?.let { onLayoutEdited?.invoke(layout) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val minDimension = min(width, height).toFloat().coerceAtLeast(1f)
        layout.controls.forEach { control ->
            if (control.visible || editorMode) {
                drawControl(canvas, control, minDimension)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (editorMode) {
            handleEditorTouch(event)
        } else {
            handlePlayTouch(event)
        }
    }

    private fun handleEditorTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val control = findControlAt(event.x, event.y, includeInvisible = true)
                    ?: layout.controls.firstOrNull { it.id == selectedControlId }
                    ?: return true
                editPointerId = event.getPointerId(0)
                editControlId = control.id
                selectedControlId = control.id
                onSelectionChanged?.invoke(control)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(editPointerId)
                val controlId = editControlId
                if (pointerIndex >= 0 && controlId != null && width > 0 && height > 0) {
                    updateControlPosition(
                        controlId = controlId,
                        x = (event.getX(pointerIndex) / width.toFloat()).coerceIn(0f, 1f),
                        y = (event.getY(pointerIndex) / height.toFloat()).coerceIn(0f, 1f),
                    )
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                editPointerId = MotionEvent.INVALID_POINTER_ID
                editControlId = null
            }
        }
        return true
    }

    private fun handlePlayTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            clearPlayTouchState()
            invalidate()
            return true
        }

        val liftedPointerIndex = when (event.actionMasked) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> event.actionIndex
            else -> -1
        }
        val liftedPointerId = if (liftedPointerIndex >= 0) {
            event.getPointerId(liftedPointerIndex)
        } else {
            MotionEvent.INVALID_POINTER_ID
        }

        if (liftedPointerId == activeStickPointerId) {
            activeStickPointerId = MotionEvent.INVALID_POINTER_ID
            activeStickControlId = null
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        ) {
            val pointerIndex = event.actionIndex
            val control = findControlAt(event.getX(pointerIndex), event.getY(pointerIndex), includeInvisible = false)
            if (control?.target == N64Target.ANALOG_STICK &&
                activeStickPointerId == MotionEvent.INVALID_POINTER_ID
            ) {
                activeStickPointerId = event.getPointerId(pointerIndex)
                activeStickControlId = control.id
            }
        }

        var buttonMask = 0
        var stickX = 0
        var stickY = 0
        val touchedControls = mutableSetOf<String>()

        val stickPointerIndex = event.findPointerIndex(activeStickPointerId)
        val stickControl = activeStickControl()
        if (stickPointerIndex >= 0 && stickControl != null) {
            touchedControls += stickControl.id
            val (capturedStickX, capturedStickY) = calculateStickState(
                stickControl,
                event.getX(stickPointerIndex),
                event.getY(stickPointerIndex),
            )
            stickX = capturedStickX
            stickY = capturedStickY
        } else {
            activeStickPointerId = MotionEvent.INVALID_POINTER_ID
            activeStickControlId = null
        }

        for (index in 0 until event.pointerCount) {
            if (index == liftedPointerIndex) continue
            if (event.getPointerId(index) == activeStickPointerId) continue
            val control = findControlAt(event.getX(index), event.getY(index), includeInvisible = false) ?: continue
            touchedControls += control.id
            if (control.target == N64Target.ANALOG_STICK) {
                if (activeStickPointerId == MotionEvent.INVALID_POINTER_ID) {
                    activeStickPointerId = event.getPointerId(index)
                    activeStickControlId = control.id
                    val (capturedStickX, capturedStickY) = calculateStickState(
                        control,
                        event.getX(index),
                        event.getY(index),
                    )
                    stickX = capturedStickX
                    stickY = capturedStickY
                }
            } else {
                buttonMask = buttonMask or control.target.buttonMask
            }
        }

        activeControlIds.clear()
        activeControlIds.addAll(touchedControls)
        activeStickX = stickX
        activeStickY = stickY
        notifyTouchState(buttonMask, stickX, stickY)
        invalidate()
        return true
    }

    private fun clearPlayTouchState() {
        activeControlIds.clear()
        activeStickPointerId = MotionEvent.INVALID_POINTER_ID
        activeStickControlId = null
        activeStickX = 0
        activeStickY = 0
        notifyTouchState(0, 0, 0)
    }

    private fun activeStickControl(): TouchControl? {
        val stickControlId = activeStickControlId ?: return null
        return layout.controls.firstOrNull {
            it.id == stickControlId && it.visible && it.target == N64Target.ANALOG_STICK
        }
    }

    private fun calculateStickState(control: TouchControl, x: Float, y: Float): Pair<Int, Int> {
        val centerX = control.x * width
        val centerY = control.y * height
        val radius = controlRadius(control).coerceAtLeast(1f)
        val rawX = ((x - centerX) / radius).coerceIn(-1f, 1f)
        val rawY = ((centerY - y) / radius).coerceIn(-1f, 1f)
        return (rawX * STICK_MAX).roundToInt().coerceIn(-STICK_MAX, STICK_MAX) to
            (rawY * STICK_MAX).roundToInt().coerceIn(-STICK_MAX, STICK_MAX)
    }

    private fun drawControl(canvas: Canvas, control: TouchControl, minDimension: Float) {
        val centerX = control.x * width
        val centerY = control.y * height
        val radius = (control.size * minDimension) / 2f
        val alpha = ((if (control.visible) control.opacity else 0.22f) * 255).roundToInt().coerceIn(35, 255)
        val active = control.id in activeControlIds
        val selected = editorMode && control.id == selectedControlId

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = colorFor(control.target)
        fillPaint.alpha = if (active || selected) 235 else alpha
        strokePaint.alpha = if (selected) 255 else 170
        strokePaint.strokeWidth = if (selected) 5f else 3f

        if (control.target == N64Target.ANALOG_STICK) {
            canvas.drawCircle(centerX, centerY, radius, fillPaint)
            canvas.drawCircle(centerX, centerY, radius, strokePaint)
            val knobX = centerX + (activeStickX / STICK_MAX.toFloat()) * radius * 0.55f
            val knobY = centerY - (activeStickY / STICK_MAX.toFloat()) * radius * 0.55f
            fillPaint.color = Color.argb(fillPaint.alpha, 230, 230, 236)
            canvas.drawCircle(knobX, knobY, radius * 0.36f, fillPaint)
        } else {
            canvas.drawCircle(centerX, centerY, radius, fillPaint)
            canvas.drawCircle(centerX, centerY, radius, strokePaint)
        }

        val label = shortLabel(control.target)
        textPaint.alpha = if (control.visible) 255 else 120
        textPaint.textSize = (radius * 0.48f).coerceIn(12f, 34f)
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        canvas.drawText(label, centerX, centerY - textBounds.exactCenterY(), textPaint)
    }

    private fun findControlAt(x: Float, y: Float, includeInvisible: Boolean): TouchControl? {
        var best: TouchControl? = null
        var bestDistance = Float.MAX_VALUE
        layout.controls.asReversed().forEach { control ->
            if (!includeInvisible && !control.visible) return@forEach
            val centerX = control.x * width
            val centerY = control.y * height
            val radius = controlRadius(control)
            val distance = hypot(x - centerX, y - centerY)
            if (distance <= radius && distance < bestDistance) {
                best = control
                bestDistance = distance
            }
        }
        return best
    }

    private fun updateControlPosition(controlId: String, x: Float, y: Float) {
        val updated = layout.controls.map { control ->
            if (control.id == controlId) control.copy(x = x, y = y) else control
        }
        layout = TouchLayout(updated)
        updated.firstOrNull { it.id == controlId }?.let { onSelectionChanged?.invoke(it) }
        onLayoutEdited?.invoke(layout)
    }

    private fun controlRadius(control: TouchControl): Float =
        (control.size * min(width, height).toFloat().coerceAtLeast(1f)) / 2f

    private fun notifyTouchState(buttonMask: Int, stickX: Int, stickY: Int) {
        onTouchStateChanged?.invoke(buttonMask, stickX, stickY)
    }

    private fun colorFor(target: N64Target): Int = when (target) {
        N64Target.A_BUTTON -> Color.rgb(0, 86, 234)
        N64Target.B_BUTTON -> Color.rgb(0, 192, 99)
        N64Target.START -> Color.rgb(217, 49, 49)
        N64Target.C_UP,
        N64Target.C_DOWN,
        N64Target.C_LEFT,
        N64Target.C_RIGHT -> Color.rgb(254, 223, 90)
        N64Target.L_TRIGGER,
        N64Target.R_TRIGGER -> Color.rgb(92, 98, 112)
        N64Target.Z_TRIGGER -> Color.rgb(96, 78, 160)
        N64Target.ANALOG_STICK -> Color.rgb(86, 92, 110)
        else -> Color.rgb(50, 54, 64)
    }

    private fun shortLabel(target: N64Target): String = when (target) {
        N64Target.ANALOG_STICK -> "STICK"
        N64Target.DPAD_UP -> "UP"
        N64Target.DPAD_DOWN -> "DN"
        N64Target.DPAD_LEFT -> "LT"
        N64Target.DPAD_RIGHT -> "RT"
        N64Target.START -> "START"
        N64Target.A_BUTTON -> "A"
        N64Target.B_BUTTON -> "B"
        N64Target.L_TRIGGER -> "L"
        N64Target.R_TRIGGER -> "R"
        N64Target.Z_TRIGGER -> "Z"
        N64Target.C_UP -> "C-UP"
        N64Target.C_DOWN -> "C-DN"
        N64Target.C_LEFT -> "C-LT"
        N64Target.C_RIGHT -> "C-RT"
    }

    companion object {
        private const val STICK_MAX = 80
    }
}
