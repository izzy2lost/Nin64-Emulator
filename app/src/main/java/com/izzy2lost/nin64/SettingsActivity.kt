package com.izzy2lost.nin64

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import android.widget.ImageButton
import android.widget.TextView

class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val PREFS_NAME = "nin64_prefs"
        const val PREF_ROM_FOLDER_URI = "rom_folder_uri"
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private lateinit var folderPathText: TextView

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            prefs.edit().putString(PREF_ROM_FOLDER_URI, uri.toString()).apply()
            updateFolderDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        folderPathText = findViewById(R.id.folderPathText)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.changeFolderButton).setOnClickListener {
            val current = prefs.getString(PREF_ROM_FOLDER_URI, null)?.let(Uri::parse)
            folderPicker.launch(current)
        }

        updateFolderDisplay()
    }

    override fun onResume() {
        super.onResume()
        updateFolderDisplay()
    }

    private fun updateFolderDisplay() {
        val uri = prefs.getString(PREF_ROM_FOLDER_URI, null)?.let(Uri::parse)
        folderPathText.text = if (uri != null) {
            DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: getString(R.string.game_folder_not_set)
        } else {
            getString(R.string.game_folder_not_set)
        }
    }
}
