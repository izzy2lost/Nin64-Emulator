package com.izzy2lost.nin64

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var configPathText: TextView
    private lateinit var romFolderText: TextView
    private lateinit var gamesHeaderText: TextView
    private lateinit var romListView: ListView
    private lateinit var framePreviewImage: ImageView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var pickFolderButton: android.widget.Button
    private lateinit var romAdapter: ArrayAdapter<String>
    private val romEntries = mutableListOf<RomEntry>()

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                statusText.text = getString(R.string.folder_pick_cancelled)
                return@registerForActivityResult
            }

            persistRomFolderPermission(uri)
            saveRomFolderUri(uri)
            updateEmptyState()
            loadAvailableRoms()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        configPathText = findViewById(R.id.configPathText)
        romFolderText = findViewById(R.id.romFolderText)
        gamesHeaderText = findViewById(R.id.gamesHeaderText)
        romListView = findViewById(R.id.romListView)
        framePreviewImage = findViewById(R.id.framePreviewImage)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        pickFolderButton = findViewById(R.id.pickFolderButton)

        val emptyRomListText = findViewById<TextView>(R.id.emptyRomListText)

        romAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        romListView.adapter = romAdapter
        romListView.emptyView = emptyRomListText
        romListView.setOnItemClickListener { _, _, position, _ ->
            romEntries.getOrNull(position)?.let(::bootSelectedRom)
        }

        configPathText.text = File(preferredRootDir(), ULTRA_INI_NAME).absolutePath
        updateRomFolderLabel(readSavedRomFolderUri())
        updateGamesHeader(0)

        swipeRefreshLayout.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.brand_blue),
            ContextCompat.getColor(this, R.color.brand_yellow),
            ContextCompat.getColor(this, R.color.brand_green),
            ContextCompat.getColor(this, R.color.brand_red),
        )
        swipeRefreshLayout.setOnRefreshListener { loadAvailableRoms() }

        pickFolderButton.setOnClickListener {
            folderPicker.launch(readSavedRomFolderUri())
        }

        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateEmptyState()

        if (intent.getStringExtra(EXTRA_BOOT_ROM_PATH) != null) {
            handleBootIntent()
        }
    }

    override fun onResume() {
        super.onResume()
        updateEmptyState()
        loadAvailableRoms()
    }

    private fun ensureConfigReady(): File {
        val rootDir = preferredRootDir()
        val target = File(rootDir, ULTRA_INI_NAME)

        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }

        val existingBody = when {
            !target.exists() -> null
            target.readText().startsWith(LEGACY_ASSET_HEADER) -> null
            else -> target.readText()
        }
        val configBody = buildConfigBody(existingBody, rootDir)
        val shouldWrite = !target.exists() || target.readText() != configBody

        if (shouldWrite) {
            target.writeText(configBody)
        }

        return rootDir
    }

    private fun buildConfigBody(existingBody: String?, rootDir: File): String {
        val normalizedRoot = rootDir.absolutePath.replace('\\', '/') + "/"
        val normalizedRomPath = appRomDirectory().absolutePath.replace('\\', '/') + "/"
        val baseBody = existingBody ?: assets.open(ULTRA_INI_NAME).bufferedReader().use { reader ->
            reader.readText()
        }

        val normalizedBody = baseBody
            .lineSequence()
            .map { line ->
                when {
                    line.startsWith("savepath=") -> "savepath=$normalizedRoot"
                    line.startsWith("rompath=") -> "rompath=$normalizedRomPath"
                    else -> line
                }
            }
            .joinToString("\n")

        return ensureSectionSetting(
            text = normalizedBody,
            sectionName = "GOLDENEYE",
            key = "directsp",
            value = "0",
        ).trimEnd() + "\n"
    }

    private fun ensureSectionSetting(
        text: String,
        sectionName: String,
        key: String,
        value: String,
    ): String {
        val lines = text.lines().toMutableList()
        val header = "[$sectionName]"
        var sectionStart = -1
        var sectionEnd = lines.size

        for (index in lines.indices) {
            if (lines[index].trim() == header) {
                sectionStart = index
                break
            }
        }

        if (sectionStart < 0) {
            return text
        }

        for (index in sectionStart + 1 until lines.size) {
            if (lines[index].startsWith("[") && lines[index].endsWith("]")) {
                sectionEnd = index
                break
            }
        }

        for (index in sectionStart + 1 until sectionEnd) {
            if (lines[index].startsWith("$key=")) {
                lines[index] = "$key=$value"
                return lines.joinToString("\n")
            }
        }

        lines.add(sectionEnd, "$key=$value")
        return lines.joinToString("\n")
    }

    private fun preferredRootDir(): File {
        return getExternalFilesDir(null) ?: filesDir
    }

    private fun appRomDirectory(): File {
        return File(preferredRootDir(), APP_ROMS_DIR_NAME).apply {
            mkdirs()
        }
    }

    private fun bootCacheDirectory(): File {
        return File(cacheDir, APP_ROMS_DIR_NAME).apply {
            mkdirs()
        }
    }

    private fun loadAvailableRoms() {
        val savedFolderUri = readSavedRomFolderUri()
        updateRomFolderLabel(savedFolderUri)
        statusText.text = getString(R.string.scanning_games)
        clearPreview()

        Thread {
            val result = runCatching {
                val combined = mutableListOf<RomEntry>()
                combined += scanLocalRomFolder(appRomDirectory())
                if (savedFolderUri != null) {
                    combined += scanDocumentRomFolder(savedFolderUri)
                }
                combined.sortedWith(
                    compareBy<RomEntry> { it.displayName.lowercase() }
                        .thenBy { it.fileName.lowercase() }
                )
            }

            runOnUiThread {
                result.onSuccess { roms ->
                    romEntries.clear()
                    romEntries.addAll(roms)
                    romAdapter.clear()
                    romAdapter.addAll(roms.map { it.listLabel() })
                    romAdapter.notifyDataSetChanged()
                    updateGamesHeader(roms.size)
                    statusText.text = if (roms.isEmpty()) {
                        getString(R.string.no_games_found_status, appRomDirectory().absolutePath)
                    } else {
                        getString(R.string.games_found_status, roms.size)
                    }
                }.onFailure { error ->
                    romEntries.clear()
                    romAdapter.clear()
                    updateGamesHeader(0)
                    statusText.text = error.stackTraceToString()
                }
                swipeRefreshLayout.isRefreshing = false
                updateEmptyState()
            }
        }.start()
    }

    private fun updateEmptyState() {
        val hasFolderConfigured = readSavedRomFolderUri() != null
        pickFolderButton.visibility = if (hasFolderConfigured) android.view.View.GONE else android.view.View.VISIBLE
        romListView.visibility = if (hasFolderConfigured) android.view.View.VISIBLE else android.view.View.GONE
        swipeRefreshLayout.isEnabled = hasFolderConfigured
    }

    private fun scanDocumentRomFolder(uri: Uri): List<RomEntry> {
        val root = DocumentFile.fromTreeUri(this, uri)
            ?: error(getString(R.string.invalid_rom_folder))

        val queue = ArrayDeque<DocumentFile>()
        val roms = mutableListOf<RomEntry>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles()
                .sortedBy { document -> document.name?.lowercase().orEmpty() }

            for (child in children) {
                when {
                    child.isDirectory -> queue.addLast(child)
                    child.isFile && isRomFile(child.name) -> roms += child.toRomEntry()
                }
            }
        }

        return roms
    }

    private fun scanLocalRomFolder(root: File): List<RomEntry> {
        if (!root.exists()) {
            return emptyList()
        }

        return root.walkTopDown()
            .filter { file -> file.isFile && isRomFile(file.name) }
            .sortedBy { file -> file.name.lowercase() }
            .map { file -> file.toRomEntry() }
            .toList()
    }

    private fun isRomFile(name: String?): Boolean {
        val extension = name
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()

        return extension in ROM_EXTENSIONS
    }

    private fun DocumentFile.toRomEntry(): RomEntry {
        val fileName = name ?: getString(R.string.unknown_game_file)
        val fallbackName = fileName.substringBeforeLast('.', fileName)
        val displayName = contentResolver.openInputStream(uri)?.use { input ->
            readRomTitle(input, fallbackName)
        } ?: fallbackName

        return RomEntry(
            displayName = displayName,
            fileName = fileName,
            fallbackName = fallbackName,
            documentUri = uri,
        )
    }

    private fun File.toRomEntry(): RomEntry {
        val fileName = name
        val fallbackName = fileName.substringBeforeLast('.', fileName)
        val displayName = FileInputStream(this).use { input ->
            readRomTitle(input, fallbackName)
        }

        return RomEntry(
            displayName = displayName,
            fileName = fileName,
            fallbackName = fallbackName,
            localFile = this,
        )
    }

    private fun readRomTitle(input: InputStream, fallbackName: String): String {
        val header = readHeaderBytes(input)

        if (header.size < 0x34) {
            return fallbackName
        }

        val normalized = normalizeRomHeader(header)
        val rawTitle = normalized
            .copyOfRange(0x20, 0x34)
            .toString(StandardCharsets.US_ASCII)
            .replace(Regex("\\s+"), " ")
            .trim { it <= ' ' || it == '\u0000' }

        return rawTitle.ifBlank { fallbackName }
    }

    private fun readHeaderBytes(input: InputStream): ByteArray {
        val header = ByteArray(0x40)
        var offset = 0

        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read <= 0) {
                break
            }
            offset += read
        }

        return header.copyOf(offset)
    }

    private fun normalizeRomHeader(header: ByteArray): ByteArray {
        val normalized = header.copyOf()
        if (normalized.size < 4) {
            return normalized
        }

        val b0 = normalized[0]
        val b1 = normalized[1]
        val b2 = normalized[2]
        val b3 = normalized[3]

        if (b0 == 0x37.toByte() && b1 == 0x80.toByte() &&
            b2 == 0x40.toByte() && b3 == 0x12.toByte()
        ) {
            for (index in 0 until normalized.size - 1 step 2) {
                val tmp = normalized[index]
                normalized[index] = normalized[index + 1]
                normalized[index + 1] = tmp
            }
        } else if (b0 == 0x40.toByte() && b1 == 0x12.toByte() &&
            b2 == 0x37.toByte() && b3 == 0x80.toByte()
        ) {
            for (index in 0 until normalized.size - 3 step 4) {
                val tmp0 = normalized[index]
                val tmp1 = normalized[index + 1]
                normalized[index] = normalized[index + 3]
                normalized[index + 1] = normalized[index + 2]
                normalized[index + 2] = tmp1
                normalized[index + 3] = tmp0
            }
        }

        return normalized
    }

    private fun bootSelectedRom(entry: RomEntry) {
        statusText.text = getString(R.string.booting_rom_status, entry.displayName)

        Thread {
            try {
                val rootDir = ensureConfigReady()
                val romFile = prepareRomForBoot(entry)
                runOnUiThread {
                    GameActivity.launch(this, rootDir.absolutePath, romFile.absolutePath)
                }
            } catch (t: Throwable) {
                val error = t.stackTraceToString()
                runOnUiThread { statusText.text = error }
            }
        }.start()
    }

    private fun prepareRomForBoot(entry: RomEntry): File {
        entry.localFile?.let { return it }

        val documentUri = entry.documentUri
            ?: error(getString(R.string.invalid_rom_folder))
        val cachedRom = File(bootCacheDirectory(), entry.fileName)

        contentResolver.openInputStream(documentUri)?.use { input ->
            cachedRom.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error(getString(R.string.copy_rom_failed, entry.fileName))

        return cachedRom
    }

    private fun clearPreview() {
        framePreviewImage.setImageBitmap(null)
        framePreviewImage.alpha = 0.2f
    }

    private fun persistRomFolderPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
    }

    private fun saveRomFolderUri(uri: Uri) {
        prefs.edit().putString(PREF_ROM_FOLDER_URI, uri.toString()).apply()
    }

    private fun readSavedRomFolderUri(): Uri? {
        return prefs.getString(PREF_ROM_FOLDER_URI, null)?.let(Uri::parse)
    }

    private fun updateRomFolderLabel(uri: Uri?) {
        val localPath = appRomDirectory().absolutePath
        if (uri == null) {
            romFolderText.text = getString(R.string.rom_sources_local_only, localPath)
            return
        }

        val folderName = DocumentFile.fromTreeUri(this, uri)?.name
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: uri.toString()

        romFolderText.text = getString(
            R.string.rom_sources_selected,
            localPath,
            folderName,
            uri.toString(),
        )
    }

    private fun updateGamesHeader(count: Int) {
        gamesHeaderText.text = getString(R.string.games_header_count, count)
    }

    private fun handleBootIntent() {
        val bootRomPath = intent.getStringExtra(EXTRA_BOOT_ROM_PATH) ?: return
        val romFile = File(bootRomPath)

        when {
            romFile.canRead() -> bootSelectedRom(romFile.toRomEntry())
            else -> {
                val resolvedEntry = resolveBootIntentEntry(bootRomPath)
                if (resolvedEntry != null) {
                    bootSelectedRom(resolvedEntry)
                } else {
                    statusText.text = getString(R.string.debug_boot_missing, bootRomPath)
                }
            }
        }
    }

    private fun resolveBootIntentEntry(bootRomPath: String): RomEntry? {
        val savedFolderUri = readSavedRomFolderUri() ?: return null
        val treeRoot = DocumentFile.fromTreeUri(this, savedFolderUri) ?: return null
        val relativePath = resolveRelativePathFromTree(savedFolderUri, bootRomPath)
        val matchedDocument = relativePath?.let { findDocumentByRelativePath(treeRoot, it) }
            ?: findDocumentByFileName(treeRoot, File(bootRomPath).name)

        return matchedDocument
            ?.takeIf { it.isFile && isRomFile(it.name) }
            ?.toRomEntry()
    }

    private fun resolveRelativePathFromTree(treeUri: Uri, bootRomPath: String): String? {
        val treeRootPath = treeUriToExternalStoragePath(treeUri) ?: return null
        val normalizedBootPath = normalizeExternalPath(bootRomPath)
        val normalizedRootPath = normalizeExternalPath(treeRootPath)
        if (!normalizedBootPath.startsWith(normalizedRootPath)) {
            return null
        }

        return normalizedBootPath
            .removePrefix(normalizedRootPath)
            .trimStart('/')
            .takeIf { it.isNotEmpty() }
    }

    private fun treeUriToExternalStoragePath(treeUri: Uri): String? {
        val documentId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull()
        val decodedId = documentId?.let(Uri::decode) ?: return null
        val root = when {
            decodedId == "primary:" -> "/storage/emulated/0"
            decodedId.startsWith("primary:") -> "/storage/emulated/0/${decodedId.removePrefix("primary:")}"
            else -> return null
        }
        return normalizeExternalPath(root)
    }

    private fun normalizeExternalPath(path: String): String {
        val normalized = path.replace('\\', '/')
        return when {
            normalized == "/sdcard" -> "/storage/emulated/0"
            normalized.startsWith("/sdcard/") -> normalized.replaceFirst("/sdcard", "/storage/emulated/0")
            else -> normalized.trimEnd('/')
        }
    }

    private fun findDocumentByRelativePath(root: DocumentFile, relativePath: String): DocumentFile? {
        var current: DocumentFile? = root
        for (segment in relativePath.split('/').filter { it.isNotBlank() }) {
            current = current?.findFile(segment) ?: return null
        }
        return current
    }

    private fun findDocumentByFileName(root: DocumentFile, fileName: String): DocumentFile? {
        val queue = ArrayDeque<DocumentFile>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in current.listFiles()) {
                when {
                    child.isDirectory -> queue.addLast(child)
                    child.isFile && child.name == fileName -> return child
                }
            }
        }

        return null
    }

    private data class RomEntry(
        val displayName: String,
        val fileName: String,
        val fallbackName: String,
        val localFile: File? = null,
        val documentUri: Uri? = null,
    ) {
        fun listLabel(): String {
            return if (displayName.equals(fallbackName, ignoreCase = true)) {
                displayName
            } else {
                "$displayName ($fileName)"
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "nin64_prefs"
        private const val PREF_ROM_FOLDER_URI = "rom_folder_uri"
        private const val ULTRA_INI_NAME = "ultra.ini"
        private const val LEGACY_ASSET_HEADER = "// UltraHLE initialization file V1.0.0"
        private const val APP_ROMS_DIR_NAME = "roms"
        private const val EXTRA_BOOT_ROM_PATH = "bootRomPath"

        private val ROM_EXTENSIONS = setOf("z64", "n64", "v64", "rom", "bin")
    }
}
