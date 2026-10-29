package com.izzy2lost.nin64

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var configPathText: TextView
    private lateinit var romFolderText: TextView
    private lateinit var gamesHeaderText: TextView
    private lateinit var romRecyclerView: RecyclerView
    private lateinit var framePreviewImage: ImageView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var pickFolderButton: android.widget.Button
    private lateinit var emptyRomListText: TextView
    private lateinit var viewToggleButton: ImageButton
    private lateinit var scanProgressGroup: View
    private lateinit var scanProgressText: TextView
    private lateinit var scanProgressBar: LinearProgressIndicator
    private lateinit var listAdapter: ListAdapter
    private lateinit var gridAdapter: GridAdapter
    private val romEntries = mutableListOf<RomEntry>()
    private var viewMode: String = VIEW_MODE_LIST
    private var pendingTexturePackEntry: RomEntry? = null
    @Volatile private var coverWarmupGeneration = 0
    private val libraryCache by lazy { LibraryCache(File(filesDir, "library.json")) }
    private var loadedFolderUri: Uri? = null
    private var scanGeneration = 0
    private var scanning = false
    private var coverRefreshQueued = false

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

    private val texturePackPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val entry = pendingTexturePackEntry
            pendingTexturePackEntry = null
            if (uri == null || entry == null) {
                return@registerForActivityResult
            }

            importTexturePack(entry, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableNin64EdgeToEdge()
        setContentView(R.layout.activity_main)

        val headerLayout = findViewById<RelativeLayout>(R.id.headerLayout)
        headerLayout.applyTopBarInsets()

        statusText = findViewById(R.id.statusText)
        configPathText = findViewById(R.id.configPathText)
        romFolderText = findViewById(R.id.romFolderText)
        gamesHeaderText = findViewById(R.id.gamesHeaderText)
        romRecyclerView = findViewById(R.id.romRecyclerView)
        framePreviewImage = findViewById(R.id.framePreviewImage)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout.applyBottomContentInsets()

        pickFolderButton = findViewById(R.id.pickFolderButton)
        emptyRomListText = findViewById(R.id.emptyRomListText)
        viewToggleButton = findViewById(R.id.viewToggleButton)
        scanProgressGroup = findViewById(R.id.scanProgressGroup)
        scanProgressText = findViewById(R.id.scanProgressText)
        scanProgressBar = findViewById(R.id.scanProgressBar)

        listAdapter = ListAdapter()
        gridAdapter = GridAdapter()

        RomDatabase.init(this, File(preferredRootDir(), "Mupen64plus/mupen64plus.ini"))
        CoverMatcher.init(this, "$COVER_BASE_URL/index.txt") {
            runOnUiThread {
                scheduleCoverRefresh()
                warmCoverCache(romEntries.toList())
            }
        }

        viewMode = prefs.getString(PREF_VIEW_MODE, VIEW_MODE_LIST) ?: VIEW_MODE_LIST
        applyViewMode()

        viewToggleButton.setOnClickListener { toggleViewMode() }

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
        findViewById<ImageButton>(R.id.joinMultiplayerButton).setOnClickListener { showJoinMultiplayer() }

        updateEmptyState()

        if (savedInstanceState == null) {
            ConsentManager.gatherConsent(this) { canRequestAds ->
                if (canRequestAds) {
                    AppOpenAdManager.loadAd(this)
                }
            }
        }

        if (intent.getStringExtra(EXTRA_BOOT_ROM_PATH) != null) {
            handleBootIntent()
        }
    }

    override fun onResume() {
        super.onResume()
        updateEmptyState()
        // The library is kept while the app runs: coming back from a game does not
        // rescan it. A new folder (picked in Settings) does; pull to refresh for new files.
        if (readSavedRomFolderUri() != loadedFolderUri) {
            loadAvailableRoms()
        }
    }

    private fun preferredRootDir(): File {
        return getExternalFilesDir(null) ?: filesDir
    }

    private fun bootCacheDirectory(): File {
        return File(cacheDir, "roms").apply {
            mkdirs()
        }
    }

    private fun loadAvailableRoms() {
        val folderUri = readSavedRomFolderUri()
        updateRomFolderLabel(folderUri)
        clearPreview()

        val generation = ++scanGeneration
        scanning = folderUri != null
        if (folderUri != loadedFolderUri) {
            loadedFolderUri = folderUri
            // Show the last scan straight away; the rescan below only reads what changed.
            showRoms(folderUri?.let { libraryCache.load(it).values.mapNotNull { scanned -> scanned.entry } }.orEmpty())
        }
        if (folderUri == null) {
            swipeRefreshLayout.isRefreshing = false
            updateEmptyState()
            return
        }

        val incremental = romEntries.isEmpty()
        Thread {
            val result = runCatching {
                scanRomFolder(folderUri) { read, toRead, found ->
                    runOnUiThread {
                        if (generation != scanGeneration) return@runOnUiThread
                        showScanProgress(read, toRead)
                        if (incremental && found != null) showRoms(found)
                    }
                }
            }

            runOnUiThread {
                if (generation != scanGeneration) return@runOnUiThread
                scanning = false
                scanProgressGroup.visibility = View.GONE
                result.onSuccess { roms ->
                    showRoms(roms)
                    warmCoverCache(roms)
                }.onFailure { error ->
                    showRoms(emptyList())
                    statusText.text = error.stackTraceToString()
                }
                swipeRefreshLayout.isRefreshing = false
                updateEmptyState()
            }
        }.apply {
            name = "Nin64-LibraryScan"
            start()
        }
    }

    private fun showRoms(roms: List<RomEntry>) {
        if (roms != romEntries) {
            romEntries.clear()
            romEntries.addAll(roms)
            romRecyclerView.adapter?.notifyDataSetChanged()
        }
        updateGamesHeader(roms.size)
        emptyRomListText.visibility = if (roms.isEmpty() && !scanning) View.VISIBLE else View.GONE
    }

    private fun showScanProgress(read: Int, toRead: Int) {
        scanProgressGroup.visibility = View.VISIBLE
        scanProgressText.text = getString(R.string.scanning_games_progress, read, toRead)
        scanProgressBar.max = toRead
        scanProgressBar.setProgressCompat(read, true)
    }

    private fun warmCoverCache(roms: List<RomEntry>) {
        if (!CoverMatcher.isReady() || roms.isEmpty()) return

        val generation = ++coverWarmupGeneration
        val appContext = applicationContext
        Thread {
            val coverFileNames = roms
                .mapNotNull(::coverFileNameFor)
                .distinct()
            if (generation == coverWarmupGeneration && coverFileNames.isNotEmpty()) {
                CoverMatcher.prefetchCovers(appContext, coverFileNames, COVER_BASE_URL) {
                    runOnUiThread { scheduleCoverRefresh() }
                }
            }
        }.apply {
            name = "Nin64-CoverWarmup"
            start()
        }
    }

    private fun scheduleCoverRefresh() {
        if (coverRefreshQueued) return
        coverRefreshQueued = true
        romRecyclerView.postDelayed({
            coverRefreshQueued = false
            romRecyclerView.adapter?.notifyDataSetChanged()
        }, 250L)
    }

    private fun updateEmptyState() {
        val hasFolderConfigured = readSavedRomFolderUri() != null
        pickFolderButton.visibility = if (hasFolderConfigured) android.view.View.GONE else android.view.View.VISIBLE
        romRecyclerView.visibility = if (hasFolderConfigured) android.view.View.VISIBLE else android.view.View.GONE
        if (!hasFolderConfigured) emptyRomListText.visibility = android.view.View.GONE
        swipeRefreshLayout.isEnabled = hasFolderConfigured
    }

    /**
     * Lists the folder and reads only the files that are new or changed since the last
     * scan. [onProgress] gets the files read so far out of those to read and, every few
     * files, the games found so far.
     */
    private fun scanRomFolder(
        folderUri: Uri,
        onProgress: (read: Int, toRead: Int, found: List<RomEntry>?) -> Unit,
    ): List<RomEntry> {
        val cached = libraryCache.load(folderUri)
        val files = listLibraryFiles(folderUri)
        val toRead = files.count { file -> cached[file.uri.toString()]?.matches(file) != true }
        if (toRead > 0) onProgress(0, toRead, null)

        val scanned = mutableListOf<Pair<LibraryFile, RomEntry?>>()
        var read = 0
        var lastPublished = 0L
        for (file in files) {
            val hit = cached[file.uri.toString()]?.takeIf { it.matches(file) }
            if (hit != null) {
                scanned += file to hit.entry
                continue
            }
            // A file that cannot be read is left out of the cache, so the next scan retries it.
            runCatching {
                romEntryFor(file.name, documentUri = file.uri) { contentResolver.openInputStream(file.uri) }
            }.onSuccess { entry -> scanned += file to entry }

            read++
            val now = System.currentTimeMillis()
            val publish = now - lastPublished > 500 || read == toRead
            if (publish) lastPublished = now
            onProgress(read, toRead, if (publish) scanned.mapNotNull { it.second } else null)
        }

        val withCovers = scanned.map { (file, entry) ->
            file to entry?.let { it.copy(coverFile = coverFileNameFor(it) ?: it.coverFile) }
        }
        libraryCache.save(folderUri, withCovers)
        return withCovers.mapNotNull { it.second }
    }

    /** Every game file under the tree, folder by folder in name order, from one query per folder. */
    private fun listLibraryFiles(treeUri: Uri): List<LibraryFile> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val files = mutableListOf<LibraryFile>()
        val folders = ArrayDeque<String>()
        folders.add(DocumentsContract.getTreeDocumentId(treeUri))

        while (folders.isNotEmpty()) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folders.removeFirst())
            val children = mutableListOf<Triple<String, String, Boolean>>()
            val stamps = mutableMapOf<String, Pair<Long, Long>>()
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val isFolder = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    children += Triple(id, name, isFolder)
                    stamps[id] = (if (cursor.isNull(3)) -1L else cursor.getLong(3)) to
                        (if (cursor.isNull(4)) -1L else cursor.getLong(4))
                }
            } ?: error(getString(R.string.invalid_rom_folder))

            for ((id, name, isFolder) in children.sortedBy { it.second.lowercase() }) {
                when {
                    isFolder -> folders.addLast(id)
                    isLibraryFile(name) -> {
                        val (size, modified) = stamps.getValue(id)
                        files += LibraryFile(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, size, modified)
                    }
                }
            }
        }
        return files
    }

    private fun isRomFile(name: String?): Boolean {
        val extension = name
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()

        return extension in ROM_EXTENSIONS
    }

    private fun isLibraryFile(name: String?): Boolean =
        isRomFile(name) || ZippedRoms.isZip(name)

    private fun DocumentFile.toRomEntry(): RomEntry? =
        romEntryFor(name ?: getString(R.string.unknown_game_file), documentUri = uri) {
            contentResolver.openInputStream(uri)
        }

    private fun File.toRomEntry(): RomEntry? =
        romEntryFor(name, localFile = this) { inputStream() }

    private fun romEntryFor(
        fileName: String,
        localFile: File? = null,
        documentUri: Uri? = null,
        open: () -> java.io.InputStream?,
    ): RomEntry? {
        val fallbackName = fileName.substringBeforeLast('.', fileName)
        var zipEntryName: String? = null
        val identity = open()?.use { input ->
            if (ZippedRoms.isZip(fileName)) {
                // A zip with no N64 game inside is somebody else's archive.
                ZipInputStream(input.buffered()).use { zip ->
                    val rom = ZippedRoms.seekRom(zip, ::isRomFile) ?: return null
                    zipEntryName = rom.name
                    RomIdentityReader.read(zip, fallbackName)
                }
            } else {
                RomIdentityReader.read(input, fallbackName)
            }
        }
        val databaseName = identity?.let(RomDatabase::goodNameFor)
        val displayName = databaseName ?: identity?.headerTitle ?: fallbackName

        return RomEntry(
            displayName = displayName,
            fileName = fileName,
            fallbackName = fallbackName,
            headerTitle = identity?.headerTitle ?: fallbackName,
            md5 = identity?.md5,
            crc = identity?.crc,
            databaseName = databaseName,
            localFile = localFile,
            documentUri = documentUri,
            zipEntryName = zipEntryName,
        )
    }

    private fun bootSelectedRom(
        entry: RomEntry,
        loadStateSlot: Int = NO_LOAD_STATE_SLOT,
        netplayRole: Int = GameActivity.NETPLAY_NONE,
        netplayHost: String? = null,
        netplayPort: Int = Netplay.SESSION_PORT,
    ) {
        statusText.text = getString(R.string.booting_rom_status, entry.cleanDisplayName())

        Thread {
            try {
                val rootDir = preferredRootDir().also { it.mkdirs() }
                val romFile = prepareRomForBoot(entry)
                val options = perGameOptionsFor(entry)
                runOnUiThread {
                    GameActivity.launch(
                        this,
                        rootDir.absolutePath,
                        romFile.absolutePath,
                        useTexturePack = options.useTexturePack,
                        disableExpansionPak = options.disableExpansionPak,
                        romPreferenceKey = entry.preferenceKey(),
                        romCrc = entry.crc,
                        loadStateSlot = loadStateSlot,
                        netplayRole = netplayRole,
                        netplayHost = netplayHost,
                        netplayPort = netplayPort,
                        romMd5 = entry.md5,
                        gameTitle = entry.cleanDisplayName(),
                    )
                }
            } catch (t: Throwable) {
                val error = t.stackTraceToString()
                runOnUiThread { statusText.text = error }
            }
        }.start()
    }

    private fun prepareRomForBoot(entry: RomEntry): File {
        entry.zipEntryName?.let { zipEntryName ->
            return ZippedRoms.unpack(this, entry.preferenceKey(), zipEntryName) {
                entry.localFile?.inputStream() ?: entry.documentUri?.let(contentResolver::openInputStream)
            }
        }
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
        if (uri == null) {
            romFolderText.text = ""
            return
        }

        val folderName = DocumentFile.fromTreeUri(this, uri)?.name
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: uri.toString()

        romFolderText.text = folderName
    }

    private fun updateGamesHeader(count: Int) {
        gamesHeaderText.text = getString(R.string.games_header_count, count)
    }

    private fun handleBootIntent() {
        val bootRomPath = intent.getStringExtra(EXTRA_BOOT_ROM_PATH) ?: return
        val romFile = File(bootRomPath)

        when {
            romFile.canRead() -> romFile.toRomEntry()?.let { bootSelectedRom(it) }
                ?: run { statusText.text = getString(R.string.debug_boot_missing, bootRomPath) }
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
            ?.takeIf { it.isFile && isLibraryFile(it.name) }
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

    private fun applyViewMode() {
        when (viewMode) {
            VIEW_MODE_GRID -> {
                romRecyclerView.layoutManager = GridLayoutManager(this, gridSpanCount())
                romRecyclerView.adapter = gridAdapter
                viewToggleButton.setImageResource(R.drawable.ic_view_list)
                viewToggleButton.contentDescription = getString(R.string.switch_to_list)
            }
            else -> {
                romRecyclerView.layoutManager = LinearLayoutManager(this)
                romRecyclerView.adapter = listAdapter
                viewToggleButton.setImageResource(R.drawable.ic_view_grid)
                viewToggleButton.contentDescription = getString(R.string.switch_to_grid)
            }
        }
    }

    private fun gridSpanCount(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2

    private fun toggleViewMode() {
        viewMode = if (viewMode == VIEW_MODE_LIST) VIEW_MODE_GRID else VIEW_MODE_LIST
        prefs.edit().putString(PREF_VIEW_MODE, viewMode).apply()
        applyViewMode()
    }

    private fun showGameOptions(entry: RomEntry) {
        val hasTexturePack = texturePackFileFor(entry).isFile
        val texturePackEnabled = isTexturePackEnabled(entry)
        val expansionPakDisabled = isExpansionPakDisabled(entry)
        val blue = ContextCompat.getColor(this, R.color.brand_blue)
        val yellow = ContextCompat.getColor(this, R.color.brand_yellow)
        val green = ContextCompat.getColor(this, R.color.brand_green)
        val red = ContextCompat.getColor(this, R.color.brand_red)

        val actions = mutableListOf(
            GameOptionAction(
                title = getString(R.string.game_options_play),
                summary = getString(R.string.game_options_play_summary),
                iconRes = R.drawable.ic_play_arrow,
                containerColor = blue,
                iconTint = Color.WHITE,
                onSelected = { bootSelectedRom(entry) },
            ),
            GameOptionAction(
                title = getString(R.string.game_options_load_save),
                summary = getString(R.string.game_options_load_save_summary),
                iconRes = R.drawable.ic_save,
                containerColor = green,
                iconTint = Color.WHITE,
                onSelected = { showLoadSaveStateDialog(entry) },
            ),
        )
        // Players are matched by the ROM's md5, so a game without one cannot be hosted.
        if (entry.md5 != null) {
            actions += GameOptionAction(
                title = getString(R.string.netplay_host_action),
                summary = getString(R.string.netplay_host_summary),
                iconRes = R.drawable.ic_multiplayer,
                containerColor = blue,
                iconTint = Color.WHITE,
                onSelected = { bootSelectedRom(entry, netplayRole = GameActivity.NETPLAY_HOST) },
            )
        }
        actions.addAll(listOf(
            GameOptionAction(
                title = getString(
                    if (hasTexturePack) {
                        R.string.game_options_replace_texture_pack
                    } else {
                        R.string.game_options_add_texture_pack
                    }
                ),
                summary = getString(
                    if (hasTexturePack) {
                        R.string.game_options_texture_pack_replace_summary
                    } else {
                        R.string.game_options_texture_pack_add_summary
                    }
                ),
                iconRes = R.drawable.ic_image,
                containerColor = yellow,
                iconTint = Color.BLACK,
                onSelected = { pickTexturePack(entry) },
            ),
        ))

        if (hasTexturePack) {
            actions += GameOptionAction(
                title = getString(
                    if (texturePackEnabled) {
                        R.string.game_options_disable_texture_pack
                    } else {
                        R.string.game_options_enable_texture_pack
                    }
                ),
                summary = getString(
                    if (texturePackEnabled) {
                        R.string.game_options_texture_pack_enabled_summary
                    } else {
                        R.string.game_options_texture_pack_disabled_summary
                    }
                ),
                iconRes = R.drawable.ic_image,
                containerColor = if (texturePackEnabled) red else green,
                iconTint = Color.WHITE,
                onSelected = { setTexturePackEnabled(entry, !texturePackEnabled) },
            )
            actions += GameOptionAction(
                title = getString(R.string.game_options_remove_texture_pack),
                summary = getString(R.string.game_options_texture_pack_remove_summary),
                iconRes = R.drawable.ic_delete,
                containerColor = red,
                iconTint = Color.WHITE,
                onSelected = { removeTexturePack(entry) },
            )
        }
        actions += GameOptionAction(
            title = getString(
                if (expansionPakDisabled) {
                    R.string.game_options_enable_expansion_pak
                } else {
                    R.string.game_options_disable_expansion_pak
                }
            ),
            summary = getString(
                if (expansionPakDisabled) {
                    R.string.game_options_expansion_pak_disabled_summary
                } else {
                    R.string.game_options_expansion_pak_enabled_summary
                }
            ),
            iconRes = R.drawable.ic_memory,
            containerColor = if (expansionPakDisabled) green else red,
            iconTint = Color.WHITE,
            onSelected = { setExpansionPakDisabled(entry, !expansionPakDisabled) },
        )
        actions += GameOptionAction(
            title = getString(R.string.cheats_manager),
            summary = getString(R.string.game_options_cheats_summary),
            iconRes = R.drawable.ic_cheats,
            containerColor = blue,
            iconTint = Color.WHITE,
            onSelected = { CheatManagerActivity.launch(this, entry.preferenceKey(), entry.crc, entry.cleanDisplayName()) },
        )
        actions += GameOptionAction(
            title = getString(R.string.game_options_configure_display),
            summary = getString(R.string.game_options_display_summary),
            iconRes = R.drawable.ic_display,
            containerColor = yellow,
            iconTint = Color.BLACK,
            onSelected = { GameDisplaySettingsActivity.launch(this, entry.preferenceKey(), entry.cleanDisplayName()) },
        )
        actions += GameOptionAction(
            title = getString(R.string.game_options_configure_controls),
            summary = getString(R.string.game_options_controls_summary),
            iconRes = R.drawable.ic_videogame,
            containerColor = green,
            iconTint = Color.WHITE,
            onSelected = { GameControlSettingsActivity.launch(this, entry.preferenceKey(), entry.cleanDisplayName()) },
        )

        showGameOptionsDialog(entry, actions)
    }

    /** Lists games hosted on this Wi-Fi as they are found; picking one boots the same game from this library. */
    private fun showJoinMultiplayer() {
        val beacons = mutableListOf<Netplay.Beacon>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        val wifi = Netplay.WifiLocks(this).also { it.acquire() }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val scanner = try {
            Netplay.Scanner { found ->
                runOnUiThread {
                    beacons.clear()
                    beacons.addAll(found)
                    adapter.clear()
                    adapter.addAll(found.map { getString(R.string.netplay_host_entry, it.title, it.device, it.players, Netplay.MAX_PLAYERS) })
                    dialog?.setMessage(if (found.isEmpty()) getString(R.string.netplay_searching) else null)
                }
            }
        } catch (e: Exception) {
            wifi.release()
            Toast.makeText(this, getString(R.string.netplay_join_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        val list = ListView(this).apply { this.adapter = adapter }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.netplay_join_title)
            .setIcon(R.drawable.ic_multiplayer)
            .setMessage(R.string.netplay_searching)
            .setView(list)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            scanner.close()
            wifi.release()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val beacon = beacons.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            joinMultiplayer(beacon)
        }
        dialog.show()
    }

    private fun joinMultiplayer(beacon: Netplay.Beacon) {
        val entry = romEntries.firstOrNull { it.md5.equals(beacon.md5, ignoreCase = true) }
        if (entry == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.netplay_join)
                .setMessage(getString(R.string.netplay_missing_game, beacon.title))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        bootSelectedRom(
            entry,
            netplayRole = GameActivity.NETPLAY_GUEST,
            netplayHost = beacon.address.hostAddress,
            netplayPort = beacon.port,
        )
    }

    private fun showGameOptionsDialog(entry: RomEntry, actions: List<GameOptionAction>) {
        val content = layoutInflater.inflate(R.layout.dialog_game_options, null)
        content.findViewById<TextView>(R.id.gameOptionsTitle).text = entry.cleanDisplayName()
        val list = content.findViewById<LinearLayout>(R.id.gameOptionsList)
        var dialog: androidx.appcompat.app.AlertDialog? = null

        actions.forEach { action ->
            val row = layoutInflater.inflate(R.layout.item_game_option_action, list, false)
            row.findViewById<TextView>(R.id.gameOptionTitle).text = action.title
            row.findViewById<TextView>(R.id.gameOptionSummary).text = action.summary
            row.findViewById<ImageView>(R.id.gameOptionIcon).apply {
                setImageResource(action.iconRes)
                setColorFilter(action.iconTint)
            }
            row.findViewById<View>(R.id.gameOptionIconChip).background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16.dp.toFloat()
                setColor(action.containerColor)
            }
            row.setOnClickListener {
                dialog?.dismiss()
                action.onSelected()
            }
            list.addView(row)
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(content)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }

    private fun showLoadSaveStateDialog(entry: RomEntry) {
        val rootDir = preferredRootDir().also { it.mkdirs() }
        val content = layoutInflater.inflate(R.layout.dialog_game_options, null)
        content.findViewById<TextView>(R.id.gameOptionsHeader).text = getString(R.string.in_game_load_state)
        content.findViewById<TextView>(R.id.gameOptionsTitle).text = entry.cleanDisplayName()

        val list = content.findViewById<LinearLayout>(R.id.gameOptionsList)
        var dialog: androidx.appcompat.app.AlertDialog? = null

        for (slot in 1..SaveStateFiles.SAVE_SLOT_COUNT) {
            val stateFile = SaveStateFiles.stateFile(rootDir.absolutePath, filesDir, entry.preferenceKey(), "", slot)
            val thumbFile = SaveStateFiles.thumbnailFile(rootDir.absolutePath, filesDir, entry.preferenceKey(), "", slot)
            val hasState = stateFile.isFile

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp, 10.dp, 14.dp, 10.dp)
                minimumHeight = 76.dp
                setBackgroundResource(R.drawable.bg_game_options_row)
                isEnabled = hasState
                alpha = if (hasState) 1f else 0.55f
            }

            val thumbView = ImageView(this).apply {
                setBackgroundColor(Color.rgb(22, 23, 30))
                scaleType = ImageView.ScaleType.CENTER_CROP
                val bitmap = if (thumbFile.isFile) BitmapFactory.decodeFile(thumbFile.absolutePath) else null
                setImageBitmap(bitmap)
                alpha = if (bitmap != null) 1f else 0.35f
                contentDescription = getString(R.string.in_game_state_thumbnail)
            }
            row.addView(thumbView, LinearLayout.LayoutParams(96.dp, 60.dp).apply { marginEnd = 14.dp })

            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            textColumn.addView(TextView(this).apply {
                text = getString(R.string.save_state_slot_label, slot)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface))
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            textColumn.addView(TextView(this).apply {
                text = getString(
                    if (hasState) {
                        R.string.in_game_save_state_available
                    } else {
                        R.string.in_game_no_save_state
                    }
                )
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_surface_muted))
                textSize = 13f
            })
            row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (hasState) {
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener {
                    dialog?.dismiss()
                    bootSelectedRom(entry, loadStateSlot = slot)
                }
            }

            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8.dp })
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setView(content)
            .setNegativeButton(R.string.done, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }

    private fun pickTexturePack(entry: RomEntry) {
        pendingTexturePackEntry = entry
        texturePackPicker.launch(arrayOf("*/*"))
    }

    private fun importTexturePack(entry: RomEntry, uri: Uri) {
        val sourceName = displayNameForUri(uri)

        val dialogView = layoutInflater.inflate(R.layout.dialog_texture_pack_progress, null)
        dialogView.findViewById<TextView>(R.id.progress_message).text =
            getString(R.string.texture_pack_importing, sourceName)
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.texture_pack_importing_title)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            val result = runCatching {
                installTexturePackFromUri(entry, uri, sourceName)
            }

            runOnUiThread {
                progressDialog.dismiss()
                result.onSuccess { importResult ->
                    Toast.makeText(
                        this,
                        getString(R.string.texture_pack_imported, importResult.sourceName, entry.cleanDisplayName()),
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        getString(R.string.texture_pack_import_failed, error.message ?: error.toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun installTexturePackFromUri(entry: RomEntry, uri: Uri, sourceName: String): TexturePackImportResult {
        val target = texturePackFileFor(entry)
        val mimeType = contentResolver.getType(uri).orEmpty().lowercase(Locale.US)
        val normalizedName = sourceName.lowercase(Locale.US)
        val importedSourceName = when {
            normalizedName.endsWith(".hts") -> copyDirectHts(uri, target)
            normalizedName.endsWith(".zip") || mimeType.contains("zip") -> copyHtsFromZip(uri, sourceName, target)
            normalizedName.endsWith(".7z") || mimeType.contains("7z") -> copyHtsFromSevenZ(uri, sourceName, target)
            else -> error(getString(R.string.texture_pack_unsupported_format))
        }

        prefs.edit()
            .putBoolean(texturePackEnabledPrefKey(entry), true)
            .putString(texturePackSourcePrefKey(entry), importedSourceName)
            .apply()

        return TexturePackImportResult(importedSourceName)
    }

    private fun copyDirectHts(uri: Uri, target: File): String {
        contentResolver.openInputStream(uri)?.use { input ->
            writeAtomically(target) { output ->
                input.copyTo(output)
            }
        } ?: error(getString(R.string.texture_pack_open_failed))

        return target.name
    }

    private fun copyHtsFromZip(uri: Uri, sourceName: String, target: File): String {
        var selectedName: String? = null
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryFileName = archiveFileName(entry.name)
                    if (!entry.isDirectory && entryFileName.endsWith(".hts", ignoreCase = true)) {
                        if (selectedName == null || entryFileName.equals(target.name, ignoreCase = true)) {
                            writeAtomically(target) { output ->
                                zip.copyTo(output)
                            }
                            selectedName = entryFileName
                            if (entryFileName.equals(target.name, ignoreCase = true)) {
                                break
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error(getString(R.string.texture_pack_open_failed))

        return selectedName ?: error(getString(R.string.texture_pack_missing_hts, sourceName))
    }

    private fun copyHtsFromSevenZ(uri: Uri, sourceName: String, target: File): String {
        val archive = File.createTempFile("nin64-texture-pack-", ".7z", cacheDir)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                archive.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error(getString(R.string.texture_pack_open_failed))

            var selectedName: String? = null
            SevenZFile.builder().setFile(archive).get().use { sevenZ ->
                var entry = sevenZ.nextEntry
                while (entry != null) {
                    val entryFileName = archiveFileName(entry.name)
                    if (!entry.isDirectory && entry.hasStream() && entryFileName.endsWith(".hts", ignoreCase = true)) {
                        if (selectedName == null || entryFileName.equals(target.name, ignoreCase = true)) {
                            writeAtomically(target) { output ->
                                sevenZ.copyCurrentEntryTo(output)
                            }
                            selectedName = entryFileName
                            if (entryFileName.equals(target.name, ignoreCase = true)) {
                                break
                            }
                        }
                    }
                    entry = sevenZ.nextEntry
                }
            }

            return selectedName ?: error(getString(R.string.texture_pack_missing_hts, sourceName))
        } finally {
            archive.delete()
        }
    }

    private fun SevenZFile.copyCurrentEntryTo(output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) {
                break
            }
            if (read > 0) {
                output.write(buffer, 0, read)
            }
        }
    }

    private fun writeAtomically(target: File, writer: (OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.delete()

        try {
            temp.outputStream().use(writer)
            if (target.exists() && !target.delete()) {
                error(getString(R.string.texture_pack_replace_failed))
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun removeTexturePack(entry: RomEntry) {
        val target = texturePackFileFor(entry)
        val removed = !target.exists() || target.delete()
        if (removed) {
            prefs.edit()
                .putBoolean(texturePackEnabledPrefKey(entry), false)
                .remove(texturePackSourcePrefKey(entry))
                .apply()
            Toast.makeText(this, getString(R.string.texture_pack_removed, entry.cleanDisplayName()), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.texture_pack_remove_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun setTexturePackEnabled(entry: RomEntry, enabled: Boolean) {
        prefs.edit().putBoolean(texturePackEnabledPrefKey(entry), enabled).apply()
        val message = if (enabled) {
            getString(R.string.texture_pack_enabled, entry.cleanDisplayName())
        } else {
            getString(R.string.texture_pack_disabled, entry.cleanDisplayName())
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setExpansionPakDisabled(entry: RomEntry, disabled: Boolean) {
        prefs.edit().putBoolean(expansionPakDisabledPrefKey(entry), disabled).apply()
        val message = if (disabled) {
            getString(R.string.expansion_pak_disabled, entry.cleanDisplayName())
        } else {
            getString(R.string.expansion_pak_enabled, entry.cleanDisplayName())
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun perGameOptionsFor(entry: RomEntry): PerGameOptions {
        return PerGameOptions(
            useTexturePack = texturePackFileFor(entry).isFile && isTexturePackEnabled(entry),
            disableExpansionPak = isExpansionPakDisabled(entry),
        )
    }

    private fun texturePackFileFor(entry: RomEntry): File {
        return File(texturePackCacheDirectory(), texturePackFileNameFor(entry))
    }

    private fun texturePackCacheDirectory(): File {
        return File(preferredRootDir(), "Mupen64plus/cache").apply {
            mkdirs()
        }
    }

    private fun texturePackFileNameFor(entry: RomEntry): String {
        val ident = entry.headerTitle
            .ifBlank { entry.fallbackName }
            .replace(':', '-')
            .replace('/', '-')
        return "${ident}_HIRESTEXTURES.hts"
    }

    private fun isTexturePackEnabled(entry: RomEntry): Boolean {
        return prefs.getBoolean(texturePackEnabledPrefKey(entry), true)
    }

    private fun isExpansionPakDisabled(entry: RomEntry): Boolean {
        return prefs.getBoolean(expansionPakDisabledPrefKey(entry), false)
    }

    private fun texturePackEnabledPrefKey(entry: RomEntry): String =
        "per_game.${entry.preferenceKey()}.texture_pack_enabled"

    private fun texturePackSourcePrefKey(entry: RomEntry): String =
        "per_game.${entry.preferenceKey()}.texture_pack_source"

    private fun expansionPakDisabledPrefKey(entry: RomEntry): String =
        "per_game.${entry.preferenceKey()}.disable_expansion_pak"

    private fun displayNameForUri(uri: Uri): String {
        return DocumentFile.fromSingleUri(this, uri)?.name
            ?: uri.lastPathSegment
            ?: getString(R.string.game_options_add_texture_pack)
    }

    private fun archiveFileName(path: String): String =
        path.replace('\\', '/').substringAfterLast('/')

    private fun coverFileNameFor(entry: RomEntry): String? {
        CoverMatcher.resolve(entry.coverCandidates())?.let { return it }
        if (!CoverMatcher.isReady()) return entry.coverFile

        val stem = entry.fileName.substringBeforeLast('.', entry.fileName).trim()
        return stem.takeIf { it.isNotEmpty() }?.let { "$it.webp" }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).roundToInt()

    /**
     * With a controller, the game under the cursor grows a little and lifts;
     * [style] adds each layout's own highlight. Recycled rows are reset on bind.
     */
    private fun highlightFocusedGame(item: View, scale: Float, style: (focused: Boolean) -> Unit) {
        item.setOnFocusChangeListener { view, focused ->
            style(focused)
            view.animate()
                .scaleX(if (focused) scale else 1f)
                .scaleY(if (focused) scale else 1f)
                .translationZ(if (focused) 8.dp.toFloat() else 0f)
                .setDuration(120)
                .start()
        }
    }

    private fun resetFocusHighlight(item: View, scale: Float, style: (focused: Boolean) -> Unit) {
        val focused = item.isFocused
        style(focused)
        item.animate().cancel()
        item.scaleX = if (focused) scale else 1f
        item.scaleY = if (focused) scale else 1f
        item.translationZ = if (focused) 8.dp.toFloat() else 0f
    }

    private inner class ListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val strokeColors = intArrayOf(
            Color.parseColor("#0056EA"),
            Color.parseColor("#FEDF5A"),
            Color.parseColor("#00C063"),
            Color.parseColor("#D93131"),
        )

        inner class RomViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val card: com.google.android.material.card.MaterialCardView =
                itemView as com.google.android.material.card.MaterialCardView
            val text: TextView = itemView.findViewById(R.id.romTitle)
            private val normalTextColor = text.textColors

            /** Focused, the row fills with its outline colour. */
            fun style(focused: Boolean) {
                val color = card.strokeColor
                card.setCardBackgroundColor(if (focused) color else Color.TRANSPARENT)
                card.strokeWidth = ((if (focused) 3f else 1.5f) * resources.displayMetrics.density).roundToInt()
                if (focused) {
                    // The yellow row needs dark text.
                    text.setTextColor(if (color == strokeColors[1]) Color.BLACK else Color.WHITE)
                } else {
                    text.setTextColor(normalTextColor)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.list_item_rom, parent, false)
            return RomViewHolder(view).also { holder ->
                highlightFocusedGame(view, LIST_FOCUS_SCALE, holder::style)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            holder as RomViewHolder
            val entry = romEntries[position]
            holder.text.text = entry.listLabel()
            holder.card.strokeColor = strokeColors[position % 4]
            resetFocusHighlight(holder.itemView, LIST_FOCUS_SCALE, holder::style)
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    romEntries.getOrNull(pos)?.let(::bootSelectedRom)
                }
            }
            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                val entry = if (pos != RecyclerView.NO_POSITION) romEntries.getOrNull(pos) else null
                if (entry != null) {
                    showGameOptions(entry)
                    true
                } else {
                    false
                }
            }
        }

        override fun getItemCount() = romEntries.size
    }

    private inner class GridAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class RomViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val cover: ImageView = itemView.findViewById(R.id.coverImage)
            val title: TextView = itemView.findViewById(R.id.gameTitle)
            private val coverCard: com.google.android.material.card.MaterialCardView = itemView.findViewById(R.id.coverCard)
            private val normalTitleColor = title.textColors
            private val focusColor = ContextCompat.getColor(this@MainActivity, R.color.brand_blue)

            /** Focused, the cover gets a bold outline and the title a coloured label. */
            fun style(focused: Boolean) {
                coverCard.strokeColor = focusColor
                coverCard.strokeWidth = if (focused) 4.dp else 0
                if (focused) {
                    title.background = GradientDrawable().apply {
                        cornerRadius = 8.dp.toFloat()
                        setColor(focusColor)
                    }
                    title.setTextColor(Color.WHITE)
                } else {
                    title.background = null
                    title.setTextColor(normalTitleColor)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_rom_grid, parent, false)
            return RomViewHolder(view).also { holder ->
                highlightFocusedGame(view, GRID_FOCUS_SCALE, holder::style)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            holder as RomViewHolder
            resetFocusHighlight(holder.itemView, GRID_FOCUS_SCALE, holder::style)
            // Recompute height on every bind so recycled holders get the right size after rotation.
            val rv = romRecyclerView
            val spanCount = (rv.layoutManager as? GridLayoutManager)?.spanCount ?: gridSpanCount()
            val rvWidth = (rv.width - rv.paddingStart - rv.paddingEnd).takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            val coverHeight = (rvWidth / spanCount / 1.21f).toInt().coerceAtLeast(1)
            val titleHeight = holder.title.lineHeight * 2 + holder.title.paddingTop + holder.title.paddingBottom
            holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
                height = coverHeight + titleHeight
            }

            val entry = romEntries[position]
            holder.title.text = entry.cleanDisplayName()
            holder.cover.contentDescription = entry.cleanDisplayName()

            val coverFile = coverFileNameFor(entry)
            val cachedCover = coverFile?.let {
                CoverMatcher.cachedCoverOrQueueDownload(this@MainActivity, it, COVER_BASE_URL) {
                    runOnUiThread { scheduleCoverRefresh() }
                }
            }

            if (cachedCover != null) {
                holder.cover.load(cachedCover) {
                    crossfade(true)
                    placeholder(R.drawable.logo)
                    error(R.drawable.logo)
                }
            } else {
                holder.cover.dispose()
                holder.cover.setImageResource(R.drawable.logo)
            }

            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    romEntries.getOrNull(pos)?.let(::bootSelectedRom)
                }
            }
            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                val entry = if (pos != RecyclerView.NO_POSITION) romEntries.getOrNull(pos) else null
                if (entry != null) {
                    showGameOptions(entry)
                    true
                } else {
                    false
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is RomViewHolder) {
                holder.cover.dispose()
            }
            super.onViewRecycled(holder)
        }

        override fun getItemCount() = romEntries.size
    }

    private data class PerGameOptions(
        val useTexturePack: Boolean,
        val disableExpansionPak: Boolean,
    )

    private data class GameOptionAction(
        val title: String,
        val summary: String,
        val iconRes: Int,
        val containerColor: Int,
        val iconTint: Int,
        val onSelected: () -> Unit,
    )

    private data class TexturePackImportResult(
        val sourceName: String,
    )

    companion object {
        private const val PREFS_NAME = "nin64_prefs"
        private const val PREF_ROM_FOLDER_URI = "rom_folder_uri"
        private const val PREF_VIEW_MODE = "view_mode"
        private const val EXTRA_BOOT_ROM_PATH = "bootRomPath"

        private const val VIEW_MODE_LIST = "list"
        private const val VIEW_MODE_GRID = "grid"
        private const val NO_LOAD_STATE_SLOT = -1
        private const val GRID_FOCUS_SCALE = 1.07f
        private const val LIST_FOCUS_SCALE = 1.03f

        private const val COVER_BASE_URL =
            "https://raw.githubusercontent.com/izzy2lost/n64_covers/main"

        private val ROM_EXTENSIONS = setOf("z64", "n64", "v64", "rom", "bin")

    }
}
