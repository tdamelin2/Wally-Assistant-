package dev.tim.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs [query] off the UI thread and again after every local change; null until the first result. */
@Composable
fun <T> rememberQuery(services: AppServices, key: Any? = null, query: () -> T): State<T?> {
    val version by services.store.changes.collectAsState()
    return produceState<T?>(null, version, key) {
        value = withContext(Dispatchers.Default) { query() }
    }
}
