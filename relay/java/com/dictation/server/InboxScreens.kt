package com.dictation.server

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import com.dictation.server.model.RelayMessage
import com.dictation.server.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ═══════════════════════════════════════════════════════════════════════════
// Relay inbox grid — a paged 2-column card wall of AI replies / voice
// transcriptions. Opaque (this is the "browse history" surface); the
// translucent reading surface is MessageDetailActivity.
// ═══════════════════════════════════════════════════════════════════════════

private const val GRID_COLUMNS = 2
private const val GRID_ROWS = 3
private const val PAGE_SIZE = GRID_COLUMNS * GRID_ROWS

@Composable
fun sourceLabel(source: String): String = when (source) {
    "llm" -> stringResource(R.string.inbox_source_llm)
    "manual" -> stringResource(R.string.inbox_source_manual)
    else -> stringResource(R.string.inbox_source_stt)
}

fun formatInboxTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val fmt = if (now - ts < 20 * 3600_000L) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(ts))
}

@Composable
fun InboxGridScreen(
    messages: List<RelayMessage>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteMany: (List<String>) -> Unit,
    onMerge: (List<String>) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onPin: (String, Boolean) -> Unit = { _, _ -> },
    onReadd: (List<String>) -> Unit = {},
) {
    var page by remember { mutableStateOf(0) }
    val pageCount = maxOf(1, (messages.size + PAGE_SIZE - 1) / PAGE_SIZE)
    if (page >= pageCount) page = pageCount - 1
    val pageItems = messages.drop(page * PAGE_SIZE).take(PAGE_SIZE)

    // Long-press multi-select mode (black action bar on top, like the
    // Inkling crop screen's toolbar).
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    fun exitSelection() { selecting = false; selected.clear() }

    Column(Modifier.fillMaxSize().background(Paper)) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                onCancel = { exitSelection() },
                onSelectAll = {
                    selected.clear()
                    selected.addAll(messages.map { it.id })
                },
                onMerge = {
                    if (selected.size >= 2) { onMerge(selected.toList()); exitSelection() }
                },
                onReadd = {
                    if (selected.isNotEmpty()) { onReadd(selected.toList()); exitSelection() }
                },
                onDelete = {
                    if (selected.isNotEmpty()) { onDeleteMany(selected.toList()); exitSelection() }
                },
            )
        } else {
            EinkTitleBar(
                title = stringResource(R.string.inbox_title),
                onBack = onBack,
                rightLabel = if (messages.isNotEmpty()) stringResource(R.string.inbox_clear) else null,
                onRight = if (messages.isNotEmpty()) onClear else null,
            )
        }

        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.inbox_empty),
                    fontSize = 15.sp,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp,
                )
            }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (row in 0 until GRID_ROWS) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        for (col in 0 until GRID_COLUMNS) {
                            val msg = pageItems.getOrNull(row * GRID_COLUMNS + col)
                            if (msg != null) {
                                InboxCard(
                                    msg = msg,
                                    selecting = selecting,
                                    isSelected = selected.contains(msg.id),
                                    onOpen = {
                                        if (selecting) {
                                            if (!selected.remove(msg.id)) selected.add(msg.id)
                                            if (selected.isEmpty()) selecting = false
                                        } else onOpen(msg.id)
                                    },
                                    onLongPress = {
                                        if (!selecting) {
                                            selecting = true
                                            selected.clear()
                                            selected.add(msg.id)
                                        }
                                    },
                                    onDelete = { onDelete(msg.id) },
                                    onPin = { onPin(msg.id, !msg.pinned) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // ‹ n/m › pager — e-ink friendly, no scrolling.
            Row(
                Modifier.fillMaxWidth().topLine().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.clickable(enabled = page > 0) { page-- }.padding(horizontal = 24.dp, vertical = 6.dp),
                ) {
                    Text("‹", fontSize = 24.sp, color = if (page > 0) Ink else Faint)
                }
                Text("${page + 1}/$pageCount", fontSize = 16.sp, color = Ink)
                Box(
                    Modifier.clickable(enabled = page < pageCount - 1) { page++ }.padding(horizontal = 24.dp, vertical = 6.dp),
                ) {
                    Text("›", fontSize = 24.sp, color = if (page < pageCount - 1) Ink else Faint)
                }
            }
        }
    }
}

/** Black top action bar for multi-select mode (Inkling crop-toolbar style). */
@Composable
private fun SelectionBar(
    count: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    onReadd: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().background(Ink).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clickable(onClick = onCancel).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text("✕", fontSize = 18.sp, color = Paper)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.inbox_selected, count),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Paper,
        )
        Spacer(Modifier.weight(1f))
        SelectionBarButton(stringResource(R.string.inbox_select_all), enabled = true, onClick = onSelectAll)
        Spacer(Modifier.width(8.dp))
        // Re-add: pin the selection back onto the Inkling capsule bars
        // (also revives already-inserted messages).
        SelectionBarButton(stringResource(R.string.inbox_readd), enabled = count >= 1, onClick = onReadd)
        Spacer(Modifier.width(8.dp))
        SelectionBarButton(stringResource(R.string.inbox_merge), enabled = count >= 2, onClick = onMerge)
        Spacer(Modifier.width(8.dp))
        SelectionBarButton(stringResource(R.string.inbox_delete), enabled = count >= 1, onClick = onDelete)
    }
}

@Composable
private fun SelectionBarButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.5.dp, if (enabled) Paper else Paper.copy(alpha = 0.35f), RectangleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Paper else Paper.copy(alpha = 0.35f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxCard(
    msg: RelayMessage,
    selecting: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .einkBorder()
            .then(if (isSelected) Modifier.border(3.dp, Ink, RectangleShape) else Modifier)
            // Inserted messages stay browsable but read as "done": faint
            // gray card background.
            .then(if (msg.inserted) Modifier.background(Faint, RectangleShape) else Modifier)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(14.dp),
    ) {
        // Header: source + time + pin-to-capsule + delete (or selection checkbox)
        Row(verticalAlignment = Alignment.CenterVertically) {
            EinkChip(label = sourceLabel(msg.source), filled = msg.source == "llm")
            if (msg.inserted) {
                Spacer(Modifier.width(6.dp))
                EinkChip(label = stringResource(R.string.inbox_inserted))
            }
            Spacer(Modifier.width(8.dp))
            Text(formatInboxTime(msg.updatedAt), fontSize = 11.sp, color = Muted)
            Spacer(Modifier.weight(1f))
            if (!selecting && !msg.inserted) {
                // Pin toggle: show this message in the Inkling capsule bars.
                Box(
                    Modifier.clickable(onClick = onPin).padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        if (msg.pinned) "★" else "☆",
                        fontSize = 15.sp,
                        color = if (msg.pinned) Ink else Muted,
                    )
                }
            }
            if (selecting) {
                Box(
                    Modifier
                        .size(22.dp)
                        .border(1.5.dp, Ink, RectangleShape)
                        .background(if (isSelected) Ink else Paper),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) Text("✓", fontSize = 13.sp, color = Paper)
                }
            } else {
                Box(
                    Modifier.clickable(onClick = onDelete).padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("✕", fontSize = 14.sp, color = Muted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // First line as title
        Text(
            msg.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        // Body preview, remaining lines ellipsized
        Text(
            msg.preview.ifEmpty { msg.title },
            fontSize = 13.sp,
            color = Ink.copy(alpha = 0.8f),
            lineHeight = 19.sp,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
