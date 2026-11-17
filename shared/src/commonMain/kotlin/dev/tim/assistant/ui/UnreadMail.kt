package dev.tim.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.google.decodeSnippet

private val ContentDirection = TextStyle(textDirection = TextDirection.Content)

/** One unread mail: sender, subject and snippet. Today and the Mail tab both show it; [trailing] is an optional button. */
@Composable
fun UnreadMailRow(mail: Mail_item, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(mail.sender, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(mail.subject, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                decodeSnippet(mail.snippet),
                style = MaterialTheme.typography.bodySmall.merge(ContentDirection),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}
