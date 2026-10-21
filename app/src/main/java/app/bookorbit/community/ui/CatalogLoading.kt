package app.bookorbit.community.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import app.bookorbit.community.data.ApiFailure

fun LazyListScope.catalogLoading(state: LoadState, retry: () -> Unit, key: String) {
    if (state is LoadState.Loading)
        item(key = key) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
        }
    if (state is LoadState.Error)
        item(key = key) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    (state.error as? ApiFailure)?.message
                        ?: "Could not load books. Check your connection and try again."
                )
                TextButton(onClick = retry) { Text("Retry loading books") }
            }
        }
}
