package com.music.bitchord.desktop

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Query reads and debounce restarts stay in this small scope, outside the whole desktop root. */
@Composable
internal fun <T> DesktopTypeaheadEffect(
    query: State<String>,
    typing: State<Boolean>,
    generation: State<Long>,
    debounceMillis: Long,
    enabled: Boolean = true,
    load: suspend (String) -> Result<List<T>>,
    publish: (List<T>) -> Unit,
) {
    val text = query.value
    val active = typing.value
    val revision = generation.value
    val loader by rememberUpdatedState(load)
    val deliver by rememberUpdatedState(publish)
    LaunchedEffect(text, active, revision, enabled) {
        if (!enabled || !active || text.isBlank()) {
            deliver(emptyList())
            return@LaunchedEffect
        }
        delay(debounceMillis)
        // Providers may process catalogues after their suspending HTTP call.
        val result = try {
            withContext(Dispatchers.Default) { loader(text) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure<List<T>>(failure)
        }
        currentCoroutineContext().ensureActive()
        if (typing.value && query.value == text && generation.value == revision) {
            deliver(result.getOrDefault(emptyList()))
        }
    }
}
