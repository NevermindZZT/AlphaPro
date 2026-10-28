@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.letter.alphapro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Visual size is contextual; rounded shape, semantic colors and state behavior are shared. */
enum class AlphaButtonSize(
    val minHeight: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val iconSize: Dp,
    val iconSpacing: Dp
) {
    Compact(32.dp, 12.dp, 4.dp, 16.dp, 6.dp),
    Regular(40.dp, 20.dp, 8.dp, 20.dp, 8.dp),
    Large(48.dp, 24.dp, 12.dp, 20.dp, 8.dp)
}

object AlphaButtonDefaults {
    val MinHeight = AlphaButtonSize.Regular.minHeight
    val IconSize = 20.dp
    val IconSpacing = 8.dp
    val IconTouchSize = 48.dp
    val Shape = CircleShape
    val ShutterSize = 160.dp
    val ShutterBorderWidth = 6.dp

    @Composable
    fun shutterColors(active: Boolean = false): ButtonColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
    )

    @Composable
    fun shutterBorderColor(enabled: Boolean, active: Boolean = false) = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    /** Reserve for destructive primary actions; Material owns disabled colors. */
    @Composable
    fun destructiveColors(): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError
    )

    /** Destructive content with the same theme outline as other secondary actions. */
    @Composable
    fun outlinedDestructiveColors(): ButtonColors = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.error
    )
}

private fun actionPadding(size: AlphaButtonSize) = PaddingValues(
    horizontal = size.horizontalPadding, vertical = size.verticalPadding
)

private fun textPadding(size: AlphaButtonSize) = PaddingValues(
    horizontal = if (size == AlphaButtonSize.Compact) 12.dp else 16.dp,
    vertical = size.verticalPadding
)

@Composable
private fun labelStyle(size: AlphaButtonSize) = if (size == AlphaButtonSize.Compact)
    MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge

@Composable
private fun actionOutline(enabled: Boolean) = BorderStroke(
    width = 1.dp,
    color = if (enabled) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
)

/** Primary action. Height is a minimum so translated or large-font labels can grow. */
@Composable
fun AlphaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    size: AlphaButtonSize = AlphaButtonSize.Regular,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = size.minHeight),
        enabled = enabled,
        colors = colors,
        shape = AlphaButtonDefaults.Shape,
        contentPadding = actionPadding(size),
        content = { ProvideTextStyle(labelStyle(size)) { content() } }
    )
}

/** Standard inline busy indicator; caller still owns enabled/loading state. */
@Composable
fun AlphaButtonProgress(modifier: Modifier = Modifier, size: AlphaButtonSize = AlphaButtonSize.Regular) {
    CircularProgressIndicator(
        modifier = modifier.size(size.iconSize),
        strokeWidth = 2.dp,
        color = LocalContentColor.current
    )
}

/** Medium-emphasis supporting action. */
@Composable
fun AlphaTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    size: AlphaButtonSize = AlphaButtonSize.Regular,
    content: @Composable RowScope.() -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = size.minHeight),
        enabled = enabled,
        colors = colors,
        shape = AlphaButtonDefaults.Shape,
        contentPadding = actionPadding(size),
        content = { ProvideTextStyle(labelStyle(size)) { content() } }
    )
}

/** Secondary action, with a disabled-aware Material theme outline. */
@Composable
fun AlphaOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    size: AlphaButtonSize = AlphaButtonSize.Regular,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = size.minHeight),
        enabled = enabled,
        colors = colors,
        shape = AlphaButtonDefaults.Shape,
        border = actionOutline(enabled),
        contentPadding = actionPadding(size),
        content = { ProvideTextStyle(labelStyle(size)) { content() } }
    )
}

/** Low-emphasis action, including dialog dismissal. */
@Composable
fun AlphaTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    size: AlphaButtonSize = AlphaButtonSize.Regular,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = size.minHeight),
        enabled = enabled,
        colors = colors,
        shape = AlphaButtonDefaults.Shape,
        contentPadding = textPadding(size),
        content = { ProvideTextStyle(labelStyle(size)) { content() } }
    )
}

/** Icon-only action; callers supply a localized icon content description. */
@Composable
fun AlphaIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(AlphaButtonDefaults.IconTouchSize),
        enabled = enabled,
        colors = colors,
        content = content
    )
}

/** Higher-emphasis circular icon-only action. */
@Composable
fun AlphaFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    content: @Composable () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(AlphaButtonDefaults.IconTouchSize),
        enabled = enabled,
        colors = colors,
        shape = AlphaButtonDefaults.Shape,
        content = content
    )
}

/** Pill-shaped filter selection, retaining Material selected and disabled behavior. */
@Composable
fun AlphaFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    label: @Composable () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = AlphaButtonDefaults.MinHeight),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = AlphaButtonDefaults.Shape,
        label = label
    )
}

/** Group mutually exclusive choices; the caller owns selection state. */
@Composable
fun AlphaSingleChoiceSegmentedButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.heightIn(min = AlphaButtonDefaults.MinHeight),
        content = content
    )
}

/** index is zero-based; count is the total number of segments in this row. */
@Composable
fun SingleChoiceSegmentedButtonRowScope.AlphaSegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit
) {
    SegmentedButton(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = AlphaButtonDefaults.MinHeight),
        enabled = enabled,
        shape = SegmentedButtonDefaults.itemShape(
            index = index,
            count = count,
            baseShape = AlphaButtonDefaults.Shape
        ),
        label = label
    )
}

/**
 * Dedicated camera shutter: the sole 160dp action-size exception.
 * A normal Material click only; autofocus and press sequences belong to the caller.
 */
@Composable
fun AlphaShutterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.size(AlphaButtonDefaults.ShutterSize),
        enabled = enabled,
        shape = AlphaButtonDefaults.Shape,
        colors = AlphaButtonDefaults.shutterColors(active),
        border = BorderStroke(AlphaButtonDefaults.ShutterBorderWidth, AlphaButtonDefaults.shutterBorderColor(enabled, active)),
        contentPadding = actionPadding(AlphaButtonSize.Large),
        content = content
    )
}
