package dev.tim.assistant.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextGeometricTransform

internal actual val markdownItalicStyle: SpanStyle =
    SpanStyle(textGeometricTransform = TextGeometricTransform(skewX = -0.25f))
