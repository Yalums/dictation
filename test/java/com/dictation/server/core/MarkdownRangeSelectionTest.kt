package com.dictation.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRangeSelectionTest {

    @Test
    fun two_taps_create_a_group_in_either_direction() {
        val forward = complete(MarkdownSelectionState(), 1, 3, 5)
        val reverse = complete(MarkdownSelectionState(), 3, 1, 5)

        assertEquals(listOf(MarkdownBlockRange(1, 3)), forward.ranges)
        assertEquals(forward, reverse)
        assertNull(forward.anchorBlockIndex)
    }

    @Test
    fun tapping_same_item_twice_selects_one_markdown_item() {
        val state = complete(MarkdownSelectionState(), 2, 2, 4)

        assertEquals(listOf(MarkdownBlockRange(2, 2)), state.ranges)
    }

    @Test
    fun adjacent_and_overlapping_groups_are_merged() {
        var state = complete(MarkdownSelectionState(), 0, 1, 6)
        state = complete(state, 3, 4, 6)
        state = complete(state, 2, 4, 6)

        assertEquals(listOf(MarkdownBlockRange(0, 4)), state.ranges)
        assertEquals(1, state.groupCount)
    }

    @Test
    fun tapping_selected_item_removes_its_complete_group() {
        var state = complete(MarkdownSelectionState(), 0, 1, 5)
        state = complete(state, 3, 4, 5)

        state = MarkdownRangeSelection.toggle(state, blockIndex = 3, blockCount = 5)

        assertEquals(listOf(MarkdownBlockRange(0, 1)), state.ranges)
    }

    @Test
    fun payload_keeps_source_order_and_markdown_formatting() {
        val source = "# Title\n\n- first\n\n```\n  code\n```\n\nTail"
        val document = MarkdownDocumentParser.parse(source)
        var state = complete(MarkdownSelectionState(), 3, 3, document.blocks.size)
        state = complete(state, 0, 1, document.blocks.size)

        assertEquals("# Title\n\n- first\n\nTail", MarkdownRangeSelection.payload(document, state))
    }

    @Test
    fun selecting_code_and_table_preserves_complete_markdown() {
        val source = "```\n  code\n```\n\nA | B\n--- | ---\n1 | 2"
        val document = MarkdownDocumentParser.parse(source)
        val state = complete(MarkdownSelectionState(), 0, 1, document.blocks.size)

        assertEquals(source, MarkdownRangeSelection.payload(document, state))
    }

    @Test
    fun empty_or_pending_selection_has_no_payload() {
        val document = MarkdownDocumentParser.parse("Alpha\n\nBeta")
        val pending = MarkdownRangeSelection.toggle(
            MarkdownSelectionState(),
            blockIndex = 0,
            blockCount = document.blocks.size,
        )

        assertFalse(pending.hasSelection)
        assertTrue(MarkdownRangeSelection.payload(document, pending).isEmpty())
    }

    private fun complete(
        initial: MarkdownSelectionState,
        first: Int,
        last: Int,
        blockCount: Int,
    ): MarkdownSelectionState {
        val anchored = MarkdownRangeSelection.toggle(initial, first, blockCount)
        return MarkdownRangeSelection.toggle(anchored, last, blockCount)
    }
}
