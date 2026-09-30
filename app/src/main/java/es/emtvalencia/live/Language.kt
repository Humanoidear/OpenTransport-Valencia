package es.emtvalencia.live

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** App language: Spanish, English or Valencian. */
enum class Language(val code: String, val label: String) {
    Es("es", "Español"),
    En("en", "English"),
    Va("va", "Valencià");

    companion object {
        fun of(code: String?): Language = entries.firstOrNull { it.code == code } ?: En
    }
}

/**
 * Lightweight string table. Kept as a single mutable holder (read from
 * composables) so switching language recomposes every label without threading a
 * parameter through the whole tree.
 */
class Strings(private val language: Language) {
    private fun pick(es: String, en: String, va: String = es) = when (language) {
        Language.Es -> es
        Language.En -> en
        Language.Va -> va
    }

    val map = pick("Mapa", "Map")
    val saved = pick("Guardadas", "Saved", "Guardades")
    val alerts = pick("Avisos", "Alerts", "Avisos")
    val plan = pick("Planificar", "Plan", "Planificar")
    val settings = pick("Ajustes", "Settings", "Ajustos")
    val languageTitle = pick("Idioma", "Language", "Idioma")
    val searchStop = pick("Buscar parada", "Search a stop", "Buscar parada")
    val searchLine = pick("Buscar línea", "Search a line", "Buscar línia")
    val stops = pick("Paradas", "Stops", "Parades")
    val lines = pick("Líneas", "Lines", "Línies")
    val planTitle = pick("Planifica un viaje", "Plan a trip", "Planifica un viatge")
    val layersTitle = pick("Mostrar en el mapa", "Show on the map", "Mostrar en el mapa")
    val nextDepartures = pick("Próximas salidas", "Next departures", "Pròximes eixides")
}

val currentStrings = mutableStateOf(Strings(Language.En))

private const val PREFS = "emt"

fun loadLanguage(context: Context): Language =
    Language.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("language", null))

fun saveLanguage(context: Context, language: Language) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("language", language.code).apply()
}
