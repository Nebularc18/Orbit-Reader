package app.bookorbit.community.ui

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bookorbit.community.OrbitApplication
import app.bookorbit.community.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class MetadataEditState(
    val source: JsonObject? = null,
    val drafts: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

class MetadataEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OrbitApplication
    private val mutable = MutableStateFlow(MetadataEditState())
    val state = mutable.asStateFlow()

    fun load(id: Int, owner: String) {
        if (mutable.value.source != null || mutable.value.busy) return
        operation {
            val source = app.api.bookMetadata(id, owner)
            mutable.value = MetadataEditState(source)
        }
    }

    fun change(key: String, value: String) {
        mutable.update { it.copy(drafts = it.drafts + (key to value), error = null) }
    }

    fun save(id: Int, owner: String) {
        val initial = mutable.value
        val source = initial.source ?: return
        operation {
            val fields = metadataFields + customMetadataFields(source)
            val patch = metadataPatch(source, fields, initial.drafts)
            if (patch.isEmpty()) error("Change a field before saving.")
            val fresh = app.api.bookMetadata(id, owner)
            check(
                fields.none { field ->
                    initial.drafts[field.key]?.let {
                        it != metadataText(metadataSource(source, field.key), field.kind) &&
                            metadataSource(source, field.key) != metadataSource(fresh, field.key)
                    } == true
                }
            ) {
                "An edited field changed on the server. Close and reopen this editor before saving."
            }
            app.api.saveBookMetadata(id, owner, source, fields, initial.drafts)
            val detail = app.api.ebookDetail(id, owner)
            BookDetailsStorage(app, owner).save(detail)
            mutable.update { it.copy(saved = true) }
        }
    }

    private fun operation(action: () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(
                        error =
                            if (e is ApiFailure) e.message
                            else if (e is IllegalArgumentException || e is IllegalStateException)
                                e.message ?: "Check the edited fields."
                            else "Could not save metadata. Check your connection and try again."
                    )
                }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}

@Composable
fun MetadataEditor(id: Int, owner: String, close: () -> Unit, saved: () -> Unit) {
    val instance = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val model: MetadataEditorViewModel = viewModel(key = "metadata-$owner-$id-$instance")
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.load(id, owner) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            saved()
            close()
        }
    }
    var group by rememberSaveable { mutableStateOf("Book") }
    var rows by remember { mutableStateOf<MetadataField?>(null) }
    val fields =
        remember(state.source) {
            metadataFields + state.source?.let(::customMetadataFields).orEmpty()
        }
    Dialog(
        onDismissRequest = { if (!state.busy) close() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(12.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Edit metadata", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Shared fields affect other users. Your server may also update book files. Series totals affect every book in that series.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    fields
                        .map { it.group }
                        .distinct()
                        .forEach { label ->
                            FilterChip(group == label, { group = label }, label = { Text(label) })
                        }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.source != null)
                        items(fields.filter { it.group == group }, key = { it.key }) { field ->
                            val value =
                                state.drafts[field.key]
                                    ?: metadataText(
                                        metadataSource(state.source!!, field.key),
                                        field.kind,
                                    )
                            if (field.kind == "rows")
                                OutlinedButton({ rows = field }, enabled = !state.busy) {
                                    Text("Edit ${field.label.lowercase()}")
                                }
                            else
                                OutlinedTextField(
                                    value,
                                    { model.change(field.key, it) },
                                    label = { Text(field.label) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.busy,
                                    minLines =
                                        if (field.kind == "list" || field.key == "description") 3
                                        else 1,
                                    supportingText =
                                        when (field.kind) {
                                            "list" -> ({ Text("One item per line") })
                                            "date" -> ({ Text("YYYY-MM-DD, or leave blank") })
                                            "boolean" -> ({ Text("true, false, or leave blank") })
                                            else -> null
                                        },
                                )
                        }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(close, enabled = !state.busy) { Text("Cancel") }
                    Button(
                        { model.save(id, owner) },
                        enabled = !state.busy && state.source != null && state.drafts.isNotEmpty(),
                    ) {
                        Text("Save changes")
                    }
                }
            }
        }
    }
    rows?.let { field ->
        val value =
            state.drafts[field.key]
                ?: metadataText(metadataSource(state.source!!, field.key), field.kind)
        MetadataRowsEditor(field, value, { rows = null }) {
            model.change(field.key, it)
            rows = null
        }
    }
}

@Composable
private fun MetadataRowsEditor(
    field: MetadataField,
    value: String,
    close: () -> Unit,
    save: (String) -> Unit,
) {
    val children = remember(field.key) { metadataRowFields(field.key) }
    var entries by
        remember(field.key) {
            mutableStateOf(
                (runCatching { wireJson.parseToJsonElement(value).jsonArray }.getOrNull()
                        ?: JsonArray(emptyList()))
                    .map { row ->
                        children.associate {
                            it.key to metadataText(row.jsonObject[it.key] ?: JsonNull, it.kind)
                        }
                    }
            )
        }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(field.label) },
        text = {
            Column {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(
                    Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries.size) { index ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Entry ${index + 1}")
                            children.forEach { child ->
                                OutlinedTextField(
                                    entries[index][child.key].orEmpty(),
                                    { text ->
                                        entries =
                                            entries.mapIndexed { i, row ->
                                                if (i == index) row + (child.key to text) else row
                                            }
                                    },
                                    label = { Text(child.label) },
                                )
                            }
                            TextButton({ entries = entries.filterIndexed { i, _ -> i != index } }) {
                                Text("Remove entry")
                            }
                        }
                    }
                    item {
                        OutlinedButton(
                            { entries = entries + listOf(emptyMap()) },
                            enabled = entries.size < 500,
                        ) {
                            Text("Add entry")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                try {
                    val result =
                        JsonArray(
                            entries.map { row ->
                                buildJsonObject {
                                    children.forEach { child ->
                                        put(
                                            child.key,
                                            metadataValue(child, row[child.key].orEmpty()),
                                        )
                                    }
                                }
                            }
                        )
                    save(metadataValue(field, result.toString()).toString())
                } catch (e: Exception) {
                    error = e.message ?: "Check the entry values."
                }
            }) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
    )
}
