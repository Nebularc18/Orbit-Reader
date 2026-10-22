package app.bookorbit.community.data

import android.content.Context

enum class CatalogReadFilter(val label: String, val statuses: List<String>) {
    ALL("All books", emptyList()),
    UNREAD("Unread", listOf("unread")),
    IN_PROGRESS("In progress", listOf("reading", "rereading")),
    FINISHED("Finished", listOf("read")),
    WANT_TO_READ("Want to read", listOf("want_to_read")),
    ON_HOLD("On hold", listOf("on_hold")),
    SKIMMED("Skimmed", listOf("skimmed")),
    ABANDONED("Abandoned", listOf("abandoned")),
}

class CatalogReadFilterStorage(context: Context, private val scope: String) {
    private val preferences = context.getSharedPreferences("catalog-read-filters", 0)

    fun read(shelf: String): CatalogReadFilter =
        CatalogReadFilter.entries.firstOrNull {
            it.name == preferences.getString("$scope/$shelf", null)
        } ?: CatalogReadFilter.ALL

    fun save(shelf: String, filter: CatalogReadFilter) {
        preferences.edit().putString("$scope/$shelf", filter.name).apply()
    }
}
