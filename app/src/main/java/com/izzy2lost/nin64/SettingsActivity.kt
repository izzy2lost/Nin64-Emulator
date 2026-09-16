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

class SettingsActivity : AppCompatActivity(), NoAdsPurchaseManager.Listener {

    private companion object {
        const val PREF_ROM_FOLDER_URI = "rom_folder_uri"
    }

    private val prefs by lazy { getSharedPreferences(DisplayOptionsRepository.PREFS_NAME, MODE_PRIVATE) }
    private val nativeAdPlacement by lazy { NativeAdPlacement(this) }

    private lateinit var folderPathText: TextView
    private lateinit var removeAdsButton: ImageButton
    private lateinit var settingsNativeAdContainer: FrameLayout
    private lateinit var privacyOptionsRow: View
    private var removeAdsPurchaseBusy = false

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
        removeAdsButton = findViewById(R.id.removeAdsButton)
        removeAdsButton.setOnClickListener {
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

        privacyOptionsRow = findViewById(R.id.privacyOptionsRow)
        findViewById<MaterialButton>(R.id.privacyOptionsButton).setOnClickListener {
            showPrivacyOptions()
        }

        updateFolderDisplay()
        settingsNativeAdContainer = findViewById(R.id.settingsNativeAdContainer)
        updateRemoveAdsUi()
        updatePrivacyOptionsUi()
        nativeAdPlacement.loadInto(settingsNativeAdContainer)
    }

    override fun onStart() {
        super.onStart()
        NoAdsPurchaseManager.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        updateFolderDisplay()
        NoAdsPurchaseManager.refreshEntitlement(this) {
            updateRemoveAdsUi()
        }
        updatePrivacyOptionsUi()
    }

    override fun onStop() {
        NoAdsPurchaseManager.removeListener(this)
        super.onStop()
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
        if (AdsController.areAdsRemoved(this)) {
            showRemoveAdsAlreadyOwned()
            return
        }
        if (removeAdsPurchaseBusy) return

        removeAdsPurchaseBusy = true
        updateRemoveAdsUi()
        Toast.makeText(this, R.string.remove_ads_loading, Toast.LENGTH_SHORT).show()

        NoAdsPurchaseManager.refreshEntitlement(this) { adsRemoved ->
            if (!canShowBillingUi()) return@refreshEntitlement
            if (adsRemoved) {
                removeAdsPurchaseBusy = false
                updateRemoveAdsUi()
                showRemoveAdsAlreadyOwned()
                return@refreshEntitlement
            }

            NoAdsPurchaseManager.queryRemoveAdsOffer(this) { offer, billingResult ->
                if (!canShowBillingUi()) return@queryRemoveAdsOffer
                removeAdsPurchaseBusy = false
                updateRemoveAdsUi()

                if (offer == null) {
                    showRemoveAdsUnavailable(billingResult)
                    return@queryRemoveAdsOffer
                }

                MaterialAlertDialogBuilder(this)
                    .setIcon(R.drawable.no_ads_logo)
                    .setTitle(R.string.remove_ads)
                    .setMessage(getString(R.string.remove_ads_purchase_available, offer.formattedPrice))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.remove_ads) { _, _ ->
                        removeAdsPurchaseBusy = true
                        updateRemoveAdsUi()
                        NoAdsPurchaseManager.launchRemoveAdsPurchase(this, offer)
                    }
                    .show()
            }
        }
    }

    private fun showRemoveAdsAlreadyOwned() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.no_ads_logo)
            .setTitle(R.string.remove_ads)
            .setMessage(R.string.remove_ads_already_owned)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showRemoveAdsUnavailable(billingResult: com.android.billingclient.api.BillingResult) {
        val message = if (billingResult.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
            getString(R.string.remove_ads_purchase_unavailable)
        } else {
            val detail = billingResult.debugMessage
                .takeIf { it.isNotBlank() }
                ?: "Google Play Billing response ${billingResult.responseCode}"
            getString(R.string.remove_ads_purchase_failed, detail)
        }

        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.no_ads_logo)
            .setTitle(R.string.remove_ads)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onNoAdsEntitlementChanged(adsRemoved: Boolean) {
        removeAdsPurchaseBusy = false
        updateRemoveAdsUi()
        if (adsRemoved) {
            Toast.makeText(this, R.string.remove_ads_purchase_success, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNoAdsPurchasePending() {
        removeAdsPurchaseBusy = false
        updateRemoveAdsUi()
        Toast.makeText(this, R.string.remove_ads_purchase_pending, Toast.LENGTH_LONG).show()
    }

    override fun onNoAdsPurchaseCancelled() {
        removeAdsPurchaseBusy = false
        updateRemoveAdsUi()
        Toast.makeText(this, R.string.remove_ads_purchase_cancelled, Toast.LENGTH_SHORT).show()
    }

    override fun onNoAdsPurchaseError(message: String) {
        removeAdsPurchaseBusy = false
        updateRemoveAdsUi()
        Toast.makeText(this, getString(R.string.remove_ads_purchase_failed, message), Toast.LENGTH_LONG).show()
    }

    private fun updateRemoveAdsUi() {
        if (!::removeAdsButton.isInitialized) return

        val adsRemoved = AdsController.areAdsRemoved(this)
        removeAdsButton.isEnabled = !adsRemoved && !removeAdsPurchaseBusy
        removeAdsButton.alpha = when {
            adsRemoved -> 0.45f
            removeAdsPurchaseBusy -> 0.65f
            else -> 1f
        }

        if (adsRemoved && ::settingsNativeAdContainer.isInitialized) {
            nativeAdPlacement.destroy()
            settingsNativeAdContainer.removeAllViews()
            settingsNativeAdContainer.visibility = View.GONE
        }
    }

    private fun showPrivacyOptions() {
        ConsentManager.showPrivacyOptionsForm(this) { formError ->
            if (isFinishing || isDestroyed) return@showPrivacyOptionsForm

            if (formError != null) {
                Toast.makeText(
                    this,
                    getString(R.string.privacy_options_failed, formError.message),
                    Toast.LENGTH_LONG,
                ).show()
            }
            updatePrivacyOptionsUi()
        }
    }

    private fun updatePrivacyOptionsUi() {
        if (!::privacyOptionsRow.isInitialized) return

        privacyOptionsRow.visibility = if (ConsentManager.isPrivacyOptionsRequired(this)) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun canShowBillingUi(): Boolean {
        return !isFinishing && !isDestroyed
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
