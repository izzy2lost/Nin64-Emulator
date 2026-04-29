package com.izzy2lost.nin64

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val PREFS_NAME = "nin64_prefs"
        const val PREF_ROM_FOLDER_URI = "rom_folder_uri"
        const val PREF_ASPECT = "mupen64plus-aspect"
        const val PREF_RES_FACTOR = "mupen64plus-EnableNativeResFactor"
        const val DEFAULT_ASPECT = "4:3"
        const val DEFAULT_RES_FACTOR = "0"
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
        enableNin64EdgeToEdge()
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.topBar).applyTopBarInsets()
        findViewById<View>(R.id.settingsScroll).applyBottomContentInsets()

        folderPathText = findViewById(R.id.folderPathText)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.changeFolderButton).setOnClickListener {
            val current = prefs.getString(PREF_ROM_FOLDER_URI, null)?.let(Uri::parse)
            folderPicker.launch(current)
        }

        bindSpinner(
            spinnerId = R.id.aspectSpinner,
            labelsRes = R.array.aspect_labels,
            valuesRes = R.array.aspect_values,
            prefKey = PREF_ASPECT,
            defaultValue = DEFAULT_ASPECT
        )
        bindSpinner(
            spinnerId = R.id.resolutionSpinner,
            labelsRes = R.array.resolution_labels,
            valuesRes = R.array.resolution_values,
            prefKey = PREF_RES_FACTOR,
            defaultValue = DEFAULT_RES_FACTOR
        )

        findViewById<MaterialButton>(R.id.editTouchLayoutButton).setOnClickListener {
            TouchLayoutActivity.launch(this)
        }
        findViewById<MaterialButton>(R.id.editControllerMappingButton).setOnClickListener {
            GamepadMappingActivity.launch(this)
        }
        findViewById<MaterialButton>(R.id.resetControlsButton).setOnClickListener {
            ControlsRepository.resetGlobal(this)
            Toast.makeText(this, R.string.controls_global_reset_done, Toast.LENGTH_SHORT).show()
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

    private fun bindSpinner(
        spinnerId: Int,
        labelsRes: Int,
        valuesRes: Int,
        prefKey: String,
        defaultValue: String
    ) {
        val spinner = findViewById<Spinner>(spinnerId)
        val labels = resources.getStringArray(labelsRes)
        val values = resources.getStringArray(valuesRes)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val current = prefs.getString(prefKey, defaultValue) ?: defaultValue
        val index = values.indexOf(current).let { if (it < 0) values.indexOf(defaultValue).coerceAtLeast(0) else it }
        spinner.setSelection(index, false)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString(prefKey, values[position]).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
}
