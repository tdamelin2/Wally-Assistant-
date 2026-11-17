package dev.tim.assistant.ui

import androidx.compose.ui.text.SpanStyle

/** Italic look for markdown emphasis. Android skews the glyphs because some phone fonts have no italic face. */
internal expect val markdownItalicStyle: SpanStyle
