package com.dictation.server

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dictation.server.core.AppPrefs
import com.dictation.server.service.DictationService
import com.dictation.server.ui.*
import java.io.File

class MainActivity : ComponentActivity() {

    private var service: DictationService? = null
    private var isBound by mutableStateOf(false)
    private var stateVersion by mutableStateOf(0)

    var selectedFilePath by mutableStateOf<String?>(null)
        private set
    var convertProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var convertError by mutableStateOf<String?>(null)
        private set
    var convertOutput by mutableStateOf<String?>(null)
        private set
    var isConverting by mutableStateOf(false)
        private set

    // In-app document browser (instead of the system SAF picker): browse
    // /sdcard folders directly and pick a real file path.
    var showFileBrowser by mutableStateOf(false)
        private set

    // Relay inbox grid overlay (AI replies / transcriptions awaiting review).
    var showInbox by mutableStateOf(false)
        private set

    fun openInbox() { showInbox = true }
    fun closeInbox() { showInbox = false }

    /** Plugin deep-link: extra "open"="inbox" opens the grid; "main" always
     *  lands on the main screen even if the grid was left open last time. */
    private fun routeIntent(intent: Intent?) {
        when (intent?.getStringExtra("open")) {
            "inbox" -> showInbox = true
            "main" -> showInbox = false
        }
    }
    // Bumped on resume so the browser's directory listing re-reads after the
    // user returns from granting all-files access.
    var uiRefresh by mutableStateOf(0)
        private set

    companion object {
        const val BUNDLED_MAIN_FONT = "main.ttf"
        const val BUNDLED_FALLBACK_FONT = "fallback.ttf"
        private const val BASE_FONT_SIZE = 32
    }

    // +8 boost on Supernote A6X (1404×1872 screens).
    private fun resolvedFontSize(): Int {
        val m = resources.displayMetrics
        val short = minOf(m.widthPixels, m.heightPixels)
        val long = maxOf(m.widthPixels, m.heightPixels)
        return if (short == 1404 && long == 1872) BASE_FONT_SIZE + 8 else BASE_FONT_SIZE
    }

    // Runtime permission request for WRITE_EXTERNAL_STORAGE (Android 8.1–10).
    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled implicitly via re-check in startConvert */ }

    private fun hasPublicWritePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePublicWritePermission() {
        if (hasPublicWritePermission()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: need MANAGE_EXTERNAL_STORAGE via Settings.
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        } else {
            // Android 8.1–10: runtime grant.
            storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // Extracts a bundled font from APK assets to filesDir on first use.
    // FreeType needs a real filesystem path; assets URIs won't work.
    private fun ensureBundledFont(assetName: String): String {
        val outFile = File(filesDir, "fonts/$assetName")
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath
        outFile.parentFile?.mkdirs()
        assets.open("fonts/$assetName").use { input ->
            outFile.outputStream().use { input.copyTo(it) }
        }
        return outFile.absolutePath
    }

    // ── In-app document browser ──────────────────────────────────────────
    fun openFileBrowser() {
        // Browsing /sdcard needs all-files access; prompt if missing. The
        // browser also shows a grant button when listing comes back empty.
        if (!hasPublicWritePermission()) ensurePublicWritePermission()
        showFileBrowser = true
    }

    fun closeFileBrowser() { showFileBrowser = false }

    fun hasStorageAccess(): Boolean = hasPublicWritePermission()
    fun requestStorageAccess() = ensurePublicWritePermission()

    fun onBrowserFileSelected(path: String) {
        selectedFilePath = path
        convertError = null
        convertOutput = null
        showFileBrowser = false
    }

    fun startConvert() {
        val input = selectedFilePath ?: return
        if (isConverting) return
        isConverting = true
        convertError = null
        convertOutput = null
        convertProgress = 0 to 0

        val isComic = InklingSettings.comicMode.value
        val outExt = if (isComic) ".inkling.cbz" else ".inkling.pdf"
        val outName = File(input).nameWithoutExtension + outExt
        // Supernote's file browser only sees public top-level dirs like
        // /Document, /Note, /Inbox. Write there when public write permission
        // is granted; otherwise prompt the user and fall back to the
        // app-specific dir so the conversion can still complete.
        val outDir: File = if (hasPublicWritePermission()) {
            File("/storage/emulated/0/Document/Inkling")
        } else {
            ensurePublicWritePermission()
            getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        }
        outDir.mkdirs()
        val output = File(outDir, outName).absolutePath
        val jobId = System.currentTimeMillis().toString()

        Thread({
            try {
                val listener = object : InklingNative.ProgressListener {
                    override fun onProgress(jobId: String, stage: Int, percent: Int) {
                        convertProgress = stage to percent
                    }
                }
                val optionsJson = org.json.JSONObject().apply {
                    put("fontPath", ensureBundledFont(BUNDLED_MAIN_FONT))
                    put("fontPathFallback", ensureBundledFont(BUNDLED_FALLBACK_FONT))
                    put("fontSize", resolvedFontSize())
                    put("pageWidth", 1404)
                    put("pageHeight", 1872)
                    put("marginTop", 50)
                    put("marginRight", 50)
                    put("marginBottom", 50)
                    put("marginLeft", 90)
                    put("lineHeightMul", 1.28)
                    // Advanced settings (mirrored from InklingSettings).
                    put("drawRuling", InklingSettings.drawRuling.value)
                    put("syntheticBold", InklingSettings.syntheticBold.value)
                    put("contrastBoost", InklingSettings.contrastBoost.value.toDouble())
                    put("embedTextLayer", InklingSettings.embedTextLayer.value)
                    put("embedBookmarks", InklingSettings.embedBookmarks.value)
                    put("splitLandscape", InklingSettings.splitLandscape.value)
                    put("jpegQuality", InklingSettings.jpegQuality.value)
                    // Comic settings
                    put("comicMode", InklingSettings.comicMode.value)
                    put("ditherAlgo", InklingSettings.ditherAlgo.value)
                    put("ditherLevels", 16)
                    if (InklingSettings.comicResize.value) {
                        put("comicResizeW", InklingSettings.comicResizeW.value)
                        put("comicResizeH", InklingSettings.comicResizeH.value)
                    }
                }.toString()
                val rc = InklingNative.nativeConvert(
                    input, output, optionsJson, jobId, listener
                )
                if (rc == 0) {
                    convertOutput = output
                } else {
                    convertError = getString(R.string.convert_failed, rc)
                }
            } catch (t: Throwable) {
                convertError = t.message ?: getString(R.string.unknown_error)
            } finally {
                isConverting = false
            }
        }, "inkling-convert").start()
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as DictationService.LocalBinder).getService().also {
                it.onStateChanged = { stateVersion++ }
            }
            isBound = true
            stateVersion++
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; isBound = false
        }
    }

    private fun forceUnbind() {
        if (isBound) { unbindService(conn); isBound = false; service = null }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensurePublicWritePermission()
        routeIntent(intent)

        setContent {
            @Suppress("UNUSED_EXPRESSION") stateVersion
            Box(Modifier.fillMaxSize()) {
                InklingScreen(
                    activity = this@MainActivity,
                    svc = service,
                    onBack = { moveTaskToBack(true) },
                    onClose = {
                        startService(DictationService.stopIntent(this@MainActivity))
                        forceUnbind()
                        finishAndRemoveTask()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                )
                if (showFileBrowser) {
                    @Suppress("UNUSED_EXPRESSION") uiRefresh
                    FileBrowser(
                        activity = this@MainActivity,
                        isComic = InklingSettings.comicMode.value,
                        onPick = { path -> onBrowserFileSelected(path) },
                        onClose = { closeFileBrowser() },
                    )
                }
                if (showInbox) {
                    InboxGridScreen(
                        messages = service?.relayInbox?.snapshot() ?: emptyList(),
                        onOpen = { id ->
                            startActivity(MessageDetailActivity.intent(this@MainActivity, id))
                        },
                        onDelete = { id -> service?.deleteInboxMessage(id) },
                        onDeleteMany = { ids -> service?.deleteInboxMessages(ids) },
                        onMerge = { ids -> service?.mergeInboxMessages(ids) },
                        onClear = { service?.clearInbox() },
                        onPin = { id, pinned -> service?.pinInboxMessage(id, pinned) },
                        onReadd = { ids -> service?.readdInboxMessages(ids) },
                        // Grid top-left: hide Dictation to the background (same
                        // as the main screen's back), not navigate to main UI.
                        onBack = { moveTaskToBack(true) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the all-files-access settings screen: refresh the
        // browser listing so newly-granted access takes effect.
        uiRefresh++
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, DictationService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, conn, 0)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) { unbindService(conn); isBound = false }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Composables — relay flavor screens, built from the shared e-ink kit.
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun InklingScreen(
    activity: MainActivity,
    svc: DictationService?,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val selectedFile = activity.selectedFilePath
    val progress = activity.convertProgress
    val error = activity.convertError
    val output = activity.convertOutput
    val converting = activity.isConverting

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
    ) {
        EinkTitleBar(
            title = "Inkling",
            onBack = onBack,
            rightLabel = stringResource(R.string.close),
            onRight = onClose,
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 30.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // File selection
            EinkCard(Modifier.fillMaxWidth(), title = stringResource(R.string.select_document)) {
                if (selectedFile != null) {
                    Text(
                        File(selectedFile).name,
                        fontSize = 15.sp,
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        selectedFile,
                        fontSize = 12.sp,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                EinkButton(
                    label = if (selectedFile == null) stringResource(R.string.select_file) else stringResource(R.string.reselect),
                    onClick = { activity.openFileBrowser() },
                )
                Text(
                    stringResource(R.string.support_comic),
                    fontSize = 13.sp,
                    color = Muted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }

            EinkCard(Modifier.fillMaxWidth(), title = stringResource(R.string.grayscale_dither)) {
                EinkSegmented(
                    labels = listOf(
                        stringResource(R.string.dither_off),
                        stringResource(R.string.dither_bayer),
                        stringResource(R.string.dither_fs),
                    ),
                    selected = InklingSettings.ditherAlgo.value,
                    onSelect = { InklingSettings.ditherAlgo.value = it },
                )
                if (InklingSettings.ditherAlgo.value > 0) {
                    Text(
                        stringResource(R.string.dither_desc),
                        fontSize = 12.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            EinkCard(Modifier.fillMaxWidth(), title = stringResource(R.string.scale)) {
                EinkCheckRow(
                    label = stringResource(R.string.scale_to_device),
                    checked = InklingSettings.comicResize.value,
                    onToggle = { InklingSettings.comicResize.value = it },
                )
                if (InklingSettings.comicResize.value) {
                    val curIdx = if (InklingSettings.comicResizeW.value == 1920) 1 else 0
                    EinkSegmented(
                        labels = listOf("1404×1872", "1920×2560"),
                        selected = curIdx,
                        onSelect = { i ->
                            if (i == 0) {
                                InklingSettings.comicResizeW.value = 1404
                                InklingSettings.comicResizeH.value = 1872
                            } else {
                                InklingSettings.comicResizeW.value = 1920
                                InklingSettings.comicResizeH.value = 2560
                            }
                        },
                    )
                }
            }

            if (InklingSettings.ditherAlgo.value > 0) {
                EinkCard(Modifier.fillMaxWidth(), title = stringResource(R.string.jpeg_quality)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${InklingSettings.jpegQuality.value}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                            modifier = Modifier.width(36.dp),
                        )
                        EinkSlider(
                            value = InklingSettings.jpegQuality.value.toFloat(),
                            onValueChange = { InklingSettings.jpegQuality.value = it.toInt() },
                            valueRange = 50f..100f,
                            steps = 49,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Progress / result
            if (converting && progress != null) {
                val stageNames = listOf(
                    stringResource(R.string.stage_parse),
                    stringResource(R.string.stage_layout),
                    stringResource(R.string.stage_render),
                    stringResource(R.string.stage_pack),
                )
                val stageName = stageNames.getOrElse(progress.first) { stringResource(R.string.stage_processing) }
                EinkCard(Modifier.fillMaxWidth(), title = stringResource(R.string.converting_section)) {
                    Text(
                        stringResource(R.string.stage_progress, stageName, progress.second),
                        fontSize = 15.sp,
                        color = Ink,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .einkBorder()
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress.second / 100f)
                                .background(Ink)
                        )
                    }
                }
            }

            if (output != null) {
                EinkCard(Modifier.fillMaxWidth(), title = stringResource(R.string.convert_done)) {
                    Text(File(output).name, fontSize = 15.sp, color = Ink)
                    Text(
                        output,
                        fontSize = 12.sp,
                        color = Muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (error != null) {
                EinkCard(Modifier.fillMaxWidth(), title = stringResource(R.string.error_section)) {
                    Text(error, fontSize = 14.sp, color = Ink)
                }
            }

            EinkButton(
                label = if (converting) stringResource(R.string.btn_converting) else stringResource(R.string.btn_start_convert),
                onClick = { activity.startConvert() },
                primary = true,
                enabled = selectedFile != null && !converting,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Bottom relay bar
        RelayBar(svc = svc, onOpenInbox = { activity.openInbox() })
    }
}

// ── Bottom relay bar ────────────────────────────────────────────────────────

@Composable
fun RelayBar(svc: DictationService?, onOpenInbox: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Both links run concurrently and the service routes automatically
    // (phone first, LLM/VPS fallback); the chip only picks which endpoint
    // the IP field edits.
    var editVps by remember { mutableStateOf(false) }
    val phoneConnected = (svc?.connectedClients ?: 0) > 0
    val llmConnected = svc?.isLlmConnected == true
    val connected = phoneConnected || llmConnected
    var multiReply by remember { mutableStateOf(AppPrefs.multiReply(context)) }
    var ipText by remember(svc, editVps) {
        mutableStateOf(if (editVps) AppPrefs.llmHost(context) else AppPrefs.phoneIp(context))
    }
    // Whether the in-app e-ink numeric keypad is raised. We never call the
    // system IME — tapping the IP field toggles this custom keypad instead.
    var showKeypad by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .topLine()
            .background(Paper)
            .padding(horizontal = 30.dp, vertical = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (connected) Ink else Muted, RectangleShape)
            )
            Text(
                when {
                    phoneConnected -> stringResource(R.string.relay_connected)
                    llmConnected -> stringResource(R.string.relay_connected) + " · VPS"
                    else -> stringResource(R.string.relay_disconnected)
                },
                fontSize = 13.sp,
                color = if (connected) Ink else Muted,
            )

            Spacer(Modifier.weight(1f))

            // Read-only display that opens the custom keypad on tap. A blinking
            // bar after the text stands in for a cursor when the keypad is up.
            Box(
                Modifier
                    .weight(2f)
                    .einkBorder()
                    .background(if (showKeypad) Faint else Paper, RectangleShape)
                    .clickable { showKeypad = !showKeypad }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    if (ipText.isEmpty()) stringResource(R.string.phone_ip) else if (showKeypad) "$ipText|" else ipText,
                    fontSize = 14.sp,
                    color = if (ipText.isEmpty()) Muted else Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            EinkChip(
                label = "VPS",
                filled = editVps,
                onClick = { editVps = !editVps },
            )

            EinkChip(
                label = stringResource(R.string.multi_reply),
                filled = multiReply,
                onClick = {
                    multiReply = !multiReply
                    AppPrefs.setMultiReply(context, multiReply)
                },
            )

            EinkButton(
                label = stringResource(R.string.btn_connect),
                onClick = {
                    if (ipText.isNotBlank()) {
                        showKeypad = false
                        if (editVps) {
                            svc?.startLlmBridge(ipText.trim())
                        } else {
                            svc?.reconnectRelay(ipText.trim())
                        }
                    }
                },
                enabled = ipText.isNotBlank(),
            )

            EinkButton(
                label = stringResource(R.string.btn_inbox),
                onClick = onOpenInbox,
            )

            EinkButton(
                label = stringResource(R.string.btn_dev),
                onClick = {
                    val ctx = svc?.applicationContext ?: return@EinkButton
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
        }

        // Raised in-app keypad — digits and dot only, no system IME involved.
        if (showKeypad) {
            EinkNumpad(
                onKey = { c -> ipText += c },
                onDelete = { if (ipText.isNotEmpty()) ipText = ipText.dropLast(1) },
                onClear = { ipText = "" },
                onDone = { showKeypad = false },
            )
        }
    }
}

// ── E-ink numeric keypad (no system IME) ─────────────────────────────────────
// A 3-column digit grid styled to match the monochrome relay UI. Used to fill
// the IP field without ever raising the Android soft keyboard.

@Composable
fun EinkNumpad(
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "⌫"),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(54.dp)
                            .einkBorder()
                            .background(Paper, RectangleShape)
                            .clickable {
                                when (key) {
                                    "⌫" -> onDelete()
                                    else -> onKey(key[0])
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            key,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EinkButton(label = stringResource(R.string.numpad_clear), onClick = onClear, modifier = Modifier.weight(1f))
            EinkButton(label = stringResource(R.string.numpad_done), onClick = onDone, primary = true, modifier = Modifier.weight(2f))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// In-app document browser (Compose) — folder shortcut chips, direct /sdcard
// listing, file rows with an icon badge, single selection.
// ═══════════════════════════════════════════════════════════════════════════

private const val SDCARD = "/storage/emulated/0"

// Folder shortcut chips → (label string-res, absolute path under /sdcard).
private val DOC_ROOTS = listOf(
    R.string.folder_document to "$SDCARD/Document",
    R.string.folder_inkling to "$SDCARD/Document/Inkling",
    R.string.folder_books to "$SDCARD/Books",
    R.string.folder_download to "$SDCARD/Download",
    R.string.folder_export to "$SDCARD/EXPORT",
    R.string.folder_notes to "$SDCARD/Note",
    R.string.folder_inbox to "$SDCARD/INBOX",
)

private fun browserExts(isComic: Boolean): Set<String> =
    if (isComic) setOf("cbz", "zip", "mobi", "prc", "epub", "pdf")
    else setOf("txt", "md", "markdown", "epub", "pdf")

private fun docIconBadge(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "PDF"
    "epub" -> "EPB"
    "cbz", "zip" -> "CBZ"
    "txt" -> "TXT"
    "md", "markdown" -> "MD"
    "mobi", "prc" -> "MOB"
    else -> "DOC"
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private data class BrowserEntry(val name: String, val path: String, val isDir: Boolean, val size: Long)

private fun listBrowserDir(path: String, exts: Set<String>): List<BrowserEntry> {
    val dir = File(path)
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    val files = dir.listFiles() ?: return emptyList()
    return files.asSequence()
        .filter { !it.name.startsWith(".") }
        .filter { it.isDirectory || exts.contains(it.extension.lowercase()) }
        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        .map { BrowserEntry(it.name, it.absolutePath, it.isDirectory, it.length()) }
        .toList()
}

@Composable
fun FileBrowser(
    activity: MainActivity,
    isComic: Boolean,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val exts = remember(isComic) { browserExts(isComic) }
    var path by remember { mutableStateOf("$SDCARD/Document") }
    val hasAccess = activity.hasStorageAccess()

    // Re-list when the path, mode, access, or resume-refresh changes.
    val entries = remember(path, isComic, hasAccess, activity.uiRefresh) {
        if (hasAccess) listBrowserDir(path, exts) else emptyList()
    }
    val canGoUp = path.length > SDCARD.length && path.startsWith(SDCARD)

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        EinkTitleBar(
            title = stringResource(R.string.select_document),
            onBack = onClose,
            rightLabel = stringResource(R.string.btn_cancel),
            onRight = onClose,
        )

        // Folder shortcut chips (horizontal scroll).
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DOC_ROOTS.forEach { (labelRes, p) ->
                EinkChip(label = stringResource(labelRes), filled = path == p, onClick = { path = p })
            }
        }

        // Current path + up navigation.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                path.removePrefix("$SDCARD/").ifEmpty { stringResource(R.string.internal_storage) },
                fontSize = 12.sp,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canGoUp) {
                Box(
                    Modifier
                        .einkBorder()
                        .clickable { File(path).parent?.let { if (it.startsWith(SDCARD)) path = it } }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.browser_up), fontSize = 13.sp, color = Ink)
                }
            }
        }

        if (!hasAccess) {
            // No all-files access yet — prompt to grant.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(30.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.need_files_access),
                    fontSize = 15.sp,
                    color = Ink,
                    textAlign = TextAlign.Center,
                )
                EinkButton(label = stringResource(R.string.grant_access), primary = true, onClick = { activity.requestStorageAccess() })
            }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_docs_here), fontSize = 14.sp, color = Muted)
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(entries) { e ->
                    BrowserRow(
                        entry = e,
                        onClick = {
                            if (e.isDir) path = e.path else onPick(e.path)
                        },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun BrowserRow(entry: BrowserEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .einkBorder()
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon badge.
        Box(
            Modifier
                .size(42.dp)
                .einkBorder(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (entry.isDir) "DIR" else docIconBadge(entry.name),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDir) {
                Text(
                    formatFileSize(entry.size),
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
        }
        if (entry.isDir) {
            Text("›", fontSize = 20.sp, color = Muted)
        }
    }
}
