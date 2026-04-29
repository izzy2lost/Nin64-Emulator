package com.izzy2lost.nin64

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class GamepadMappingActivity : AppCompatActivity() {
    private var romKey: String? = null
    private var currentMapping = GamepadMapping.default()
    private var capturingTarget: N64Target? = null
    private val rowViews = mutableMapOf<N64Target, LinearLayout>()
    private val bindingLabels = mutableMapOf<N64Target, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableNin64EdgeToEdge()
        romKey = intent.getStringExtra(EXTRA_ROM_KEY)
        currentMapping = ControlsRepository.loadGamepadMapping(this, romKey)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_COLOR)
        }
        val topBar = createTopBar()
        root.addView(topBar)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 8.dp, 16.dp, 24.dp)
        }
        GROUPS.forEach { (header, targets) ->
            container.addView(sectionHeader(header))
            targets.forEach { target ->
                val row = buildRow(target)
                rowViews[target] = row
                container.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = 4.dp })
            }
        }

        val scrollView = ScrollView(this).apply { addView(container) }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
        topBar.applyTopBarInsets()
        scrollView.applyBottomContentInsets()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = capturingTarget
        if (target == null || event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            cancelCapture()
            return true
        }
        if (!isGamepadSource(event.source)) return super.dispatchKeyEvent(event)
        if (target != N64Target.ANALOG_STICK) {
            currentMapping = currentMapping.withBinding(target, GamepadBinding.key(event.keyCode))
            saveAndStopCapture()
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val target = capturingTarget ?: return super.dispatchGenericMotionEvent(event)
        if (event.action != MotionEvent.ACTION_MOVE || !isGamepadSource(event.source)) {
            return super.dispatchGenericMotionEvent(event)
        }
        val device = event.device ?: return super.dispatchGenericMotionEvent(event)
        val detected = detectMovedAxis(event, device) ?: return true
        currentMapping = if (target == N64Target.ANALOG_STICK) {
            val pair = analogPairForAxis(detected.axis)
            currentMapping.withAnalogAxes(pair.first, pair.second)
        } else {
            currentMapping.withBinding(target, GamepadBinding.axis(detected.axis, detected.direction))
        }
        saveAndStopCapture()
        return true
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (capturingTarget != null) cancelCapture() else super.onBackPressed()
    }

    private fun createTopBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dp, 0, 16.dp, 0)
        setBackgroundColor(SURFACE_COLOR)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dp)

        addView(
            ImageButton(this@GamepadMappingActivity).apply {
                setImageResource(R.drawable.ic_back)
                background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                    .useAndReturnDrawable(0)
                contentDescription = getString(R.string.navigate_up)
                setColorFilter(Color.WHITE)
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(48.dp, 48.dp),
        )

        addView(
            TextView(this@GamepadMappingActivity).apply {
                text = scopedTitle(getString(R.string.controls_controller_mapping))
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(8.dp, 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        addView(
            TextView(this@GamepadMappingActivity).apply {
                text = getString(R.string.done)
                setTextColor(DONE_COLOR)
                textSize = 15f
                background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                    .useAndReturnDrawable(0)
                isClickable = true
                isFocusable = true
                setPadding(12.dp, 8.dp, 4.dp, 8.dp)
                setOnClickListener { finish() }
            },
        )
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        setTextColor(Color.argb(130, 255, 255, 255))
        textSize = 11f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        letterSpacing = 0.12f
        setPadding(4.dp, 20.dp, 4.dp, 8.dp)
    }

    private fun buildRow(target: N64Target): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            background = cardDrawable(false)
            isClickable = true
            isFocusable = true
            setOnClickListener { startCapturing(target) }
        }

        row.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accentColorFor(target))
                }
            },
            LinearLayout.LayoutParams(10.dp, 10.dp).apply {
                marginEnd = 14.dp
                gravity = Gravity.CENTER_VERTICAL
            },
        )

        row.addView(
            TextView(this).apply {
                text = target.label
                setTextColor(Color.WHITE)
                textSize = 15f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val label = TextView(this).apply {
            text = bindingSummary(target)
            setTextColor(DIM_TEXT_COLOR)
            textSize = 13f
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = 180.dp
        }
        bindingLabels[target] = label
        row.addView(label)
        return row
    }

    private fun startCapturing(target: N64Target) {
        capturingTarget?.let { updateRowIdle(it) }
        capturingTarget = target
        rowViews[target]?.background = cardDrawable(true)
        bindingLabels[target]?.apply {
            text = if (target == N64Target.ANALOG_STICK) getString(R.string.controls_capture_stick)
                   else getString(R.string.controls_capture_button)
            setTextColor(CAPTURE_COLOR)
        }
    }

    private fun cancelCapture() {
        capturingTarget?.let { updateRowIdle(it) }
        capturingTarget = null
    }

    private fun saveAndStopCapture() {
        val target = capturingTarget ?: return
        ControlsRepository.saveGamepadMapping(this, romKey, currentMapping)
        capturingTarget = null
        updateRowIdle(target)
    }

    private fun updateRowIdle(target: N64Target) {
        rowViews[target]?.background = cardDrawable(false)
        bindingLabels[target]?.apply {
            text = bindingSummary(target)
            setTextColor(DIM_TEXT_COLOR)
        }
    }

    private fun bindingSummary(target: N64Target): String {
        if (target == N64Target.ANALOG_STICK) {
            return getString(
                R.string.controls_controller_analog_summary,
                currentMapping.analogXAxes.joinToString("/") { axisName(it) },
                currentMapping.analogYAxes.joinToString("/") { axisName(it) },
            )
        }
        val bindings = currentMapping.targetBindings[target].orEmpty()
        if (bindings.isEmpty()) return getString(R.string.controls_unbound)
        return bindings.joinToString(", ") { binding ->
            when (binding.type) {
                BindingType.KEY -> keyName(binding.code)
                BindingType.AXIS -> "${axisName(binding.code)} ${if (binding.direction < 0) "−" else "+"}"
            }
        }
    }

    private fun cardDrawable(active: Boolean) = GradientDrawable().apply {
        setColor(if (active) ROW_ACTIVE_COLOR else ROW_COLOR)
        cornerRadius = 10f * resources.displayMetrics.density
    }

    private fun detectMovedAxis(event: MotionEvent, device: InputDevice): AxisDetection? {
        var best: AxisDetection? = null
        var bestMagnitude = 0f
        for (range in device.motionRanges) {
            if ((range.source and event.source) == 0) continue
            val value = event.getAxisValue(range.axis)
            val centered = if (abs(value) > maxOf(range.flat, AXIS_CAPTURE_THRESHOLD)) value else 0f
            val magnitude = abs(centered)
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                best = AxisDetection(range.axis, if (centered < 0f) -1 else 1)
            }
        }
        return best
    }

    private fun analogPairForAxis(axis: Int): Pair<Int, Int> = when (axis) {
        MotionEvent.AXIS_RX, MotionEvent.AXIS_RY -> MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ -> MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        else -> MotionEvent.AXIS_X to MotionEvent.AXIS_Y
    }

    private fun isGamepadSource(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun keyName(keyCode: Int) =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')

    private fun axisName(axis: Int) =
        MotionEvent.axisToString(axis).removePrefix("AXIS_").replace('_', ' ')

    private fun scopedTitle(base: String): String {
        val gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE)
        return if (gameTitle.isNullOrBlank()) base else "$base — $gameTitle"
    }

    private fun accentColorFor(target: N64Target): Int = when (target) {
        N64Target.A_BUTTON -> Color.rgb(0, 86, 234)
        N64Target.B_BUTTON -> Color.rgb(0, 192, 99)
        N64Target.START -> Color.rgb(217, 49, 49)
        N64Target.C_UP, N64Target.C_DOWN,
        N64Target.C_LEFT, N64Target.C_RIGHT -> Color.rgb(254, 223, 90)
        N64Target.L_TRIGGER, N64Target.R_TRIGGER -> Color.rgb(92, 98, 112)
        N64Target.Z_TRIGGER -> Color.rgb(96, 78, 160)
        N64Target.ANALOG_STICK -> Color.rgb(86, 92, 110)
        else -> Color.rgb(80, 86, 100)
    }

    private fun android.content.res.TypedArray.useAndReturnDrawable(index: Int): android.graphics.drawable.Drawable? =
        try { getDrawable(index) } finally { recycle() }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private data class AxisDetection(val axis: Int, val direction: Int)

    companion object {
        private const val EXTRA_ROM_KEY = "extra_rom_key"
        private const val EXTRA_GAME_TITLE = "extra_game_title"
        private const val AXIS_CAPTURE_THRESHOLD = 0.45f

        private val BG_COLOR = Color.rgb(14, 15, 20)
        private val SURFACE_COLOR = Color.rgb(20, 22, 30)
        private val ROW_COLOR = Color.rgb(24, 26, 36)
        private val ROW_ACTIVE_COLOR = Color.rgb(38, 42, 62)
        private val DIM_TEXT_COLOR = Color.argb(140, 255, 255, 255)
        private val CAPTURE_COLOR = Color.rgb(255, 210, 80)
        private val DONE_COLOR = Color.argb(220, 120, 160, 255)

        private val GROUPS = listOf(
            "ANALOG" to listOf(N64Target.ANALOG_STICK),
            "FACE BUTTONS" to listOf(N64Target.A_BUTTON, N64Target.B_BUTTON, N64Target.START),
            "C BUTTONS" to listOf(N64Target.C_UP, N64Target.C_DOWN, N64Target.C_LEFT, N64Target.C_RIGHT),
            "TRIGGERS" to listOf(N64Target.L_TRIGGER, N64Target.R_TRIGGER, N64Target.Z_TRIGGER),
            "D-PAD" to listOf(N64Target.DPAD_UP, N64Target.DPAD_DOWN, N64Target.DPAD_LEFT, N64Target.DPAD_RIGHT),
        )

        fun launch(context: Context, romKey: String? = null, gameTitle: String? = null) {
            context.startActivity(
                Intent(context, GamepadMappingActivity::class.java)
                    .putExtra(EXTRA_ROM_KEY, romKey)
                    .putExtra(EXTRA_GAME_TITLE, gameTitle)
            )
        }
    }
}
