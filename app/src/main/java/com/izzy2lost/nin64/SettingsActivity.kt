package com.izzy2lost.nin64

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val PREF_ROM_FOLDER_URI = "rom_folder_uri"
    }

    private val prefs by lazy { getSharedPreferences(DisplayOptionsRepository.PREFS_NAME, MODE_PRIVATE) }
    private val nativeAdPlacement by lazy { NativeAdPlacement(this) }

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
        findViewById<ImageButton>(R.id.removeAdsButton).setOnClickListener {
            showRemoveAdsPurchase()
        }
        findViewById<ImageButton>(R.id.aboutButton).setOnClickListener {
            showAboutDialog()
        }

        findViewById<MaterialButton>(R.id.changeFolderButton).setOnClickListener {
            val current = prefs.getString(PREF_ROM_FOLDER_URI, null)?.let(Uri::parse)
            folderPicker.launch(current)
        }

        bindSpinner(
            spinnerId = R.id.aspectSpinner,
            labelsRes = R.array.aspect_labels,
            valuesRes = R.array.aspect_values,
            prefKey = DisplayOptionsRepository.PREF_ASPECT,
            defaultValue = DisplayOptionsRepository.DEFAULT_ASPECT
        )
        bindSpinner(
            spinnerId = R.id.resolutionSpinner,
            labelsRes = R.array.resolution_labels,
            valuesRes = R.array.resolution_values,
            prefKey = DisplayOptionsRepository.PREF_RES_FACTOR,
            defaultValue = DisplayOptionsRepository.DEFAULT_RES_FACTOR
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
        nativeAdPlacement.loadInto(findViewById<FrameLayout>(R.id.settingsNativeAdContainer))
    }

    override fun onResume() {
        super.onResume()
        updateFolderDisplay()
    }

    override fun onDestroy() {
        nativeAdPlacement.destroy()
        super.onDestroy()
    }

    private fun updateFolderDisplay() {
        val uri = prefs.getString(PREF_ROM_FOLDER_URI, null)?.let(Uri::parse)
        folderPathText.text = if (uri != null) {
            DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: getString(R.string.game_folder_not_set)
        } else {
            getString(R.string.game_folder_not_set)
        }
    }

    private fun showRemoveAdsPurchase() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.no_ads_logo)
            .setTitle(R.string.remove_ads)
            .setMessage(R.string.remove_ads_purchase_unavailable)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAboutDialog() {
        val labels = resources.getStringArray(R.array.about_link_labels)
        val urls = resources.getStringArray(R.array.about_link_urls)

        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_info)
            .setTitle(R.string.about_nin64)
            .setItems(labels) { _, which ->
                urls.getOrNull(which)?.let(::openAboutLink)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAboutLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_link_unavailable, Toast.LENGTH_SHORT).show()
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
