package dev.tim.assistant.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.tim.assistant.generated.resources.Res
import dev.tim.assistant.generated.resources.ic_check
import dev.tim.assistant.generated.resources.ic_delete
import dev.tim.assistant.generated.resources.ic_edit
import org.jetbrains.compose.resources.vectorResource

/** Reports mouse enter/exit on this element. The phone has no mouse, so Android returns the modifier unchanged. */
expect fun Modifier.onHoverChange(onChange: (Boolean) -> Unit): Modifier

/**
 * The small Done / Edit / Delete icons a laptop row shows while the mouse is over it. Only the actions that are not
 * null are drawn; every icon is its own button, so a click on it never reaches the row behind it. Callers pass their
 * existing callbacks (including the Undo-aware ones), never a new write path.
 */
@Composable
fun HoverActions(
    visible: Boolean,
    onDone: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Row(modifier) {
        if (onDone != null) IconButton(onClick = onDone) { Icon(vectorResource(Res.drawable.ic_check), contentDescription = "Done") }
        if (onEdit != null) IconButton(onClick = onEdit) { Icon(vectorResource(Res.drawable.ic_edit), contentDescription = "Edit") }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(vectorResource(Res.drawable.ic_delete), contentDescription = "Delete") }
    }
}
