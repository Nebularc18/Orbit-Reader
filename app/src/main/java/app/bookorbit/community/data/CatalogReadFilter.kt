package app.bookorbit.community.data

import android.content.Context

enum class CatalogReadFilter(val label: String, val statuses: List<String>) {
    ALL("All books", emptyList()),
    UNREAD("Unread", listOf("unread", "want_to_read")),
    IN_PROGRESS("In progress", listOf("reading", "rereading")),
    FINISHED("Finished", listOf("read")),
    ON_HOLD("On hold", listOf("on_hold")),
    SKIMMED("Skimmed", listOf("skimmed")),
    ABANDONED("Abandoned", listOf("abandoned")),
}

class CatalogReadFilterStorage(context: Context, private val scope: String) {
    private val preferences = context.getSharedPreferences("catalog-read-filters", 0)

    fun read(shelf: String): CatalogReadFilter {
        val saved = preferences.getString("$scope/$shelf", null)
        if (saved == "WANT_TO_READ") return CatalogReadFilter.UNREAD
        return CatalogReadFilter.entries.firstOrNull { it.name == saved } ?: CatalogReadFilter.ALL
    }

    fun save(shelf: String, filter: CatalogReadFilter) {
        preferences.edit().putString("$scope/$shelf", filter.name).apply()
    }
}
