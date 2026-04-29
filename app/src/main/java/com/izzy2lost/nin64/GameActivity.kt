package com.izzy2lost.nin64

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.util.Log
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private val logTag = "Nin64Game"

    private lateinit var rootContainer: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var touchControlsView: TouchControlsView
    private lateinit var visibilityToggle: ImageButton
    private lateinit var menuButton: ImageButton
    private lateinit var fastForwardButton: ImageButton
    private lateinit var controlsConfig: ControlsConfig
    private var touchControlsVisible = true
    private var fastForwardEnabled = false
    private var menuOverlay: View? = null
    private var currentSlot = 1

    @Volatile private var running = false
    @Volatile private var paused = false
    private var emulationThread: Thread? = null
    private val emulationTaskLock = Object()
    private val emulationTasks = ArrayDeque<() -> Unit>()
    private var keyButtonMask = 0
    private var axisButtonMask = 0
    private var touchButtonMask = 0
    private var analogStickX = 0
    private var analogStickY = 0
    private var touchAnalogStickX = 0
    private var touchAnalogStickY = 0
    private var startComboPressed = false
    private var selectComboPressed = false

    private val rootPath: String get() = intent.getStringExtra(EXTRA_ROOT_PATH) ?: ""
    private val romPath: String get() = intent.getStringExtra(EXTRA_ROM_PATH) ?: ""
    private val useTexturePack: Boolean get() = intent.getBooleanExtra(EXTRA_USE_TEXTURE_PACK, false)
    private val disableExpansionPak: Boolean get() = intent.getBooleanExtra(EXTRA_DISABLE_EXPANSION_PAK, false)
    private val romPreferenceKey: String? get() = intent.getStringExtra(EXTRA_ROM_PREFERENCE_KEY)
    private val romCrc: String? get() = intent.getStringExtra(EXTRA_ROM_CRC)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableNin64EdgeToEdge(immersive = true)
        controlsConfig = ControlsRepository.load(this, romPreferenceKey)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.RGBX_8888)
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        rootContainer.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        touchControlsView = TouchControlsView(this).apply {
            layout = controlsConfig.touchLayout
            onTouchStateChanged = { buttonMask, stickX, stickY ->
                touchButtonMask = buttonMask
                touchAnalogStickX = stickX
                touchAnalogStickY = stickY
                pushControllerState()
            }
        }
        rootContainer.addView(
            touchControlsView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        val density = resources.displayMetrics.density
        val btnSize = (44 * density).toInt()
        val btnMargin = (14 * density).toInt()
        visibilityToggle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_visibility)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(160, 14, 15, 20))
            }
            setOnClickListener { toggleTouchControls() }
        }
        rootContainer.addView(
            visibilityToggle,
            FrameLayout.LayoutParams(btnSize, btnSize, Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = btnMargin
                marginEnd = btnMargin
            }
        )
        visibilityToggle.applySafeAreaMargins(applyEnd = true, applyBottom = true)

        menuButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 14, 15, 20))
            }
            contentDescription = getString(R.string.in_game_menu)
            setOnClickListener { showInGameMenu() }
        }
        rootContainer.addView(
            menuButton,
            FrameLayout.LayoutParams(btnSize, btnSize, Gravity.TOP or Gravity.START).apply {
                topMargin = btnMargin
                marginStart = btnMargin
            }
        )
        menuButton.applySafeAreaMargins(applyStart = true, applyTop = true)

        fastForwardButton = ImageButton(this).apply {
            setImageResource(R.drawable.fast_forward_24px)
            setColorFilter(Color.WHITE)
            background = circularButtonBackground(Color.argb(170, 14, 15, 20))
            contentDescription = getString(R.string.in_game_fast_forward)
            setOnClickListener { setFastForwardEnabled(!fastForwardEnabled) }
        }
        rootContainer.addView(
            fastForwardButton,
            FrameLayout.LayoutParams(btnSize, btnSize, Gravity.TOP or Gravity.END).apply {
                topMargin = btnMargin
                marginEnd = btnMargin
            }
        )
        fastForwardButton.applySafeAreaMargins(applyTop = true, applyEnd = true)

        setContentView(rootContainer)
        surfaceView.holder.addCallback(this)
        pushControllerState()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (rootPath.isEmpty() || romPath.isEmpty()) {
            finish()
            return
        }
        Log.i(logTag, "surfaceCreated ${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()} rom=$romPath")
        NativeBridge.setSurface(holder.surface, holder.surfaceFrame.width(), holder.surfaceFrame.height())
        applyEmulatorSettings(holder.surfaceFrame.height())
        startEmulation()
    }

    private fun applyEmulatorSettings(surfaceHeight: Int) {
        val prefs = getSharedPreferences("nin64_prefs", MODE_PRIVATE)
        prefs.getString("mupen64plus-aspect", null)?.let {
            NativeBridge.setOption("mupen64plus-aspect", it)
        }

        val resPref = prefs.getString("mupen64plus-EnableNativeResFactor", null)
        val factor = if (resPref.isNullOrEmpty() || resPref == "0") {
            ((surfaceHeight + 120) / 240).coerceIn(1, 8)
        } else {
            resPref.toIntOrNull()?.coerceIn(1, 8) ?: 1
        }
        NativeBridge.setOption("mupen64plus-EnableNativeResFactor", factor.toString())
        applyPerGameEmulatorSettings()
        Log.i(logTag, "applyEmulatorSettings surfaceHeight=$surfaceHeight resFactor=$factor")
    }

    private fun applyPerGameEmulatorSettings() {
        NativeBridge.setOption("mupen64plus-txHiresEnable", if (useTexturePack) "True" else "False")
        NativeBridge.setOption("mupen64plus-txHiresFullAlphaChannel", if (useTexturePack) "True" else "False")
        NativeBridge.setOption("mupen64plus-EnableEnhancedHighResStorage", if (useTexturePack) "True" else "False")
        NativeBridge.setOption("mupen64plus-CorrectTexrectCoords", if (useTexturePack) "Auto" else "Off")
        NativeBridge.setOption("mupen64plus-GLideN64IniBehaviour", if (useTexturePack) "early" else "late")
        NativeBridge.setOption("mupen64plus-ForceDisableExtraMem", if (disableExpansionPak) "True" else "False")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(logTag, "surfaceChanged ${width}x${height} format=$format")
        NativeBridge.setSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(logTag, "surfaceDestroyed")
        stopEmulation()
        NativeBridge.clearSurface()
    }

    override fun onResume() {
        super.onResume()
        surfaceView.requestFocus()
        updateSurfaceLayoutForCurrentFrame()
    }

    private fun startEmulation() {
        running = true
        emulationThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            try {
                Log.i(logTag, "emulation thread boot start")
                val bootResult = NativeBridge.bootRomForPlay(rootPath, romPath)
                Log.i(logTag, "boot result=$bootResult")
                if (bootResult != "booted") {
                    running = false
                    runOnUiThread {
                        Toast.makeText(this, bootResult, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@Thread
                }

                applyEnabledCheats()
                updateSurfaceLayoutForCurrentFrame()
                emulationLoop()
            } finally {
                Log.i(logTag, "emulation thread shutting down")
                NativeBridge.shutdownSession()
            }
        }.apply {
            name = "Nin64-Emu"
            start()
        }
    }

    private fun stopEmulation() {
        setFastForwardEnabled(false)
        running = false
        emulationThread?.join(3000)
        emulationThread = null
    }

    private fun applyEnabledCheats() {
        val codeLines = CheatRepository.enabledCheatCodeLines(this, romPreferenceKey, romCrc)
        NativeBridge.setCheats(codeLines.toTypedArray())
        Log.i(logTag, "applied ${codeLines.size} cheat(s)")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleMenuComboKey(event)) return true

        val button = mapGamepadKeyToN64Button(event.keyCode)
        if (button == 0 || !isGamepadSource(event.source)) return super.dispatchKeyEvent(event)

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
        analogStickX = scaleStickAxes(event, device, controlsConfig.gamepadMapping.analogXAxes, false)
        analogStickY = scaleStickAxes(event, device, controlsConfig.gamepadMapping.analogYAxes, true)
        axisButtonMask = mapGamepadAxesToN64ButtonMask(event, device)
        pushControllerState()
        return true
    }

    private fun emulationLoop() {
        while (running) {
            runPendingEmulationTasks()
            if (paused) { Thread.sleep(16); continue }
            NativeBridge.runFrame(OPS_PER_CHUNK)
        }
    }

    private fun enqueueEmulationTask(task: () -> Unit) {
        synchronized(emulationTaskLock) { emulationTasks.add(task) }
    }

    private fun runPendingEmulationTasks() {
        while (true) {
            val task = synchronized(emulationTaskLock) {
                if (emulationTasks.isEmpty()) null else emulationTasks.removeFirst()
            } ?: return
            task()
        }
    }

    private fun updateSurfaceLayoutForCurrentFrame() {
        val frameWidth = NativeBridge.getFrameWidth()
        val frameHeight = NativeBridge.getFrameHeight()
        if (frameWidth <= 0 || frameHeight <= 0) return

        runOnUiThread {
            val parentWidth = rootContainer.width
            val parentHeight = rootContainer.height
            if (parentWidth <= 0 || parentHeight <= 0) {
                rootContainer.post { updateSurfaceLayoutForCurrentFrame() }
                return@runOnUiThread
            }
            val scale = min(parentWidth.toFloat() / frameWidth, parentHeight.toFloat() / frameHeight)
            val targetWidth = (frameWidth * scale).roundToInt().coerceAtLeast(2)
            val targetHeight = (frameHeight * scale).roundToInt().coerceAtLeast(2)
            val currentParams = surfaceView.layoutParams as FrameLayout.LayoutParams
            if (currentParams.width == targetWidth && currentParams.height == targetHeight && currentParams.gravity == Gravity.CENTER) return@runOnUiThread
            Log.i(logTag, "updateSurfaceLayout frame=${frameWidth}x${frameHeight} target=${targetWidth}x${targetHeight} parent=${parentWidth}x${parentHeight}")
            surfaceView.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
        }
    }

    private fun pushControllerState() {
        val gamepadMag = analogStickX * analogStickX + analogStickY * analogStickY
        val touchMag = touchAnalogStickX * touchAnalogStickX + touchAnalogStickY * touchAnalogStickY
        val stickX = if (touchMag > gamepadMag) touchAnalogStickX else analogStickX
        val stickY = if (touchMag > gamepadMag) touchAnalogStickY else analogStickY
        NativeBridge.setControllerState(keyButtonMask or axisButtonMask or touchButtonMask, stickX, stickY)
    }

    private fun mapGamepadKeyToN64Button(keyCode: Int): Int {
        var mask = 0
        controlsConfig.gamepadMapping.targetBindings.forEach { (target, bindings) ->
            if (bindings.any { it.type == BindingType.KEY && it.code == keyCode }) mask = mask or target.buttonMask
        }
        return mask
    }

    private fun mapGamepadAxesToN64ButtonMask(event: MotionEvent, device: InputDevice): Int {
        var mask = 0
        controlsConfig.gamepadMapping.targetBindings.forEach { (target, bindings) ->
            for (binding in bindings) {
                if (binding.type != BindingType.AXIS) continue
                val value = getAxisValue(event, device, binding.code)
                val pressed = if (binding.direction < 0) value <= -AXIS_BUTTON_THRESHOLD else value >= AXIS_BUTTON_THRESHOLD
                if (pressed) { mask = mask or target.buttonMask; break }
            }
        }
        return mask
    }

    private fun isGamepadSource(source: Int) =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun scaleStickAxes(event: MotionEvent, device: InputDevice, axes: List<Int>, positiveUp: Boolean): Int {
        val value = getAxisValue(event, device, axes)
        return ((if (positiveUp) -value else value) * STICK_MAX).roundToInt().coerceIn(-STICK_MAX, STICK_MAX)
    }

    private fun getAxisValue(event: MotionEvent, device: InputDevice, axes: List<Int>): Float {
        var best = 0f; var bestMag = 0f
        for (axis in axes) {
            val v = getAxisValue(event, device, axis)
            val m = abs(v)
            if (m > bestMag) { bestMag = m; best = v }
        }
        return best
    }

    private fun getAxisValue(event: MotionEvent, device: InputDevice, vararg axes: Int): Float {
        var best = 0f; var bestMag = 0f
        for (axis in axes) {
            val range = getMotionRange(device, axis, event.source) ?: continue
            val value = event.getAxisValue(axis)
            val centered = if (abs(value) > range.flat) value else 0f
            val m = abs(centered)
            if (m > bestMag) { bestMag = m; best = centered }
        }
        return best
    }

    private fun getMotionRange(device: InputDevice, axis: Int, source: Int): InputDevice.MotionRange? =
        device.getMotionRange(axis, source)
            ?: device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            ?: device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
            ?: device.getMotionRange(axis)

    private fun toggleTouchControls() {
        touchControlsVisible = !touchControlsVisible
        touchControlsView.visibility = if (touchControlsVisible) View.VISIBLE else View.GONE
        visibilityToggle.setImageResource(if (touchControlsVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
        if (!touchControlsVisible) {
            touchButtonMask = 0; touchAnalogStickX = 0; touchAnalogStickY = 0
            pushControllerState()
        }
    }

    private fun setFastForwardEnabled(enabled: Boolean) {
        if (fastForwardEnabled == enabled) return
        fastForwardEnabled = enabled
        NativeBridge.setFastForwardEnabled(enabled)
        updateFastForwardButton()
    }

    private fun updateFastForwardButton() {
        if (!::fastForwardButton.isInitialized) return
        fastForwardButton.setColorFilter(if (fastForwardEnabled) Color.rgb(32, 34, 40) else Color.WHITE)
        fastForwardButton.background = circularButtonBackground(
            if (fastForwardEnabled) Color.argb(230, 254, 223, 90) else Color.argb(170, 14, 15, 20)
        )
        fastForwardButton.contentDescription = getString(
            if (fastForwardEnabled) R.string.in_game_fast_forward_enabled else R.string.in_game_fast_forward
        )
    }

    private fun circularButtonBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun handleMenuComboKey(event: KeyEvent): Boolean {
        if (!isGamepadSource(event.source)) return false
        val isStart = event.keyCode == KeyEvent.KEYCODE_BUTTON_START
        val isSelect = event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
        if (!isStart && !isSelect) return false
        var openedMenu = false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (isStart) startComboPressed = true
                if (isSelect) selectComboPressed = true
                if (startComboPressed && selectComboPressed) { showInGameMenu(); openedMenu = true }
            }
            KeyEvent.ACTION_UP -> {
                if (isStart) startComboPressed = false
                if (isSelect) selectComboPressed = false
            }
        }
        return isSelect || openedMenu
    }

    private fun isNightMode(): Boolean {
        val mask = Configuration.UI_MODE_NIGHT_MASK
        return (resources.configuration.uiMode and mask) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun showInGameMenu() {
        if (menuOverlay != null) return
        paused = true
        clearControllerInputs()

        val isNight  = isNightMode()
        val panelBg  = if (isNight) Color.parseColor("#1E1F28") else Color.parseColor("#F2F2F7")
        val onSurf   = if (isNight) Color.WHITE else Color.parseColor("#212121")
        val muted    = if (isNight) Color.parseColor("#AAAAAA") else Color.parseColor("#757575")
        val blue     = Color.parseColor("#0056EA")
        val green    = Color.parseColor("#00C063")
        val red      = Color.parseColor("#D93131")

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            isClickable = true
            isFocusable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 18.dp, 20.dp, 20.dp)
            background = GradientDrawable().apply {
                setColor(panelBg)
                cornerRadius = 14f * resources.displayMetrics.density
            }
        }

        val mp = LinearLayout.LayoutParams.MATCH_PARENT
        val wc = LinearLayout.LayoutParams.WRAP_CONTENT
        fun mwp() = LinearLayout.LayoutParams(mp, wc)

        // Title
        panel.addView(TextView(this).apply {
            text = getString(R.string.in_game_menu)
            setTextColor(onSurf)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, mwp().apply { bottomMargin = 10.dp })

        // Thumbnail
        val thumbnail = ImageView(this).apply {
            setBackgroundColor(Color.rgb(22, 23, 30))
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = false
        }
        panel.addView(thumbnail, LinearLayout.LayoutParams(180.dp, 112.dp).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = 6.dp
        })

        // Status
        val statusText = TextView(this).apply {
            setTextColor(muted)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        panel.addView(statusText, mwp().apply { bottomMargin = 2.dp })

        // Slot label
        val slotText = TextView(this).apply {
            setTextColor(onSurf)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val loadBtn = coloredMenuButton(getString(R.string.in_game_load_state), green) {
            loadStateFromMenu(statusText)
        }

        fun refreshSlot() {
            slotText.text = slotLabel(currentSlot)
            statusText.text = stateStatusText(currentSlot)
            refreshThumbnail(thumbnail, loadBtn)
        }

        // Slot selector row
        val slotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        slotRow.addView(
            slotNavButton("‹", onSurf) { if (currentSlot > 1) { currentSlot--; refreshSlot() } },
            LinearLayout.LayoutParams(44.dp, 44.dp)
        )
        slotRow.addView(slotText, LinearLayout.LayoutParams(0, wc, 1f))
        slotRow.addView(
            slotNavButton("›", onSurf) { if (currentSlot < SAVE_SLOT_COUNT) { currentSlot++; refreshSlot() } },
            LinearLayout.LayoutParams(44.dp, 44.dp)
        )
        panel.addView(slotRow, mwp().apply { topMargin = 4.dp; bottomMargin = 4.dp })

        // Action buttons
        panel.addView(coloredMenuButton(getString(R.string.in_game_resume), blue) { resumeGame() })
        panel.addView(coloredMenuButton(getString(R.string.in_game_save_state), green) {
            saveStateFromMenu(statusText, thumbnail, loadBtn)
        })
        panel.addView(loadBtn)
        panel.addView(coloredMenuButton(getString(R.string.in_game_quit), red) { finish() })

        thumbnail.setOnClickListener {
            if (stateThumbnailFile(currentSlot).isFile) showExpandedThumbnail(stateThumbnailFile(currentSlot))
        }

        val maxMenuHeight = ((rootContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) - 48.dp)
            .coerceAtLeast(260.dp)
        val scrollContainer = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                panel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        overlay.addView(scrollContainer, FrameLayout.LayoutParams(300.dp, maxMenuHeight, Gravity.CENTER))
        menuOverlay = overlay
        rootContainer.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        refreshSlot()
    }

    private fun resumeGame() {
        menuOverlay?.let(rootContainer::removeView)
        menuOverlay = null
        paused = false
        startComboPressed = false
        selectComboPressed = false
    }

    private fun saveStateFromMenu(statusText: TextView, thumbnail: ImageView, loadButton: MaterialButton) {
        val slot = currentSlot
        val stateFile = stateFile(slot)
        stateFile.parentFile?.mkdirs()
        statusText.text = getString(R.string.in_game_saving_state)
        enqueueEmulationTask {
            val result = NativeBridge.saveState(stateFile.absolutePath)
            runOnUiThread {
                if (result == "saved") {
                    captureStateThumbnail(stateThumbnailFile(slot)) { saved ->
                        statusText.text = if (saved) getString(R.string.in_game_state_saved) else getString(R.string.in_game_state_saved_no_thumbnail)
                        refreshThumbnail(thumbnail, loadButton)
                    }
                } else {
                    statusText.text = result
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadStateFromMenu(statusText: TextView) {
        val stateFile = stateFile(currentSlot)
        if (!stateFile.isFile) { statusText.text = getString(R.string.in_game_no_save_state); return }
        statusText.text = getString(R.string.in_game_loading_state)
        enqueueEmulationTask {
            val result = NativeBridge.loadState(stateFile.absolutePath)
            runOnUiThread {
                if (result == "loaded") {
                    statusText.text = getString(R.string.in_game_state_loaded)
                    resumeGame()
                } else {
                    statusText.text = result
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun captureStateThumbnail(target: File, callback: (Boolean) -> Unit) {
        val width = surfaceView.width
        val height = surfaceView.height
        if (width <= 0 || height <= 0) { callback(false); return }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surfaceView, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val thumb = Bitmap.createScaledBitmap(bitmap, 360, 224, true)
                    val saved = runCatching {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 95, it) }
                    }.getOrDefault(false)
                    if (thumb !== bitmap) thumb.recycle()
                    bitmap.recycle()
                    callback(saved)
                } else {
                    bitmap.recycle()
                    callback(false)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            callback(false)
        }
    }

    private fun refreshThumbnail(thumbnail: ImageView, loadButton: MaterialButton) {
        val thumbFile = stateThumbnailFile(currentSlot)
        val bitmap = if (thumbFile.isFile) BitmapFactory.decodeFile(thumbFile.absolutePath) else null
        thumbnail.setImageBitmap(bitmap)
        thumbnail.alpha = if (bitmap == null) 0.35f else 1f
        thumbnail.contentDescription = if (bitmap == null) getString(R.string.in_game_no_save_state) else getString(R.string.in_game_state_thumbnail)
        loadButton.isEnabled = stateFile(currentSlot).isFile
    }

    private fun showExpandedThumbnail(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val expanded = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(230, 0, 0, 0))
            isClickable = true
            setOnClickListener { rootContainer.removeView(this) }
        }
        expanded.addView(
            ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
                .apply { setMargins(24.dp, 24.dp, 24.dp, 24.dp) }
        )
        rootContainer.addView(expanded, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun coloredMenuButton(label: String, color: Int, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            setTextColor(color)
            strokeColor = ColorStateList.valueOf(color)
            rippleColor = ColorStateList.valueOf(color)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = 8.dp }
        }

    private fun slotNavButton(symbol: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            setTextColor(color)
            textSize = 24f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun slotLabel(slot: Int): String {
        val dot = if (stateFile(slot).isFile) "●" else "○"
        return "Slot $slot  $dot"
    }

    private fun clearControllerInputs() {
        keyButtonMask = 0; axisButtonMask = 0; touchButtonMask = 0
        analogStickX = 0; analogStickY = 0; touchAnalogStickX = 0; touchAnalogStickY = 0
        pushControllerState()
    }

    private fun stateStatusText(slot: Int = currentSlot): String =
        if (stateFile(slot).isFile) getString(R.string.in_game_save_state_available) else getString(R.string.in_game_no_save_state)

    private fun stateFile(slot: Int = currentSlot): File =
        File(stateDirectory(), "${stateBaseName()}_slot${slot}.state")

    private fun stateThumbnailFile(slot: Int = currentSlot): File =
        File(stateDirectory(), "${stateBaseName()}_slot${slot}.png")

    private fun stateDirectory(): File =
        File(rootPath.ifBlank { filesDir.absolutePath }, "Mupen64plus/states").apply { mkdirs() }

    private fun stateBaseName(): String {
        val raw = romPreferenceKey ?: File(romPath).nameWithoutExtension.ifBlank { "game" }
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(96)
        return safe.ifBlank { "game" }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).roundToInt()

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (menuOverlay != null) { resumeGame(); return }
        stopEmulation()
        super.onBackPressed()
    }

    override fun onDestroy() {
        keyButtonMask = 0; axisButtonMask = 0; touchButtonMask = 0
        analogStickX = 0; analogStickY = 0; touchAnalogStickX = 0; touchAnalogStickY = 0
        pushControllerState()
        stopEmulation()
        NativeBridge.clearSurface()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ROOT_PATH = "extra_root_path"
        private const val EXTRA_ROM_PATH = "extra_rom_path"
        private const val EXTRA_USE_TEXTURE_PACK = "extra_use_texture_pack"
        private const val EXTRA_DISABLE_EXPANSION_PAK = "extra_disable_expansion_pak"
        private const val EXTRA_ROM_PREFERENCE_KEY = "extra_rom_preference_key"
        private const val EXTRA_ROM_CRC = "extra_rom_crc"
        private const val OPS_PER_CHUNK = 2_000_000
        private const val STICK_MAX = 80
        private const val AXIS_BUTTON_THRESHOLD = 0.45f
        private const val SAVE_SLOT_COUNT = 10

        fun launch(
            context: Context,
            rootPath: String,
            romPath: String,
            useTexturePack: Boolean = false,
            disableExpansionPak: Boolean = false,
            romPreferenceKey: String? = null,
            romCrc: String? = null,
        ) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_ROOT_PATH, rootPath)
                    .putExtra(EXTRA_ROM_PATH, romPath)
                    .putExtra(EXTRA_USE_TEXTURE_PACK, useTexturePack)
                    .putExtra(EXTRA_DISABLE_EXPANSION_PAK, disableExpansionPak)
                    .putExtra(EXTRA_ROM_PREFERENCE_KEY, romPreferenceKey)
                    .putExtra(EXTRA_ROM_CRC, romCrc)
            )
        }
    }
}
