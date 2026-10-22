package app.bookorbit.community.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.bookorbit.community.OrbitApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Appearance(val theme: String = "System", val finish: String = "Brass")

class AppearanceViewModel(application: Application) : AndroidViewModel(application) {
    private val scope = (application as OrbitApplication).sessions.read()?.scope ?: "signed-out"
    private val storage = AppearanceStorage(application, scope)
    private val mutable = MutableStateFlow(storage.read())
    val state = mutable.asStateFlow()

    fun theme(value: String) = change(mutable.value.copy(theme = value))

    fun finish(value: String) = change(mutable.value.copy(finish = value))

    private fun change(value: Appearance) {
        if (storage.save(value)) mutable.value = value
    }
}

class AppearanceStorage(context: android.content.Context, private val scope: String) {
    private val preferences = context.getSharedPreferences("appearance", 0)

    fun read(): Appearance =
        Appearance(
            preferences.getString("$scope/theme", "System")?.takeIf { it in themes } ?: "System",
            preferences.getString("$scope/finish", "Brass")?.takeIf { it in finishes } ?: "Brass",
        )

    fun save(value: Appearance): Boolean {
        if (value.theme !in themes || value.finish !in finishes) return false
        preferences
            .edit()
            .putString("$scope/theme", value.theme)
            .putString("$scope/finish", value.finish)
            .apply()
        return true
    }

    companion object {
        private val themes = setOf("System", "Light", "Dark")
        private val finishes = setOf("Brass", "Sage", "Ocean")
    }
}
