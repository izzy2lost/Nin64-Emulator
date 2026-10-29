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
import java.io.IOException
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
    private lateinit var splitViewButton: ImageButton
    private lateinit var controlsConfig: ControlsConfig
    private var touchControlsVisible = true
    private var fastForwardEnabled = false
    private var menuOverlay: View? = null
    private var currentSlot = 1
    private var lastInGameSaveFlushTimeMs = 0L
    private var pendingLaunchStateSlot = NO_LOAD_STATE_SLOT
    private var launchStateLoadAttempts = 0
    private var launchStateFramesUntilNextAttempt = 0

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
    private var cheevoPopups: Achievements.Popups? = null
    @Volatile private var netplayLink: Netplay.Session? = null
    @Volatile private var netplayLobby: Netplay.HostLobby? = null
    private var netplayWifi: Netplay.WifiLocks? = null
    private var netplayStatusView: View? = null
    private var netplayStallView: TextView? = null
    private var netplayDesyncReported = false
    /** In split-screen multiplayer: showing only this player's part, and the game's split. */
    private var splitViewZoomed = false
    private var splitLayout = Netplay.SplitLayout.TOP_BOTTOM
    @Volatile private var viewCrop = Netplay.SplitLayout.WHOLE
    private var surfaceBufferPinned = false
    private val cheevoHandler = Handler(Looper.getMainLooper())
    private val cheevoPoll = object : Runnable {
        override fun run() {
            cheevoPopups?.poll()
            cheevoHandler.postDelayed(this, 250)
        }
    }

    private val rootPath: String get() = intent.getStringExtra(EXTRA_ROOT_PATH) ?: ""
    private val romPath: String get() = intent.getStringExtra(EXTRA_ROM_PATH) ?: ""
    private val useTexturePack: Boolean get() = intent.getBooleanExtra(EXTRA_USE_TEXTURE_PACK, false)
    private val disableExpansionPak: Boolean get() = intent.getBooleanExtra(EXTRA_DISABLE_EXPANSION_PAK, false)
    private val romPreferenceKey: String? get() = intent.getStringExtra(EXTRA_ROM_PREFERENCE_KEY)
    private val romCrc: String? get() = intent.getStringExtra(EXTRA_ROM_CRC)
    private val launchStateSlot: Int get() = intent.getIntExtra(EXTRA_LOAD_STATE_SLOT, NO_LOAD_STATE_SLOT)
    private val netplayRole: Int get() = intent.getIntExtra(EXTRA_NETPLAY_ROLE, NETPLAY_NONE)
    private val netplayHost: String get() = intent.getStringExtra(EXTRA_NETPLAY_HOST) ?: ""
    private val netplayPort: Int get() = intent.getIntExtra(EXTRA_NETPLAY_PORT, Netplay.SESSION_PORT)
    private val romMd5: String get() = intent.getStringExtra(EXTRA_ROM_MD5) ?: ""
    private val gameTitle: String get() = intent.getStringExtra(EXTRA_GAME_TITLE) ?: File(romPath).nameWithoutExtension

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
        menuButton.applySafeAreaMargins(applyTop = true)

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

        // Multiplayer has no fast forward; its place is the split-screen zoom.
        splitViewButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_zoom_in)
            setColorFilter(Color.WHITE)
            background = circularButtonBackground(Color.argb(170, 14, 15, 20))
            contentDescription = getString(R.string.netplay_view_zoom_in)
            visibility = View.GONE
            setOnClickListener { setSplitViewZoomed(!splitViewZoomed) }
        }
        rootContainer.addView(
            splitViewButton,
            FrameLayout.LayoutParams(btnSize, btnSize, Gravity.TOP or Gravity.END).apply {
                topMargin = btnMargin
                marginEnd = btnMargin
            }
        )
        splitViewButton.applySafeAreaMargins(applyTop = true, applyEnd = true)

        setContentView(rootContainer)
        cheevoPopups = Achievements.Popups(this, rootContainer)
        if (netplayRole != NETPLAY_NONE) {
            // No fast forward or save states in multiplayer: both games must run the same frames.
            fastForwardButton.visibility = View.GONE
            netplayWifi = Netplay.WifiLocks(this).also { it.acquire() }
        }
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
        applyEmulatorSettings(holder.surfaceFrame.width(), holder.surfaceFrame.height())
        startEmulation()
    }

    private fun applyEmulatorSettings(surfaceWidth: Int, surfaceHeight: Int) {
        val displayOptions = DisplayOptionsRepository.load(this, romPreferenceKey)
        NativeBridge.setOption(DisplayOptionsRepository.PREF_ASPECT, displayOptions.aspect)

        val factor = DisplayOptionsRepository.nativeResolutionFactorForPreference(
            displayOptions.resolutionFactor,
            surfaceHeight,
        )
        NativeBridge.setOption(DisplayOptionsRepository.PREF_RES_FACTOR, factor.toString())
        applyPerGameEmulatorSettings()
        Log.i(
            logTag,
            "applyEmulatorSettings surface=${surfaceWidth}x$surfaceHeight aspect=${displayOptions.aspect} resFactor=$factor"
        )
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
        cheevoHandler.removeCallbacks(cheevoPoll)
        cheevoHandler.post(cheevoPoll)
    }

    override fun onPause() {
        cheevoHandler.removeCallbacks(cheevoPoll)
        requestInGameSaveFlush()
        super.onPause()
    }

    private fun startEmulation() {
        running = true
        emulationThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            var joining: Netplay.Joining? = null
            try {
                if (netplayRole == NETPLAY_GUEST) {
                    showNetplayStatus(getString(R.string.netplay_joining), cancellable = true)
                    joining = try {
                        Netplay.Joining.connect(netplayHost, netplayPort, romMd5)
                    } catch (e: Exception) {
                        failAndFinish(getString(R.string.netplay_join_failed, e.message ?: e.javaClass.simpleName))
                        return@Thread
                    }
                    // The host's emulation options, before the core reads them at boot.
                    joining.config.options.forEach { (key, value) -> NativeBridge.setOption(key, value) }
                }
                if (netplayRole != NETPLAY_NONE) {
                    Netplay.deterministicOptions.forEach { (key, value) -> NativeBridge.setOption(key, value) }
                }

                Log.i(logTag, "emulation thread boot start")
                val bootResult = NativeBridge.bootRomForPlay(rootPath, romPath, inGameSaveKey())
                Log.i(logTag, "boot result=$bootResult")
                if (bootResult != "booted") {
                    joining?.fail(bootResult)
                    failAndFinish(bootResult)
                    return@Thread
                }

                lastInGameSaveFlushTimeMs = System.currentTimeMillis()
                // Before any state load, so the state's achievement progress is restored with it.
                Achievements.beginForLoadedGame(this)
                when (netplayRole) {
                    NETPLAY_HOST -> {
                        applyEnabledCheats()
                        netplayLink = hostNetplaySession() ?: return@Thread
                    }
                    NETPLAY_GUEST -> {
                        val session = joining!!
                        if (!NativeBridge.setSaveMemory(session.config.saveMemory)) {
                            session.fail("save memory mismatch")
                            failAndFinish(getString(R.string.netplay_join_failed, "save memory mismatch"))
                            return@Thread
                        }
                        NativeBridge.setCheats(session.config.cheats.toTypedArray())
                        netplayLink = try {
                            session.ready(
                                onLobby = { names -> showGuestLobby(session.hostName, names) },
                                keepWaiting = { running },
                            )
                        } catch (e: IOException) {
                            session.close()
                            failAndFinish(getString(R.string.netplay_join_failed, e.message ?: e.javaClass.simpleName))
                            return@Thread
                        }
                        joining = null
                    }
                    else -> {
                        applyEnabledCheats()
                        scheduleLaunchStateLoadIfNeeded()
                    }
                }
                netplayLink?.let { link ->
                    NativeBridge.netplayBegin(discardSaves = netplayRole == NETPLAY_GUEST)
                    hideNetplayStatus()
                    runOnUiThread {
                        restoreSplitView(link.playerCount)
                        Toast.makeText(this, getString(R.string.netplay_started, link.playerCount, link.localPlayer + 1), Toast.LENGTH_LONG).show()
                    }
                }
                updateSurfaceLayoutForCurrentFrame()
                emulationLoop()
            } finally {
                Log.i(logTag, "emulation thread shutting down")
                joining?.close()
                netplayLink?.close()
                NativeBridge.shutdownSession()
            }
        }.apply {
            name = "Nin64-Emu"
            start()
        }
    }

    /** Hosting, after boot and before the first frame: open a lobby and wait for the host to start. */
    private fun hostNetplaySession(): Netplay.Session? {
        val saveMemory = NativeBridge.getSaveMemory()
        if (saveMemory == null) {
            failAndFinish(getString(R.string.netplay_host_failed, "no save memory"))
            return null
        }
        val config = Netplay.SessionConfig(
            options = listOf("mupen64plus-ForceDisableExtraMem" to if (disableExpansionPak) "True" else "False"),
            cheats = CheatRepository.enabledCheatCodeLines(this, romPreferenceKey, romCrc),
            saveMemory = saveMemory,
        )
        val lobby = try {
            Netplay.HostLobby(gameTitle, romMd5, config) { runOnUiThread(::refreshHostLobby) }
        } catch (e: IOException) {
            failAndFinish(getString(R.string.netplay_host_failed, e.message ?: e.javaClass.simpleName))
            return null
        }
        netplayLobby = lobby
        runOnUiThread(::refreshHostLobby)
        return try {
            lobby.await { running }
        } finally {
            netplayLobby = null
        }
    }

    /** On the UI thread: the lobby as it is now (a roster handed over from a network thread could be stale). */
    private fun refreshHostLobby() {
        val roster = netplayLobby?.roster() ?: return
        val lines = roster.mapIndexed { index, player ->
            val name = if (index == 0) getString(R.string.netplay_player_you, player.name) else player.name
            getString(R.string.netplay_player_line, index + 1, name) +
                if (player.ready) "" else " " + getString(R.string.netplay_player_loading)
        }
        val address = Netplay.localAddress()
        val message = buildString {
            append(getString(R.string.netplay_hosting, gameTitle))
            address?.let { append("\n").append(getString(R.string.netplay_host_address, it)) }
            append("\n\n").append(getString(R.string.netplay_players_header, roster.size, Netplay.MAX_PLAYERS))
            lines.forEach { append("\n").append(it) }
            if (roster.size == 1) append("\n\n").append(getString(R.string.netplay_waiting_for_players))
        }
        val canStart = roster.size > 1 && roster.all { it.ready }
        showNetplayStatus(
            message,
            cancellable = true,
            startLabel = getString(R.string.netplay_start, roster.size),
            startEnabled = canStart,
            onStart = {
                netplayLobby?.requestStart()
                showNetplayStatus(getString(R.string.netplay_starting), cancellable = true)
            },
        )
    }

    private fun showGuestLobby(hostName: String, names: List<String>) {
        val message = buildString {
            append(getString(R.string.netplay_joined, hostName))
            append("\n\n").append(getString(R.string.netplay_players_header, names.size, Netplay.MAX_PLAYERS))
            names.forEachIndexed { index, name -> append("\n").append(getString(R.string.netplay_player_line, index + 1, name)) }
            append("\n\n").append(getString(R.string.netplay_waiting_for_start, hostName))
        }
        showNetplayStatus(message, cancellable = true)
    }

    private fun failAndFinish(message: String) {
        running = false
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showNetplayStatus(
        message: String,
        cancellable: Boolean,
        startLabel: String? = null,
        startEnabled: Boolean = false,
        onStart: (() -> Unit)? = null,
    ) {
        runOnUiThread {
            hideNetplayStatusNow()
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(24.dp, 20.dp, 24.dp, 16.dp)
                background = GradientDrawable().apply {
                    setColor(Color.argb(230, 20, 21, 28))
                    cornerRadius = 14f * resources.displayMetrics.density
                }
                isClickable = true
            }
            if (onStart == null) {
                panel.addView(android.widget.ProgressBar(this), LinearLayout.LayoutParams(36.dp, 36.dp))
            }
            panel.addView(TextView(this).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, 12.dp, 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (onStart != null && startLabel != null) {
                panel.addView(coloredMenuButton(startLabel, Color.parseColor("#00C063")) { onStart() }.apply {
                    isEnabled = startEnabled
                    alpha = if (startEnabled) 1f else 0.45f
                })
            }
            if (cancellable) {
                panel.addView(coloredMenuButton(getString(android.R.string.cancel), Color.parseColor("#D93131")) { finish() })
            }
            netplayStatusView = panel
            // A fixed width: wrapped around a spinner, the message would be squeezed to the spinner's width.
            val screenWidth = rootContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            rootContainer.addView(
                panel,
                FrameLayout.LayoutParams((screenWidth - 32.dp).coerceAtMost(360.dp), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
        }
    }

    private fun hideNetplayStatus() = runOnUiThread { hideNetplayStatusNow() }

    private fun hideNetplayStatusNow() {
        netplayStatusView?.let(rootContainer::removeView)
        netplayStatusView = null
    }

    /**
     * "Waiting for <name>", while someone's input is late (they paused, or the
     * network hiccuped); [who] is "" when it is not known who, null when it is over.
     */
    private fun setNetplayStalled(who: String?) {
        runOnUiThread {
            if (who == null) {
                netplayStallView?.let(rootContainer::removeView)
                netplayStallView = null
                return@runOnUiThread
            }
            if (netplayStallView != null) return@runOnUiThread
            val view = TextView(this).apply {
                text = if (who.isEmpty()) getString(R.string.netplay_waiting_for_others) else getString(R.string.netplay_waiting_for, who)
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(16.dp, 8.dp, 16.dp, 8.dp)
                background = GradientDrawable().apply {
                    setColor(Color.argb(200, 20, 21, 28))
                    cornerRadius = 18f * resources.displayMetrics.density
                }
            }
            netplayStallView = view
            rootContainer.addView(
                view,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    .apply { bottomMargin = 24.dp }
            )
        }
    }

    /** The session ended: a host plays on alone, a guest (on the host's save) goes back to the library. */
    private fun endNetplay(link: Netplay.Session) {
        netplayLink = null
        NativeBridge.netplayEnd()
        setNetplayStalledOff()
        runOnUiThread {
            splitViewButton.visibility = View.GONE
            splitViewZoomed = false
            applyViewCrop()
        }
        val reason = link.closeReason ?: getString(R.string.netplay_ended)
        if (netplayRole == NETPLAY_GUEST) {
            failAndFinish(reason)
        } else {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.netplay_host_continues, reason), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setNetplayStalledOff() = runOnUiThread {
        netplayStallView?.let(rootContainer::removeView)
        netplayStallView = null
    }

    // ── Split-screen view ─────────────────────────────────────────────────────

    private fun splitViewPrefs() = getSharedPreferences(DisplayOptionsRepository.PREFS_NAME, MODE_PRIVATE)

    private fun splitViewKey(name: String) = "netplay_$name.${romPreferenceKey ?: romMd5}"

    /** Once the game starts: the split last chosen for this game, zoomed in if it was. */
    private fun restoreSplitView(players: Int) {
        val prefs = splitViewPrefs()
        splitLayout = Netplay.SplitLayout.fromKey(prefs.getString(splitViewKey("split_layout"), null))
            ?: Netplay.SplitLayout.defaultFor(players)
        splitViewZoomed = prefs.getBoolean(splitViewKey("split_zoomed"), false)
        splitViewButton.visibility = View.VISIBLE
        applyViewCrop()
    }

    private fun setSplitViewZoomed(zoomed: Boolean) {
        splitViewZoomed = zoomed
        splitViewPrefs().edit().putBoolean(splitViewKey("split_zoomed"), zoomed).apply()
        applyViewCrop()
    }

    private fun applyViewCrop() {
        val link = netplayLink
        val crop = if (splitViewZoomed && link != null) splitLayout.crop(link.localPlayer) else Netplay.SplitLayout.WHOLE
        // The renderer keeps the size it started with; pinning the buffer to it means
        // the view can take the zoomed part's shape without the renderer noticing.
        if (crop !== Netplay.SplitLayout.WHOLE && !surfaceBufferPinned) {
            val width = NativeBridge.getFrameWidth()
            val height = NativeBridge.getFrameHeight()
            if (width > 0 && height > 0) {
                surfaceView.holder.setFixedSize(width, height)
                surfaceBufferPinned = true
            }
        }
        viewCrop = crop
        NativeBridge.setViewCrop(crop[0], crop[1], crop[2], crop[3])
        splitViewButton.setImageResource(if (splitViewZoomed) R.drawable.ic_zoom_out else R.drawable.ic_zoom_in)
        splitViewButton.contentDescription = getString(
            if (splitViewZoomed) R.string.netplay_view_zoom_out else R.string.netplay_view_zoom_in
        )
        updateSurfaceLayoutForCurrentFrame()
    }

    /** Whole screen (split screen), or only your part of one of the usual splits. */
    private fun showSplitViewDialog() {
        val layouts = Netplay.SplitLayout.entries
        val labels = arrayOf(getString(R.string.netplay_view_whole)) + layouts.map {
            getString(
                when (it) {
                    Netplay.SplitLayout.TOP_BOTTOM -> R.string.netplay_view_top_bottom
                    Netplay.SplitLayout.LEFT_RIGHT -> R.string.netplay_view_left_right
                    Netplay.SplitLayout.QUARTERS -> R.string.netplay_view_quarters
                }
            )
        }
        val checked = if (splitViewZoomed) layouts.indexOf(splitLayout) + 1 else 0
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.netplay_view_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                if (which > 0) {
                    splitLayout = layouts[which - 1]
                    splitViewPrefs().edit().putString(splitViewKey("split_layout"), splitLayout.key).apply()
                }
                setSplitViewZoomed(which > 0)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** This device's controller, as the lockstep exchange packs it. */
    private fun currentLocalInput(): Int {
        val gamepadMag = analogStickX * analogStickX + analogStickY * analogStickY
        val touchMag = touchAnalogStickX * touchAnalogStickX + touchAnalogStickY * touchAnalogStickY
        val stickX = if (touchMag > gamepadMag) touchAnalogStickX else analogStickX
        val stickY = if (touchMag > gamepadMag) touchAnalogStickY else analogStickY
        return Netplay.pack(keyButtonMask or axisButtonMask or touchButtonMask, stickX, stickY)
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
        var netplayFrame = 0
        while (running) {
            runPendingEmulationTasks()
            val link = netplayLink
            if (paused) {
                link?.idle()
                if (link != null && link.closed) endNetplay(link)
                Thread.sleep(16)
                continue
            }
            if (link != null) {
                val inputs = link.exchange(netplayFrame, currentLocalInput(), ::setNetplayStalled)
                if (inputs == null) {
                    endNetplay(link)
                    continue
                }
                NativeBridge.netplaySetInputs(inputs)
                NativeBridge.runFrame(OPS_PER_CHUNK)
                link.takeEvents().forEach { event ->
                    runOnUiThread { Toast.makeText(this, event, Toast.LENGTH_LONG).show() }
                }
                if (netplayFrame > 0 && netplayFrame % Netplay.CHECKSUM_INTERVAL == 0) {
                    link.recordChecksum(netplayFrame, NativeBridge.memoryChecksum())
                }
                if (link.desyncedAtFrame >= 0 && !netplayDesyncReported) {
                    netplayDesyncReported = true
                    runOnUiThread { Toast.makeText(this, R.string.netplay_desynced, Toast.LENGTH_LONG).show() }
                }
                netplayFrame++
            } else {
                NativeBridge.runFrame(OPS_PER_CHUNK)
                maybeLoadLaunchStateAfterFrame()
            }
            maybeFlushInGameSave()
        }
    }

    private fun maybeFlushInGameSave() {
        val now = System.currentTimeMillis()
        if (now - lastInGameSaveFlushTimeMs < IN_GAME_SAVE_FLUSH_INTERVAL_MS) return
        lastInGameSaveFlushTimeMs = now
        NativeBridge.flushInGameSave()
    }

    private fun requestInGameSaveFlush() {
        if (!running) return
        enqueueEmulationTask { NativeBridge.flushInGameSave() }
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
        // Zoomed in on a split-screen part, the surface takes that part's shape.
        val crop = viewCrop
        val frameWidth = (NativeBridge.getFrameWidth() * crop[2]).roundToInt()
        val frameHeight = (NativeBridge.getFrameHeight() * crop[3]).roundToInt()
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
        val blue     = Color.parseColor("#0056EA")
        val green    = Color.parseColor("#00C063")
        val red      = Color.parseColor("#D93131")
        val gold     = if (isNight) Color.parseColor("#FEDF5A") else Color.parseColor("#B07F00")

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

        panel.addView(TextView(this).apply {
            text = getString(R.string.in_game_menu)
            setTextColor(onSurf)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, mwp().apply { bottomMargin = 10.dp })

        panel.addView(coloredMenuButton(getString(R.string.in_game_resume), blue) { resumeGame() })
        if (netplayRole == NETPLAY_NONE) {
            panel.addView(coloredMenuButton(getString(R.string.in_game_save_state), green) {
                showSlotPickerDialog(isSave = true)
            })
            panel.addView(coloredMenuButton(getString(R.string.in_game_load_state), green) {
                showSlotPickerDialog(isSave = false)
            })
        }
        if (netplayLink != null) {
            panel.addView(coloredMenuButton(getString(R.string.netplay_view_title), blue) {
                showSplitViewDialog()
            })
        }
        panel.addView(coloredMenuButton(getString(R.string.menu_achievements), gold) {
            Achievements.showList(this)
        })
        panel.addView(coloredMenuButton(getString(R.string.in_game_quit), red) { finish() })

        val screenWidth = rootContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val menuWidth = (screenWidth - 32.dp).coerceAtMost(400.dp)
        val maxMenuHeight = ((rootContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) - 48.dp)
            .coerceAtLeast(260.dp)
        val scrollContainer = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        overlay.addView(scrollContainer, FrameLayout.LayoutParams(menuWidth, maxMenuHeight, Gravity.CENTER))
        menuOverlay = overlay
        rootContainer.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun resumeGame() {
        menuOverlay?.let(rootContainer::removeView)
        menuOverlay = null
        paused = false
        startComboPressed = false
        selectComboPressed = false
    }

    private fun saveStateFromMenu(slot: Int, statusText: TextView, onRefresh: (thumbFile: File, hasState: Boolean) -> Unit) {
        currentSlot = slot
        val stateFile = stateFile(slot)
        stateFile.parentFile?.mkdirs()
        statusText.text = getString(R.string.in_game_saving_state)
        enqueueEmulationTask {
            val result = NativeBridge.saveState(stateFile.absolutePath)
            runOnUiThread {
                if (result == "saved") {
                    captureStateThumbnail(stateThumbnailFile(slot)) { saved ->
                        statusText.text = if (saved) getString(R.string.in_game_state_saved) else getString(R.string.in_game_state_saved_no_thumbnail)
                        onRefresh(stateThumbnailFile(slot), stateFile.isFile)
                    }
                } else {
                    statusText.text = result
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadStateFromMenu(slot: Int, statusText: TextView, onSuccess: (() -> Unit)? = null) {
        currentSlot = slot
        val stateFile = stateFile(slot)
        if (!stateFile.isFile) { statusText.text = getString(R.string.in_game_no_save_state); return }
        statusText.text = getString(R.string.in_game_loading_state)
        enqueueEmulationTask {
            val result = NativeBridge.loadState(stateFile.absolutePath)
            runOnUiThread {
                if (result == "loaded") {
                    statusText.text = getString(R.string.in_game_state_loaded)
                    onSuccess?.invoke()
                    resumeGame()
                } else {
                    statusText.text = result
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun scheduleLaunchStateLoadIfNeeded() {
        val slot = launchStateSlot
        if (slot !in 1..SaveStateFiles.SAVE_SLOT_COUNT) return

        currentSlot = slot
        val stateFile = stateFile(slot)
        if (!stateFile.isFile) {
            runOnUiThread {
                Toast.makeText(this, R.string.in_game_no_save_state, Toast.LENGTH_LONG).show()
            }
            return
        }

        pendingLaunchStateSlot = slot
        launchStateLoadAttempts = 0
        launchStateFramesUntilNextAttempt = LAUNCH_STATE_INITIAL_DELAY_FRAMES
    }

    private fun maybeLoadLaunchStateAfterFrame() {
        val slot = pendingLaunchStateSlot
        if (slot !in 1..SaveStateFiles.SAVE_SLOT_COUNT) return

        if (launchStateFramesUntilNextAttempt > 0) {
            launchStateFramesUntilNextAttempt--
            return
        }

        val result = NativeBridge.loadState(stateFile(slot).absolutePath)
        if (result == "loaded") {
            pendingLaunchStateSlot = NO_LOAD_STATE_SLOT
            return
        }

        launchStateLoadAttempts++
        if (launchStateLoadAttempts >= LAUNCH_STATE_LOAD_ATTEMPTS) {
            pendingLaunchStateSlot = NO_LOAD_STATE_SLOT
            runOnUiThread {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            }
        } else {
            launchStateFramesUntilNextAttempt = LAUNCH_STATE_RETRY_DELAY_FRAMES
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

    private fun showSlotPickerDialog(isSave: Boolean) {
        val isNight  = isNightMode()
        val panelBg  = if (isNight) Color.parseColor("#1E1F28") else Color.parseColor("#F2F2F7")
        val onSurf   = if (isNight) Color.WHITE else Color.parseColor("#212121")
        val muted    = if (isNight) Color.parseColor("#AAAAAA") else Color.parseColor("#757575")
        val divider  = if (isNight) Color.parseColor("#2E2F3A") else Color.parseColor("#DCDCE0")
        val green    = Color.parseColor("#00C063")
        val red      = Color.parseColor("#D93131")

        val dialogOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
            isFocusable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
            background = GradientDrawable().apply {
                setColor(panelBg)
                cornerRadius = 14f * resources.displayMetrics.density
            }
        }

        val mp = LinearLayout.LayoutParams.MATCH_PARENT
        val wc = LinearLayout.LayoutParams.WRAP_CONTENT

        panel.addView(TextView(this).apply {
            text = if (isSave) getString(R.string.in_game_save_state) else getString(R.string.in_game_load_state)
            setTextColor(onSurf)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(mp, wc).apply { bottomMargin = 6.dp })

        val statusText = TextView(this).apply {
            setTextColor(muted)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        panel.addView(statusText, LinearLayout.LayoutParams(mp, wc).apply { bottomMargin = 8.dp })

        data class SlotRow(val thumb: ImageView, val btn: MaterialButton, val label: TextView)
        val slotRows = mutableListOf<SlotRow>()

        val slotContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        for (slot in 1..SaveStateFiles.SAVE_SLOT_COUNT) {
            val thumbFile = stateThumbnailFile(slot)
            val hasState = stateFile(slot).isFile

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4.dp, 10.dp, 4.dp, 10.dp)
                gravity = Gravity.CENTER_VERTICAL
            }

            val thumbView = ImageView(this).apply {
                setBackgroundColor(Color.rgb(22, 23, 30))
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = false
                val bmp = if (thumbFile.isFile) BitmapFactory.decodeFile(thumbFile.absolutePath) else null
                setImageBitmap(bmp)
                alpha = if (bmp != null) 1f else 0.35f
                isClickable = true
                setOnClickListener {
                    if (thumbFile.isFile) showExpandedThumbnail(thumbFile)
                }
            }
            row.addView(thumbView, LinearLayout.LayoutParams(108.dp, 68.dp).apply { marginEnd = 12.dp })

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val labelText = TextView(this).apply {
                text = "Slot $slot  ${if (hasState) "●" else "○"}"
                setTextColor(onSurf)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            infoCol.addView(labelText)
            if (!hasState) {
                infoCol.addView(TextView(this).apply {
                    text = getString(R.string.in_game_no_save_state)
                    setTextColor(muted)
                    textSize = 12f
                })
            }
            row.addView(infoCol, LinearLayout.LayoutParams(0, wc, 1f))

            val actionBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (isSave) getString(R.string.in_game_save_state) else getString(R.string.in_game_load_state)
                setTextColor(green)
                strokeColor = ColorStateList.valueOf(green)
                rippleColor = ColorStateList.valueOf(green)
                isEnabled = if (isSave) true else hasState
            }
            slotRows.add(SlotRow(thumbView, actionBtn, labelText))

            actionBtn.setOnClickListener {
                if (isSave) {
                    saveStateFromMenu(slot, statusText) { updatedThumbFile, _ ->
                        val bmp = if (updatedThumbFile.isFile) BitmapFactory.decodeFile(updatedThumbFile.absolutePath) else null
                        thumbView.setImageBitmap(bmp)
                        thumbView.alpha = if (bmp != null) 1f else 0.35f
                        labelText.text = "Slot $slot  ${if (stateFile(slot).isFile) "●" else "○"}"
                    }
                } else {
                    loadStateFromMenu(slot, statusText) { rootContainer.removeView(dialogOverlay) }
                }
            }

            row.addView(actionBtn, LinearLayout.LayoutParams(wc, wc))
            slotContainer.addView(row, LinearLayout.LayoutParams(mp, wc))

            if (slot < SaveStateFiles.SAVE_SLOT_COUNT) {
                slotContainer.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(mp, 1))
            }
        }

        val slotScroll = ScrollView(this).apply {
            addView(slotContainer, FrameLayout.LayoutParams(mp, wc))
        }
        panel.addView(slotScroll, LinearLayout.LayoutParams(mp, 0, 1f))
        panel.addView(coloredMenuButton("Close", red) {
            rootContainer.removeView(dialogOverlay)
        }, LinearLayout.LayoutParams(mp, wc).apply { topMargin = 8.dp })

        val screenWidth  = rootContainer.width.takeIf  { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenHeight = rootContainer.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val dialogWidth  = (screenWidth - 24.dp).coerceAtMost(560.dp)
        val dialogHeight = (screenHeight * 0.85f).toInt().coerceAtLeast(420.dp)

        dialogOverlay.addView(panel, FrameLayout.LayoutParams(dialogWidth, dialogHeight, Gravity.CENTER))
        rootContainer.addView(dialogOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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

    private fun clearControllerInputs() {
        keyButtonMask = 0; axisButtonMask = 0; touchButtonMask = 0
        analogStickX = 0; analogStickY = 0; touchAnalogStickX = 0; touchAnalogStickY = 0
        pushControllerState()
    }

    private fun stateFile(slot: Int = currentSlot): File =
        SaveStateFiles.stateFile(rootPath, filesDir, romPreferenceKey, romPath, slot)

    private fun stateThumbnailFile(slot: Int = currentSlot): File =
        SaveStateFiles.thumbnailFile(rootPath, filesDir, romPreferenceKey, romPath, slot)

    private fun inGameSaveKey(): String = SaveStateFiles.stateBaseName(romPreferenceKey, romPath)

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).roundToInt()

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (menuOverlay != null) { resumeGame(); return }
        stopEmulation()
        super.onBackPressed()
    }

    override fun onDestroy() {
        cheevoHandler.removeCallbacks(cheevoPoll)
        cheevoPopups?.clear()
        keyButtonMask = 0; axisButtonMask = 0; touchButtonMask = 0
        analogStickX = 0; analogStickY = 0; touchAnalogStickX = 0; touchAnalogStickY = 0
        pushControllerState()
        stopEmulation()
        NativeBridge.clearSurface()
        netplayWifi?.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ROOT_PATH = "extra_root_path"
        private const val EXTRA_ROM_PATH = "extra_rom_path"
        private const val EXTRA_USE_TEXTURE_PACK = "extra_use_texture_pack"
        private const val EXTRA_DISABLE_EXPANSION_PAK = "extra_disable_expansion_pak"
        private const val EXTRA_ROM_PREFERENCE_KEY = "extra_rom_preference_key"
        private const val EXTRA_ROM_CRC = "extra_rom_crc"
        private const val EXTRA_LOAD_STATE_SLOT = "extra_load_state_slot"
        private const val EXTRA_NETPLAY_ROLE = "extra_netplay_role"
        private const val EXTRA_NETPLAY_HOST = "extra_netplay_host"
        private const val EXTRA_NETPLAY_PORT = "extra_netplay_port"
        private const val EXTRA_ROM_MD5 = "extra_rom_md5"
        private const val EXTRA_GAME_TITLE = "extra_game_title"
        const val NETPLAY_NONE = 0
        const val NETPLAY_HOST = 1
        const val NETPLAY_GUEST = 2
        private const val NO_LOAD_STATE_SLOT = -1
        private const val OPS_PER_CHUNK = 2_000_000
        private const val IN_GAME_SAVE_FLUSH_INTERVAL_MS = 15_000L
        private const val STICK_MAX = 80
        private const val AXIS_BUTTON_THRESHOLD = 0.45f
        private const val LAUNCH_STATE_LOAD_ATTEMPTS = 4
        private const val LAUNCH_STATE_INITIAL_DELAY_FRAMES = 180
        private const val LAUNCH_STATE_RETRY_DELAY_FRAMES = 30

        fun launch(
            context: Context,
            rootPath: String,
            romPath: String,
            useTexturePack: Boolean = false,
            disableExpansionPak: Boolean = false,
            romPreferenceKey: String? = null,
            romCrc: String? = null,
            loadStateSlot: Int = NO_LOAD_STATE_SLOT,
            netplayRole: Int = NETPLAY_NONE,
            netplayHost: String? = null,
            netplayPort: Int = Netplay.SESSION_PORT,
            romMd5: String? = null,
            gameTitle: String? = null,
        ) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_ROOT_PATH, rootPath)
                    .putExtra(EXTRA_ROM_PATH, romPath)
                    .putExtra(EXTRA_USE_TEXTURE_PACK, useTexturePack)
                    .putExtra(EXTRA_DISABLE_EXPANSION_PAK, disableExpansionPak)
                    .putExtra(EXTRA_ROM_PREFERENCE_KEY, romPreferenceKey)
                    .putExtra(EXTRA_ROM_CRC, romCrc)
                    .putExtra(EXTRA_LOAD_STATE_SLOT, loadStateSlot)
                    .putExtra(EXTRA_NETPLAY_ROLE, netplayRole)
                    .putExtra(EXTRA_NETPLAY_HOST, netplayHost)
                    .putExtra(EXTRA_NETPLAY_PORT, netplayPort)
                    .putExtra(EXTRA_ROM_MD5, romMd5)
                    .putExtra(EXTRA_GAME_TITLE, gameTitle)
            )
        }
    }
}
