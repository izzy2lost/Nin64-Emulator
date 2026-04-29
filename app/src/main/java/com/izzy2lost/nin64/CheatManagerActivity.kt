package com.izzy2lost.nin64

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class CheatManagerActivity : AppCompatActivity() {
    private lateinit var romKey: String
    private var romCrc: String? = null
    private var gameTitle: String? = null
    private var enabledCheatIds = mutableSetOf<String>()
    private var selectedOptions = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableNin64EdgeToEdge()

        romKey = intent.getStringExtra(EXTRA_ROM_KEY) ?: run {
            finish()
            return
        }
        romCrc = intent.getStringExtra(EXTRA_ROM_CRC)
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE)
        enabledCheatIds = CheatRepository.loadEnabledCheatIds(this, romKey).toMutableSet()
        selectedOptions = CheatRepository.loadSelectedOptions(this, romKey).toMutableMap()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@CheatManagerActivity, R.color.surface))
        }

        val topBar = createTopBar()
        root.addView(topBar)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 10.dp, 16.dp, 24.dp)
        }

        val game = CheatDatabase.findByCrc(this, romCrc)
        when {
            romCrc.isNullOrBlank() -> {
                container.addView(emptyMessage(getString(R.string.cheats_no_crc)))
            }
            game == null || game.cheats.isEmpty() -> {
                container.addView(emptyMessage(getString(R.string.cheats_none_found)))
            }
            else -> {
                game.cheats.forEach { cheat ->
                    container.addView(cheatRow(cheat), LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = 8.dp })
                }
            }
        }

        val scrollView = ScrollView(this).apply { addView(container) }
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        setContentView(root)
        topBar.applyTopBarInsets()
        scrollView.applyBottomContentInsets()
    }

    private fun createTopBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dp, 0, 16.dp, 0)
        setBackgroundColor(ContextCompat.getColor(this@CheatManagerActivity, R.color.surface))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 64.dp)

        addView(
            ImageButton(this@CheatManagerActivity).apply {
                setImageResource(R.drawable.ic_back)
                background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                    .useAndReturnDrawable(0)
                contentDescription = getString(R.string.navigate_up)
                setColorFilter(ContextCompat.getColor(this@CheatManagerActivity, R.color.on_surface))
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(48.dp, 48.dp),
        )

        addView(
            TextView(this@CheatManagerActivity).apply {
                text = scopedTitle(getString(R.string.cheats_manager))
                setTextColor(ContextCompat.getColor(this@CheatManagerActivity, R.color.on_surface))
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(8.dp, 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun cheatRow(cheat: CheatEntry): View {
        val nameView = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        fun refresh() {
            val enabled = cheat.id in enabledCheatIds
            nameView.text = cheat.name
            nameView.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (enabled) R.color.brand_green else R.color.brand_red,
                )
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            background = rowBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (cheat.id in enabledCheatIds) {
                    enabledCheatIds.remove(cheat.id)
                } else {
                    enabledCheatIds.add(cheat.id)
                }
                CheatRepository.saveEnabledCheatIds(this@CheatManagerActivity, romKey, enabledCheatIds)
                refresh()
            }

            addView(nameView)
            if (cheat.options.isNotEmpty()) {
                addView(optionSpinner(cheat), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 8.dp })
            }
            cheat.description?.let { description ->
                addView(TextView(this@CheatManagerActivity).apply {
                    text = description
                    setTextColor(ContextCompat.getColor(this@CheatManagerActivity, R.color.on_surface_muted))
                    textSize = 13f
                    setPadding(0, 6.dp, 0, 0)
                })
            }
            refresh()
        }
    }

    private fun optionSpinner(cheat: CheatEntry): Spinner =
        Spinner(this).apply {
            val options = cheat.options
            val labels = options.map { it.label }
            adapter = ArrayAdapter(
                this@CheatManagerActivity,
                android.R.layout.simple_spinner_item,
                labels,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            val savedValue = selectedOptions[cheat.id]
            val selectedIndex = options.indexOfFirst { it.value == savedValue }.takeIf { it >= 0 } ?: 0
            setSelection(selectedIndex, false)

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val option = options.getOrNull(position) ?: return
                    if (selectedOptions[cheat.id] == option.value) return
                    selectedOptions[cheat.id] = option.value
                    CheatRepository.saveSelectedOption(this@CheatManagerActivity, romKey, cheat.id, option.value)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

    private fun emptyMessage(message: String): View =
        TextView(this).apply {
            text = message
            setTextColor(ContextCompat.getColor(this@CheatManagerActivity, R.color.on_surface_muted))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(24.dp, 80.dp, 24.dp, 24.dp)
        }

    private fun rowBackground() = GradientDrawable().apply {
        setColor(if (isNightMode()) Color.rgb(24, 26, 36) else Color.WHITE)
        cornerRadius = 10f * resources.displayMetrics.density
    }

    private fun scopedTitle(base: String): String =
        if (gameTitle.isNullOrBlank()) base else "$base - $gameTitle"

    private fun isNightMode(): Boolean {
        val mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return (resources.configuration.uiMode and mask) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun android.content.res.TypedArray.useAndReturnDrawable(index: Int): android.graphics.drawable.Drawable? =
        try { getDrawable(index) } finally { recycle() }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ROM_KEY = "extra_rom_key"
        private const val EXTRA_ROM_CRC = "extra_rom_crc"
        private const val EXTRA_GAME_TITLE = "extra_game_title"

        fun launch(context: Context, romKey: String, romCrc: String?, gameTitle: String?) {
            context.startActivity(
                Intent(context, CheatManagerActivity::class.java)
                    .putExtra(EXTRA_ROM_KEY, romKey)
                    .putExtra(EXTRA_ROM_CRC, romCrc)
                    .putExtra(EXTRA_GAME_TITLE, gameTitle)
            )
        }
    }
}
