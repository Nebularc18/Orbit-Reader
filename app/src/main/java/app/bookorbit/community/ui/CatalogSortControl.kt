package app.bookorbit.community.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bookorbit.community.data.CatalogReadFilter
import app.bookorbit.community.data.CatalogSort

@Composable
fun CatalogSortControl(sort: CatalogSort, offline: Boolean, onSelect: (CatalogSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, "Sort books", Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(sort.label, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            CatalogSort.entries
                .filter { !offline || it.local }
                .forEach { option ->
                    DropdownMenuItem(
                        modifier = Modifier.semantics { selected = option == sort },
                        text = { Text(option.label) },
                        trailingIcon = {
                            if (option == sort)
                                Icon(Icons.Rounded.Check, "Selected", Modifier.size(18.dp))
                        },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
        }
    }
}

@Composable
fun CatalogReadFilterControl(filter: CatalogReadFilter, onSelect: (CatalogReadFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.FilterList, "Filter reading status", Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(filter.label, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            CatalogReadFilter.entries.forEach { option ->
                DropdownMenuItem(
                    modifier = Modifier.semantics { selected = option == filter },
                    text = { Text(option.label) },
                    trailingIcon = {
                        if (option == filter)
                            Icon(Icons.Rounded.Check, "Selected", Modifier.size(18.dp))
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogControls(
    sort: CatalogSort,
    filter: CatalogReadFilter,
    offline: Boolean,
    grid: Boolean,
    onSort: (CatalogSort) -> Unit,
    onFilter: (CatalogReadFilter) -> Unit,
    onLayout: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogSortControl(sort, offline, onSort)
            if (!offline) CatalogReadFilterControl(filter, onFilter)
        }
        onLayout?.let { click ->
            IconButton(onClick = click) {
                Icon(
                    if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                    if (grid) "List view" else "Grid view",
                    Modifier.size(20.dp),
                )
            }
        }
    }
}
