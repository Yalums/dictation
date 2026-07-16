package com.dictation.server

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dictation.server.core.AppPrefs
import com.dictation.server.service.DictationService
import com.dictation.server.ui.*

class MainActivity : ComponentActivity() {

    private var service: DictationService? = null
    private var isBound by mutableStateOf(false)
    private var stateVersion by mutableStateOf(0)

    // Runtime permission request for WRITE_EXTERNAL_STORAGE (Android 8.1–10).
    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled implicitly on next use */ }

    private fun hasPublicWritePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    // Image queries from the Inkling plugin hand over /sdcard file paths that
    // the relay must read (cropImageWithMask), so all-files access stays even
    // though the document converter moved to its own plugin.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensurePublicWritePermission()

        setContent {
            @Suppress("UNUSED_EXPRESSION") stateVersion
            Column(Modifier.fillMaxSize().background(Paper)) {
                // The inbox grid IS the main screen now — AI replies and voice
                // transcriptions land here for review before insertion.
                InboxGridScreen(
                    modifier = Modifier.weight(1f),
                    messages = service?.relayInbox?.snapshot() ?: emptyList(),
                    onOpen = { id ->
                        startActivity(MessageDetailActivity.intent(this@MainActivity, id))
                    },
                    onDelete = { id -> service?.deleteInboxMessage(id) },
                    onDeleteMany = { ids -> service?.deleteInboxMessages(ids) },
                    onMerge = { ids -> service?.mergeInboxMessages(ids) },
                    onPin = { id, pinned -> service?.pinInboxMessage(id, pinned) },
                    onReadd = { ids -> service?.readdInboxMessages(ids) },
                    onBack = { moveTaskToBack(true) },
                    onClose = {
                        startService(DictationService.stopIntent(this@MainActivity))
                        forceUnbind()
                        finishAndRemoveTask()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                )
                RelayBar(svc = service)
            }
        }
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
// Bottom relay bar — status + reply-mode control on the standing row; the
// connection editor (IP, phone/server target, keypad) folds away behind the
// "连接" toggle since both links auto-connect and auto-route once configured.
// The "server" endpoint is any remote running rikkahub-server (VPS, NAS, a
// LAN box) — not tied to a specific deployment.
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun RelayBar(svc: DictationService?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val phoneConnected = (svc?.connectedClients ?: 0) > 0
    val llmConnected = svc?.isLlmConnected == true
    val connected = phoneConnected || llmConnected

    // Reply mode: plain human↔assistant chat, or prefill fan-out where one
    // query spawns N assistant branches (count adjustable below).
    var multiReply by remember { mutableStateOf(AppPrefs.multiReply(context)) }
    var branchCount by remember { mutableStateOf(AppPrefs.multiReplyCount(context)) }

    // Connection editor visibility; both links run concurrently and the
    // service routes automatically (phone first, LLM server fallback) — the
    // target chip only picks which endpoint the IP field edits.
    var showConn by remember { mutableStateOf(false) }
    var editServer by remember { mutableStateOf(false) }
    var ipText by remember(svc, editServer) {
        mutableStateOf(if (editServer) AppPrefs.llmHost(context) else AppPrefs.phoneIp(context))
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
                    llmConnected -> stringResource(R.string.relay_connected) +
                        " · " + stringResource(R.string.server_label)
                    else -> stringResource(R.string.relay_disconnected)
                },
                fontSize = 13.sp,
                color = if (connected) Ink else Muted,
            )

            Spacer(Modifier.weight(1f))

            // Single toggle: unfilled = plain chat, filled = prefill fan-out.
            EinkChip(
                label = stringResource(R.string.multi_reply),
                filled = multiReply,
                onClick = {
                    multiReply = !multiReply
                    AppPrefs.setMultiReply(context, multiReply)
                },
            )
            if (multiReply) {
                BranchCountStepper(
                    count = branchCount,
                    onChange = { n ->
                        branchCount = n
                        AppPrefs.setMultiReplyCount(context, n)
                    },
                )
            }

            EinkChip(
                label = stringResource(R.string.btn_connect),
                filled = showConn,
                onClick = { showConn = !showConn; if (!showConn) showKeypad = false },
            )
        }

        if (showConn) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Read-only display that opens the custom keypad on tap. A
                // blinking bar after the text stands in for a cursor.
                Box(
                    Modifier
                        .weight(1f)
                        .einkBorder()
                        .background(if (showKeypad) Faint else Paper, RectangleShape)
                        .clickable { showKeypad = !showKeypad }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        if (ipText.isEmpty()) {
                            stringResource(if (editServer) R.string.server_host else R.string.phone_ip)
                        } else if (showKeypad) "$ipText|" else ipText,
                        fontSize = 14.sp,
                        color = if (ipText.isEmpty()) Muted else Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                EinkChip(
                    label = stringResource(R.string.phone_ip),
                    filled = !editServer,
                    onClick = { editServer = false },
                )
                EinkChip(
                    label = stringResource(R.string.server_label),
                    filled = editServer,
                    onClick = { editServer = true },
                )

                EinkButton(
                    label = stringResource(R.string.btn_connect),
                    onClick = {
                        if (ipText.isNotBlank()) {
                            showKeypad = false
                            if (editServer) {
                                svc?.startLlmBridge(ipText.trim())
                            } else {
                                svc?.reconnectRelay(ipText.trim())
                            }
                        }
                    },
                    enabled = ipText.isNotBlank(),
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

            // Raised in-app keypad — digits and dot only, no system IME.
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
}

/** − n + stepper for the multi-reply branch count (2–9). */
@Composable
private fun BranchCountStepper(count: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperKey("−", enabled = count > 2) { onChange(count - 1) }
        Box(
            Modifier.width(34.dp).padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("$count", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        StepperKey("+", enabled = count < 9) { onChange(count + 1) }
    }
}

@Composable
private fun StepperKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .einkBorder()
            .background(Paper, RectangleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, color = if (enabled) Ink else Faint)
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
