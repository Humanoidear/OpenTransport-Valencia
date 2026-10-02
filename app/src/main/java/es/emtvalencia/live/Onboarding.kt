package es.emtvalencia.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Map

/** First-launch onboarding: intro, feature tour, and map/theme setup. */
@Composable
fun Onboarding(
    language: Language,
    onLanguage: (Language) -> Unit,
    theme: String,
    onTheme: (String) -> Unit,
    mapType: String,
    onMapType: (String) -> Unit,
    networks: Set<Network>,
    onToggleNetwork: (Network) -> Unit,
    onDone: () -> Unit,
) {
    val t = Strings(language)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(t.appName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(t.welcome, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(4.dp))
        Text(t.onboardFeatures, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Feature(Icons.Filled.DirectionsBus, t.featureBus)
        Feature(Icons.Filled.DirectionsSubway, t.featureMetro)
        Feature(Icons.Filled.Train, t.featureRodalies)
        Feature(Icons.Filled.DirectionsBike, t.featureBikes)
        Feature(Icons.Filled.Star, t.featureFavs)
        Feature(Icons.Filled.Route, t.featurePlan)
        Feature(Icons.Filled.Warning, t.featureAlerts)

        Spacer(Modifier.height(8.dp))
        Text(t.onboardMap, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Network.entries.forEach { network ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ServiceLogo(network, 24.dp)
                Spacer(Modifier.size(10.dp))
                Text(network.label, modifier = Modifier.weight(1f))
                Switch(checked = network in networks, onCheckedChange = { onToggleNetwork(network) })
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(t.mapStyle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("default" to t.auto, "light" to t.light, "dark" to t.dark).forEach { (value, label) ->
                FilterChip(selected = mapType == value, onClick = { onMapType(value) }, label = { Text(label) })
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(t.languageTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { entry ->
                FilterChip(selected = entry == language, onClick = { onLanguage(entry) }, label = { Text(entry.label) })
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(t.appearance, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to t.auto, "light" to t.light, "dark" to t.dark).forEach { (value, label) ->
                FilterChip(selected = theme == value, onClick = { onTheme(value) }, label = { Text(label) })
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Map, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(t.start)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
