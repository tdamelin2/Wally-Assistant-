package dev.tim.assistant.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.onHoverChange(onChange: (Boolean) -> Unit): Modifier =
    this
        .onPointerEvent(PointerEventType.Enter) { onChange(true) }
        .onPointerEvent(PointerEventType.Exit) { onChange(false) }
