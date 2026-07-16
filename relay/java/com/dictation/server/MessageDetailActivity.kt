package com.dictation.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dictation.server.core.MarkdownDocument
import com.dictation.server.core.MarkdownDocumentParser
import com.dictation.server.core.MarkdownRangeSelection
import com.dictation.server.core.MarkdownSelectionState
import com.dictation.server.model.RelayMessage
import com.dictation.server.service.DictationService
import com.dictation.server.ui.EinkButton
import com.dictation.server.ui.Faint
import com.dictation.server.ui.Ink
import com.dictation.server.ui.Muted
import com.dictation.server.ui.Paper
import com.dictation.server.ui.topLine

enum class MessageDetailMode { READ, SELECT }

private fun Modifier.tapNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

class MessageDetailActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ID = "message_id"

        fun intent(context: Context, id: String) =
            Intent(context, MessageDetailActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private var service: DictationService? = null
    private var stateVersion by mutableStateOf(0)
    private var msgId by mutableStateOf<String?>(null)
    private var detailMode by mutableStateOf(MessageDetailMode.READ)
    private var selection by mutableStateOf(MarkdownSelectionState())

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as DictationService.LocalBinder).getService()
            stateVersion++
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            stateVersion++
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ID)?.let { msgId = it }
        resetSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        msgId = intent.getStringExtra(EXTRA_ID)
        resetSelection()

        setContent {
            @Suppress("UNUSED_EXPRESSION") stateVersion
            val msg = msgId?.let { service?.relayInbox?.get(it) }
            val document = remember(msg?.text) {
                MarkdownDocumentParser.parse(msg?.text.orEmpty())
            }
            val payload = remember(document, selection) {
                MarkdownRangeSelection.payload(document, selection)
            }

            BackHandler { handleBack() }

            DetailSurface(
                msg = msg,
                document = document,
                mode = detailMode,
                selection = selection,
                canInsert = payload.isNotBlank() && service != null,
                onBack = { handleBack() },
                onSelect = {
                    detailMode = MessageDetailMode.SELECT
                    selection = MarkdownRangeSelection.clear()
                },
                onToggleBlock = { blockIndex ->
                    selection = MarkdownRangeSelection.toggle(
                        state = selection,
                        blockIndex = blockIndex,
                        blockCount = document.blocks.size,
                    )
                },
                onClearSelection = { selection = MarkdownRangeSelection.clear() },
                onCancelSelection = { resetSelection() },
                onInsert = {
                    val id = msgId
                    val boundService = service
                    if (id != null && boundService != null && payload.isNotBlank()) {
                        boundService.insertInboxSelection(id, payload)
                        resetSelection()
                        finish()
                    }
                },
                onDelete = {
                    msgId?.let { service?.deleteInboxMessage(it) }
                    finish()
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DictationService::class.java), conn, 0)
    }

    override fun onStop() {
        super.onStop()
        try {
            unbindService(conn)
        } catch (_: Exception) {
        }
        service = null
        stateVersion++
    }

    private fun handleBack() {
        if (detailMode == MessageDetailMode.SELECT) {
            resetSelection()
        } else {
            moveTaskToBack(true)
        }
    }

    private fun resetSelection() {
        detailMode = MessageDetailMode.READ
        selection = MarkdownRangeSelection.clear()
    }
}

@Composable
private fun DetailStatusBar(
    msg: RelayMessage?,
    mode: MessageDetailMode,
    selection: MarkdownSelectionState,
    readScroll: ScrollState,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 32.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (msg != null) {
                sourceLabel(msg.source) + " · " + formatInboxTime(msg.updatedAt)
            } else {
                stringResource(R.string.btn_inbox)
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Muted,
            modifier = Modifier.weight(1f),
        )

        when (mode) {
            MessageDetailMode.READ -> if (readScroll.maxValue > 0) {
                val percent = (readScroll.value * 100 / readScroll.maxValue).coerceIn(0, 100)
                Text(
                    text = "$percent%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Muted,
                )
            }

            MessageDetailMode.SELECT -> Text(
                text = stringResource(R.string.inbox_select_title, selection.groupCount),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Muted,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailSurface(
    msg: RelayMessage?,
    document: MarkdownDocument,
    mode: MessageDetailMode,
    selection: MarkdownSelectionState,
    canInsert: Boolean,
    onBack: () -> Unit,
    onSelect: () -> Unit,
    onToggleBlock: (Int) -> Unit,
    onClearSelection: () -> Unit,
    onCancelSelection: () -> Unit,
    onInsert: () -> Unit,
    onDelete: () -> Unit,
) {
    val readScroll = rememberScrollState()
    val rootModifier = Modifier
        .fillMaxSize()
        .background(Color(0xA3FFFFFF), RectangleShape)
        .then(if (mode == MessageDetailMode.READ) Modifier.tapNoRipple(onBack) else Modifier)

    Column(rootModifier) {
        DetailStatusBar(
            msg = msg,
            mode = mode,
            selection = selection,
            readScroll = readScroll,
        )

        if (msg == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✉", fontSize = 30.sp, color = Muted)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.inbox_empty),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Muted,
                    )
                }
            }
        } else {
            when (mode) {
                MessageDetailMode.READ -> {
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(readScroll)
                                .padding(start = 24.dp, end = 32.dp, top = 18.dp, bottom = 18.dp),
                        ) {
                            MarkdownDocumentView(
                                document = document,
                                palette = MdPalette(
                                    ink = Ink,
                                    muted = Muted,
                                    codeBg = Faint,
                                    strong = Ink,
                                ),
                                baseFontSize = 18,
                                boldBody = true,
                            )
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }

                MessageDetailMode.SELECT -> {
                    ContentSelectionPane(
                        document = document,
                        selection = selection,
                        onToggleBlock = onToggleBlock,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (msg != null) {
            when (mode) {
                MessageDetailMode.READ -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .topLine()
                            .padding(start = 18.dp, end = 32.dp, top = 14.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EinkButton(
                            label = stringResource(R.string.inbox_select_content),
                            onClick = onSelect,
                            primary = true,
                            modifier = Modifier.weight(1f),
                        )
                        EinkButton(
                            label = stringResource(R.string.inbox_delete),
                            onClick = onDelete,
                        )
                    }
                }

                MessageDetailMode.SELECT -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .topLine()
                            .padding(start = 14.dp, end = 32.dp, top = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EinkButton(
                            label = stringResource(R.string.inbox_select_cancel),
                            onClick = onCancelSelection,
                            modifier = Modifier.weight(1f),
                        )
                        EinkButton(
                            label = stringResource(R.string.inbox_select_clear),
                            onClick = onClearSelection,
                            enabled = selection.anchorBlockIndex != null || selection.hasSelection,
                            modifier = Modifier.weight(1f),
                        )
                        EinkButton(
                            label = stringResource(R.string.inbox_insert_note),
                            onClick = onInsert,
                            primary = true,
                            enabled = canInsert,
                            modifier = Modifier.weight(1.2f),
                        )
                    }
                }
            }
        }
    }
}
