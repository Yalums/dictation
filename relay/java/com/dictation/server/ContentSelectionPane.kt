package com.dictation.server

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dictation.server.core.MarkdownBlock
import com.dictation.server.core.MarkdownDocument
import com.dictation.server.core.MarkdownRangeSelection
import com.dictation.server.core.MarkdownSelectionState
import com.dictation.server.ui.Faint
import com.dictation.server.ui.Ink
import com.dictation.server.ui.Muted

/**
 * E-ink 16-level gray backgrounds for selection states.
 * 0xCC = gray level 13/16 — clearly distinct from the page background.
 * 0xDD = gray level 14/16 — lighter hint for the anchor block.
 */
private val SelectedBg = Color(0xFFCCCCCC)
private val AnchorBg = Color(0xFFDDDDDD)

private fun Modifier.selectionClick(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

/**
 * Returns ▲ for the first block in a range (or the anchor),
 * ▼ for the last block of a completed range, null otherwise.
 */
private fun blockIndicator(
    selection: MarkdownSelectionState,
    index: Int,
): String? {
    if (selection.anchorBlockIndex == index) return "▲"
    for (range in selection.ranges) {
        if (index == range.firstBlockIndex) return "▲"
        if (index == range.lastBlockIndex) return "▼"
    }
    return null
}

/**
 * Block-level content selection that visually matches the READ mode layout.
 * Selected blocks show a solid e-ink gray background. The rightmost 32 dp
 * serves as the indicator strip where ▲ / ▼ mark range boundaries.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContentSelectionPane(
    document: MarkdownDocument,
    selection: MarkdownSelectionState,
    onToggleBlock: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = MdPalette(ink = Ink, muted = Muted, codeBg = Faint, strong = Ink)

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            document.blocks.forEachIndexed { index, block ->
                val isSelected = MarkdownRangeSelection.isSelected(selection, index)
                val isAnchor = selection.anchorBlockIndex == index
                val indicator = blockIndicator(selection, index)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                isSelected -> SelectedBg
                                isAnchor -> AnchorBg
                                else -> Color.Transparent
                            },
                        )
                        .selectionClick { onToggleBlock(index) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                    ) {
                        MarkdownBlockView(
                            block = block,
                            palette = palette,
                            baseFontSize = 18,
                            boldBody = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // Right indicator strip — sits in the 32 dp gap
                    Box(
                        Modifier.width(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (indicator != null) {
                            Text(
                                indicator,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
