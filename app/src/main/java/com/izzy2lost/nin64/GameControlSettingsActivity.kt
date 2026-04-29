package com.izzy2lost.nin64

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class GameControlSettingsActivity : AppCompatActivity() {
    private lateinit var romKey: String
    private var gameTitle: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        romKey = intent.getStringExtra(EXTRA_ROM_KEY) ?: run {
            finish()
            return
        }
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE)

        setContentView(R.layout.activity_game_control_settings)

        val base = getString(R.string.controls_game_controls)
        findViewById<TextView>(R.id.titleText).text =
            if (gameTitle.isNullOrBlank()) base else "$base - $gameTitle"

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.editTouchLayoutButton).setOnClickListener {
            TouchLayoutActivity.launch(this, romKey, gameTitle)
        }
        findViewById<MaterialButton>(R.id.editControllerMappingButton).setOnClickListener {
            GamepadMappingActivity.launch(this, romKey, gameTitle)
        }
        findViewById<MaterialButton>(R.id.resetControlsButton).setOnClickListener {
            ControlsRepository.resetPerGame(this, romKey)
            Toast.makeText(this, getString(R.string.controls_per_game_reset_done), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_ROM_KEY = "extra_rom_key"
        private const val EXTRA_GAME_TITLE = "extra_game_title"

        fun launch(context: Context, romKey: String, gameTitle: String) {
            context.startActivity(
                Intent(context, GameControlSettingsActivity::class.java)
                    .putExtra(EXTRA_ROM_KEY, romKey)
                    .putExtra(EXTRA_GAME_TITLE, gameTitle)
            )
        }
    }
}
