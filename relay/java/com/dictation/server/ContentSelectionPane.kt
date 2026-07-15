package com.dictation.server

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dictation.server.core.MarkdownDocument
import com.dictation.server.core.MarkdownRangeSelection
import com.dictation.server.core.MarkdownSelectionState
import com.dictation.server.ui.Faint
import com.dictation.server.ui.Ink
import com.dictation.server.ui.Muted

/** Semi-transparent overlays for selection states on e-ink. */
private val SelectionOverlay = Color(0x30000000)
private val AnchorOverlay = Color(0x18000000)

private fun Modifier.selectionClick(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

/**
 * Block-level content selection that visually matches the READ mode layout.
 * Selected blocks show a semi-transparent black overlay; the anchor block
 * shows a lighter overlay. No checkboxes or markers — tap to toggle.
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
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            document.blocks.forEachIndexed { index, block ->
                val isSelected = MarkdownRangeSelection.isSelected(selection, index)
                val isAnchor = selection.anchorBlockIndex == index

                Box(
                    Modifier
                        .fillMaxWidth()
                        .selectionClick { onToggleBlock(index) },
                ) {
                    MarkdownBlockView(
                        block = block,
                        palette = palette,
                        baseFontSize = 18,
                        boldBody = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (isSelected || isAnchor) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(if (isSelected) SelectionOverlay else AnchorOverlay),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
