package com.izzy2lost.nin64

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class TouchLayoutActivity : AppCompatActivity() {
    private var romKey: String? = null
    private lateinit var touchView: TouchControlsView
    private lateinit var editButton: ImageButton
    private lateinit var saveButton: ImageButton
    private lateinit var zoomInButton: ImageButton
    private lateinit var zoomOutButton: ImageButton

    private var currentLayout = TouchLayout.default()
    private var isEditing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableNin64EdgeToEdge(immersive = true)
        romKey = intent.getStringExtra(EXTRA_ROM_KEY)
        currentLayout = ControlsRepository.loadTouchLayout(this, romKey)

        val root = FrameLayout(this)

        touchView = TouchControlsView(this).apply {
            layout = currentLayout
            editorMode = false
            setBackgroundColor(BG_COLOR)
            onLayoutEdited = { edited ->
                currentLayout = edited
                ControlsRepository.saveTouchLayout(this@TouchLayoutActivity, romKey, edited)
            }
        }
        root.addView(touchView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        val cluster = buildCluster()
        root.addView(cluster, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))

        setContentView(root)
        cluster.applySafeAreaMargins(applyStart = true, applyTop = true, applyEnd = true, applyBottom = true)
    }

    private fun buildCluster(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(6.dp, 6.dp, 6.dp, 6.dp)
        background = clusterBackground()
        elevation = 8f * resources.displayMetrics.density

        editButton = iconBtn(R.drawable.ic_construction) { startEditing() }
        saveButton = iconBtn(R.drawable.ic_save) { stopEditing() }.also { it.visibility = View.GONE }
        zoomOutButton = iconBtn(R.drawable.ic_zoom_out) { zoomSelected(-1) }.also { it.visibility = View.GONE }
        zoomInButton = iconBtn(R.drawable.ic_zoom_in) { zoomSelected(+1) }.also { it.visibility = View.GONE }

        addView(zoomOutButton, LinearLayout.LayoutParams(48.dp, 48.dp))
        addView(saveButton, LinearLayout.LayoutParams(48.dp, 48.dp))
        addView(zoomInButton, LinearLayout.LayoutParams(48.dp, 48.dp))
        addView(editButton, LinearLayout.LayoutParams(48.dp, 48.dp))
    }

    private fun startEditing() {
        isEditing = true
        touchView.editorMode = true
        editButton.visibility = View.GONE
        saveButton.visibility = View.VISIBLE
        zoomOutButton.visibility = View.VISIBLE
        zoomInButton.visibility = View.VISIBLE
    }

    private fun stopEditing() {
        isEditing = false
        touchView.editorMode = false
        editButton.visibility = View.VISIBLE
        saveButton.visibility = View.GONE
        zoomOutButton.visibility = View.GONE
        zoomInButton.visibility = View.GONE
        ControlsRepository.saveTouchLayout(this, romKey, currentLayout)
    }

    private fun zoomSelected(direction: Int) {
        touchView.replaceSelectedControl { control ->
            control.copy(size = (control.size + direction * ZOOM_STEP).coerceIn(0.04f, 0.35f))
        }
        currentLayout = touchView.layout
    }

    private fun iconBtn(iconRes: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                .useAndReturnDrawable(0)
            setColorFilter(Color.WHITE)
            setOnClickListener { onClick() }
        }

    private fun clusterBackground() = GradientDrawable().apply {
        setColor(Color.argb(210, 14, 15, 20))
        cornerRadius = 28f * resources.displayMetrics.density
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (isEditing) stopEditing() else super.onBackPressed()
    }

    private fun android.content.res.TypedArray.useAndReturnDrawable(index: Int): android.graphics.drawable.Drawable? =
        try { getDrawable(index) } finally { recycle() }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ROM_KEY = "extra_rom_key"
        private const val EXTRA_GAME_TITLE = "extra_game_title"
        private const val ZOOM_STEP = 0.02f

        private val BG_COLOR = Color.rgb(14, 15, 20)

        fun launch(context: Context, romKey: String? = null, gameTitle: String? = null) {
            context.startActivity(
                Intent(context, TouchLayoutActivity::class.java)
                    .putExtra(EXTRA_ROM_KEY, romKey)
                    .putExtra(EXTRA_GAME_TITLE, gameTitle)
            )
        }
    }
}
