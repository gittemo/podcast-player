package app.spool.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _root = MutableStateFlow(normalizeRoot(prefs.getString(KEY_ROOT, null) ?: DEFAULT_ROOT))

    /** Library folder relative to main storage, without slashes, e.g. "Download/Seal". */
    val root: StateFlow<String> = _root.asStateFlow()

    fun setRoot(value: String) {
        val v = normalizeRoot(value)
        prefs.edit { putString(KEY_ROOT, v) }
        _root.value = v
    }

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f)
        set(value) = prefs.edit { putFloat(KEY_SPEED, value) }

    companion object {
        const val DEFAULT_ROOT = "Download/Seal"
        private const val KEY_ROOT = "root"
        private const val KEY_SPEED = "speed"

        fun normalizeRoot(value: String): String = value.trim().trim('/').ifEmpty { DEFAULT_ROOT }
    }
}
