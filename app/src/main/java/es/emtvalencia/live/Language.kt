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

    // Onboarding
    val appName = "OpenTransport Valencia"
    val welcome = pick(
        "Transporte público de València en tiempo real: EMT, Metrovalencia, Rodalies, Metrobús y Valenbisi en un solo mapa.",
        "Valencia public transport in real time: EMT buses, Metrovalencia, Rodalies, Metrobús and Valenbisi on one map.",
        "Transport públic de València en temps real: EMT, Metrovalencia, Rodalies, Metrobús i Valenbisi en un sol mapa.",
    )
    val onboardFeatures = pick("Qué puedes hacer", "What you can do", "Què pots fer")
    val featureBus = pick("Ver autobuses EMT en directo sobre el mapa", "Track EMT buses live on the map", "Veure autobusos EMT en directe al mapa")
    val featureMetro = pick("Metro y tranvía con llegadas en tiempo real", "Metro & tram with live arrivals", "Metro i tramvia amb arribades en temps real")
    val featureRodalies = pick("Cercanías/Rodalies con horarios y avisos", "Rodalies trains with times and alerts", "Rodalies amb horaris i avisos")
    val featureBikes = pick("Bicis Valenbisi: bicis y anclajes libres", "Valenbisi bikes: available bikes and docks", "Bicis Valenbisi: bicis i ancoratges lliures")
    val featureFavs = pick("Guarda paradas y recibe avisos de llegada", "Save stops and get arrival alerts", "Guarda parades i rep avisos d'arribada")
    val featurePlan = pick("Planifica un viaje combinando líneas (beta)", "Plan a trip combining lines (beta)", "Planifica un viatge combinant línies (beta)")
    val featureAlerts = pick("Avisos e incidencias del servicio", "Service alerts and incidents", "Avisos i incidències del servei")
    val onboardMap = pick("Qué mostrar en el mapa", "What to show on the map", "Què mostrar al mapa")
    val mapStyle = pick("Estilo del mapa", "Map style", "Estil del mapa")
    val appearance = pick("Tema de la app", "App theme", "Tema de l'app")
    val auto = pick("Automático", "Auto", "Automàtic")
    val light = pick("Claro", "Light", "Clar")
    val dark = pick("Oscuro", "Dark", "Fosc")
    val start = pick("Empezar", "Get started", "Comença")
}

val currentStrings = mutableStateOf(Strings(Language.En))

private const val PREFS = "emt"

fun loadLanguage(context: Context): Language =
    Language.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("language", null))

fun saveLanguage(context: Context, language: Language) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("language", language.code).apply()
}
