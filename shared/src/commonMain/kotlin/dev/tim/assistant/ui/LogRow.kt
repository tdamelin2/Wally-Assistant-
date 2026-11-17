package dev.tim.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A list row with its quick actions. Phone: swipe right = done (when [onDone] is given), left = delete. Laptop: no
 * swipe; the Done / Edit / Delete icons appear while the mouse is over the row. Both call the same callbacks, so
 * every change still goes through the Undo buffer. [endInset] keeps the icons clear of a button at the row's end.
 */
@Composable
fun LogRow(
    onDone: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    endInset: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    if (LocalLayoutMode.current != LayoutMode.Wide) {
        SwipeRow(onComplete = onDone, onDelete = onDelete, modifier = modifier, content = content)
        return
    }
    var hovered by remember { mutableStateOf(false) }
    Box(modifier.onHoverChange { hovered = it }) {
        content()
        if (hovered) {
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp + endInset),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
            ) { HoverActions(visible = true, onDone = onDone, onEdit = onEdit, onDelete = onDelete) }
        }
    }
}
