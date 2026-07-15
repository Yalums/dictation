package com.dictation.server.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════════════════════
// E-ink design system — the single source of truth for both flavors (phone
// monitor UI and Supernote relay UI). Monochrome, square borders, no Material
// elevation/colors.
// ═══════════════════════════════════════════════════════════════════════════

val Ink = Color(0xFF000000)
val Paper = Color(0xFFFFFFFF)
val Muted = Color(0xFF888888)
val Faint = Color(0xFFE0E0E0)
const val BORDER_W = 1.5f

fun Modifier.topLine() = drawBehind {
    drawLine(Ink, Offset(0f, 0f), Offset(size.width, 0f), BORDER_W.dp.toPx())
}

fun Modifier.bottomLine() = drawBehind {
    drawLine(Ink, Offset(0f, size.height), Offset(size.width, size.height), BORDER_W.dp.toPx())
}

fun Modifier.leftLine() = drawBehind {
    drawLine(Ink, Offset(0f, 0f), Offset(0f, size.height), BORDER_W.dp.toPx())
}

fun Modifier.einkBorder(enabled: Boolean = true) =
    border(BORDER_W.dp, if (enabled) Ink else Muted, RectangleShape)

@Composable
fun EinkButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val bg = when { !enabled -> Faint; primary -> Ink; else -> Paper }
    val fg = when { !enabled -> Muted; primary -> Paper; else -> Ink }
    Box(
        modifier
            .then(if (!primary) Modifier.einkBorder(enabled) else Modifier)
            .background(bg, RectangleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 13.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

@Composable
fun EinkIconButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(36.dp)
            .einkBorder()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = Ink)
    }
}

@Composable
fun EinkChip(label: String, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .einkBorder()
            .background(if (filled) Ink else Paper, RectangleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, color = if (filled) Paper else Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EinkCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.einkBorder().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (title != null) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        content()
    }
}

@Composable
fun EinkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Box(
        modifier.einkBorder().padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = 15.sp, color = Ink),
            cursorBrush = SolidColor(Ink),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = Muted)
                inner()
            },
        )
    }
}

/** Segmented selector; also used as a tab row. */
@Composable
fun EinkSegmented(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().einkBorder()) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) Ink else Paper)
                    .clickable { onSelect(i) }
                    .then(if (i > 0) Modifier.leftLine() else Modifier)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    color = if (on) Paper else Ink,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun EinkCheckbox(checked: Boolean, enabled: Boolean = true) {
    Box(
        Modifier
            .size(24.dp)
            .einkBorder(enabled)
            .background(if (checked && enabled) Ink else Paper),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EinkCheckRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    desc: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EinkCheckbox(checked, enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = if (enabled) Ink else Muted, fontWeight = FontWeight.Medium)
            if (desc != null) Text(desc, fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
fun EinkSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = Ink,
            activeTrackColor = Ink,
            inactiveTrackColor = Muted,
        ),
        modifier = modifier,
    )
}

@Composable
fun EinkTitleBar(
    title: String,
    onBack: () -> Unit,
    rightLabel: String? = null,
    onRight: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Paper)
            .bottomLine()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed 72dp slots keep the title centered; the clickable hot zone is
        // the inner Box, so hover/tap fits the glyph on both sides.
        Box(Modifier.width(72.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("←", fontSize = 22.sp, color = Ink)
            }
        }
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.width(72.dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            if (rightLabel != null) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .then(if (onRight != null) Modifier.clickable(onClick = onRight) else Modifier)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(rightLabel, fontSize = 15.sp, color = Ink)
                }
            }
        }
    }
}
