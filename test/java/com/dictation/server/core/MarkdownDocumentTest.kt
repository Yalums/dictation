package com.dictation.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentTest {

    @Test
    fun parses_gfm_table_with_alignment_and_inline_markdown() {
        val source = """
            | Name | Count | State |
            | :--- | ---: | :---: |
            | Apple | 2 | **Done** |
            | Pear | 10 | ~~Gone~~ |
        """.trimIndent()

        val table = MarkdownDocumentParser.parse(source).blocks.single() as MarkdownBlock.Table

        assertEquals(listOf("Name", "Count", "State"), table.headers)
        assertEquals(
            listOf(MarkdownAlignment.LEFT, MarkdownAlignment.RIGHT, MarkdownAlignment.CENTER),
            table.alignments,
        )
        assertEquals(listOf("Apple", "2", "**Done**"), table.rows.first())
        assertEquals(source, source.substring(table.sourceRange.startOffset, table.sourceRange.endOffsetExclusive))
    }

    @Test
    fun parses_table_without_outer_pipes_and_unescapes_pipe() {
        val source = "Name | Note\n--- | ---\nA | left \\| right"
        val table = MarkdownDocumentParser.parse(source).blocks.single() as MarkdownBlock.Table

        assertEquals(listOf("Name", "Note"), table.headers)
        assertEquals(listOf("A", "left | right"), table.rows.single())
    }

    @Test
    fun normalizes_short_and_long_table_rows_for_display() {
        val source = "A | B\n--- | ---\none |\nx | y | z"
        val table = MarkdownDocumentParser.parse(source).blocks.single() as MarkdownBlock.Table

        assertEquals(listOf("one", ""), table.rows[0])
        assertEquals(listOf("x", "y"), table.rows[1])
    }

    @Test
    fun does_not_treat_invalid_delimiter_or_code_fence_as_table() {
        val invalid = MarkdownDocumentParser.parse("A | B\n-- | ---")
        assertTrue(invalid.blocks.none { it is MarkdownBlock.Table })

        val fenced = MarkdownDocumentParser.parse("```\nA | B\n--- | ---\n```")
        assertTrue(fenced.blocks.single() is MarkdownBlock.CodeFence)
        assertFalse(fenced.blocks.any { it is MarkdownBlock.Table })
    }

    @Test
    fun table_range_stops_before_following_paragraph() {
        val source = "A | B\n--- | ---\n1 | 2\n\nAfter"
        val document = MarkdownDocumentParser.parse(source)
        val table = document.blocks.first() as MarkdownBlock.Table

        assertEquals("A | B\n--- | ---\n1 | 2", source.substring(table.sourceRange.startOffset, table.sourceRange.endOffsetExclusive).trimEnd())
        assertEquals("After", (document.blocks.last() as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun source_ranges_preserve_original_line_endings() {
        listOf("\n", "\r\n", "\r").forEach { newline ->
            val tableSource = "A | B${newline}--- | ---${newline}1 | 2"
            val source = tableSource + newline + newline + "After"
            val table = MarkdownDocumentParser.parse(source).blocks.first() as MarkdownBlock.Table

            assertEquals(
                tableSource,
                source.substring(table.sourceRange.startOffset, table.sourceRange.endOffsetExclusive).trimEnd('\r', '\n'),
            )
        }
    }

    @Test
    fun parses_long_ten_column_table_inside_md_wrapper() {
        val source = """
            <md>
            表中凡写“**推断**”者，表示根据公开资料推导。

            | 厂商 / 设备 | 公开更新抓手 | 支持的导出格式 | 是否保留矢量 / 图层 | 是否支持批量 / 定时导出 | 是否有公开 API / SDK | 是否支持直接打印或云打印集成 | 同步 / 云端流程 | 导出质量限制 | 是否支持手写识别 / OCR |
            | ---------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
            | 科大讯飞 AINOTE / X5 / Air 2 | 2025 年手册与 FAQ | **PDF / Word** | **分层/矢量导出页未展开** | 批量与定时导出 | 未见公开 API | 打印、扫码、邮件 | 默认同步云端 | PDF 功能不同 | 支持手写转文字 |

            ## 厂商深度分析
            </md>
        """.trimIndent()

        val document = MarkdownDocumentParser.parse(source)
        val table = document.blocks.filterIsInstance<MarkdownBlock.Table>().single()

        assertEquals(10, table.headers.size)
        assertEquals(10, table.rows.single().size)
    }
}
