package com.letter.alphapro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * Hold-to-shoot shutter. The caller owns shooting state; this component owns only the exact token
 * returned for its input session. Accessibility clicks toggle instead of requiring a sustained hold.
 */
@Composable
fun AlphaHoldShutterButton(
    onPress: () -> Long?,
    onRelease: (Long) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    actionLabel: String,
    stateLabel: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val latestOnPress by rememberUpdatedState(onPress)
    val latestOnRelease by rememberUpdatedState(onRelease)
    val latestOnStop by rememberUpdatedState(onStop)
    val latestActive by rememberUpdatedState(active)
    val latestEnabled by rememberUpdatedState(enabled)
    val interactions = remember { MutableInteractionSource() }
    val pressState = remember(interactions) { HoldShutterPressState(interactions) }
    val accessibilityOwner = remember { Any() }
    val colors = AlphaButtonDefaults.shutterColors(active)

    // Keyboard and accessibility sessions have no pointer coroutine to clean them up.
    SideEffect {
        if (!enabled) pressState.finish(onRelease = latestOnRelease)
    }
    DisposableEffect(pressState) {
        onDispose { pressState.finish(onRelease = latestOnRelease) }
    }

    Surface(
        modifier = modifier
            .size(AlphaButtonDefaults.ShutterSize)
            .clip(AlphaButtonDefaults.Shape)
            .indication(interactions, LocalIndication.current)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = stateLabel
                if (!enabled) disabled()
                onClick(label = actionLabel) {
                    if (!latestEnabled) {
                        false
                    } else if (latestActive) {
                        // Invalidate local ownership before either callback can re-enter us.
                        try {
                            pressState.finish(onRelease = latestOnRelease)
                        } finally {
                            latestOnStop()
                        }
                        true
                    } else {
                        // A timed/remote stop may have ended an earlier accessibility session.
                        pressState.finish(accessibilityOwner, onRelease = latestOnRelease)
                        pressState.start(
                            owner = accessibilityOwner,
                            position = Offset.Zero,
                            onPress = latestOnPress,
                            onRelease = latestOnRelease
                        )
                    }
                }
            }
            .onFocusChanged {
                if (!it.isFocused) pressState.finish(onRelease = latestOnRelease)
            }
            .onKeyEvent { event ->
                val key = event.key
                if (!latestEnabled || !key.isShutterKey()) {
                    false
                } else when (event.type) {
                    KeyEventType.KeyDown -> {
                        // Native repeatCount also rejects repeats after focus loss/re-enable.
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            if (!latestActive) {
                                pressState.finish(accessibilityOwner, onRelease = latestOnRelease)
                            }
                            pressState.start(
                                owner = Any(),
                                key = key,
                                position = Offset.Zero,
                                onPress = latestOnPress,
                                onRelease = latestOnRelease
                            )
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        pressState.keyboardOwner(key)?.let { owner ->
                            pressState.finish(owner, released = true, onRelease = latestOnRelease)
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled, interactions)
            // Deliberately not keyed by active or callbacks: VM updates must not end a hold.
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f
                    fun inside(position: Offset): Boolean =
                        (position - center).getDistanceSquared() <= radius * radius

                    if (!inside(down.position)) return@awaitEachGesture
                    down.consume()
                    val owner = Any()
                    var released = false
                    try {
                        if (!latestActive) {
                            pressState.finish(accessibilityOwner, onRelease = latestOnRelease)
                        }
                        pressState.start(
                            owner = owner,
                            position = down.position,
                            onPress = latestOnPress,
                            onRelease = latestOnRelease
                        )
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed || !inside(change.position)) break
                            if (!change.pressed) {
                                change.consume()
                                released = true
                                break
                            }
                            // Let a competing gesture recognizer cancel the hold, just as clickable
                            // does, without installing any window/global input listener.
                            val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                            if (finalEvent.changes.any { it.id == down.id && it.isConsumed }) break
                        }
                    } finally {
                        // Identity matching prevents an old finally/up from releasing a newer token.
                        pressState.finish(owner, released, latestOnRelease)
                    }
                }
            },
        shape = AlphaButtonDefaults.Shape,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = BorderStroke(
            AlphaButtonDefaults.ShutterBorderWidth,
            AlphaButtonDefaults.shutterBorderColor(enabled, active)
        )
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content
            )
        }
    }
}

private fun Key.isShutterKey(): Boolean =
    this == Key.Enter || this == Key.NumPadEnter || this == Key.Spacebar || this == Key.DirectionCenter

/** UI-thread confined; never substitutes a VM's current token for the token this input acquired. */
private class HoldShutterPressState(private val interactions: MutableInteractionSource) {
    private class Press(
        val owner: Any,
        val key: Key?,
        val interaction: PressInteraction.Press,
        var token: Long? = null
    )

    private var current: Press? = null

    fun start(
        owner: Any,
        position: Offset,
        onPress: () -> Long?,
        onRelease: (Long) -> Unit,
        key: Key? = null
    ): Boolean {
        if (current != null) return false
        val press = Press(owner, key, PressInteraction.Press(position))
        current = press
        interactions.tryEmit(press.interaction)
        try {
            val token = onPress()
            if (current === press) {
                press.token = token
                if (token == null) finish(owner, onRelease = onRelease)
            } else if (token != null) {
                // A synchronous callback may already have disposed/disabled this input session.
                onRelease(token)
            }
            return token != null
        } catch (failure: Throwable) {
            finish(owner, onRelease = onRelease)
            throw failure
        }
    }

    fun keyboardOwner(key: Key): Any? = current?.takeIf { it.key == key }?.owner

    fun finish(
        owner: Any? = null,
        released: Boolean = false,
        onRelease: (Long) -> Unit
    ) {
        val press = current ?: return
        if (owner != null && press.owner !== owner) return
        current = null
        val token = press.token
        press.token = null
        interactions.tryEmit(
            if (released) PressInteraction.Release(press.interaction)
            else PressInteraction.Cancel(press.interaction)
        )
        // Clear first: even a throwing/reentrant callback cannot release this token twice.
        if (token != null) onRelease(token)
    }
}
