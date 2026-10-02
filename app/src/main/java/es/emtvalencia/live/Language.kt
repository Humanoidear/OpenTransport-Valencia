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
    val onboardMore = pick("Y además", "And more", "I a més")
    val previous = pick("Atrás", "Back", "Arrere")
    val next = pick("Siguiente", "Next", "Següent")
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

    // Common UI
    val all = pick("Todas", "All", "Totes")
    val close = pick("Cerrar", "Close", "Tanca")
    val open = pick("Abierto", "Open", "Obert")
    val closed = pick("Cerrado", "Closed", "Tancat")
    val remove = pick("Quitar", "Remove", "Lleva")
    val favourite = pick("Favorito", "Favourite", "Favorit")
    val addFavourite = pick("Añadir a favoritos", "Add favourite", "Afegir a favorits")
    val removeFavourite = pick("Quitar de favoritos", "Remove favourite", "Llevar de favorits")
    val hide = pick("Ocultar", "Hide", "Amaga")
    val show = pick("Mostrar", "Show", "Mostra")
    val noMatches = pick("Sin resultados", "No matches", "Sense resultats")
    val typeToSearch = pick("Escribe para buscar", "Type to search", "Escriu per a buscar")
    val mapLayers = pick("Capas del mapa", "Map layers", "Capes del mapa")
    val myLocation = pick("Mi ubicación", "My location", "La meua ubicació")
    val resetView = pick("Restablecer vista", "Reset view", "Restablir vista")
    val timetable = pick("Horarios", "Timetable", "Horaris")
    val trip = pick("Viaje", "Trip", "Viatge")
    val bikes = pick("Bicis", "Bikes", "Bicis")
    val freeDocks = pick("Anclajes libres", "Free docks", "Ancoratges lliures")
    val noBikesDocked = pick("Sin bicis ancladas", "No bikes docked", "Sense bicis ancorades")
    val noRating = pick("Sin valoración", "No rating", "Sense valoració")
    val stand = pick("Anclaje", "Stand", "Ancoratge")
    val noBuses = pick("No vienen autobuses ahora", "No buses coming right now", "No venen autobusos ara")
    val noTrains = pick("No hay trenes próximos", "No upcoming trains", "No hi ha trens pròxims")
    val noUpcomingBuses = pick("No hay autobuses próximos", "No upcoming buses", "No hi ha autobusos pròxims")
    val noServiceUpdates = pick("No hay avisos ahora", "No service updates right now", "No hi ha avisos ara")
    val starToSave = pick("Marca una parada para guardarla", "Star a stop to save it here", "Marca una parada per a guardar-la")
    val busLeftMap = pick("El autobús salió del mapa", "The bus left the map", "L'autobús va eixir del mapa")
    val stopsNearMe = pick("Paradas cerca de mí", "Stops near me", "Parades prop de mi")
    val waitingLocation = pick("Esperando tu ubicación…", "Waiting for your location…", "Esperant la teua ubicació…")
    val serviceUpdates = pick("Avisos del servicio", "Service updates", "Avisos del servei")
    val chooseStart = pick("Elige un origen", "Choose a start", "Tria un origen")
    val chooseDestination = pick("Elige un destino", "Choose a destination", "Tria una destinació")
    val openInMaps = pick("Abrir en Mapas", "Open in Maps", "Obrir en Mapes")
    val openStopInMaps = pick("Abrir parada en Mapas", "Open stop in Maps", "Obrir parada en Mapes")
    val openPositionInMaps = pick("Abrir posición en Mapas", "Open position in Maps", "Obrir posició en Mapes")
    val alertThisLine = pick("Avisar de esta línea", "Alert this line", "Avisar d'esta línia")
    val alertAtNextStop = pick("Avisar en la próxima parada", "Alert at next stop", "Avisar en la pròxima parada")
    val stopFollowing = pick("Dejar de seguir", "Stop following", "Deixar de seguir")
    val copyLine = pick("Copiar línea", "Copy line", "Copiar línia")
    val arrivalAlerts = pick("Avisos de llegada", "Arrival alerts", "Avisos d'arribada")
    val arrivalAlertsDesc = pick(
        "Avísame cuando una línea marcada esté a estos minutos.",
        "Notify me when a pinned line is this many minutes away.",
        "Avisa'm quan una línia marcada estiga a estos minuts.",
    )
    val aboutSources = pick("Acerca de y fuentes de datos", "About & data sources", "Sobre l'app i fonts de dades")
    val languageRestart = pick(
        "Cambiar el idioma requiere reiniciar la app.",
        "Changing the language needs an app restart.",
        "Canviar l'idioma requereix reiniciar l'app.",
    )
    val openNotice = pick("Abrir aviso", "Open notice", "Obrir avís")
}

val currentStrings = mutableStateOf(Strings(Language.En))

private const val PREFS = "emt"

fun loadLanguage(context: Context): Language =
    Language.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("language", null))

fun saveLanguage(context: Context, language: Language) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("language", language.code).apply()
}
